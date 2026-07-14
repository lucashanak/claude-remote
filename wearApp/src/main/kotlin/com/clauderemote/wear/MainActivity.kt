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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
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
    // Třetí "obrazovka" vedle seznamu a detailu. Nastavení jsou slepá ulička,
    // proto stačí prostý boolean stav (nav je jen mutableStateOf, ne NavHost).
    var showSettings by remember { mutableStateOf(false) }
    val selected = selectedId?.let { id -> sessions.firstOrNull { it.id == id } }

    // Notification/deep-link tap → open that session. Consumed immediately so
    // it fires once per tap and a later swipe-back to the list isn't dragged
    // straight back into the same session by a lingering value.
    val requestedId by NavRequest.requestedSessionId.collectAsState()
    LaunchedEffect(requestedId) {
        requestedId?.let { selectedId = it; showSettings = false; NavRequest.consume() }
    }

    // Without this, Wear OS's system swipe-back gesture has no in-app back
    // stack to pop (this screen nav is just a mutableStateOf, not a real
    // NavHost) and falls through to dismissing/exiting the whole Activity
    // instead of just returning to the session list — reported on a real
    // device as "swipe back from a session kicks me out of the app".
    BackHandler(enabled = selectedId != null) { selectedId = null }
    // Swipe-back ze Settings zpět na seznam (Settings se otevírá jen ze
    // seznamu, takže se s tím pro detail výše nepere).
    BackHandler(enabled = showSettings) { showSettings = false }

    // AppScaffold poskytuje TimeText na úrovni celé appky zadarmo (výchozí
    // timeText slot) — nevoláme TimeText napřímo, ať se nepereme s jeho
    // signaturou v M3 1.6.2 (viz poznámka u ScreenScaffold níže).
    AppScaffold {
        when {
            showSettings -> SettingsScreen(onBack = { showSettings = false })
            selected != null -> SessionDetailScreen(session = selected)
            else -> SessionListScreen(
                sessions = sessions,
                onSelect = { selectedId = it },
                onOpenSettings = { showSettings = true },
            )
        }
    }
}

@Composable
private fun SessionListScreen(
    sessions: List<WearSessionInfo>,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val hasLoaded by SessionRepository.hasLoaded.collectAsState()

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

    // Rotary (crown) scroll napojený na stejný list state jako ScalingLazyColumn.
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Rozděl na "čeká na vás" (APPROVAL_NEEDED → WAITING_FOR_INPUT, v tomto
    // pořadí priority) a "ostatní" (nejnovější nahoře). Glanceable: to, co
    // vyžaduje akci, je vždy nahoře.
    // Priorita akce první (APPROVAL_NEEDED → WAITING_FOR_INPUT), ale UVNITŘ
    // stejné priority nejnovější nahoře — jinak "čeká na vás" ignoruje pořadí
    // posledního použití, což působí jako by se seznam neřadil vůbec.
    val needsAction = sessions.filter { actionPriority(it.activity) < 2 }
        .sortedWith(compareBy({ actionPriority(it.activity) }, { -it.lastMessageAt }))
    val others = sessions.filter { actionPriority(it.activity) == 2 }
        .sortedByDescending { it.lastMessageAt }

    // ScreenScaffold: použito v nejjednodušší doložitelné podobě
    // ScreenScaffold(scrollState = listState) { ... }. Pokud by signatura v
    // 1.6.2 zlobila, stačí obal odstranit (obsah zůstane funkční) — TimeText
    // dodává AppScaffold výše.
    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp)
                .rotaryScrollable(RotaryScrollableDefaults.behavior(listState), focusRequester),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!phoneConnected) {
                item { Text("⚠ Telefon není připojen", color = Color(0xFFFF5C5C)) }
            }
            // Záměrně BEZ "aktualizováno před X": telefon pushuje jen při
            // ZMĚNĚ session (+ DataItem dedup), takže čas od posledního pushe
            // = čas od poslední změny, NE míra zastaralosti. Ukazovat ho
            // svádělo k dojmu "rozbité spojení", i když data byla aktuální.
            // Skutečný signál nese banner výše (connectedNodes probe).
            // Badge jen když je co řešit — jinak by zabíral cenný horní slot.
            if (needsAction.isNotEmpty()) {
                item {
                    Text(
                        "${needsAction.size} čeká na vás",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                    )
                }
            }
            if (needsAction.isNotEmpty()) {
                item { ListHeader { Text("ČEKÁ NA VÁS") } }
                needsAction.forEach { session ->
                    item { SessionRow(session, onClick = { onSelect(session.id) }) }
                }
            }
            if (others.isNotEmpty()) {
                item { ListHeader { Text("OSTATNÍ") } }
                others.forEach { session ->
                    item { SessionRow(session, onClick = { onSelect(session.id) }) }
                }
            }
            when {
                !hasLoaded -> item { Text("Načítám…") }
                sessions.isEmpty() -> item { Text("Žádné aktivní sessions") }
            }
            // Nastavení patří pryč z hlavního toku — malé tlačítko na konci.
            item { OutlinedButton(onClick = onOpenSettings) { Text("⚙ Nastavení") } }
        }
    }
}


