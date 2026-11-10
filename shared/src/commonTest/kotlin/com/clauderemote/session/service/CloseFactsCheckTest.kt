package com.clauderemote.session.service

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import com.clauderemote.session.CloseCheck
import com.clauderemote.session.PendingCloses
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The close facts as [PendingCloses.closeCheck] (Jarvis F0, R6 = 6d): how the server's verdict maps onto the undo
 * window, and that every way of not knowing in time lets the close go ahead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloseFactsCheckTest {
    private val server = SshServer(id = "srv", name = "one", host = "unreachable.invalid", username = "u")
    private val tab = ClaudeSession(
        id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-server-work", connectionType = ConnectionType.SSH,
    )

    private val idle = listOf("FACT alive 1", "FACT pid 10", "FACT status idle -", "FACT event stop 4000 0", "FACT draft 0",
        "FACT clients 0 1", "FACT procs 0 -", "FACT git 0 0 0", "FACT transcript 1")

    private fun out(vararg replace: String, end: Boolean = true): String {
        val lines = idle.map { l -> replace.firstOrNull { l.split(' ')[1] == it.split(' ')[1] } ?: l }
        return (lines + "TOKEN 1794040000.abc" + if (end) listOf(CloseFactsCommands.END) else emptyList()).joinToString("\n")
    }

    private fun map(s: String) = CloseFactsCheck.toCloseCheck(CloseFactsCommands.parse(s))

    @Test fun eachLevelMapsOntoItsCloseCheck() {
        assertEquals(CloseCheck.Clear, map(out()))
        assertEquals(CloseCheck.Block("běží tah, rozepsaný vstup"), map(out("FACT status busy -", "FACT draft 1")))
        assertEquals(CloseCheck.Block("dívá se jiné zařízení"), map(out("FACT clients 1 1")))
        assertEquals(CloseCheck.Warn("3 necommitnuté soubory, 1 nepushnutý commit"), map(out("FACT git 3 1 0")))
        // a BLOCK wins over a WARN: the close is off, the warning has nothing left to warn about
        assertEquals(CloseCheck.Block("rozepsaný vstup"), map(out("FACT draft 1", "FACT git 3 1 0")))
        assertNull(map(out(end = false)), "cut off before the end marker: unknown, the close goes ahead")
        assertNull(map(""), "nothing at all: unknown")
        assertEquals(CloseCheck.Clear, map(out("FACT alive 0")), "the session is gone already: nothing to protect")
    }

    /** One read: what the check sent ([cmd] and the script on [stdin]). */
    private data class Read(val serverId: String, val cmd: String, val stdin: ByteArray, val totalMs: Long)

    /** Answers with [answer]'s text as the body's output, behind the end marker [StdinScript.EXEC] prints when [marked]. */
    private class Reads(val marked: Boolean = true, val answer: suspend (String) -> String?) {
        val calls = mutableListOf<Read>()
        suspend fun read(serverId: String, cmd: String, stdin: ByteArray, totalMs: Long): String? {
            calls += Read(serverId, cmd, stdin, totalMs)
            return answer(cmd)?.let { if (marked) it + "\n" + StdinScript.MARK + "0\n" else it }
        }
    }

    private fun subject(r: Reads) = CloseFactsCheck(read = r::read, selfMarker = { "dev-1-$it" })

    @Test fun sendsTheReadOnlyFactsOfTheTabsSessionWithThisDevicesMarker() = runTest {
        val r = Reads { out("FACT status busy -") }
        assertEquals(CloseCheck.Block("běží tah"), subject(r).check(tab))
        val call = r.calls.single()
        assertEquals("srv", call.serverId)
        // D3: the request is the short launcher, the facts script goes on its stdin
        assertEquals(StdinScript.EXEC, call.cmd)
        assertEquals(CloseFactsCommands.closeFactsCommand("claude-server-work", "dev-1-claude-server-work") + "\n" + StdinScript.END + "\n",
            call.stdin.decodeToString())
        assertEquals(CloseFactsCheck.BUDGET_MS, call.totalMs)
    }

    /** D3: an answer without the launcher's end marker (the script was cut short and never ran) is unknown, never a verdict. */
    @Test fun anAnswerWithoutTheEndMarkerMeansClose() = runTest {
        assertNull(subject(Reads(marked = false) { out("FACT status busy -") }).check(tab), "a BLOCK without the marker counts for nothing")
        assertNull(subject(Reads(marked = false) { StdinScript.TRUNCATED }).check(tab), "the script did not arrive whole")
        assertEquals(CloseCheck.Block("běží tah"), subject(Reads { out("FACT status busy -") }).check(tab), "with it, the verdict stands")
    }

    @Test fun noTransportAFailureOrNoAnswerInTimeAllMeanClose() = runTest {
        assertNull(subject(Reads { null }).check(tab), "no exec and no live tab of the server")
        assertNull(subject(Reads { throw IllegalStateException("channel refused") }).check(tab))
        val t0 = currentTime
        assertNull(subject(Reads { awaitCancellation() }).check(tab), "a read that never answers")
        assertEquals(CloseFactsCheck.BUDGET_MS, currentTime - t0, "given up at the budget, not at the read's own pace")
        assertTrue(CloseFactsCheck.BUDGET_MS < PendingCloses.WINDOW_MS, "the answer has to land inside the window")
    }

    private fun TestScope.closes(r: Reads, forgotten: MutableList<String>) = PendingCloses(
        scope = backgroundScope,
        forget = { forgotten += it },
        closeCheck = subject(r)::check,
        now = { testScheduler.currentTime },
    )

    private fun TestScope.advance(ms: Long) { advanceTimeBy(ms); runCurrent() }

    /** Wired into the undo window: a slow server cannot hold the close past its 10 s. */
    @Test fun aSlowServerDoesNotHoldTheCloseUp() = runTest {
        val forgotten = mutableListOf<String>()
        val c = closes(Reads { delay(60_000); out("FACT status busy -") }, forgotten)
        c.close(tab)
        advance(PendingCloses.WINDOW_MS - 1)
        assertEquals(emptyList(), forgotten)
        advance(1)
        assertEquals(listOf("t1"), forgotten, "closed on time; the BLOCK that would come a minute later never counts")
    }

    @Test fun aBlockInTimeBringsTheTabBackAndCloseAnywayClosesWithoutAskingAgain() = runTest {
        val forgotten = mutableListOf<String>()
        val r = Reads { delay(1_500); out("FACT clients 1 1") }
        val c = closes(r, forgotten)
        c.close(tab)
        advance(1_500)
        assertEquals("dívá se jiné zařízení", c.state.value.blocked.single().reason)
        assertTrue(c.state.value.hiddenIds.isEmpty())
        c.forceClose("t1")
        runCurrent()
        assertEquals(listOf("t1"), forgotten, "Zavřít i tak: at once")
        assertEquals(1, r.calls.size, "no second facts round before the forced close")
    }

    @Test fun aWarningRidesAlongAndTheCloseHappens() = runTest {
        val forgotten = mutableListOf<String>()
        val c = closes(Reads { out("FACT git 1 0 0") }, forgotten)
        c.close(tab)
        runCurrent()
        assertEquals("1 necommitnutý soubor", c.state.value.pending.single().warning)
        advance(PendingCloses.WINDOW_MS)
        assertEquals(listOf("t1"), forgotten)
    }
}
