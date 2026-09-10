package com.clauderemote

import android.content.Context
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.util.FileLogger
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Wire model for the phone -> watch session snapshot (path "/sessions").
 * `wearApp` has no Gradle dependency on this module (kept dependency-free —
 * see wearApp/build.gradle.kts), so it declares its own matching copy of
 * these classes. Keep the two in sync when the shape changes.
 */
@Serializable
data class WearSessionInfo(
    val id: String,
    val title: String,
    val status: String,
    val activity: String,
    val lastMessage: String?,
    val lastMessageAt: Long = 0,
    // One-sentence LLM summary of lastMessage for the watch notification body.
    // Null when disabled, not notify-worthy, or the summarizer failed — the
    // watch then falls back to the truncated lastMessage. New trailing field
    // (defaulted) to stay wire-compatible with the wearApp copy.
    val summary: String? = null,
    // The markdown-cleaned body the phone's Stop-hook path resolved for the
    // CURRENT completion (what MainActivity.onClaudeNeedsInput received as
    // `body`), or null when that turn produced none. The watch prefers
    // `summary` -> `notifyBody` -> truncated `lastMessage`, which takes the
    // watch notification off the `lastAssistantText` snapshot entirely: that
    // snapshot is only sampled when tabs/activity change, so it can still hold
    // the PREVIOUS turn's text when the activity flip beats the transcript.
    // New trailing field (defaulted) to stay wire-compatible with wearApp.
    val notifyBody: String? = null,
)

@Serializable
data class WearSessionsPayload(
    val sessions: List<WearSessionInfo>,
    // Soniox key synced to the watch so it can run its own on-watch STT/TTS.
    // Rides the existing /sessions push (same personal account); blank when
    // the user hasn't set a Soniox key on the phone.
    val sonioxApiKey: String = "",
    // Mirror the phone's TTS voice + reading speed to the watch.
    val sonioxVoice: String = "Adrian",
    val ttsSpeedPct: Int = 100,
    // Silence tolerance (ms) for on-watch dictation — mirrored from the phone's
    // Voice settings. New trailing field (defaulted) to stay wire-compatible.
    val dictationSilenceMs: Int = 4000,
)

/**
 * Pushes a snapshot of live sessions to the watch companion app via the
 * Wearable Data Layer, so a Wear session list stays current without the
 * watch having to poll. Process-scoped like [OrchestratorHolder] /
 * [KeepAliveService] — started once from MainActivity.
 */
object WearSync {
    private const val TAG = "WearSync"
    private const val PATH = "/sessions"
    private const val KEY_JSON = "json"
    private const val DEFAULT_LLM_MODEL = "chadrock-35b-ace-saber-rocmfpx-q"
    // Matches the quiescence-y feel of the rest of the notify pipeline
    // without hammering the Data Layer on every streaming token.
    private const val DEBOUNCE_MS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var appContext: Context? = null
    @Volatile private var tabManager: TabManager? = null
    @Volatile private var orchestrator: SessionOrchestrator? = null

    // Claude's own transcript timestamps aren't reliably available/parseable
    // here, so "last message time" for the watch's sort order is tracked
    // locally: the moment THIS push first observes a session's message text
    // differing from the last push's, not when Claude itself produced it.
    private val lastMessageText = mutableMapOf<String, String?>()
    private val lastMessageAt = mutableMapOf<String, Long>()

    // sessionId -> small access-ordered LRU of (message text hash -> summary or
    // null on failure). Keyed on the exact text so an unchanged message isn't
    // re-summarized on every debounced push — only a genuinely new lastMessage
    // hits the LLM. The summary is cached EVEN WHEN null: a timeout/HTTP-error
    // result for a given text is remembered too, so a failed summarize isn't
    // retried on every one of the frequent pushes (× many sessions = a storm of
    // repeated failing calls). A changed message is a new key and retries.
    //
    // A FEW entries per session, not one: the phone alert path (summaryFor)
    // and the watch push can be summarizing different texts for the same
    // session at the same time — the phone's fallback hint versus the real
    // message — and a single slot made them evict each other, costing an extra
    // LLM call on the next push apiece. Four is enough to cover both surfaces
    // across a completion without letting the map grow with the transcript.
    private const val SUMMARY_CACHE_PER_SESSION = 4
    private val summaryCache = mutableMapOf<String, LinkedHashMap<Int, String?>>()

