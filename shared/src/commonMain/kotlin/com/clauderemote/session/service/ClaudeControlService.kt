package com.clauderemote.session.service

import com.clauderemote.model.ClaudeEffort
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.SessionActivity
import com.clauderemote.session.ClaudeConfig
import com.clauderemote.session.TabManager
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Preserve the exact log tag the moved bodies used while they lived in
// SessionOrchestrator, so device-log lines are byte-identical.
private const val TAG = "SessionOrchestrator"

/**
 * A bracketed paste (`ESC[200~` … `ESC[201~`, or to the very end when split across sends): its CR/LF
 * are text, not Enter. `\z`, not `$`: `$` also matches before a final line terminator, which left the
 * trailing CR of a split paste outside the match as a submit.
 */
private val BRACKETED_PASTE = Regex("\u001b\\[200~[\\s\\S]*?(?:\u001b\\[201~|\\z)")

/**
 * User input delivery (mosh-first, ssh-fallback) and the Claude slash-command control surface (model/effort switching,
 * /login flow, escape). A COORDINATOR service composing the already-extracted
 * services. Extracted verbatim from SessionOrchestrator: the ordering, timing
 * (the char-by-char slash-command pacing) and mosh-first/ssh-fallback order are
 * unchanged — a pure move so the public API and runtime behavior stay identical.
 *
 * [onOutput] bridges to the facade's `onTerminalOutput` callback so the "not sent"
 * banner still echoes to the active tab.
 */
private const val REPLY_EXEC_MS = 8_000L

