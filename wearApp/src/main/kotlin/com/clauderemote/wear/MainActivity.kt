package com.clauderemote.wear

import android.app.RemoteInput
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.serialization.decodeFromString

private const val KEY_REPLY = "reply_text"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fetchInitialSessions(applicationContext)
        setContent {
            MaterialTheme {
                WearApp()
            }
        }
    }
}

@Composable
private fun WearApp() {
    val sessions by SessionRepository.sessions.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = selectedId?.let { id -> sessions.firstOrNull { it.id == id } }

    // Without this, Wear OS's system swipe-back gesture has no in-app back
    // stack to pop (this screen nav is just a mutableStateOf, not a real
    // NavHost) and falls through to dismissing/exiting the whole Activity
    // instead of just returning to the session list — reported on a real
    // device as "swipe back from a session kicks me out of the app".
    BackHandler(enabled = selectedId != null) { selectedId = null }

    if (selected != null) {
        SessionDetailScreen(session = selected, onBack = { selectedId = null })
    } else {
        SessionListScreen(sessions = sessions, onSelect = { selectedId = it })
    }
}

@Composable
private fun SessionListScreen(sessions: List<WearSessionInfo>, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    var autoSpeak by remember { mutableStateOf(AutoSpeakPrefs.isEnabled(context)) }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            // A plain toggle Button, not Switch — Wear Compose Material3's
            // Switch signature in 1.6.2 didn't match the expected
            // checked/onCheckedChange shape and isn't worth fighting blind
            // with no device to verify against; a labeled button is just as
            // functional for this one setting.
            Button(onClick = {
                val next = !autoSpeak
                autoSpeak = next
                AutoSpeakPrefs.setEnabled(context, next)
                if (!next) WatchTts.stop(context)
            }) {
                Text(if (autoSpeak) "Číst nahlas: ANO" else "Číst nahlas: NE")
            }
        }
        item { UpdateSection() }
        if (sessions.isEmpty()) {
            item { Text("No sessions yet") }
        }
        sessions.sortedByDescending { it.lastMessageAt }.forEach { session ->
            item { SessionRow(session, onClick = { onSelect(session.id) }) }
        }
    }
}

@Composable
private fun SessionRow(session: WearSessionInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(activityColor(session.activity)),
        )
        Spacer(Modifier.size(8.dp))
        Text(session.title, maxLines = 1)
    }
}

@Composable
private fun SessionDetailScreen(session: WearSessionInfo, onBack: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("") }

    val replyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = RemoteInput.getResultsFromIntent(result.data)?.getCharSequence(KEY_REPLY)?.toString()?.trim()
        if (!text.isNullOrBlank()) {
            status = "Odesílám…"
            sendReply(context, session.id, text) { status = it }
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Box(modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onBack) { Text("← Zpět") }
            }
        }
        item { Text(session.title) }
        item { Text(session.lastMessage ?: "(no message)") }
        item {
            Button(onClick = {
                val remoteInputs = listOf(RemoteInput.Builder(KEY_REPLY).setLabel("Odpověď pro Claude").build())
                val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                RemoteInputIntentHelper.putRemoteInputsExtra(intent, remoteInputs)
                replyLauncher.launch(intent)
            }) { Text("Odpovědět") }
        }
        if (session.activity == "APPROVAL_NEEDED") {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        status = "Odesílám…"
                        sendApprove(context, session.id, "y") { status = it }
                    }) { Text("Y") }
                    Button(onClick = {
                        status = "Odesílám…"
                        sendApprove(context, session.id, "n") { status = it }
                    }) { Text("N") }
                }
            }
        }
        item {
            Button(onClick = {
                session.lastMessage?.takeIf { it.isNotBlank() }?.let { WatchTts.speak(context, it, interrupt = true) }
            }) { Text("🔊 Přehrát") }
        }
        if (status.isNotBlank()) {
            item { Text(status) }
        }
    }
}

private fun activityColor(activity: String): Color = when (activity) {
    "WORKING" -> Color(0xFFFFB44E)
    "WAITING_FOR_INPUT" -> Color(0xFF4E9CFF)
    "APPROVAL_NEEDED" -> Color(0xFFFF5C5C)
    "DISCONNECTED" -> Color(0xFF6F7E96)
    else -> Color(0xFF4EE0A0) // IDLE
}

