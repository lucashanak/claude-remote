package com.clauderemote.wear

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
        requestNotificationPermission()
        fetchInitialSessions(applicationContext)
        // Launched from a notification tap / full-screen wake — route to the
        // waiting session. NavRequest is the bridge to the Compose UI below.
        NavRequest.request(intent?.getStringExtra(WearNotifier.EXTRA_SESSION_ID))
        setContent {
            MaterialTheme {
                WearApp()
            }
        }
    }

    // singleTop (see manifest): a second notification tap while we're already
    // running is delivered here, not through a fresh onCreate, so read the
    // new session id here too.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NavRequest.request(intent.getStringExtra(WearNotifier.EXTRA_SESSION_ID))
    }

    /**
     * On Android 13+/Wear OS 4, notify() is a silent no-op until the user
     * grants POST_NOTIFICATIONS — which is why the update-progress and
     * install-confirm notifications never appeared. Ask once on launch.
     */
    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001,
            )
        }
    }
}

@Composable
private fun WearApp() {
    val sessions by SessionRepository.sessions.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = selectedId?.let { id -> sessions.firstOrNull { it.id == id } }

    // Notification/deep-link tap → open that session. Consumed immediately so
    // it fires once per tap and a later swipe-back to the list isn't dragged
    // straight back into the same session by a lingering value.
    val requestedId by NavRequest.requestedSessionId.collectAsState()
    LaunchedEffect(requestedId) {
        requestedId?.let { selectedId = it; NavRequest.consume() }
    }

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
    val lastSyncElapsed by SessionRepository.lastSyncElapsed.collectAsState()
    val hasLoaded by SessionRepository.hasLoaded.collectAsState()

    // Ticking "now" so the freshness label (e.g. "před 3 min") keeps
    // advancing while the screen is open, not just on the next data push.
    var nowElapsed by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowElapsed = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(10_000)
        }
    }

    // Optimistic default (true) so the banner doesn't flash on app start
    // before the first node probe below has a chance to run.
    var phoneConnected by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            Wearable.getNodeClient(context).connectedNodes
                .addOnSuccessListener { nodes -> phoneConnected = nodes.isNotEmpty() }
                .addOnFailureListener { /* best-effort probe; keep last known state */ }
            kotlinx.coroutines.delay(15_000)
        }
    }

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
        if (!phoneConnected) {
            item { Text("⚠ Telefon není připojen", color = Color(0xFFFF5C5C)) }
        }
        if (lastSyncElapsed > 0) {
            val ageMs = nowElapsed - lastSyncElapsed
            item {
                Text(
                    freshnessLabel(ageMs),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            !hasLoaded -> item { Text("Načítám…") }
            sessions.isEmpty() -> item { Text("Žádné aktivní sessions") }
        }
        sessions.sortedByDescending { it.lastMessageAt }.forEach { session ->
            item { SessionRow(session, onClick = { onSelect(session.id) }) }
        }
    }
}

