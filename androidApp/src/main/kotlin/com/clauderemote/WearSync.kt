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
import kotlinx.coroutines.launch
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

    // sessionId -> (message text that was summarized, resulting summary or null
    // on failure). Keyed on the exact text so an unchanged message isn't
    // re-summarized on every debounced push — only a genuinely new lastMessage
    // hits the LLM. The summary is cached EVEN WHEN null: a timeout/HTTP-error
    // result for a given text is remembered too, so a failed summarize isn't
    // retried on every one of the frequent pushes (× many sessions = a storm of
    // repeated failing calls). A changed message is a new key and retries.
    private val summaryCache = mutableMapOf<String, Pair<String, String?>>()

    fun start(context: Context, tabManager: TabManager, orchestrator: SessionOrchestrator) {
        FileLogger.log(TAG, "WearSync started")
        appContext = context.applicationContext
        this.tabManager = tabManager
        this.orchestrator = orchestrator
        scope.launch {
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
                .collectLatest { push(urgent = false) }
        }
    }

    /**
     * Push immediately, bypassing the debounce AND requesting urgent
     * delivery — used right after a notification body is computed so the
     * watch gets the newest assistant message (and can auto-speak it)
     * without waiting on Play Services' own delivery schedule.
     */
    fun pushNow() {
        scope.launch { push(urgent = true) }
    }

    private suspend fun push(urgent: Boolean) {
        val ctx = appContext ?: return
        val tm = tabManager ?: return
        val orch = orchestrator ?: return
        val prefs = ctx.getSharedPreferences("claude_remote", Context.MODE_PRIVATE)
        val activities = orch.sessionActivities.value
        // LLM-summary config read once per push (opt-in; blank URL => off).
        val llmEnabled = prefs.getBoolean("llm_summary_enabled", false)
        val llmUrl = prefs.getString("llm_summary_url", "").orEmpty()
        val llmKey = prefs.getString("llm_summary_api_key", "").orEmpty()
        val llmModel = prefs.getString("llm_summary_model", DEFAULT_LLM_MODEL)
            .orEmpty().ifBlank { DEFAULT_LLM_MODEL }
        val llmLength = prefs.getString("llm_summary_length", "SENTENCE").orEmpty().ifBlank { "SENTENCE" }
        val sessions = tm.tabs.value.map { tab ->
            val text = orch.lastAssistantText(tab.id)
            // pushNow() (called right after a notification body is computed)
            // and the debounced collector can both call push() concurrently,
            // so guard the shared bookkeeping maps.
            val changedAt = synchronized(lastMessageText) {
                if (lastMessageText[tab.id] != text) {
                    lastMessageText[tab.id] = text
                    lastMessageAt[tab.id] = System.currentTimeMillis()
                }
                lastMessageAt.getOrPut(tab.id) { System.currentTimeMillis() }
            }
            val activity = (activities[tab.id] ?: com.clauderemote.model.SessionActivity.IDLE).name
            WearSessionInfo(
                id = tab.id,
                title = tab.tabTitle,
                status = tab.status.name,
                activity = activity,
                lastMessage = text,
                lastMessageAt = changedAt,
                summary = if (llmEnabled) maybeSummarize(tab.id, activity, text, llmUrl, llmKey, llmModel, llmLength) else null,
            )
        }
        val sonioxKey = prefs.getString("soniox_api_key", "").orEmpty()
        val sonioxVoice = prefs.getString("soniox_tts_voice", "Adrian").orEmpty().ifBlank { "Adrian" }
        val ttsSpeedPct = prefs.getInt("tts_speech_rate_pct", 100)
        val dictationSilenceMs = prefs.getInt("dictation_silence_ms", 4000).coerceIn(1000, 10000)
        val payload = json.encodeToString<WearSessionsPayload>(
            WearSessionsPayload(sessions, sonioxKey, sonioxVoice, ttsSpeedPct, dictationSilenceMs),
        )
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
        val hit = synchronized(summaryCache) {
            summaryCache[sessionId]?.takeIf { it.first == msg }
        }
        if (hit != null) return hit.second
        val summary = com.clauderemote.voice.MessageSummarizer
            .summarize(url, apiKey, model, activity, msg, length)
        synchronized(summaryCache) { summaryCache[sessionId] = msg to summary }
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