/**
 * One-shot fetch of the current "/sessions" DataItem (from any node, hence
 * host "*") so a freshly-launched watch app isn't empty until the phone's
 * next state change. Best-effort; failures just leave the list empty until
 * onDataChanged fires.
 */
private fun fetchInitialSessions(context: Context) {
    val uri = Uri.Builder().scheme("wear").authority("*").path(WearDataListenerService.PATH).build()
    Wearable.getDataClient(context).getDataItems(uri)
        .addOnSuccessListener { buffer ->
            WearLog.i(context, "WearMainActivity", "fetchInitialSessions: ${buffer.count} item(s)")
            runCatching {
                val item = if (buffer.count > 0) buffer[0] else null
                val json = item?.let { DataMapItem.fromDataItem(it).dataMap.getString(WearDataListenerService.KEY_JSON) }
                if (json != null) {
                    val payload = WearDataListenerService.WEAR_JSON.decodeFromString<WearSessionsPayload>(json)
                    SessionRepository.update(payload.sessions)
                    WearLog.i(context, "WearMainActivity", "fetchInitialSessions: loaded ${payload.sessions.size} sessions")
                }
            }.onFailure { e -> WearLog.w(context, "WearMainActivity", "fetchInitialSessions parse failed: ${e.message}") }
            buffer.release()
        }
        .addOnFailureListener { e -> WearLog.w(context, "WearMainActivity", "fetchInitialSessions getDataItems failed: ${e.message}") }
}

/**
 * Self-update: three explicit taps — check GitHub Releases, download the
 * APK, then install it — rather than one combined "download+install"
 * action. Splitting them means a flaky download can be retried without
 * re-installing, and (since the download is saved to a version-tagged file
 * rather than held only in memory) a failed install can be retried without
 * re-downloading the whole ~22 MB APK again. After the one-time ADB
 * sideload, this is the only thing future updates need.
 */
@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    var checking by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<WearUpdater.UpdateInfo?>(null) }
    var status by remember { mutableStateOf("") }
    // WearUpdater's callbacks fire on a background executor thread; Compose
    // state must be mutated on the main thread.
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var downloaded by remember(updateInfo?.version) {
        mutableStateOf(updateInfo?.let { WearUpdater.downloadedFile(context, it.version).exists() } ?: false)
    }

    // The download runs in WearUpdateService, independent of this Activity
    // (the whole point — it survives the screen going to ambient/the app
    // backgrounding) — poll for the file rather than needing a callback
    // channel back from the service.
    LaunchedEffect(updateInfo?.version) {
        val info = updateInfo ?: return@LaunchedEffect
        while (!downloaded) {
            kotlinx.coroutines.delay(1000)
            if (WearUpdater.downloadedFile(context, info.version).exists()) downloaded = true
        }
    }

    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Verze: ${BuildConfig.VERSION_NAME}")
        val info = updateInfo
        when {
            info == null -> {
                Button(
                    onClick = {
                        checking = true; status = ""
                        WearUpdater.checkLatest(
                            context = context,
                            onResult = { result ->
                                mainHandler.post {
                                    checking = false
                                    if (result != null && result.version != BuildConfig.VERSION_NAME) {
                                        updateInfo = result
                                    } else {
                                        status = "Máte nejnovější verzi"
                                    }
                                }
                            },
                            onError = { msg -> mainHandler.post { checking = false; status = "Chyba: $msg" } },
                        )
                    },
                    enabled = !checking,
                ) {
                    Text(if (checking) "Kontroluji…" else "Zkontrolovat aktualizace")
                }
            }
            !downloaded -> {
                Button(onClick = {
                    status = "Stahuji na pozadí — viz notifikace"
                    WearUpdateService.startDownload(context, info.version, info.downloadUrl)
                }) {
                    Text("Stáhnout v${info.version}")
                }
            }
            else -> {
                Button(onClick = {
                    status = "Instaluji na pozadí — viz notifikace"
                    WearUpdateService.startInstall(context, info.version)
                }) {
                    Text("Nainstalovat v${info.version}")
                }
            }
        }
        if (status.isNotBlank()) {
            Text(status)
        }
    }
}