@Composable
private fun SessionRow(session: WearSessionInfo, onClick: () -> Unit) {
    val color = activityColor(session.activity)
    val label = activityLabel(session.activity)
    // Card místo prostého dot+text řádku — ohraničený, klikací blok s větším
    // dotykovým cílem. (TitleCard by byl idiomatičtější, ale jeho title/subtitle
    // sloty mají v M3 1.6.2 křehčí signaturu; Card s vlastním obsahem je
    // signaturově nejbezpečnější a dává plnou kontrolu nad layoutem.)
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            // Barva sama nestačí (TalkBack, barvoslepost) — stav i do popisu.
            .semantics { contentDescription = "$label: ${session.title}" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Stavový glyph obarvený stejně jako label — tvar nese informaci
            // i bez barvy.
            Text(activityGlyph(session.activity), color = color)
            Spacer(Modifier.size(6.dp))
            Text(
                session.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(label, color = color, fontSize = 12.sp)
    }
}

@Composable
private fun SessionDetailScreen(session: WearSessionInfo) {
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

    // Drž displej rozsvícený po dobu diktování. Bez toho watch po pár
    // vteřinách ztlumí/uspí obrazovku, OS přiškrtí mic capture a Soniox
    // dostane jen ticho → dřív se to projevovalo jako "final (0 chars)".
    val view = LocalView.current
    LaunchedEffect(dictating) { view.keepScreenOn = dictating }

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

    // Přepínač diktování — sdílený mezi kontextovými bloky níže, ať se logika
    // (stop / permission flow / start) neduplikuje.
    fun toggleDictation() {
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
    }

    // Otevře systémový RemoteInput (klávesnice / hlas) pro textovou odpověď.
    fun openReply() {
        val remoteInputs = listOf(RemoteInput.Builder(KEY_REPLY).setLabel("Odpověď pro Claude").build())
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, remoteInputs)
        replyLauncher.launch(intent)
    }

    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // ScreenScaffold + rotary jako u seznamu (viz poznámka tam). ŽÁDNÉ "← Zpět"
    // tlačítko — Wear má systémový swipe-back (řeší parent BackHandler ve
    // WearApp, ověřeno na zařízení), takže uvolníme nejcennější horní slot.
    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .rotaryScrollable(RotaryScrollableDefaults.behavior(listState), focusRequester),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text(session.title, fontSize = 13.sp) }

            // Akce NAD zprávou a kompaktní (CompactButton) — ať jsou hned
            // dosažitelné bez scrollování přes (klidně dlouhou) zprávu; ta je
            // teď až pod nimi. Y/N zůstávají na plnou šířku pod sebou (snadný
            // cíl, mis-tap schválit/zamítnout je drahý), jen nižší.
            when (session.activity) {
                "APPROVAL_NEEDED" -> {
                    // Plná šířka pod sebou, ne vedle sebe: mis-tap schválit/zamítnout
                    // je drahý, tak ať se cíle nepletou a jsou velké.
                    item {
                        CompactButton(
                            onClick = {
                                if (!sending) {
                                    sending = true
                                    status = "Odesílám…"
                                    sendApprove(context, session.id, "y") { onSendResult(it) }
                                }
                            },
                            enabled = !sending,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Schválit" },
                        ) { Text("✓ Ano") }
                    }
                    item {
                        CompactButton(
                            onClick = {
                                if (!sending) {
                                    sending = true
                                    status = "Odesílám…"
                                    sendApprove(context, session.id, "n") { onSendResult(it) }
                                }
                            },
                            enabled = !sending,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Zamítnout" },
                        ) { Text("✗ Ne") }
                    }
                }
                "WAITING_FOR_INPUT" -> {
                    // Diktování je primární akce, textová odpověď sekundární.
                    item {
                        CompactButton(
                            onClick = { toggleDictation() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (dictating) "⏹ Stop" else "🎤 Diktovat") }
                    }
                    item {
                        CompactButton(
                            onClick = { openReply() },
                            enabled = !sending,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Odpovědět") }
                    }
                }
                else -> {
                    // WORKING / IDLE / DISCONNECTED — žádné Y/N; jen běžné akce.
                    item {
                        CompactButton(
                            onClick = { openReply() },
                            enabled = !sending,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Odpovědět") }
                    }
                    item {
                        CompactButton(
                            onClick = { toggleDictation() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (dictating) "⏹ Stop" else "🎤 Diktovat") }
                    }
                }
            }

            // Live náhled diktátu — dokud posloucháme.
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
                            CompactButton(
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
                            CompactButton(
                                onClick = { pendingDraft = null },
                                modifier = Modifier.weight(1f).semantics { contentDescription = "Zrušit" },
                            ) { Text("✗") }
                            CompactButton(
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

            // TTS jako malá sekundární akce. Soniox voice když je klíč
            // nasynchronizovaný, jinak interně padá na on-device WatchTts.
            item {
                CompactButton(
                    onClick = {
                        if (speaking) {
                            SonioxWatchTts.stop()
                            speaking = false
                        } else {
                            session.lastMessage?.takeIf { it.isNotBlank() }?.let {
                                speaking = true
                                SonioxWatchTts.speak(context, it) { speaking = false }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (speaking) "⏹ Zastavit" else "🔊 Přehrát") }
            }
            // Zpráva až POD akcemi (uživatel chtěl tlačítka nad zprávou). Bez
            // maxLines ořezu — dlouhá se scrolluje, akce zůstávají nahoře.
            item { Card(onClick = {}) { Text(session.lastMessage ?: "(no message)") } }
            if (status.isNotBlank()) {
                item { Text(status) }
            }
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var autoSpeak by remember { mutableStateOf(AutoSpeakPrefs.isEnabled(context)) }

    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp)
                .rotaryScrollable(RotaryScrollableDefaults.behavior(listState), focusRequester),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("Nastavení") }
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
            // Settings je slepá ulička — swipe-back funguje taky, ale explicitní
            // Zavřít je jistota.
            item { OutlinedButton(onClick = onBack) { Text("Zavřít") } }
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

/** Stavový glyph — tvar nese informaci i bez barvy (barvoslepost, ambient). */
private fun activityGlyph(activity: String): String = when (activity) {
    "APPROVAL_NEEDED" -> "▲"
    "WAITING_FOR_INPUT" -> "✎"
    "WORKING" -> "⋯"
    "DISCONNECTED" -> "⭘"
    else -> "●" // IDLE
}

/** Krátký český stavový label pod titulem session. */
private fun activityLabel(activity: String): String = when (activity) {
    "APPROVAL_NEEDED" -> "Čeká schválení"
    "WAITING_FOR_INPUT" -> "Napište"
    "WORKING" -> "Pracuje"
    "DISCONNECTED" -> "Odpojeno"
    else -> "Nečinné" // IDLE
}

/**
 * Priorita pro řazení/sekce: 0 = potřebuje schválit, 1 = čeká na vstup,
 * 2 = ostatní. 0 a 1 tvoří sekci "ČEKÁ NA VÁS", 2 sekci "OSTATNÍ".
 */
private fun actionPriority(activity: String): Int = when (activity) {
    "APPROVAL_NEEDED" -> 0
    "WAITING_FOR_INPUT" -> 1
    else -> 2
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
