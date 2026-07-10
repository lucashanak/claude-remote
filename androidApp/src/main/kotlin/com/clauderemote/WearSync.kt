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
    private const val KEY_TS = "ts"
    // Matches the quiescence-y feel of the rest of the notify pipeline
    // without hammering the Data Layer on every streaming token.
    private const val DEBOUNCE_MS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var appContext: Context? = null
    @Volatile private var tabManager: TabManager? = null
    @Volatile private var orchestrator: SessionOrchestrator? = null

    fun start(context: Context, tabManager: TabManager, orchestrator: SessionOrchestrator) {
        FileLogger.log(TAG, "WearSync started")
        appContext = context.applicationContext
        this.tabManager = tabManager
        this.orchestrator = orchestrator
        scope.launch {
            combine(tabManager.tabs, orchestrator.sessionActivities) { tabs, activities -> tabs to activities }
                .debounce(DEBOUNCE_MS)
                .collectLatest { push() }
        }
    }

    /**
     * Push immediately, bypassing the debounce — used right after a
     * notification body is computed so the watch gets the newest assistant
     * message without waiting for the periodic collector.
     */
    fun pushNow() {
        scope.launch { push() }
    }

    private fun push() {
        val ctx = appContext ?: return
        val tm = tabManager ?: return
        val orch = orchestrator ?: return
        val activities = orch.sessionActivities.value
        val sessions = tm.tabs.value.map { tab ->
            WearSessionInfo(
                id = tab.id,
                title = tab.tabTitle,
                status = tab.status.name,
                activity = (activities[tab.id] ?: com.clauderemote.model.SessionActivity.IDLE).name,
                lastMessage = orch.lastAssistantText(tab.id),
            )
        }
        val payload = json.encodeToString<WearSessionsPayload>(WearSessionsPayload(sessions))
        val request = PutDataMapRequest.create(PATH).apply {
            dataMap.putString(KEY_JSON, payload)
            // Guarantees the DataItem's bytes differ on every push (even when
            // the session content happens to be identical), so the watch's
            // onDataChanged reliably fires.
            dataMap.putLong(KEY_TS, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(request)
            .addOnSuccessListener { FileLogger.log(TAG, "Pushed ${sessions.size} sessions to watch") }
            .addOnFailureListener { e -> FileLogger.log(TAG, "putDataItem failed: ${e.message}") }
    }
}
