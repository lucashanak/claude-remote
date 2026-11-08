package com.clauderemote.wear

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString

private const val KEY_REPLY = "reply_text"
private const val PAGE_WAITING = 0
private const val PAGE_JARVIS = 1
private const val PAGE_SETTINGS = 2

/**
 * Bylo POST_NOTIFICATIONS odmítnuto? Bez něj je `notify()` na API 33+ tichý
 * no-op: dlaždice i appka jedou dál, ale zápěstí ztichne a nikde se uživatel
 * nedozví proč. Držíme to jako flow, ať to jde ukázat na stránce Čeká.
 */
object NotificationPermissionState {
    private val _denied = MutableStateFlow(false)
    val denied: StateFlow<Boolean> = _denied

    fun refresh(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            _denied.value = false
            return
        }
        _denied.value = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Which page a launch asks for: Jarvis' merged notification opens his page ([EXTRA_PAGE] = [PAGE_EXTRA_JARVIS]),
 * everything else the Čeká page. A counter rides along, so a second tap on the same target still scrolls back to it.
 */
internal object OpenPage {
    const val EXTRA_PAGE = "page"
    const val PAGE_EXTRA_JARVIS = "jarvis"
    val target = MutableStateFlow(0 to PAGE_WAITING)

    fun go(intent: Intent?) {
        val page = if (intent?.getStringExtra(EXTRA_PAGE) == PAGE_EXTRA_JARVIS) PAGE_JARVIS else PAGE_WAITING
        target.value = (target.value.first + 1) to page
    }
}

/**
 * The watch app (2026-11-08, "na hodinky jen Jarvise a easy Velín"): three pages in a horizontal pager —
 * Čeká (default: dialogs waiting for an Ano/Ne and Jarvis' proposals), Jarvis (news on a tap, a dictated
 * instruction, his last alerts) and Nastavení. The session list is gone: 50 sessions on a wrist were too many.
 */
class MainActivity : ComponentActivity() {
    // Registrace musí proběhnout dřív, než Activity dosáhne STARTED — proto
    // inicializátor pole, ne volání v onCreate.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            NotificationPermissionState.refresh(this)
            WearLog.i(this, "WearMainActivity", "POST_NOTIFICATIONS granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        fetchInitialSessions(applicationContext)
        OpenPage.go(intent)
        setContent {
            MaterialTheme {
                WearApp()
            }
        }
    }

    // singleTop (see manifest): a notification tap while we're already running lands here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        OpenPage.go(intent)
    }

    /**
     * On Android 13+/Wear OS 4, notify() is a silent no-op until the user
     * grants POST_NOTIFICATIONS. Ask once on launch.
     */
    private fun requestNotificationPermission() {
        NotificationPermissionState.refresh(this)
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (!NotificationPermissionState.denied.value) return
        runCatching {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }.onFailure { e ->
            WearLog.w(this, "WearMainActivity", "POST_NOTIFICATIONS request failed: ${e.message}")
        }
    }

    // Uživatel mohl oprávnění přehodit v systémovém nastavení, aniž by appka
    // umřela — po návratu do popředí se stav přečte znovu, ať banner nelže.
    override fun onResume() {
        super.onResume()
        AppVisibility.visible = true
        NotificationPermissionState.refresh(this)
    }

    override fun onPause() {
        AppVisibility.visible = false
        super.onPause()
    }
}

@Composable
private fun WearApp() {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { 3 })
    val target by OpenPage.target.collectAsState()
    LaunchedEffect(target) { if (pagerState.currentPage != target.second) pagerState.scrollToPage(target.second) }

    // The phone answers the open app's Jarvis requests on its own paths (proposals, a decision, the news); the
    // listener lives as long as the app is open, nothing else on the watch asks for them.
    DisposableEffect(Unit) {
        val client = Wearable.getMessageClient(context)
        val listener = MessageClient.OnMessageReceivedListener { event -> JarvisWatchStore.onMessage(event.path, event.data) }
        client.addListener(listener)
        onDispose { client.removeListener(listener) }
    }

    // AppScaffold poskytuje TimeText na úrovni celé appky.
    AppScaffold {
        HorizontalPagerScaffold(pagerState = pagerState) {
            HorizontalPager(state = pagerState) { page ->
                val current = pagerState.currentPage == page
                when (page) {
                    PAGE_WAITING -> WaitingPage(current)
                    PAGE_JARVIS -> JarvisPage(current)
                    else -> SettingsPage(current)
                }
            }
        }
    }
}

