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
 * User input delivery (mosh-first, ssh-fallback), the offline pending-input
 * queue, and the Claude slash-command control surface (model/effort switching,
 * /login flow, escape). A COORDINATOR service composing the already-extracted
 * services. Extracted verbatim from SessionOrchestrator: the ordering, timing
 * (the char-by-char slash-command pacing) and mosh-first/ssh-fallback order are
 * unchanged — a pure move so the public API and runtime behavior stay identical.
 *
 * [onOutput] bridges to the facade's `onTerminalOutput` callback so the queued
 * / flushed / no-connection banners still echo to the active tab.
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
    private fun warnNoConnection(sessionId: String) {
        val msg = "\r\n\u001B[31mNo connection — input dropped. Try reconnecting.\u001B[0m\r\n"
        terminalIO.append(sessionId, msg)
        if (tabManager.activeTabId.value == sessionId) {
            onOutput(sessionId, msg)
        }
    }

    fun sendInput(sessionId: String, data: String) {
        notifications.promptDetector.onUserInput(sessionId)
        // Try ET, then mosh, then SSH.
        val route = route(sessionId)
        if (route == null) {
            queueInput(sessionId, data)
            return
        }
        noteSent(sessionId, data)
        route.text(data)
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
        routeOverride?.let { return it(sessionId) }
        registry.et(sessionId)?.let { if (it.isConnected) return InputRoute(it::sendInput, it::sendBytes) }
        registry.mosh(sessionId)?.let { if (it.isConnected) return InputRoute(it::sendInput, it::sendBytes) }
        registry.ssh(sessionId)?.let { if (it.isConnected) return InputRoute(it::sendInput, it::sendBytes) }
        return null
    }

    private fun activeInput(sessionId: String): ((String) -> Unit)? = route(sessionId)?.text

    /** Whether a send reaches the session now instead of the offline queue. */
    fun canDeliverNow(sessionId: String): Boolean = route(sessionId) != null

    /** Test seam: replaces the exec of [sendCheckedReply] ((serverId, command) -> stdout, null = failed). */
    internal var replyExecOverride: (suspend (String, String) -> String?)? = null

    /**
     * Type a reply into the session through one exec that first checks the server's hook event
     * log against [expect] ([ReplyGuard.serverCommand]). Not through the PTY: the check and the
     * keys must run in the same shell on the server. Returns null when sent, else why not; a
     * failed exec fails closed.
     */
    suspend fun sendCheckedReply(sessionId: String, expect: ServerExpectation, text: String): RefuseReason? {
        val tab = tabManager.getTab(sessionId) ?: return RefuseReason.NOT_CONNECTED
        val cmd = ReplyGuard.serverCommand(tab.tmuxSessionName, expect, text)
        val exec = replyExecOverride
        val out = try {
            if (exec != null) exec(tab.server.id, cmd) else registry.execRead(tab.server.id, cmd, totalMs = REPLY_EXEC_MS)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.log(TAG, "checked reply exec failed for $sessionId: ${e.message}")
            null
        }
        val why = ReplyGuard.serverResult(out)
        if (why == null) {
            // What sendClaudeCommand does around a PTY send: the dot goes to WORKING, a dialog
            // hold ends, the detector re-arms.
            notifications.promptDetector.onUserInput(sessionId)
            noteSent(sessionId, text + "\r")
        }
        return why
    }

    // ---- Offline input queue ----

    private fun queueInput(sessionId: String, data: String) {
        val size = notifications.enqueue(sessionId, data)
        val msg = "\r\n\u001B[33mQueued ($size pending) — will send on reconnect\u001B[0m\r\n"
        terminalIO.append(sessionId, msg)
        if (tabManager.activeTabId.value == sessionId) {
            onOutput(sessionId, msg)
        }
    }

    fun clearPendingInputs(sessionId: String) = notifications.clearPendingInputs(sessionId)

    fun flushPendingInputs(sessionId: String) {
        val queue = notifications.drain(sessionId) ?: return
        if (queue.isEmpty()) return
        val send = activeInput(sessionId) ?: return
        scope.launch {
            for (input in queue) {
                send(input)
                kotlinx.coroutines.delay(300) // small delay between queued messages
            }
            val msg = "\r\n\u001B[32mFlushed ${queue.size} queued message(s)\u001B[0m\r\n"
            terminalIO.append(sessionId, msg)
            if (tabManager.activeTabId.value == sessionId) {
                onOutput(sessionId, msg)
            }
        }
    }

    fun sendClaudeCommand(sessionId: String, command: String) {
        val send = activeInput(sessionId)
        if (send == null) {
            queueInput(sessionId, command)
            return
        }
        FileLogger.log(TAG, "sendClaudeCommand: ${command.length} bytes to $sessionId")
        notifications.promptDetector.onUserInput(sessionId)
        noteSent(sessionId, command)
        send(command)
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
            sendInput(sessionId, ch.toString())
            kotlinx.coroutines.delay(15)
        }
        kotlinx.coroutines.delay(60)
        sendInput(sessionId, "\r")
    }

    /** Submit a pasted /login auth code: send the code, then a separate Enter so
     *  Claude's prompt doesn't treat code+CR as one paste (which won't submit). */
    fun submitLoginCode(sessionId: String, code: String) {
        scope.launch {
            sendInput(sessionId, code)
            kotlinx.coroutines.delay(120)
            sendInput(sessionId, "\r")
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
