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
        val et = registry.et(sessionId)
        if (et != null && et.isConnected) {
            status.updateActivity(sessionId, SessionActivity.WORKING)
            et.sendInput(data)
            return
        }
        val mosh = registry.mosh(sessionId)
        if (mosh != null && mosh.isConnected) {
            status.updateActivity(sessionId, SessionActivity.WORKING)
            mosh.sendInput(data)
            return
        }
        val conn = registry.ssh(sessionId)
        if (conn == null || !conn.isConnected) {
            queueInput(sessionId, data)
            return
        }
        status.updateActivity(sessionId, SessionActivity.WORKING)
        conn.sendInput(data)
    }

    fun sendBytes(sessionId: String, data: ByteArray) {
        notifications.promptDetector.onUserInput(sessionId)
        val et = registry.et(sessionId)
        if (et != null && et.isConnected) {
            status.updateActivity(sessionId, SessionActivity.WORKING)
            et.sendBytes(data)
            return
        }
        val mosh = registry.mosh(sessionId)
        if (mosh != null && mosh.isConnected) {
            status.updateActivity(sessionId, SessionActivity.WORKING)
            mosh.sendBytes(data)
            return
        }
        val conn = registry.ssh(sessionId)
        if (conn == null || !conn.isConnected) { warnNoConnection(sessionId); return }
        status.updateActivity(sessionId, SessionActivity.WORKING)
        conn.sendBytes(data)
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
        val conn = registry.ssh(sessionId) ?: return
        scope.launch {
            for (input in queue) {
                conn.sendInput(input)
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
        val conn = registry.ssh(sessionId)
        if (conn == null || !conn.isConnected) {
            queueInput(sessionId, command)
            return
        }
        FileLogger.log(TAG, "sendClaudeCommand: ${command.length} bytes to $sessionId")
        notifications.promptDetector.onUserInput(sessionId)
        status.updateActivity(sessionId, SessionActivity.WORKING)
        conn.sendInput(command)
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