/** One page: a scaling list with the crown wired to it, focused while its page is the current one. */
@Composable
private fun PageColumn(
    isCurrent: Boolean,
    listState: ScalingLazyListState = rememberScalingLazyListState(),
    content: ScalingLazyListScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isCurrent) { if (isCurrent) runCatching { focusRequester.requestFocus() } }
    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp)
                .rotaryScrollable(RotaryScrollableDefaults.behavior(listState), focusRequester),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

// ── Čeká ─────────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun WaitingPage(isCurrent: Boolean) {
    val context = LocalContext.current
    val sessions by SessionRepository.sessions.collectAsState()
    val hasLoaded by SessionRepository.hasLoaded.collectAsState()
    val props by JarvisWatchStore.proposals.collectAsState()
    val loading by JarvisWatchStore.proposalsLoading.collectAsState()
    val deciding by JarvisWatchStore.deciding.collectAsState()
    val notice by JarvisWatchStore.notice.collectAsState()
    val notificationsDenied by NotificationPermissionState.denied.collectAsState()

    // Optimistic default (true) so the banner doesn't flash before the first probe.
    var phoneConnected by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            Wearable.getNodeClient(context).connectedNodes
                .addOnSuccessListener { nodes -> phoneConnected = nodes.isNotEmpty() }
            delay(15_000)
        }
    }

    // Proposals are read on demand (one exec on Jarvis' server per read), never polled in the background: while the
    // page is on screen, now and every minute, and once more after a decision came back.
    LaunchedEffect(isCurrent) {
        var periodic = false
        while (isCurrent) {
            readProposals(context, periodic)
            periodic = true
            delay(60_000 - JarvisWatchStore.PROPOSALS_TIMEOUT_MS)
        }
    }
    LaunchedEffect(notice) { if (notice != null) readProposals(context, periodic = false) }
    // A decision whose answer never came (phone gone, app killed): free the buttons and say so.
    LaunchedEffect(deciding) {
        if (deciding.isNotEmpty()) {
            delay(75_000)
            JarvisWatchStore.deciding.value = emptySet()
            JarvisWatchStore.notice.value = "Telefon neodpověděl – stav ověř ve Velínu"
        }
    }

    val listState = rememberScalingLazyListState()
    // A proposal may be approved only once its whole text went by: the end marker below the text has been on screen.
    val seenEnds = remember { mutableStateListOf<String>() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key } }.collect { keys ->
            keys.mapNotNull { (it as? String)?.takeIf { k -> k.startsWith("end:") } }
                .forEach { if (it !in seenEnds) seenEnds.add(it) }
        }
    }
    var nowElapsed by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            nowElapsed = SystemClock.elapsedRealtime()
        }
    }

    val items = WaitingRules.items(sessions, props?.items.orEmpty())
    PageColumn(isCurrent, listState) {
        item(key = "head") { ListHeader { Text("Čeká") } }
        if (!phoneConnected) item(key = "phone") { Text("⚠ Telefon není připojen", color = COLOR_ERROR, fontSize = 13.sp) }
        if (notificationsDenied) {
            item(key = "perm") { Text("⚠ Notifikace jsou vypnuté v nastavení hodinek", color = COLOR_WARN, fontSize = 13.sp) }
        }
        notice?.let { n -> item(key = "notice") { Text(n, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) } }
        if (items.isEmpty()) {
            item(key = "empty") {
                Text(
                    when {
                        !hasLoaded -> "Načítám…"
                        props == null && loading -> "Načítám návrhy…"
                        else -> "Klid, pane."
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        for (entry in items) {
            when (entry) {
                is WaitingRules.Item.Ask -> item(key = entry.key) { AskCard(entry) }
                is WaitingRules.Item.Proposal -> {
                    val p = entry.proposal
                    val at = props?.atElapsed ?: 0L
                    item(key = entry.key) { ProposalText(p) }
                    // keyed by id AND text: a text changed under the same id must be scrolled through again (L6)
                    item(key = WaitingRules.endKey(p)) {
                        val expired = JarvisWatchStore.expired(p, at, nowElapsed)
                        ProposalActions(
                            p,
                            canApprove = WaitingRules.canApprove(p, seenEnds, expired, busy = p.id in deciding),
                            expired = expired,
                            busy = p.id in deciding,
                        )
                    }
                }
            }
        }
        props?.error?.let { e -> item(key = "error") { Text("⚠ $e", color = COLOR_WARN, fontSize = 13.sp) } }
    }
}

/** One proposals read: sent, then given [JarvisWatchStore.PROPOSALS_TIMEOUT_MS] to come back (L7). */
private suspend fun readProposals(context: Context, periodic: Boolean) {
    val token = JarvisWatchStore.beginProposals(periodic)
    sendJarvisProposalsRequest(context, periodic) { r -> if (r != "Sent") JarvisWatchStore.proposalsTimedOut(token) }
    delay(JarvisWatchStore.PROPOSALS_TIMEOUT_MS)
    JarvisWatchStore.proposalsTimedOut(token)
}

/** A session's open dialog: the whole text, Ano/Ne; a question also an answer (typed or dictated). */
@Composable
private fun AskCard(item: WaitingRules.Item.Ask) {
    val context = LocalContext.current
    val s = item.session
    var status by remember(s.prompt) { mutableStateOf("") }
    var sending by remember(s.prompt) { mutableStateOf(false) }
    fun approve(answer: String) {
        if (sending) return
        sending = true
        status = "Odesílám…"
        // The identity of the dialog shown on this card: the phone types nothing if the session shows another by now.
        sendApprove(context, s.id, answer, s.prompt) { r ->
            sending = false
            status = sentText(r)
            if (r == "Sent") WearHaptics.success(context) else WearHaptics.error(context)
        }
    }
    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Text(
            (if (item.kind == WaitingRules.DialogKind.QUESTION) "? " else "▲ ") + s.title,
            color = COLOR_APPROVAL,
            fontSize = 13.sp,
        )
        Text(item.body, fontSize = 13.sp)
        if (item.kind == null) {
            // An approval the phone cannot name (review H1): it waits, but an answer from here could not be checked.
            Text(WaitingRules.UNNAMED_TEXT, fontSize = 12.sp, color = COLOR_WARN)
            return@Card
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            // Side by side but each half the width: a wrong tap is expensive, so they are big and labelled.
            CompactButton(
                onClick = { approve("y") },
                enabled = !sending,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Schválit" },
            ) { Text("✓ Ano") }
            CompactButton(
                onClick = { approve("n") },
                enabled = !sending,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Zamítnout" },
            ) { Text("✗ Ne") }
        }
        if (item.kind == WaitingRules.DialogKind.QUESTION) {
            ReplyControls(
                identity = s.prompt,
                enabled = true,
                dictateLabel = "🎤 Diktovat odpověď",
                typeLabel = "Odpovědět",
            ) { text, prompt, done -> sendReply(context, s.id, text, prompt, done) }
        }
        if (status.isNotBlank()) Text(status, fontSize = 12.sp)
    }
}

@Composable
private fun ProposalText(p: WearJarvisProposal) {
    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Text(p.heading, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        p.title?.takeIf { it.isNotBlank() && it != p.text }?.let { Text(it, fontSize = 13.sp) }
        Text(p.text, fontSize = 13.sp)
    }
}

/**
 * Below the text, so only reachable by scrolling through it: "Poslat"/"Provést" once the whole text went by ([seen]),
 * never for a proposal whose full text did not come or that lapsed; "Zahodit" always. The phone checks again against a
 * fresh read and approves only the very text shown here.
 */
@Composable
private fun ProposalActions(p: WearJarvisProposal, canApprove: Boolean, expired: Boolean, busy: Boolean) {
    val context = LocalContext.current
    fun decide(approve: Boolean) {
        JarvisWatchStore.deciding.value = JarvisWatchStore.deciding.value + p.id
        JarvisWatchStore.notice.value = null
        sendJarvisDecide(context, p.id, approve, p.text.hashCode()) { r ->
            if (r != "Sent") {
                JarvisWatchStore.deciding.value = JarvisWatchStore.deciding.value - p.id
                JarvisWatchStore.notice.value = "${p.heading}: neodesláno ($r)"
                WearHaptics.error(context)
            }
        }
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            !p.fullText -> Text("Celý text je jen v aplikaci – schval ho tam", fontSize = 12.sp, color = COLOR_WARN)
            expired -> Text("Návrh vypršel", fontSize = 12.sp, color = COLOR_WARN)
            else -> CompactButton(
                onClick = { decide(true) },
                enabled = canApprove,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "…" else p.approveLabel) }
        }
        CompactButton(onClick = { decide(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Zahodit") }
    }
}

// ── Jarvis ───────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun JarvisPage(isCurrent: Boolean) {
    val context = LocalContext.current
    val jarvis by SessionRepository.jarvis.collectAsState()
    LaunchedEffect(Unit) { JarvisRecentStore.load(context) }
    val recent by JarvisRecentStore.recent.collectAsState()
    val news by JarvisWatchStore.news.collectAsState()
    val newsLoading by JarvisWatchStore.newsLoading.collectAsState()
    var speaking by remember { mutableStateOf(false) }
    // Read aloud only as the answer to a tap ("Co je nového?"), never on its own.
    var readOnArrival by remember { mutableStateOf(false) }
    fun speak(text: String) {
        speaking = true
        SonioxWatchTts.speak(context, text) { speaking = false }
    }
    LaunchedEffect(news) {
        val n = news ?: return@LaunchedEffect
        if (readOnArrival && n.ok && n.text.isNotBlank()) speak(n.text)
        readOnArrival = false
    }
    LaunchedEffect(newsLoading) {
        if (newsLoading) {
            delay(30_000)
            if (JarvisWatchStore.newsLoading.value) {
                JarvisWatchStore.newsLoading.value = false
                JarvisWatchStore.news.value = WearJarvisNews(false, "Telefon neodpověděl")
                readOnArrival = false
            }
        }
    }
    DisposableEffect(Unit) { onDispose { SonioxWatchTts.stop() } }

    PageColumn(isCurrent) {
        item(key = "head") { ListHeader { Text("Jarvis") } }
        item(key = "state") {
            val j = jarvis
            Text(
                when {
                    j == null -> "Telefon o Jarvisovi neví"
                    j.alive -> "● běží · ${j.pending} čeká"
                    else -> "○ neodpovídá · ${j.pending} čeká"
                },
                fontSize = 12.sp,
                color = if (j?.alive == true) MaterialTheme.colorScheme.primary else COLOR_MUTED,
            )
        }
        item(key = "ask") {
            Button(
                onClick = {
                    SonioxWatchTts.stop()
                    speaking = false
                    readOnArrival = true
                    JarvisWatchStore.newsLoading.value = true
                    sendJarvisNewsRequest(context) { r ->
                        if (r != "Sent") {
                            JarvisWatchStore.newsLoading.value = false
                            JarvisWatchStore.news.value = WearJarvisNews(false, "Neodesláno: $r")
                            readOnArrival = false
                        }
                    }
                },
                enabled = !newsLoading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (newsLoading) "Ptám se…" else "Co je nového?") }
        }
        news?.let { n ->
            item(key = "news") {
                Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    Text(n.text, fontSize = 13.sp, color = if (n.ok) Color.Unspecified else COLOR_WARN)
                    if (n.ok && n.text.isNotBlank()) {
                        CompactButton(
                            onClick = { if (speaking) { SonioxWatchTts.stop(); speaking = false } else speak(n.text) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (speaking) "⏹ Zastavit" else "🔊 Přečíst") }
                    }
                }
            }
        }
        item(key = "dictate") {
            val tab = jarvis?.sessionId
            if (tab == null) {
                Text("Jarvis nemá na telefonu kartu – diktovat mu jde, až ho telefon otevře.", fontSize = 12.sp, color = COLOR_MUTED)
            } else {
                // An instruction (ReplyGuard INSTRUCTION): queued while he works, refused while a dialog is open.
                ReplyControls(
                    identity = JARVIS_INSTRUCTION_PROMPT,
                    enabled = true,
                    dictateLabel = "🎤 Diktovat Jarvisovi",
                    typeLabel = "⌨ Napsat Jarvisovi",
                ) { text, prompt, done -> sendReply(context, tab, text, prompt, done) }
            }
        }
        val alerts = recent.orEmpty()
        if (alerts.isNotEmpty()) {
            item(key = "recent-head") { ListHeader { Text("Poslední upozornění") } }
            for (a in alerts) {
                item(key = "recent:${a.id}") {
                    Text("${JarvisRecentStore.glyph(a.kind)} ${a.text}", fontSize = 13.sp)
                }
            }
        }
    }
}