internal class ClaudeControlService(
    private val scope: CoroutineScope,
    private val registry: ConnectionRegistry,
    private val tabManager: TabManager,
    private val status: StatusService,
    private val notifications: NotificationService,
    private val terminalIO: TerminalIOService,
    private val onOutput: (String, String) -> Unit,
) {
    /**
     * Kolo 5 (decision of the lead): input for a tab without a deliverable route is NEVER sent later — no queue. Three
     * rounds showed a delayed send cannot be made safe (the prompt token is gone after a loss, the UI sends keys as
     * "messages", order, a killed process). It is dropped and the terminal says so.
     */
    private fun warnNoConnection(sessionId: String) {
        val msg = "\r\n\u001B[31mNot connected — not sent. Try reconnecting.\u001B[0m\r\n"
        terminalIO.append(sessionId, msg)
        if (tabManager.activeTabId.value == sessionId) {
            onOutput(sessionId, msg)
        }
    }

    fun sendInput(sessionId: String, data: String) {
        typeUser(sessionId, data)
    }

    /** User input [data]; false (nothing sent, said so) without a route. */
    private fun typeUser(sessionId: String, data: String): Boolean {
        notifications.promptDetector.onUserInput(sessionId)
        // Try ET, then mosh, then SSH.
        val route = route(sessionId)
        if (route == null) {
            warnNoConnection(sessionId)   // kolo 5: never queued, never sent later
            return false
        }
        noteSent(sessionId, data)
        route.text(data)
        return true
    }

    /**
     * Kolo 6 (regression of kolo 5): the terminal emulator's OWN answers to the program (DA, CPR, XTVERSION … — tmux asks
     * at every attach) are no user input: they go whenever the tab's transport is up, also before the tab is ACTIVE
     * again (the answer comes one round trip after the attach, inside a reconnect), and never print "not sent". Not
     * counted as typing (no activity, no prompt-detector reset). The platforms hand them in apart from the keys
     * (Termux: the emulator's own output; JediTerm: `sendBytes(…, userInput = false)`).
     */
    fun sendTerminalReply(sessionId: String, data: ByteArray) {
        transportRoute(sessionId)?.bytes(data)
    }

    fun sendBytes(sessionId: String, data: ByteArray) {
        notifications.promptDetector.onUserInput(sessionId)
        val route = route(sessionId)
        if (route == null) { warnNoConnection(sessionId); return }
        noteSent(sessionId, String(data, Charsets.ISO_8859_1))
        route.bytes(data)
    }

    /**
     * The optimistic WORKING of a send. Without hook events for the session every send sets it, as
     * before (the statusline corrects it). With them (CAP=1) the statusline is out, so only a
     * submitted prompt (Enter: a CR outside a bracketed paste, not Shift+Enter's `ESC CR`; a LF is
     * a newline in the draft) starts work: Escape, arrows, digits and typing a draft do not, or the
     * dot would stay "Working" until the next hook `stop`, which an interrupt never sends. The
     * silence watch then ends a WORKING that no `stop` will end.
     * At an APPROVAL a printable key or a lone Escape answers the dialog (a digit, `y`, Escape =
     * deny); an escape SEQUENCE (arrows, terminal replies such as OSC or a focus report) does not.
     * No hook reports that answer: it starts work too, ends the notification hold behind the
     * dialog, and after a deny the silence watch brings the dot to ready.
     */
    private fun noteSent(sessionId: String, data: String) {
        val typed = data.replace(BRACKETED_PASTE, "")
        val answersDialog = status.currentActivity(sessionId) == SessionActivity.APPROVAL_NEEDED &&
            (data == "\u001b" || (!data.startsWith("\u001b") && typed.any { it == '\r' || it >= ' ' }))
        // Also without CAP=1 (a hook approval announced from an event too old for CAP, or CAP ended
        // since): only a hold behind an announced hook approval reads it, so without hooks it is a no-op.
        if (answersDialog) notifications.noteDialogAnswered(sessionId)
        if (!notifications.promptDetector.hasHookEvents(sessionId)) {
            status.updateActivity(sessionId, SessionActivity.WORKING)
            return
        }
        val submitted = typed.replace("\u001b\r", "").indexOf('\r') >= 0
        if (!submitted && !answersDialog) return
        status.updateActivity(sessionId, SessionActivity.WORKING)
        notifications.armSilenceWatch(sessionId)
    }

    /** Where a send for one session goes: both write paths of one transport. */
    internal class InputRoute(val text: (String) -> Unit, val bytes: (ByteArray) -> Unit)

    /** Test seam: replaces the transport lookup below (a unit test has no live ET/mosh/SSH). */
    internal var routeOverride: ((String) -> InputRoute?)? = null

    /** The active input target for a session: ET, then mosh, then SSH (null if
     *  none is connected). Keeps every send path routing to the same transport —
     *  an ET session's carrier SSH shell is idle, so a send that goes there
     *  instead of to ET vanishes. */
    private fun route(sessionId: String): InputRoute? {
        // Kolo 5 (adv N3, N4): only an ACTIVE tab has a route. While it reconnects its new shell is a bare login shell
        // until the attach (a message typed then ran as a command in $HOME), and an ET client left from before a
        // hand-off to autoReconnect is no terminal of the tab. (A session without a tab has no transport either.)
        val status = tabManager.getTab(sessionId)?.status
        if (status != null && status != com.clauderemote.model.SessionStatus.ACTIVE) return null
        return transportRoute(sessionId)
    }

    /** The transport a send would ride, whatever the tab's state (see [route] for what may type into it). */
    private fun transportRoute(sessionId: String): InputRoute? {
        routeOverride?.let { return it(sessionId) }
        registry.et(sessionId)?.let { if (it.isConnected) return InputRoute(it::sendInput, it::sendBytes) }
        registry.mosh(sessionId)?.let { if (it.isConnected) return InputRoute(it::sendInput, it::sendBytes) }
        // Kolo 4 (adv N2): the SSH shell of an ET tab is only its carrier (a hidden bash whose output is dropped):
        // input never goes there
        if (registry.isEtCarrier(sessionId)) return null
        registry.ssh(sessionId)?.let { if (it.isConnected) return InputRoute(it::sendInput, it::sendBytes) }
        return null
    }

    private fun activeInput(sessionId: String): ((String) -> Unit)? = route(sessionId)?.text

    /**
     * Whether the session's transport is up — the staleness input of `ReplyGuard` for a reply sent over exec, kept as it
     * was (kolo 5: the replies are unchanged; typing into the tab also needs it ACTIVE, see [route]).
     */
    fun canDeliverNow(sessionId: String): Boolean = transportRoute(sessionId) != null

    /** Test seam: replaces the exec of [sendCheckedReply] ((serverId, command) -> stdout, null = failed). */
    internal var replyExecOverride: (suspend (String, String) -> String?)? = null

    /**
     * Type a reply into the session through one exec that first checks the server's hook event
     * log against [expect] ([ReplyGuard.serverCommand]). Not through the PTY: the check and the
     * keys must run in the same shell on the server. [approve]: a `y` / `n` dialog answer, sent
     * with its Enter in one tmux call. Returns null when sent, else why not: an exec that never
     * ran fails closed, one that broke off after it started may have sent the keys.
     */
    suspend fun sendCheckedReply(
        sessionId: String,
        expect: ServerExpectation,
        text: String,
        approve: Boolean = false,
        emptyInput: Boolean = false,
    ): RefuseReason? = sendCheckedReplyTimed(sessionId, expect, text, approve, emptyInput).why

    /** [why] as [sendCheckedReply]; [serverAtMs] = when the server pressed Enter (only with `emptyInput`), else null. */
    data class CheckedReply(val why: RefuseReason?, val serverAtMs: Long? = null)

    suspend fun sendCheckedReplyTimed(
        sessionId: String,
        expect: ServerExpectation,
        text: String,
        approve: Boolean = false,
        emptyInput: Boolean = false,
    ): CheckedReply {
        val tab = tabManager.getTab(sessionId) ?: return CheckedReply(RefuseReason.NOT_CONNECTED)
        val cmd = ReplyGuard.serverCommand(tab.tmuxSessionName, expect, text, approve, emptyInput)
        val exec = replyExecOverride
        val out = try {
            if (exec != null) exec(tab.server.id, cmd) else registry.execRead(tab.server.id, cmd, totalMs = REPLY_EXEC_MS)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ExecUnavailable) {
            FileLogger.log(TAG, "checked reply exec failed for $sessionId: ${e.reason}")
            return CheckedReply(when (e.reason) {
                // the command never started
                ExecUnavailableReason.NOT_UP, ExecUnavailableReason.GATE_TIMEOUT, ExecUnavailableReason.REFUSED ->
                    RefuseReason.NOT_VERIFIABLE
                ExecUnavailableReason.TIMEOUT, ExecUnavailableReason.IO -> RefuseReason.MAYBE_SENT
            })
        } catch (e: Exception) {
            FileLogger.log(TAG, "checked reply exec failed for $sessionId: ${e.message}")
            return CheckedReply(RefuseReason.MAYBE_SENT)
        }
        val why = ReplyGuard.serverResult(out)
        if (why == null) {
            // What sendClaudeCommand does around a PTY send: the dot goes to WORKING, a dialog
            // hold ends, the detector re-arms.
            notifications.promptDetector.onUserInput(sessionId)
            noteSent(sessionId, text + "\r")
        }
        return CheckedReply(why, if (why == null) ReplyGuard.sentAtServerMs(out) else null)
    }


    /** False (nothing sent, said so) without a route. */
    fun sendClaudeCommand(sessionId: String, command: String): Boolean {
        val send = activeInput(sessionId)
        if (send == null) {
            warnNoConnection(sessionId)   // kolo 5: never queued, never sent later
            return false
        }
        FileLogger.log(TAG, "sendClaudeCommand: ${command.length} bytes to $sessionId")
        notifications.promptDetector.onUserInput(sessionId)
        noteSent(sessionId, command)
        send(command)
        return true
    }

    /**
     * Kolo 6 (adv M2, F5): one message of the input bar for ONE tab, fixed by the caller at the moment of the send (the
     * active tab may change during the typing). A slash command goes key by key, other text as body + Enter (see the
     * reasons at [sendSlashCommand] and in TerminalScreen). Without a route: one "not sent" line, and once a part did not
     * go, nothing after it goes — never an Enter alone.
     */
    fun sendMessage(sessionId: String, text: String) {
        scope.launch {
            if (text.startsWith("/") && !text.contains('\n') && text.length < 64) {
                sendSlashCommand(sessionId, text)
            } else {
                if (!sendClaudeCommand(sessionId, text)) return@launch
                kotlinx.coroutines.delay(40)
                sendClaudeCommand(sessionId, "\r")
            }
        }
    }

    fun switchModel(sessionId: String, model: ClaudeModel) {
        scope.launch { sendSlashCommand(sessionId, ClaudeConfig.modelSwitchCommand(model)) }
    }

    fun switchModelForAllSessions(model: ClaudeModel) {
        tabManager.tabs.value.forEach { switchModel(it.id, model) }
    }

    fun switchEffort(sessionId: String, effort: ClaudeEffort) {
        scope.launch { sendSlashCommand(sessionId, ClaudeConfig.effortSwitchCommand(effort)) }
    }

    fun switchEffortForAllSessions(effort: ClaudeEffort) {
        tabManager.tabs.value.forEach { switchEffort(it.id, effort) }
    }

    /**
     * Type [command] as discrete keystrokes with small gaps, then Enter
     * after a longer pause — mirrors the chat input's slash-command send
     * path (TerminalScreen's PromptInputBar/ExpandedInput). Sending a slash
     * command as one burst (whole string + \n in a single write) is detected
     * by Claude's TUI as a paste: it lands as literal text in the prompt
     * ("//model opus") instead of driving the interactive picker, so nothing
     * actually switches. switchModel/switchEffort used to do exactly that.
     */
    private suspend fun sendSlashCommand(sessionId: String, command: String) {
        for (ch in command) {
            if (!typeUser(sessionId, ch.toString())) return   // kolo 6 (F5): one "not sent", the rest and Enter stay
            kotlinx.coroutines.delay(15)
        }
        kotlinx.coroutines.delay(60)
        typeUser(sessionId, "\r")
    }

    /** Submit a pasted /login auth code: send the code, then a separate Enter so
     *  Claude's prompt doesn't treat code+CR as one paste (which won't submit). */
    fun submitLoginCode(sessionId: String, code: String) {
        scope.launch {
            if (!typeUser(sessionId, code)) return@launch   // kolo 6: no Enter alone
            kotlinx.coroutines.delay(120)
            typeUser(sessionId, "\r")
        }
    }

    /** Trigger Claude's /login flow on [sessionId] (types it char-by-char like a
     *  real slash command so the TUI doesn't treat it as a paste). */
    fun sendLoginCommand(sessionId: String) {
        scope.launch { sendSlashCommand(sessionId, "/login") }
    }

    fun sendEscape(sessionId: String) {
        sendInput(sessionId, ClaudeConfig.escapeSequence())
    }
}
