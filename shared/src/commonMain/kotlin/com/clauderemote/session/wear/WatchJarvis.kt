package com.clauderemote.session.wear

import com.clauderemote.session.QuickAccess
import com.clauderemote.session.service.JarvisAlert
import com.clauderemote.session.service.JarvisData
import com.clauderemote.session.service.JarvisProposal
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * The watch as a Jarvis remote (2026-11-08, "na hodinky jen Jarvise a easy Velín"): the wrist no longer lists
 * sessions. It shows what waits for Lukáš (perm/ask of sessions, Jarvis' proposals), Jarvis' news on a tap and a
 * dictated instruction for Jarvis. Everything the phone does for it goes through the CLI paths the Velín already uses
 * ([JarvisData]); this object is the pure part (commands, mapping, the checks before an approval), testable on the
 * desktop. The wire models below are encoded by androidApp's PhoneWearService and mirrored in wearApp.
 */

/** One proposal card on the watch. [text] is shown whole before [approveLabel] may be tapped. */
@Serializable
data class WatchProposal(
    val id: String,
    /** `mail | open | close | restore`. */
    val kind: String,
    /** "Návrh zprávy → soniox", "Návrh: zavřít x" ([JarvisData.heading]). */
    val heading: String,
    /** The CLI's one sentence, may be null (mail fallback). */
    val title: String? = null,
    val text: String,
    /** [text] is the full wording: only then may the watch approve (rev-f3-app MED6, the Velín's rule). */
    val fullText: Boolean,
    /** "Poslat" for mail, "Provést" for an action. */
    val approveLabel: String,
    /** Until it lapses by the SERVER's clock at the read, ms; null = unknown. Relative, so the watch clock never matters. */
    val expiresInMs: Long? = null,
)

/** Phone -> watch on /jarvis-proposals. [ok] false = could not be read ([error] says why), never "nothing waits". */
@Serializable
data class WatchProposals(val ok: Boolean, val error: String? = null, val items: List<WatchProposal> = emptyList())

/** Watch -> phone on /jarvis-decide. [textHash] = `String.hashCode()` of the [WatchProposal.text] the wrist showed. */
@Serializable
data class WatchDecideRequest(val id: String, val approve: Boolean, val textHash: Int = 0)

/** Phone -> watch on /jarvis-decide-result: one Czech sentence. */
@Serializable
data class WatchDecideResult(val id: String, val ok: Boolean, val text: String)

/** Phone -> watch on /jarvis-news: `cr-digest delta --peek --say`, or why it could not be read. */
@Serializable
data class WatchNews(val ok: Boolean, val text: String)

object WatchJarvis {
    /** A card longer than this is not sent whole: the wrist then only says "celé v aplikaci" and cannot approve it. */
    const val TEXT_MAX = 4000
    const val NEWS_MAX = 1500
    /** How many of Jarvis' recent alerts ride the /sessions push (the Jarvis page shows ~5). */
    const val RECENT_MAX = 5
    private const val ALERT_TEXT_MAX = 240

    private const val BIN = "~/.local/bin/"
    private const val RC = "__cr_rc="

    /**
     * "Co je nového?": `--peek` so the wrist never moves the cursor Jarvis and the Velín read the delta from (the
     * answer is a look, not a hand-over). Exit status last, as [JarvisData.parseRun] expects.
     */
    const val NEWS = "${BIN}cr-digest delta --peek --say 2>&1; echo $RC\$?"

    /** Jarvis' own tmux name as the server keeps it (the file `cr-session` and the hooks read too). */
    const val SELF_READ = "head -n 1 ~/.claude-remote/jarvis/self 2>/dev/null; echo"

    private val TMUX_NAME = Regex("claude-server-[A-Za-z0-9_-]{1,120}")

    /** The name in `jarvis/self`, or the fixed one when the file is missing, empty or not a session name. */
    fun selfName(raw: String?): String =
        raw?.lineSequence()?.firstOrNull()?.trim()?.takeIf { TMUX_NAME.matches(it) } ?: QuickAccess.JARVIS_TMUX_NAME

    /**
     * Only an approval goes to the wrist with its text (2026-11-08: per-session "hotovo" never reaches the watch).
     * Other rows keep id, title and activity, so an older watch build still draws its list and never crashes.
     */
    fun carriesText(activity: String): Boolean = activity == "APPROVAL_NEEDED"

    /** The cards for [list] (null = not read: [WatchProposals.ok] false). [serverNowMs] from [JarvisData.serverNowMs]. */
    fun proposals(list: List<JarvisProposal>?, serverNowMs: Long?): WatchProposals {
        if (list == null) return WatchProposals(false, "Návrhy nejdou načíst (server/Jarvis)")
        return WatchProposals(true, null, list.map { card(it, serverNowMs) })
    }

    fun card(p: JarvisProposal, serverNowMs: Long?): WatchProposal {
        val whole = p.text.length <= TEXT_MAX
        return WatchProposal(
            id = p.id,
            kind = p.kind.wire,
            heading = JarvisData.heading(p),
            title = p.title,
            text = if (whole) p.text else p.text.take(TEXT_MAX) + "…",
            fullText = p.fullText && whole,
            approveLabel = JarvisData.approveLabel(p),
            expiresInMs = if (p.expiresMs != null && serverNowMs != null) p.expiresMs - serverNowMs else null,
        )
    }

    /**
     * Why the phone refuses [approve] of [p] as the wrist asked it, or null = go. Re-checked against a FRESH read, so
     * an approval does exactly what the wrist showed: the full text, the same text ([textHash]), not lapsed by the
     * server's clock. A rejection is always allowed (it changes nothing but the proposal).
     */
    fun refusal(p: JarvisProposal?, approve: Boolean, textHash: Int, serverNowMs: Long?): String? {
        if (p == null) return "Návrh už nečeká"
        if (!approve) return null
        if (!p.fullText || p.text.length > TEXT_MAX) return "Celý text je jen v aplikaci – schval ho tam"
        if (p.text.hashCode() != textHash) return "Návrh se změnil – otevři ho znovu"
        if (p.expiresMs != null && serverNowMs != null && serverNowMs >= p.expiresMs) return "Návrh vypršel"
        return null
    }

    /**
     * The sentence a decision from the wrist ends with, always naming the proposal (review L6: "Zahozeno" alone does
     * not say which, and the result may arrive as a notification long after the tap).
     */
    fun decideText(p: JarvisProposal, approve: Boolean, ok: Boolean, cliMessage: String): String {
        val what = JarvisData.heading(p)
        return when {
            !ok -> "$what: $cliMessage"
            approve -> JarvisData.approvedNotice(p, cliMessage).let { n -> if (n == cliMessage) "$what: $n" else n }
            else -> "Zahozeno – $what"
        }
    }

    /** A refusal before the CLI ran, named the same way. */
    fun refusalText(p: JarvisProposal?, reason: String): String = p?.let { "${JarvisData.heading(it)}: $reason" } ?: reason

    private val REQUEST_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** The body of /jarvis-proposals-request; empty or unreadable (an older watch) = not periodic. */
    fun proposalsRequest(body: ByteArray): WatchProposalsRequest =
        runCatching { REQUEST_JSON.decodeFromString(WatchProposalsRequest.serializer(), body.decodeToString()) }
            .getOrNull() ?: WatchProposalsRequest()

    /** One of Jarvis' alerts for the Jarvis page (newest first from the feed). */
    @Serializable
    data class Recent(val id: String, val kind: String, val text: String, val atMs: Long)

    fun recent(pending: List<JarvisAlert>): List<Recent> = pending.take(RECENT_MAX).map { a ->
        Recent(a.id, a.kind.wire, a.text.take(ALERT_TEXT_MAX), a.atMs)
    }
}

/** Watch -> phone body of /jarvis-proposals-request. [periodic] = the page's minute tick (a poll permit, review L8). */
@Serializable
data class WatchProposalsRequest(val periodic: Boolean = false)

/**
 * The watch's Jarvis requests off the Data Layer listener thread (review H2): a proposals read (≤ 10 s), the news
 * (≤ 15 s) and above all an approved open or restore (≤ 35 s) used to hold the listener, and with it every other
 * message from the watch, its Ano/Ne included. [submit] returns at once; the work runs on [scope] (IO, process wide —
 * the service may be unbound as soon as onMessageReceived returns) and its answer goes back through `answer`, the way
 * /jarvis-undo-result does.
 *
 * One run per [key] at a time: a request whose key is still running is dropped, its answer comes from the run there is
 * (the Čeká page's tick and its opening, a double tap on a proposal). [limitMs] bounds the whole run.
 */
class WatchSingleFlight(private val scope: kotlinx.coroutines.CoroutineScope) {
    private val running = HashSet<String>()

    /** False = [key] is already running (nothing started). */
    fun <R> submit(
        key: String,
        limitMs: Long,
        run: suspend () -> R,
        onTimeout: () -> R,
        onError: (Exception) -> R,
        answer: (R) -> Unit,
    ): Boolean {
        synchronized(running) { if (!running.add(key)) return false }
        scope.launch {
            val r = try {
                kotlinx.coroutines.withTimeoutOrNull(limitMs) { run() } ?: onTimeout()
            } catch (e: kotlinx.coroutines.CancellationException) {
                synchronized(running) { running.remove(key) }
                throw e
            } catch (e: Exception) {
                onError(e)
            }
            synchronized(running) { running.remove(key) }
            answer(r)
        }
        return true
    }
}

/**
 * "Poslat"/"Provést"/"Zahodit" from the wrist on a [WatchSingleFlight] keyed by the proposal: a second tap while the
 * first runs is dropped (a duplicate `approve` would only be refused by the CLI as taken). A decision that hit
 * [limitMs] may or may not have happened, and the sentence says so instead of "neprovedeno".
 */
class WatchDecideRelay(
    scope: kotlinx.coroutines.CoroutineScope,
    private val limitMs: Long = LIMIT_MS,
    private val decide: suspend (WatchDecideRequest) -> WatchDecideResult,
) {
    private val flight = WatchSingleFlight(scope)

    /** False = a decision of this id is already on its way (nothing started). */
    fun submit(req: WatchDecideRequest, answer: (WatchDecideResult) -> Unit): Boolean = flight.submit(
        key = req.id,
        limitMs = limitMs,
        run = { decide(req) },
        onTimeout = { WatchDecideResult(req.id, false, TIMEOUT_TEXT) },
        onError = { e -> WatchDecideResult(req.id, false, "Rozhodnutí se nepovedlo (${e::class.simpleName}) – ověř ve Velínu.") },
        answer = answer,
    )

    companion object {
        /** decideTimeoutMs gives an open or restore 35 s, the read before it 10 s; this is the outer bound of both. */
        const val LIMIT_MS = 60_000L
        const val TIMEOUT_TEXT = "Telefon nedostal odpověď včas – nevím, jestli se to provedlo. Ověř ve Velínu."
    }
}