// ── Answering: dictation (Soniox) or the system input, confirmed before it goes ──────────────────────────────────

/**
 * Dictate or type an answer, see it, confirm it. [identity] is what is being answered (a dialog's prompt identity,
 * or Jarvis' INSTRUCTION); it is captured when answering starts, and an answer begun for one identity is thrown away
 * if the card shows another by the time it would go (a dictation takes 15 s, a new dialog may open meanwhile).
 * [onSend] gets the text, the captured identity and the MessageClient result callback.
 */
@Composable
private fun ReplyControls(
    identity: String?,
    enabled: Boolean,
    dictateLabel: String,
    typeLabel: String,
    onSend: (text: String, identity: String?, done: (String) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("") }
    var dictating by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var stt by remember { mutableStateOf<SonioxWatchStt?>(null) }
    // Diktát se NIKDY neodešle sám — přeřek/hluk se dá zrušit, než odletí.
    var pendingDraft by remember { mutableStateOf<String?>(null) }
    // Pozdní onFinal po zrušení už nesmí nic zapsat.
    var cancelled by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var captured by remember { mutableStateOf<String?>(null) }
    var answering by remember { mutableStateOf(false) }

    // Bez rozsvíceného displeje OS přiškrtí mikrofon a Soniox dostane ticho.
    val view = LocalView.current
    LaunchedEffect(dictating) { view.keepScreenOn = dictating }
    DisposableEffect(Unit) { onDispose { stt?.stop(); stt = null; view.keepScreenOn = false } }

    fun cancel() {
        cancelled = true
        stt?.stop(); stt = null
        dictating = false
        pendingDraft = null
        answering = false
        captured = null
    }
    LaunchedEffect(identity) {
        if (answering && captured != identity) {
            cancel()
            status = "Dotaz se změnil – odpověď zahozena"
            WearHaptics.error(context)
        }
    }
    BackHandler(enabled = dictating || pendingDraft != null) { cancel() }

    fun send(text: String) {
        if (sending) return
        if (!answering || captured != identity) {
            cancel()
            status = "Dotaz se změnil – odpověď zahozena"
            WearHaptics.error(context)
            return
        }
        sending = true
        status = "Odesílám…"
        val forIdentity = captured
        pendingDraft = null
        answering = false
        onSend(text, forIdentity) { r ->
            sending = false
            status = sentText(r)
            if (r == "Sent") WearHaptics.success(context) else WearHaptics.error(context)
        }
    }

    val replyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = RemoteInput.getResultsFromIntent(result.data)?.getCharSequence(KEY_REPLY)?.toString()?.trim()
        if (text.isNullOrBlank()) answering = false else send(text)
    }

    fun startDictation() {
        captured = identity
        answering = true
        cancelled = false
        pendingDraft = null
        draft = ""
        status = ""
        val d = SonioxWatchStt(
            context = context.applicationContext,
            onPartial = { draft = it },
            onFinal = { phrase ->
                stt = null
                dictating = false
                if (!cancelled && phrase.isNotBlank()) pendingDraft = phrase else if (!cancelled) answering = false
            },
            onError = { msg -> stt = null; dictating = false; answering = false; status = "Chyba: $msg" },
            onListening = { status = "Poslouchám…" },
        )
        stt = d
        dictating = true
        d.start()
    }
    val micPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startDictation() else status = "Bez mikrofonu to nejde"
    }
    fun toggleDictation() {
        if (dictating) {
            stt?.stop() // stop → onFinal naplní pendingDraft k potvrzení
        } else if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startDictation()
        } else {
            micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }
    fun openKeyboard() {
        captured = identity
        answering = true
        status = ""
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(KEY_REPLY).setLabel(typeLabel).build()))
        replyLauncher.launch(intent)
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CompactButton(
            onClick = { toggleDictation() },
            enabled = enabled && !sending && pendingDraft == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (dictating) "⏹ Stop" else dictateLabel) }
        CompactButton(
            onClick = { openKeyboard() },
            enabled = enabled && !sending && !dictating && pendingDraft == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(typeLabel) }
        if (dictating && draft.isNotBlank()) Text(draft, fontSize = 13.sp)
        val pending = pendingDraft
        if (pending != null && !dictating) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(pending, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactButton(
                        onClick = { send(pending) },
                        enabled = !sending,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Odeslat" },
                    ) { Text("✓") }
                    CompactButton(
                        onClick = { cancel() },
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Zrušit" },
                    ) { Text("✗") }
                    CompactButton(
                        onClick = {
                            pendingDraft = null
                            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) startDictation()
                        },
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Diktovat znovu" },
                    ) { Text("↻") }
                }
            }
        }
        if (status.isNotBlank()) Text(status, fontSize = 12.sp)
    }
}

