package com.clauderemote.wear

import android.app.RemoteInput
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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
            }) {
                Text(if (autoSpeak) "Číst nahlas: ANO" else "Číst nahlas: NE")
            }
        }
        item { UpdateSection() }
        if (sessions.isEmpty()) {
            item { Text("No sessions yet") }
        }
        sessions.forEach { session ->
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
                session.lastMessage?.takeIf { it.isNotBlank() }?.let { WatchTts.speak(context, it) }
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
            runCatching {
                val item = if (buffer.count > 0) buffer[0] else null
                val json = item?.let { DataMapItem.fromDataItem(it).dataMap.getString(WearDataListenerService.KEY_JSON) }
                if (json != null) {
                    val payload = WearDataListenerService.WEAR_JSON.decodeFromString<WearSessionsPayload>(json)
                    SessionRepository.update(payload.sessions)
                }
            }
            buffer.release()
        }
}

/**
 * Self-update: check GitHub Releases, then download+install on demand. After
 * the one-time ADB sideload, this is the only thing future updates need —
 * a tap here, then a tap to confirm the system's install dialog.
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

    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Verze: ${BuildConfig.VERSION_NAME}")
        val info = updateInfo
        if (info == null) {
            Button(
                onClick = {
                    checking = true; status = ""
                    WearUpdater.checkLatest(
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
        } else {
            Button(onClick = {
                status = "Stahuji…"
                WearUpdater.downloadAndInstall(
                    context, info.downloadUrl,
                    onProgress = { msg -> mainHandler.post { status = msg } },
                    onError = { msg -> mainHandler.post { status = "Chyba: $msg" } },
                )
            }) {
                Text("Aktualizovat na v${info.version}")
            }
        }
        if (status.isNotBlank()) {
            Text(status)
        }
    }
}