    private fun summarySlots(sessionId: String): LinkedHashMap<Int, String?> =
        summaryCache.getOrPut(sessionId) {
            object : LinkedHashMap<Int, String?>(8, 0.75f, /* accessOrder = */ true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String?>) =
                    size > SUMMARY_CACHE_PER_SESSION
            }
        }

    // sessionId -> the body MainActivity resolved for the completion that is
    // currently waiting, set by [setResolvedBody]. Dropped as soon as push()
    // sees the session WORKING again: that turn is over, so an in-progress
    // turn falls back to the live lastAssistantText rather than pinning a
    // stale answer forever.
    private val resolvedBodies = mutableMapOf<String, String>()

    // sessionId -> the activity the previous push observed. Used to make the
    // "session started working again" clear EDGE-triggered: the Stop hook
    // resolves the body and calls pushNow() before sessionActivities has
    // necessarily flipped away from WORKING, so a level-triggered clear would
    // wipe the body we were just handed.
    private val lastObservedActivity = mutableMapOf<String, String>()

    // sessionId -> count of screen-detected prompt events (see
    // [markPromptEvent]). Folded into the lastMessageAt change key so a
    // detection with no new assistant text still advances the stamp: an
    // approval prompt is a SCREEN event, and two of them inside one debounce
    // window coalesce into a single APPROVAL->APPROVAL push that the watch
    // would otherwise read as "nothing happened" and stay silent for.
    private val promptEvents = mutableMapOf<String, Long>()

    // Per-session map owned outside this object that should be pruned on the
    // same tab-removal signal — see [setPruneHook]. A single slot, not a list:
    // MainActivity registers it from initApp(), which runs again on every
    // Activity recreation, so appending would grow without bound.
    @Volatile private var pruneHook: ((Set<String>) -> Unit)? = null

    // The tab/activity collector launched by [start]. Held so a second start()
    // can cancel it: initApp() runs again on every Activity recreation, and a
    // stacked collector is not harmless — each one pushes and summarizes
    // independently, so N recreations mean N putDataItem writes and N LLM
    // calls per change, racing each other through the snapshot sequence.
    @Volatile private var collectorJob: kotlinx.coroutines.Job? = null

    // Serializes putDataItem and orders the snapshots. pushNow() and the
    // debounced collector run independently, so two push() bodies can be in
    // flight at once; without this, the one that started EARLIER can write
    // LAST and put its older text back on the wrist. Every payload takes a
    // sequence number the moment it is built, and a write whose number is
    // below the last one written is dropped.
    private val pushMutex = Mutex()
    private val snapshotSeq = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var lastWrittenSeq = 0L

    // Per-session cap on the text that rides the /sessions DataItem. A
    // DataItem over 100 KB is rejected outright and the ONLY trace is a single
    // "putDataItem failed" line — the whole watch sync stops, not one row. The
    // device log shows 40 live sessions with assistant messages of 1.7-2.0 KB
    // each, so uncapped text alone approaches the limit. The watch renders
    // ~100 characters of this and fetches the full conversation over the
    // separate /history-request channel when the user opens a session, so
    // nothing on the wrist is actually lost by capping here.
    private const val WATCH_TEXT_MAX = 2000

    // Total wall-clock budget for the second, summary-filling push. The
    // summariser itself allows 6 s connect + 12 s read per call, so without a
    // ceiling here a dead endpoint would keep the coroutine (not the wrist)
    // busy far longer than the answer is worth.
    private const val SUMMARY_BUDGET_MS = 8_000L

    fun start(context: Context, tabManager: TabManager, orchestrator: SessionOrchestrator) {
        // Idempotent: re-starting against the SAME tab manager and
        // orchestrator (the common case — an Activity recreation that reuses
        // them) is a no-op, and re-starting against new ones cancels the old
        // collector before launching a replacement.
        if (collectorJob?.isActive == true && this.tabManager === tabManager && this.orchestrator === orchestrator) {
            FileLogger.log(TAG, "WearSync already running for these instances")
            return
        }
        collectorJob?.cancel()
        FileLogger.log(TAG, "WearSync started")
        appContext = context.applicationContext
        this.tabManager = tabManager
        this.orchestrator = orchestrator
        collectorJob = scope.launch {
            combine(tabManager.tabs, orchestrator.sessionActivities) { tabs, activities -> tabs to activities }
                .debounce(DEBOUNCE_MS)
                // Not urgent: sessionActivities churns continuously while
                // Claude streams across many sessions, and .setUrgent() forces
                // an immediate Bluetooth radio wake on every single one of
                // those — measured as the single biggest battery cost of this
                // feature on a real watch. Routine list refreshes can ride
                // Play Services' own opportunistic/batched delivery; only a
                // message actually worth announcing (pushNow(), below) needs
                // to arrive immediately.
                .collectLatest { (tabs, _) ->
                    // Tabs are the lifetime of everything keyed by session id
                    // here, so a closed tab's rows would otherwise sit in
                    // these maps for the life of the process.
                    prune(tabs.map { it.id }.toSet())
                    push(urgent = false)
                }
        }
    }

    /**
     * Push immediately, bypassing the debounce AND requesting urgent
     * delivery — used right after a notification body is computed so the
     * watch gets the newest assistant message (and can auto-speak it)
     * without waiting on Play Services' own delivery schedule. Never waits on
     * the summariser: the first write goes out with whatever summaries are
     * already cached, and a second write follows if fresh ones arrive in time.
     */
    fun pushNow() {
        scope.launch { push(urgent = true) }
    }

    /**
     * Record the body the phone resolved for [sessionId]'s just-finished turn
     * (already markdown-cleaned), or null when that turn produced none. The
     * next push sends it as [WearSessionInfo.notifyBody] and uses it as the
     * session's `lastMessage`, so the watch shows the answer the phone
     * actually resolved instead of whatever `lastAssistantText` happened to
     * hold when the activity flipped.
     */
    fun setResolvedBody(sessionId: String, body: String?) {
        synchronized(resolvedBodies) {
            if (body.isNullOrBlank()) resolvedBodies.remove(sessionId) else resolvedBodies[sessionId] = body
        }
    }

    /**
     * Drop every per-session entry whose tab no longer exists. The registered
     * hook (MainActivity's alert-sequence map) is pruned with the same set, so
     * one tab-list observer covers all of the phone's per-session state.
     */
    private fun prune(liveIds: Set<String>) {
        synchronized(lastMessageText) {
            lastMessageText.keys.retainAll(liveIds)
            lastMessageAt.keys.retainAll(liveIds)
        }
        synchronized(summaryCache) { summaryCache.keys.retainAll(liveIds) }
        synchronized(resolvedBodies) { resolvedBodies.keys.retainAll(liveIds) }
        synchronized(lastObservedActivity) { lastObservedActivity.keys.retainAll(liveIds) }
        synchronized(promptEvents) { promptEvents.keys.retainAll(liveIds) }
        pruneHook?.let { hook -> runCatching { hook(liveIds) } }
    }

    /**
     * Register the per-session map owned elsewhere that should be pruned
     * whenever a tab closes — see [prune]. Set from MainActivity for its alert
     * sequence counters; replacing rather than appending keeps Activity
     * recreation from stacking duplicates.
     */
    fun setPruneHook(hook: (Set<String>) -> Unit) {
        pruneHook = hook
    }

    /**
     * Record that the phone detected a prompt for [sessionId] on a path that
     * carries no assistant text (an approval or input prompt read off the
     * screen rather than resolved from the transcript). One increment of a
     * per-session counter — the next push folds it into the change key, so
     * `lastMessageAt` advances and the watch treats the push as a real event
     * even though `lastMessage` and `notifyBody` are byte-identical to the
     * previous one. Call it before [pushNow].
     */
    fun markPromptEvent(sessionId: String) {
        synchronized(promptEvents) { promptEvents[sessionId] = (promptEvents[sessionId] ?: 0L) + 1L }
    }

    /** The session started a new turn — its pinned body and summary are spent. */
    private fun clearResolved(sessionId: String) {
        synchronized(resolvedBodies) { resolvedBodies.remove(sessionId) }
        // Also drop the session's cached summaries: the cache is keyed on the
        // message text, so an answer byte-identical to the previous turn's
        // would otherwise hit and hand the watch the OLD turn's summary
        // without an LLM call.
        synchronized(summaryCache) { summaryCache.remove(sessionId) }
    }

    /**
     * Two-phase so the wrist never waits on the LLM: build and write the
     * snapshot using only summaries already in the cache, then — if anything
     * notify-worthy still needs one — compute the missing summaries
     * CONCURRENTLY under a total budget and write a second, richer snapshot.
     * pushNow() therefore reaches putDataItem immediately even when the
     * summariser is slow or down.
     */
    private suspend fun push(urgent: Boolean) {
        val ctx = appContext ?: return
        val tm = tabManager ?: return
        val orch = orchestrator ?: return
        val prefs = ctx.getSharedPreferences("claude_remote", Context.MODE_PRIVATE)
        // LLM-summary config read once per push (opt-in; blank URL => off).
        val llmEnabled = prefs.getBoolean("llm_summary_enabled", false)
        val llmUrl = prefs.getString("llm_summary_url", "").orEmpty()
        val llmKey = prefs.getString("llm_summary_api_key", "").orEmpty()
        val llmModel = prefs.getString("llm_summary_model", DEFAULT_LLM_MODEL)
            .orEmpty().ifBlank { DEFAULT_LLM_MODEL }
        val llmLength = prefs.getString("llm_summary_length", "SENTENCE").orEmpty().ifBlank { "SENTENCE" }

        val first = buildSessions(tm, orch, llmEnabled)
        // Sequence taken HERE, not inside writeSnapshot: two push() bodies run
        // in parallel on Dispatchers.Default, and a thread stalled between
        // building and numbering could otherwise take the higher number while
        // holding the older content.
        val firstSeq = snapshotSeq.incrementAndGet()
        writeSnapshot(ctx, prefs, first, urgent, firstSeq)
        if (!llmEnabled) return

        // Sessions worth a fresh summary: notify-worthy, non-blank text, and
        // nothing cached for that exact text yet (a cached null counts as
        // done — that is what stops the retry storm).
        val pending = first.filter { s ->
            isNotifyWorthy(s.activity) && !s.lastMessage.isNullOrBlank() && !hasCachedSummary(s.id, s.lastMessage)
        }
        if (pending.isEmpty()) return
        withTimeoutOrNull(SUMMARY_BUDGET_MS) {
            coroutineScope {
                // MessageSummarizer is documented never to throw — it returns
                // null on timeout/non-200/malformed body — so no runCatching
                // here, which would also swallow the budget's cancellation.
                pending.map { s ->
                    async { maybeSummarize(s.id, s.activity, s.lastMessage, llmUrl, llmKey, llmModel, llmLength) }
                }.forEach { it.await() }
            }
        }
        // Rebuild from LIVE state (not the captured list) so the second write
        // carries a genuinely current snapshot and its sequence number is
        // honestly newer than anything written in the meantime.
        val second = buildSessions(tm, orch, llmEnabled)
        val secondSeq = snapshotSeq.incrementAndGet()
        if (second != first) writeSnapshot(ctx, prefs, second, urgent, secondSeq)
    }

    private fun isNotifyWorthy(activity: String) =
        activity == "WAITING_FOR_INPUT" || activity == "APPROVAL_NEEDED"

    /** Builds the payload rows from live state, using only cached summaries. */
    private fun buildSessions(
        tm: TabManager,
        orch: SessionOrchestrator,
        llmEnabled: Boolean,
    ): List<WearSessionInfo> {
        val activities = orch.sessionActivities.value
        return tm.tabs.value.map { tab ->
            val activity = (activities[tab.id] ?: com.clauderemote.model.SessionActivity.IDLE).name
            // A session that has just STARTED working again has moved past the
            // completion whose body we pinned. Edge, not level: at the moment
            // the Stop hook hands us a body the session is often still marked
            // WORKING, and a level test would throw that body straight away.
            val previousActivity = synchronized(lastObservedActivity) {
                lastObservedActivity.put(tab.id, activity)
            }
            if (activity == "WORKING" && previousActivity != "WORKING") clearResolved(tab.id)
            val live = orch.lastAssistantText(tab.id)
            val resolved = synchronized(resolvedBodies) { resolvedBodies[tab.id] }
            // Capped BEFORE the duplicate check so two texts that differ only
            // past the cap don't ship two identical 2 KB strings.
            val liveText = live?.take(WATCH_TEXT_MAX)
            val resolvedText = resolved?.take(WATCH_TEXT_MAX)
            val display = resolvedText ?: liveText
            // pushNow() (called right after a notification body is computed)
            // and the debounced collector can both call push() concurrently,
            // so guard the shared bookkeeping maps. The key folds BOTH the
            // resolved body and the live text in, so lastMessageAt advances
            // whenever either changes — the watch uses that timestamp to tell
            // a genuinely new answer from a re-push of the same one.
            // Built from the UNCAPPED text: an edit past the cap is still a
            // new answer, and the watch uses this stamp to tell one apart. The
            // prompt-event counter is the third component so a detection that
            // produced no text at all still moves the stamp — it is stable
            // across the two builds of one push, so it never double-bumps.
            val prompts = synchronized(promptEvents) { promptEvents[tab.id] ?: 0L }
            val key = (resolved ?: "") + "\u0000" + (live ?: "") + "\u0000" + prompts
            val changedAt = synchronized(lastMessageText) {
                if (lastMessageText[tab.id] != key) {
                    lastMessageText[tab.id] = key
                    lastMessageAt[tab.id] = System.currentTimeMillis()
                }
                lastMessageAt.getOrPut(tab.id) { System.currentTimeMillis() }
            }
            WearSessionInfo(
                id = tab.id,
                title = tab.tabTitle,
                status = tab.status.name,
                activity = activity,
                lastMessage = display,
                lastMessageAt = changedAt,
                summary = if (llmEnabled) cachedSummary(tab.id, activity, display) else null,
                // Only when it actually differs from lastMessage. The watch
                // prefers notifyBody and falls back to lastMessage, so an
                // identical copy changes nothing on the wrist and only costs
                // its own length in a payload with a hard 100 KB ceiling.
                notifyBody = resolvedText?.takeIf { it != liveText },
            )
        }
    }

    private suspend fun writeSnapshot(
        ctx: Context,
        prefs: android.content.SharedPreferences,
        sessions: List<WearSessionInfo>,
        urgent: Boolean,
        seq: Long,
    ) {
        val sonioxKey = prefs.getString("soniox_api_key", "").orEmpty()
        val sonioxVoice = prefs.getString("soniox_tts_voice", "Adrian").orEmpty().ifBlank { "Adrian" }
        val ttsSpeedPct = prefs.getInt("tts_speech_rate_pct", 100)
        val dictationSilenceMs = prefs.getInt("dictation_silence_ms", 4000).coerceIn(1000, 10000)
        val payload = json.encodeToString<WearSessionsPayload>(
            WearSessionsPayload(sessions, sonioxKey, sonioxVoice, ttsSpeedPct, dictationSilenceMs),
        )
        // NOTE: the mutex orders the putDataItem ENQUEUE, not the delivery —
        // the Task is not awaited. Play Services processes a client's calls in
        // order in practice, which is what makes the ordering hold end to end;
        // anything needing a stronger guarantee has to await the Task here.
        pushMutex.withLock {
            if (seq < lastWrittenSeq) {
                FileLogger.log(TAG, "Dropped stale push (seq $seq < $lastWrittenSeq)")
                return@withLock
            }
            lastWrittenSeq = seq
            // No forced per-push timestamp field: letting the DataItem's bytes be
            // identical when nothing actually changed lets putDataItem's own
            // dedup skip the sync entirely — previously a timestamp was stamped
            // on every push specifically to defeat that dedup, which meant an
            // unrelated session's activity flapping forced a radio wake for
            // every OTHER session's unchanged row too.
            val request = PutDataMapRequest.create(PATH).apply {
                dataMap.putString(KEY_JSON, payload)
            }.asPutDataRequest()
            if (urgent) request.setUrgent()
            Wearable.getDataClient(ctx).putDataItem(request)
                .addOnSuccessListener { FileLogger.log(TAG, "Pushed ${sessions.size} sessions to watch") }
                .addOnFailureListener { e -> FileLogger.log(TAG, "putDataItem failed: ${e.message}") }
        }
    }

    /** Cache lookup only — never calls the LLM, so it is safe on the fast path. */
    private fun cachedSummary(sessionId: String, activity: String, message: String?): String? {
        if (!isNotifyWorthy(activity)) return null
        val msg = message?.trim()
        if (msg.isNullOrBlank()) return null
        return synchronized(summaryCache) { summaryCache[sessionId]?.get(msg.hashCode()) }
    }

    /** True when this exact text was already summarized (or already failed). */
    private fun hasCachedSummary(sessionId: String, message: String?): Boolean {
        val msg = message?.trim() ?: return false
        return synchronized(summaryCache) { summaryCache[sessionId]?.containsKey(msg.hashCode()) == true }
    }

    /**
     * Summarize [message] for the watch, but only for notify-worthy sessions
     * (WAITING_FOR_INPUT / APPROVAL_NEEDED) with non-blank text — everything
     * else stays null so we never spend an LLM call on an idle/streaming row.
     * Cached per (session, text) so the frequent debounced pushes don't
     * re-summarize an unchanged message. Returns null on any failure/timeout.
     */
    private suspend fun maybeSummarize(
        sessionId: String,
        activity: String,
        message: String?,
        url: String,
        apiKey: String,
        model: String,
        length: String,
    ): String? {
        if (activity != "WAITING_FOR_INPUT" && activity != "APPROVAL_NEEDED") return null
        val msg = message?.trim()
        if (msg.isNullOrBlank()) return null
        // Cache HIT for this exact text (present entry) → return its result even
        // when null, WITHOUT calling the LLM again. This is what stops the retry
        // storm: once we've tried a given text we don't try it again until the
        // text changes.
        val key = msg.hashCode()
        val hit = synchronized(summaryCache) {
            summaryCache[sessionId]?.let { slots -> if (slots.containsKey(key)) slots[key] to true else null }
        }
        if (hit != null) return hit.first
        val summary = com.clauderemote.voice.MessageSummarizer
            .summarize(url, apiKey, model, activity, msg, length)
        synchronized(summaryCache) { summarySlots(sessionId)[key] = summary }
        return summary
    }

    /**
     * Summary for the phone's own notification, resolved from the same config,
     * source text (lastAssistantText) and per-(session,text) cache the watch
     * push uses — so a given message is summarized only once no matter which
     * surface asks first. Returns null when summaries are disabled or the call
     * fails, so MainActivity falls back to the raw notification body.
     */
    suspend fun summaryFor(sessionId: String, message: String): String? {
        val ctx = appContext ?: return null
        val orch = orchestrator
        val prefs = ctx.getSharedPreferences("claude_remote", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("llm_summary_enabled", false)) return null
        val url = prefs.getString("llm_summary_url", "").orEmpty()
        val key = prefs.getString("llm_summary_api_key", "").orEmpty()
        val model = prefs.getString("llm_summary_model", DEFAULT_LLM_MODEL)
            .orEmpty().ifBlank { DEFAULT_LLM_MODEL }
        val length = prefs.getString("llm_summary_length", "SENTENCE").orEmpty().ifBlank { "SENTENCE" }
        // Summarize the body the caller (onClaudeNeedsInput) already resolved —
        // NOT a re-fetched lastAssistantText. And the callback firing already
        // means the session needs input, so force a notify-worthy activity:
        // sessionActivities may not have flipped to WAITING/APPROVAL yet at
        // callback time, and reading a stale IDLE/WORKING here made maybeSummarize
        // bail to null → the phone silently fell back to the raw body.
        val live = orch?.sessionActivities?.value?.get(sessionId)?.name
        val activity = if (live == "APPROVAL_NEEDED") "APPROVAL_NEEDED" else "WAITING_FOR_INPUT"
        return maybeSummarize(sessionId, activity, message, url, key, model, length)
    }
}