/** "Sent" means only that MessageClient took it; the phone's refusal, if any, comes as a notification. */
private fun sentText(result: String): String = if (result == "Sent") "Odesláno ✓" else "Chyba: $result"

// ── Nastavení ────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsPage(isCurrent: Boolean) {
    val context = LocalContext.current
    var autoSpeak by remember { mutableStateOf(AutoSpeakPrefs.isEnabled(context)) }
    PageColumn(isCurrent) {
        item(key = "head") { ListHeader { Text("Nastavení") } }
        item(key = "speak") {
            // A plain toggle Button, not Switch — Wear Compose Material3's
            // Switch signature in 1.6.2 didn't match the expected shape.
            Button(onClick = {
                val next = !autoSpeak
                autoSpeak = next
                AutoSpeakPrefs.setEnabled(context, next)
                if (!next) WatchTts.stop(context)
            }) {
                Text(if (autoSpeak) "Číst schválení nahlas: ANO" else "Číst schválení nahlas: NE")
            }
        }
        item(key = "update") { UpdateSection() }
    }
}

private val COLOR_ERROR = Color(0xFFFF5C5C)
private val COLOR_WARN = Color(0xFFFFB74D)
private val COLOR_APPROVAL = Color(0xFFFF5C5C)
private val COLOR_MUTED = Color(0xFFB0B0B0)

/**
 * One-shot fetch of the current "/sessions" DataItem (from any node, hence
 * host "*") so a freshly-launched watch app isn't empty until the phone's
 * next state change. Best-effort; failures just leave the pages empty until
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
                    // Všechny ČTYŘI hodnoty — bez poslední by se dictationSilenceMs
                    // vrátilo na default 4000 a přepsalo to, co telefon nasynchronizoval.
                    SonioxKeyStore.update(
                        payload.sonioxApiKey,
                        payload.sonioxVoice,
                        payload.ttsSpeedPct,
                        payload.dictationSilenceMs,
                    )
                    SessionRepository.update(payload.sessions)
                    SessionRepository.updateJarvis(payload.jarvis)
                    JarvisRecentStore.update(context, payload.jarvis?.recent.orEmpty())
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
 * re-downloading the whole ~22 MB APK again.
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

    // The download runs in WearUpdateService, independent of this Activity —
    // poll for the file rather than needing a callback channel back from it.
    LaunchedEffect(updateInfo?.version) {
        val info = updateInfo ?: return@LaunchedEffect
        while (!downloaded) {
            delay(1000)
            if (WearUpdater.downloadedFile(context, info.version).exists()) downloaded = true
        }
    }

    Column(
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