/** Human-readable age of the last phone sync, in Czech, with a stale warning past 2 min. */
private fun freshnessLabel(ageMs: Long): String {
    val seconds = ageMs / 1000
    val label = when {
        seconds < 15 -> "právě teď"
        seconds < 60 -> "před ${seconds} s"
        seconds < 3600 -> "před ${seconds / 60} min"
        else -> "před ${seconds / 3600} h"
    }
    return if (seconds > 120) "⚠ možná zastaralé — $label" else label
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
    var dictating by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var stt by remember { mutableStateOf<SonioxWatchStt?>(null) }
    var speaking by remember { mutableStateOf(false) }
    // Zachycený diktát čekající na explicitní potvrzení (null = nic nečeká).
    // Diktát se NIKDY neodešle sám — přeřek/hluk se dá zrušit než odletí agentovi.
    var pendingDraft by remember { mutableStateOf<String?>(null) }
    // Race guard: back/zrušení nastaví tohle, aby pozdní onFinal (stop → onFinal
    // dojde asynchronně) už zachycený text nezapsal do pendingDraft.
    var cancelledDictation by remember { mutableStateOf(false) }
    // Zámek proti dvojímu odeslání — fire-and-forget bez ACK, takže nervózní
    // dvojtap by jinak poslal akci dvakrát živému agentovi.
    var sending by remember { mutableStateOf(false) }

    // Sjednocený dokončovací callback všech odeslání: zhasne zámek, přeloží
    // MessageClient výsledek do češtiny a bzikne na zápěstí. "Sent" znamená jen
    // že MessageClient převzal (ne že to Claude dostal), proto necháme
    // "Odesláno ✓" viset dokud další /sessions push session nepřerecomposuje.
    fun onSendResult(result: String) {
        sending = false
        status = if (result == "Sent") "Odesláno ✓" else "Chyba: $result"
        if (result == "Sent") WearHaptics.success(context) else WearHaptics.error(context)
    }

    // Až se session po odeslání reálně pohne (další /sessions push změní
    // activity), zahoď status, ať "Odesláno ✓" nezůstane viset po vyřešení.
    LaunchedEffect(session.activity) { status = "" }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { stt?.stop(); stt = null; SonioxWatchTts.stop() }
    }

    // Back během diktování / čekajícího draftu = ZRUŠIT, ne odeslat a ne skočit
    // na seznam (back spotřebujeme). Druhý back (nic se neděje) propadne do
    // parent BackHandleru ve WearApp; vnořený enabled BackHandler má přednost.
    BackHandler(enabled = dictating || pendingDraft != null) {
        cancelledDictation = true
        stt?.stop(); stt = null
        dictating = false
        pendingDraft = null
    }

    val replyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = RemoteInput.getResultsFromIntent(result.data)?.getCharSequence(KEY_REPLY)?.toString()?.trim()
        if (!text.isNullOrBlank() && !sending) {
            sending = true
            status = "Odesílám…"
            sendReply(context, session.id, text) { onSendResult(it) }
        }
    }

    fun startSonioxDictation() {
        cancelledDictation = false
        pendingDraft = null
        draft = ""
        val d = SonioxWatchStt(
            context = context.applicationContext,
            onPartial = { draft = it },
            // Jen naplň pendingDraft k potvrzení — NEODESÍLEJ tady. Když bylo
            // diktování mezitím zrušené (cancelledDictation), zachycený text zahoď.
            onFinal = { phrase ->
                stt = null
                dictating = false
                if (!cancelledDictation && phrase.isNotBlank()) pendingDraft = phrase
            },
            onError = { msg -> stt = null; dictating = false; status = "Chyba: $msg" },
            onListening = { status = "Poslouchám…" },
        )
        stt = d
        dictating = true
        d.start()
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startSonioxDictation() else status = "Bez mikrofonu to nejde" }

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
            Button(
                onClick = {
                    val remoteInputs = listOf(RemoteInput.Builder(KEY_REPLY).setLabel("Odpověď pro Claude").build())
                    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                    RemoteInputIntentHelper.putRemoteInputsExtra(intent, remoteInputs)
                    replyLauncher.launch(intent)
                },
                enabled = !sending,
            ) { Text("Odpovědět") }
        }
        // Soniox on-watch dictation — stream mic → transcript → potvrzení → send.
        item {
            Button(onClick = {
                if (dictating) {
                    stt?.stop() // stop → onFinal naplní pendingDraft k potvrzení
                } else if (ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    startSonioxDictation()
                } else {
                    micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            }) { Text(if (dictating) "⏹ Stop" else "🎤 Diktovat") }
        }
        if (dictating && draft.isNotBlank()) {
            item { Text(draft) }
        }
        // Confirm-before-send: zachycený diktát v ohraničení, ať je jasné CO se
        // pošle, s explicitním Odeslat / Zrušit / Znovu.
        val pending = pendingDraft
        if (pending != null && !dictating) {
            item {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(pending)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                if (!sending) {
                                    sending = true
                                    status = "Odesílám…"
                                    sendReply(context, session.id, pending) { onSendResult(it) }
                                    pendingDraft = null
                                }
                            },
                            enabled = !sending,
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Odeslat odpověď" },
                        ) { Text("✓") }
                        Button(
                            onClick = { pendingDraft = null },
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Zrušit" },
                        ) { Text("✗") }
                        Button(
                            onClick = {
                                pendingDraft = null
                                // Znovu jen když mikrofon máme; jinak nech uživatele
                                // tapnout Diktovat (kde běží permission flow).
                                if (ContextCompat.checkSelfPermission(
                                        context, android.Manifest.permission.RECORD_AUDIO,
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) startSonioxDictation()
                            },
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Diktovat znovu" },
                        ) { Text("↻") }
                    }
                }
            }
        }
        if (session.activity == "APPROVAL_NEEDED") {
            // Plná šířka pod sebou, ne vedle sebe: mis-tap schválit/zamítnout je
            // drahý, tak ať se cíle nepletou a jsou velké.
            item {
                Button(
                    onClick = {
                        if (!sending) {
                            sending = true
                            status = "Odesílám…"
                            sendApprove(context, session.id, "y") { onSendResult(it) }
                        }
                    },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Schválit" },
                ) { Text("✓ Ano (Y)") }
            }
            item {
                Button(
                    onClick = {
                        if (!sending) {
                            sending = true
                            status = "Odesílám…"
                            sendApprove(context, session.id, "n") { onSendResult(it) }
                        }
                    },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Zamítnout" },
                ) { Text("✗ Ne (N)") }
            }
        }
        item {
            Button(onClick = {
                if (speaking) {
                    SonioxWatchTts.stop()
                    speaking = false
                } else {
                    // Soniox voice when a key is synced; falls back to
                    // on-device WatchTts internally otherwise.
                    session.lastMessage?.takeIf { it.isNotBlank() }?.let {
                        speaking = true
                        SonioxWatchTts.speak(context, it) { speaking = false }
                    }
                }
            }) { Text(if (speaking) "⏹ Zastavit" else "🔊 Přehrát") }
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
                    SonioxKeyStore.update(payload.sonioxApiKey, payload.sonioxVoice, payload.ttsSpeedPct)
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
