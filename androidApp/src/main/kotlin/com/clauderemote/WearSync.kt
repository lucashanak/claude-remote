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
)

@Serializable
data class WearSessionsPayload(val sessions: List<WearSessionInfo>)

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

    private fun push(urgent: Boolean) {
        val ctx = appContext ?: return
        val tm = tabManager ?: return
        val orch = orchestrator ?: return
        val activities = orch.sessionActivities.value
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
            WearSessionInfo(
                id = tab.id,
                title = tab.tabTitle,
                status = tab.status.name,
                activity = (activities[tab.id] ?: com.clauderemote.model.SessionActivity.IDLE).name,
                lastMessage = text,
                lastMessageAt = changedAt,
            )
        }
        val payload = json.encodeToString<WearSessionsPayload>(WearSessionsPayload(sessions))
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
