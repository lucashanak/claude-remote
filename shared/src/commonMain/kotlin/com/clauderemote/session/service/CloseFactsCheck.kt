package com.clauderemote.session.service

import com.clauderemote.model.ClaudeSession
import com.clauderemote.session.CloseCheck
import com.clauderemote.session.PendingCloses
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [PendingCloses.closeCheck] backed by the server's close facts (Jarvis F0, R6 = 6d): one read-only exec of
 * [CloseFactsCommands.closeFactsCommand] — on stdin behind [StdinScript.EXEC] (D3) — while the tab waits out its undo window.
 *
 * Every way of not knowing is `null`, which lets the close go ahead: no transport to the server ([read] returns
 * null), an exec that fails or times out, an answer that does not arrive within [budgetMs], output cut off before
 * its end marker or without [StdinScript.MARK]. The check may only stop a close, never hold one up; [budgetMs] stays under the window so a late
 * answer is not simply thrown away by [PendingCloses] at the deadline.
 *
 * [selfMarker] names this device's client marker for a tmux session (`TmuxProbes.singleClientPreamble` writes it on
 * every attach with the same device key), so the closing device's own attached client never counts as "someone else
 * is watching".
 */
internal class CloseFactsCheck(
    /** Runs [cmd] with [stdin] on the server (D3: [StdinScript.EXEC] and the facts script on its stdin); its stdout, or null. */
    private val read: suspend (serverId: String, cmd: String, stdin: ByteArray, totalMs: Long) -> String?,
    private val selfMarker: (tmuxName: String) -> String?,
    private val budgetMs: Long = BUDGET_MS,
) {
    suspend fun check(session: ClaudeSession): CloseCheck? {
        val name = session.tmuxSessionName
        // D3: the ~6.5 KB script on stdin, run only when it arrived whole; an answer without its end marker is unknown
        val script = StdinScript.stdin(CloseFactsCommands.closeFactsCommand(name, selfMarker(name)))
        val stdout = try {
            withTimeoutOrNull(budgetMs) { read(session.server.id, StdinScript.EXEC, script, budgetMs) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.log(TAG, "close facts for $name failed (${e.message}) — closing as if unknown")
            null
        }
        if (stdout == null) {
            FileLogger.log(TAG, "close facts for $name: no answer within ${budgetMs}ms or no transport — closing as if unknown")
            return null
        }
        val out = (StdinScript.parse(stdout) as? StdinScript.Outcome.Completed)?.out ?: run {
            FileLogger.log(TAG, "close facts for $name: the script did not complete (no end marker) — closing as if unknown")
            return null
        }
        val facts = CloseFactsCommands.parse(out)
        FileLogger.log(TAG, "close facts for $name: ${facts.level} ${facts.reasons.joinToString { it.code.name }}")
        return toCloseCheck(facts)
    }

    companion object {
        private const val TAG = "CloseFacts"

        /** 2 s short of the 10 s window: the answer has to be in before the close fires, not race it. */
        const val BUDGET_MS = PendingCloses.WINDOW_MS - 2_000

        /**
         * BLOCK → [CloseCheck.Block] with the comma-joined reasons, WARN → [CloseCheck.Warn], OK → [CloseCheck.Clear],
         * UNKNOWN → null (close).
         */
        fun toCloseCheck(facts: CloseFacts): CloseCheck? = when (facts.level) {
            CloseLevel.BLOCK -> CloseCheck.Block(facts.blockText ?: "")
            CloseLevel.WARN -> facts.warnText?.let { CloseCheck.Warn(it) } ?: CloseCheck.Clear
            CloseLevel.OK -> CloseCheck.Clear
            CloseLevel.UNKNOWN -> null
        }
    }
}
