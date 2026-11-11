package com.clauderemote.session.service

import com.clauderemote.model.ClaudeAccount
import com.clauderemote.util.FileLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Jarvis F5d, the switch contract (`f5d/00-brief.md` "Kontrakt přepnutí"): jarvis-d cannot move Jarvis to another
 * account itself (the app and drift write `sessions.json`), so it asks the app. It writes
 * `~/.claude-remote/jarvis/switch-request.json` only while Jarvis is idle, his input empty, no conversation, no open
 * wake or proposal, mail on. A connected client that holds Jarvis' tab:
 * 1. claims it with an atomic `mv` to `switch-request.<id>.taken` — whoever's `mv` fails does nothing;
 * 2. checks again that Jarvis is idle with an empty input (ReplyGuard's rules, app and server); if not, puts the
 *    request back (`mv -n`, never over a newer one) and reports `busy`;
 * 3. restarts Jarvis' pane under the new account with `--resume` of the same conversation, mode NORMAL;
 * 4. writes `switch-result.json` (tmp + mv).
 * Every other client only aligns its model (tab + SessionStorage) to `jarvisAccount.slot` once a new
 * `lastSwitch.id` appears — never a second restart.
 */
data class JarvisSwitchRequest(
    val id: String,
    /** Server ms. */
    val at: Long,
    /** Server ms; a request past it is never claimed (jarvis-d deletes it). */
    val expires: Long,
    val tmux: String,
    val claudeSessionId: String?,
    val from: String?,
    /** A slug of `~/.claude-remote/accounts/` or `default`. */
    val to: String,
    val why: String?,
)

/** The `jarvisAccount` facts a non-claiming client aligns its model with. */
data class JarvisSwitchSeen(val slot: String?, val lastSwitchId: String?)

object JarvisSwitch {
    const val DIR = "~/.claude-remote/jarvis"
    private val ID = Regex("^s-[A-Za-z0-9-]{1,80}$")
    /**
     * A tmux name's charset only — the prefix is TmuxNameParser's business, and what matters is that the request
     * names exactly this device's tab of Jarvis (compared in [JarvisSwitchAgent.tick]).
     */
    private val TMUX = Regex("^[A-Za-z0-9_.-]{1,160}$")
    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val REQ_MARK = "__cr_req__"
    private const val ACCT_MARK = "__cr_acct__"
    private const val NOW_MARK = "__cr_now__"

    /**
     * One read for the poll: the request (if any), Jarvis' `jarvisAccount` (only that field of status.json) and the
     * server's clock in ms — expiry is judged on the server's time, never this device's.
     */
    val POLL: String = "cat $DIR/switch-request.json 2>/dev/null; printf '\\n$REQ_MARK\\n'; " +
        JarvisAccountView.READ + "; printf '$ACCT_MARK\\n$NOW_MARK %s\\n' \"\$(date +%s%3N)\""

    data class Poll(val request: JarvisSwitchRequest?, val account: JarvisAccountView.Read, val serverNowMs: Long?)

    /** Null = the poll did not finish (no markers): nothing is decided from half an answer. */
    fun parsePoll(out: String?): Poll? {
        val o = out ?: return null
        val r = o.indexOf(REQ_MARK)
        val a = o.indexOf(ACCT_MARK, r + 1)
        val n = o.indexOf(NOW_MARK, a + 1)
        if (r < 0 || a < 0 || n < 0) return null
        val now = o.substring(n + NOW_MARK.length).trim().lineSequence().firstOrNull()?.trim()?.toLongOrNull()?.takeIf { it > 1_000_000_000_000 }
        return Poll(parseRequest(o.substring(0, r)), JarvisAccountView.parse(o.substring(r + REQ_MARK.length, a)), now)
    }

    /** The request file, or null when absent, torn or not one this app may act on (bad id, name or slot). */
    fun parseRequest(text: String?): JarvisSwitchRequest? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val o = try { JSON.parseToJsonElement(t) as? JsonObject } catch (_: Exception) { null } ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        fun num(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toLong()
        if (num("v") != 1L) return null
        val id = str("id")?.takeIf { ID.matches(it) } ?: return null
        val tmux = str("tmux")?.takeIf { TMUX.matches(it) } ?: return null
        val to = str("to")?.takeIf { JarvisAccountsFile.validSlot(it) } ?: return null
        val at = num("at") ?: return null
        val expires = num("expires") ?: return null
        return JarvisSwitchRequest(id, at, expires, tmux, str("claudeSessionId"), str("from"), to, str("why"))
    }

    /** The account slug a tab carries for [slot]: `default` is the `~/.claude` login, null in the model. */
    fun accountSlugOf(slot: String?): String? = slot?.takeUnless { it.isBlank() || it == ClaudeAccount.DEFAULT_SLUG }

    /** The atomic claim: only the client whose `mv` succeeds sees the request's content after the marker. */
    fun claimCommand(id: String): String {
        require(ID.matches(id))
        return "cd $DIR 2>/dev/null && mv switch-request.json switch-request.$id.taken 2>/dev/null && " +
            "printf '__cr_claimed__\\n' && cat switch-request.$id.taken"
    }

    /** What the claim took (its content, to be checked against the id), or null when this client did not get it. */
    fun claimed(out: String?): JarvisSwitchRequest? {
        val o = out ?: return null
        val i = o.indexOf("__cr_claimed__")
        if (i < 0) return null
        return parseRequest(o.substring(i + "__cr_claimed__".length))
    }

    /**
     * The request goes back for jarvis-d (busy, or the claim caught a newer request than the one decided on):
     * `mv -n` never overwrites a request written meanwhile; the taken copy is dropped then.
     */
    fun returnCommand(id: String): String {
        require(ID.matches(id))
        return "cd $DIR 2>/dev/null && { mv -n switch-request.$id.taken switch-request.json 2>/dev/null; " +
            "rm -f switch-request.$id.taken; echo __cr_ok__; }"
    }

    /** `switch-result.json` as the contract has it. [error] null on success. */
    fun resultJson(id: String, installId: String, atMs: Long, ok: Boolean?, error: String?): String {
        fun q(s: String) = JsonPrimitive(s).toString()
        val by = "app:" + installId.filter { it.isLetterOrDigit() || it in "-_." }.take(64)
        return "{\"id\":${q(id)},\"by\":${q(by)},\"at\":$atMs,\"ok\":$ok" + (error?.let { ",\"error\":${q(it.take(300))}" } ?: "") + "}"
    }

    /** Written atomically (tmp + mv) as a short base64 argument. */
    fun resultCommand(json: String): String {
        val b64 = java.util.Base64.getEncoder().encodeToString(json.encodeToByteArray())
        return "cd $DIR 2>/dev/null && printf %s '$b64' | base64 -d > .switch-result.tmp && mv -f .switch-result.tmp switch-result.json && echo __cr_ok__"
    }

    const val BUSY = "busy"
    const val MISMATCH = "mismatch"
    const val UNKNOWN = "unknown"

    /** How the switch itself ended ([switchScript] on the server). */
    sealed interface SwitchRun {
        /** Jarvis' pane was respawned and the launch line typed: now the model may follow. */
        data object Ok : SwitchRun
        /** Not idle with an empty input at that moment: nothing was touched, the request goes back. */
        data class Busy(val why: String) : SwitchRun
        /** Nothing was respawned (not listed, no login, no pane) or it broke ([why] for the result). */
        data class Failed(val why: String) : SwitchRun
        /**
         * The run's end was not seen (the connection broke): it may have respawned or not. The model stays, the
         * result says `ok: null` and the server decides from the process (its `CLAUDE_CONFIG_DIR`).
         */
        data object Unknown : SwitchRun
    }

    /**
     * The whole switch as ONE shell run on the server (rev HIGH-1), sent on stdin behind [StdinScript.EXEC]:
     * 1. [to] must be a line of `jarvis/accounts` (rev MED-2: only an account Lukáš chose) and its config dir's
     *    `.claude.json` must carry a login (`oauthAccount.emailAddress`) — no provisioning, a slot without a login
     *    would leave Jarvis at a login prompt;
     * 2. ReplyGuard's idle check of the pane (last hook event `stop`/`start`, the pane there, the input box empty);
     * 3. ONLY after `CR_REPLY IDLE`, in the same shell: `respawn-pane -k` of THAT pane (`$P`, the one the check read
     *    and every checked reply types into, rev LOW3) and the launch line typed into it.
     * The launch is mode NORMAL (never `--dangerously-skip-permissions`), model Opus (Jarvis' decision R1, rev
     * LOW4), `--resume` of [claudeSessionId] decided by the server, under the new `CLAUDE_CONFIG_DIR` — or, for
     * `default`, with the variable UNSET (rev LOW1: a tmux global environment must not hand him another login).
     */
    fun switchScript(tmux: String, folder: String, claudeSessionId: String, to: String): String {
        require(JarvisAccountsFile.validSlot(to))
        val slug = accountSlugOf(to)
        val launch = com.clauderemote.session.ClaudeConfig.buildLaunchCommandOrServerDecided(
            folder, com.clauderemote.model.ClaudeMode.NORMAL, com.clauderemote.model.ClaudeModel.OPUS, claudeSessionId, null, slug,
        )
        val inner = "cd \"\$HOME\"; " + (if (slug == null) "unset CLAUDE_CONFIG_DIR; " else "") + launch
        val loginRun = "bash -lc ${sq(inner)}"
        val tm = "timeout -k 1 2 tmux -u"
        // one retry of the typing (a fresh pane's shell may not take keys yet): a pane left at a bare shell is the worst end
        val type = "$tm send-keys -t \"\$P\" ${sq(loginRun)} Enter 2>/dev/null"
        val then = "if $tm respawn-pane -k -t \"\$P\" 'bash -l' 2>/dev/null; then sleep 0.4; " +
            "if $type || { sleep 0.5; $type; }; then echo '$SWITCH_TOKEN RESPAWNED'; " +
            "else echo '$SWITCH_TOKEN TYPEFAIL'; fi; else echo '$SWITCH_TOKEN RESPAWNFAIL'; fi"
        val cfg = if (slug == null) "\"\$HOME/.claude.json\"" else "\"\$HOME/.claude-remote/accounts/\"${sq(slug)}\"/.claude.json\""
        return "TO=${sq(to)}; " +
            "if ! grep -qxF -- \"\$TO\" \"\$HOME/.claude-remote/jarvis/accounts\" 2>/dev/null; then echo '$SWITCH_TOKEN NOTLISTED'; " +
            "elif ! grep -q '\"emailAddress\"' $cfg 2>/dev/null; then echo '$SWITCH_TOKEN NOLOGIN'; " +
            "else " + ReplyGuard.idleThenCommand(tmux, then) + "; fi"
    }

    const val SWITCH_TOKEN = "CR_SWITCH"

    /** The stdout of [StdinScript.EXEC] running [switchScript]. A cut-off run is a failure, said as such. */
    fun parseSwitch(stdout: String?): SwitchRun {
        val done = stdout?.let { StdinScript.parse(it) } as? StdinScript.Outcome.Completed
            ?: return SwitchRun.Unknown
        val lines = done.out.lineSequence().map { it.trim() }
        lines.lastOrNull { it.startsWith("$SWITCH_TOKEN ") }?.removePrefix("$SWITCH_TOKEN ")?.let { t ->
            return if (t == "RESPAWNED") SwitchRun.Ok else SwitchRun.Failed(t.lowercase())
        }
        return when (val why = ReplyGuard.idleCheckResult(done.out)) {
            null -> SwitchRun.Failed("no answer after idle")
            RefuseReason.PROMPT_CHANGED, RefuseReason.DRAFT_IN_INPUT -> SwitchRun.Busy(why.name)
            else -> SwitchRun.Failed(why.name.lowercase())
        }
    }

    private fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

/** What one [JarvisSwitchAgent.tick] did (tests and the log). */
sealed interface JarvisSwitchOutcome {
    data object Nothing : JarvisSwitchOutcome
    data object Unread : JarvisSwitchOutcome
    data class Expired(val id: String) : JarvisSwitchOutcome
    /** The request names a session this device has no tab of: another client's to take. */
    data class NotHere(val id: String) : JarvisSwitchOutcome
    data class LostClaim(val id: String) : JarvisSwitchOutcome
    data class Busy(val id: String, val why: String) : JarvisSwitchOutcome
    /** The request's conversation is not the one this tab resumes (rev MED-1): refused, nothing touched. */
    data class Mismatch(val id: String) : JarvisSwitchOutcome
    /** [ok] null = unknown (the run's end was not seen). */
    data class Switched(val id: String, val ok: Boolean?, val error: String?) : JarvisSwitchOutcome
    data class Aligned(val sessionId: String, val slot: String?) : JarvisSwitchOutcome
}

/**
 * The client side of the switch contract, platform-free: every effect is a function, so the whole flow (claim, two
 * clients, busy and back, expiry, alignment) is a test. [run] executes on Jarvis' server (the exec gate's permits);
 * [jarvisTab] is this device's tab of Jarvis (null = none here); [appCheck] is ReplyGuard's app-side rule; [switchNow]
 * runs [JarvisSwitch.switchScript] (the server's idle check and the respawn in one shell) and changes the model only
 * on [JarvisSwitch.SwitchRun.Ok]; [align] changes only the model.
 */
class JarvisSwitchAgent(
    private val installId: () -> String,
    private val run: suspend (cmd: String, periodic: Boolean) -> String?,
    private val jarvisTab: () -> JarvisTabRef?,
    private val appCheck: (sessionId: String) -> String?,
    private val switchNow: suspend (sessionId: String, slot: String) -> JarvisSwitch.SwitchRun,
    private val align: (sessionId: String, slot: String) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * What this device knows of Jarvis' tab: its id, tmux name, the account slug the model holds (null = default) and
     * the conversation it resumes.
     */
    data class JarvisTabRef(val sessionId: String, val tmux: String, val accountSlug: String?, val claudeSessionId: String? = null)

    /** A request found busy is not claimed again for [BUSY_RETRY_MS] (jarvis-d asks only while idle; it may settle). */
    private var busySince: Pair<String, Long>? = null

    /**
     * The newest switch id this client has carried out or aligned to. Ids are `s-<UTC>-<hex>`, so their order is
     * their time: a status still naming an OLDER switch (jarvis-d has not verified ours yet) never moves the model back.
     */
    @kotlin.concurrent.Volatile var alignedTo: String? = null
        private set

    /** When the model's slot was last checked against `jarvisAccount` (rev LOW2: a push waits for a fresh check). */
    @kotlin.concurrent.Volatile var confirmedAtMs: Long? = null
        private set

    suspend fun tick(): JarvisSwitchOutcome {
        val p = JarvisSwitch.parsePoll(run(JarvisSwitch.POLL, true)) ?: return JarvisSwitchOutcome.Unread
        val tab = jarvisTab()
        val req = p.request
        if (req != null) {
            if (p.serverNowMs == null || p.serverNowMs >= req.expires) return JarvisSwitchOutcome.Expired(req.id)
            if (tab == null || tab.tmux != req.tmux) return JarvisSwitchOutcome.NotHere(req.id)
            busySince?.let { (id, at) -> if (id == req.id && clock() - at < BUSY_RETRY_MS) return JarvisSwitchOutcome.Nothing }
            return carryOut(req, tab)
        }
        return alignIfMoved(p.account, tab)
    }

    /**
     * Rev LOW2: before this device pushes the manifest, its model of Jarvis' account is checked against
     * `jarvisAccount` when the last check is older than [CONFIRM_MS] — a client that lost the claim would otherwise
     * push the OLD slot over the winner's until its next poll. Only the poll and the alignment; never a claim here.
     */
    suspend fun confirmBeforePush() {
        val at = confirmedAtMs
        if (at != null && clock() - at < CONFIRM_MS) return
        val p = JarvisSwitch.parsePoll(run(JarvisSwitch.POLL, true)) ?: return
        alignIfMoved(p.account, jarvisTab())
    }

    private suspend fun carryOut(req: JarvisSwitchRequest, tab: JarvisTabRef): JarvisSwitchOutcome {
        val took = JarvisSwitch.claimed(run(JarvisSwitch.claimCommand(req.id), false))
            ?: return JarvisSwitchOutcome.LostClaim(req.id).also { log("${req.id}: claimed by another client") }
        // the file changed between the read and the mv: what was taken is not what was decided on — back it goes
        if (took.id != req.id) {
            run(JarvisSwitch.returnCommand(req.id), false)
            return JarvisSwitchOutcome.LostClaim(req.id).also { log("${req.id}: a newer request (${took.id}) was taken, put back") }
        }
        // rev MED-1: the conversation the server means must be the one this tab resumes
        if (took.claudeSessionId.isNullOrBlank() || took.claudeSessionId != tab.claudeSessionId) {
            writeResult(req.id, false, JarvisSwitch.MISMATCH)
            return JarvisSwitchOutcome.Mismatch(req.id)
                .also { log("${req.id}: request resumes ${took.claudeSessionId}, the tab ${tab.claudeSessionId}: refused") }
        }
        appCheck(tab.sessionId)?.let { return busy(req, it) }
        log("${req.id}: switching Jarvis ${tab.tmux} ${req.from ?: "?"} → ${req.to} (${req.why ?: "-"})")
        // Before the switch: its own manifest push runs the pre-push check, and a status that still names an OLDER
        // switch must not move the model back meanwhile (anything at or below this id is older than this request).
        alignedTo = maxOf(alignedTo ?: "", req.id)
        confirmedAtMs = clock()
        val r = try {
            switchNow(tab.sessionId, req.to)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            JarvisSwitch.SwitchRun.Failed("${e::class.simpleName}")
        }
        return when (r) {
            is JarvisSwitch.SwitchRun.Busy -> busy(req, r.why)
            is JarvisSwitch.SwitchRun.Failed -> {
                writeResult(req.id, false, r.why)
                JarvisSwitchOutcome.Switched(req.id, false, r.why)
            }
            JarvisSwitch.SwitchRun.Ok -> {
                writeResult(req.id, true, null)
                JarvisSwitchOutcome.Switched(req.id, true, null)
            }
            // the connection broke mid-run: the server tells from the process whether he moved
            JarvisSwitch.SwitchRun.Unknown -> {
                writeResult(req.id, null, JarvisSwitch.UNKNOWN)
                JarvisSwitchOutcome.Switched(req.id, null, JarvisSwitch.UNKNOWN)
            }
        }
    }

    private suspend fun busy(req: JarvisSwitchRequest, why: String): JarvisSwitchOutcome {
        run(JarvisSwitch.returnCommand(req.id), false)
        writeResult(req.id, false, JarvisSwitch.BUSY)
        busySince = req.id to clock()
        log("${req.id}: Jarvis busy ($why), request put back")
        return JarvisSwitchOutcome.Busy(req.id, why)
    }

    private suspend fun writeResult(id: String, ok: Boolean?, error: String?) {
        val out = run(JarvisSwitch.resultCommand(JarvisSwitch.resultJson(id, installId(), clock(), ok, error)), false)
        if (out?.contains("__cr_ok__") != true) log("$id: switch-result.json not written")
    }

    /**
     * Another client switched him: the model follows `jarvisAccount.slot` once per NEWER `lastSwitch.id`, no restart.
     * Every read of the field confirms the model ([confirmedAtMs]).
     */
    private fun alignIfMoved(account: JarvisAccountView.Read, tab: JarvisTabRef?): JarvisSwitchOutcome {
        val info = (account as? JarvisAccountView.Read.Ok)?.info ?: return JarvisSwitchOutcome.Nothing
        confirmedAtMs = clock()
        val switchId = info.lastSwitch?.id ?: return JarvisSwitchOutcome.Nothing
        val slot = info.slot?.takeIf { JarvisAccountsFile.validSlot(it) } ?: return JarvisSwitchOutcome.Nothing
        if (tab == null) return JarvisSwitchOutcome.Nothing
        val seen = alignedTo
        if (seen != null && switchId <= seen) return JarvisSwitchOutcome.Nothing
        alignedTo = switchId
        if (JarvisSwitch.accountSlugOf(slot) == tab.accountSlug) return JarvisSwitchOutcome.Nothing
        align(tab.sessionId, slot)
        log("aligned the model of ${tab.tmux} to $slot after switch $switchId (no restart)")
        return JarvisSwitchOutcome.Aligned(tab.sessionId, slot)
    }

    private fun log(msg: String) = FileLogger.log("JarvisSwitch", msg)

    companion object {
        const val BUSY_RETRY_MS = 60_000L
        const val CONFIRM_MS = 60_000L
    }
}
