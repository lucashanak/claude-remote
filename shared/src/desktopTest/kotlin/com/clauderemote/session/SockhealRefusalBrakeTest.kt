package com.clauderemote.session

import com.clauderemote.connection.SshManager
import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SessionStatus
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecResult
import com.clauderemote.session.service.Pace
import com.clauderemote.session.service.RemoteExec
import com.clauderemote.session.service.SessionPersistenceService
import com.clauderemote.session.service.TerminalIOService
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.FakeSession
import com.jcraft.jsch.JSchException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R1 (review D1 adv N1, kt N4): a Rebuild whose `tmux-sockheal.sh` keeps refusing must back off 4 s, 8 s … and not
 * spin. The refusal takes 4.5–6 s on the server; the brake used to count from ACTIVE (~0.3 s after the line) with a 5 s
 * window, so a loss that came back over a link slower than ~0.5 s read as "the connection held" and every round began
 * at attempt 1 with no wait — every tab of the server reconnecting every 6–10 s for as long as the guard refused.
 *
 * The brake now counts from the line (stamped before it is sent) with a window per line: [SessionOrchestrator.EARLY_DEATH_MS]
 * for an attach, [SessionOrchestrator.LAUNCH_EARLY_DEATH_MS] for a launch line. The real `sendTmuxCommand` runs (has-session
 * answered by a scripted exec transport, the shell a fake whose typed lines are captured); the brake's clock is a seam
 * the test moves forward to play the server's latency, the waits of the reconnect are real.
 */
class SockhealRefusalBrakeTest {
    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(90)

    private val savedLog = FileLogger.platformLog
    private val logLines = CopyOnWriteArrayList<String>()
    private val tabs = TabManager()
    private val storage = ServerStorage(FakeKeyValueStore())
    private val sessions = SessionStorage(FakeKeyValueStore())
    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")
    private var orchestrator: SessionOrchestrator? = null

    /** The brake's clock: real time plus what the test adds to play the server's latency. */
    private val offset = AtomicLong()
    private fun clock() = System.currentTimeMillis() + offset.get()

    /** Each line the tab's shells received: (the line, the brake's clock when it arrived). */
    private val lines = CopyOnWriteArrayList<Pair<String, Long>>()
    /** The shells the reconnects built: (the fake session, real time it was built). */
    private val shells = CopyOnWriteArrayList<Pair<FakeSession, Long>>()
    /** Played time between a line's arrival and the rest of the reconnect (prompt, settle, ACTIVE): a slow link. */
    @Volatile private var settleMs = 0L

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
    }

    @AfterTest fun tearDown() {
        orchestrator?.shutdownForTest()
        FileLogger.platformLog = savedLog
        SshSessionHelper.execChannelsProvider = null
        SshSessionHelper.liveSessionProvider = null
        SshSessionHelper.fallbackRunner = null
        CommFlags.resetForTest()
    }

    /** has-session answers [tmuxAlive] (NO: Rebuild through the guard; YES: attach), the transcript is there, the rest is quiet. */
    private class ScriptedExec(private val tmuxAlive: Boolean) : RemoteExec {
        override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace): ExecResult =
            when {
                "has-session" in cmd -> ExecResult(if (tmuxAlive) "YES\n" else "NO\n", 0)
                ".jsonl" in cmd -> ExecResult("YES\n", 0)
                else -> ExecResult("", 0)
            }
    }

    private fun orchestrator(tmuxAlive: Boolean): SessionOrchestrator {
        storage.addServer(server)
        val o = SessionOrchestrator(storage, tabs, sessions).also { orchestrator = it }
        o.connectionRegistry.remoteExec = ScriptedExec(tmuxAlive)
        o.earlyDeathClockSeam = { clock() }
        o.reconnectShellSeam = {
            // no exec channel on the tab's own session: the close-state probe fails open (not tombstoned, still tracked)
            val fake = FakeSession { _ -> throw JSchException("no channel in this test") }
            BudgetFixture.connectedManager(storage, fake).also { m ->
                val out = object : OutputStream() {
                    private val buf = StringBuilder()
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        synchronized(buf) {
                            buf.append(String(b, off, len, Charsets.UTF_8))
                            while (true) {
                                val nl = buf.indexOf("\n").takeIf { it >= 0 } ?: break
                                val line = buf.substring(0, nl); buf.delete(0, nl + 1)
                                if ("tmux" in line) { lines += line to clock(); offset.addAndGet(settleMs) }
                            }
                        }
                    }
                }
                SshManager::class.java.getDeclaredField("outputStream").also { it.isAccessible = true }.set(m, out)
                shells += fake to System.currentTimeMillis()
            }
        }
        val tio = o.javaClass.getDeclaredField("terminalIO").also { it.isAccessible = true }.get(o) as TerminalIOService
        tio.initBuffer("t1"); tio.append("t1", "u@box:~\$ ")   // the login shell's prompt: no 3 s wait for it
        return o
    }

    private fun tab() = ClaudeSession(
        id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-box-t1", connectionType = ConnectionType.SSH, status = SessionStatus.DISCONNECTED,
        claudeSessionId = "0f6c2a4e-1b2c-4d5e-8f90-123456789abc",
    ).also { tabs.addTab(it); tabs.updateTabStatus(it.id, SessionStatus.DISCONNECTED) }

    /** One autoReconnect, as the shell's loss report starts it; waits until it ends ACTIVE. */
    private fun reconnect(o: SessionOrchestrator, t: ClaudeSession) {
        runBlocking { withTimeout(60_000) { o.autoReconnect(t, {}) } }
        assertEquals(SessionStatus.ACTIVE, tabs.getTab("t1")!!.status, "the reconnect ended ACTIVE; log: ${logLines.takeLast(8)}")
    }

    /** The shell of the last reconnect is lost [afterLineMs] after its line went out (played on the brake's clock). */
    private fun loseShell(afterLineMs: Long) {
        val (_, at) = lines.last()
        offset.addAndGet(maxOf(0, at + afterLineMs - clock()))
        shells.last().first.connected = false
    }

    /** Real ms from the start of the last reconnect to the shell it built. */
    private fun waitedBefore(started: Long) = shells.last().second - started

    @Test fun aRebuildWhoseGuardKeepsRefusingOnASlowLinkBacksOff4sThen8s() {
        val o = orchestrator(tmuxAlive = false)
        val t = tab()
        reconnect(o, t)
        assertTrue("new-session" in lines.single().first, "a Rebuild through the guard went out: ${lines.single().first}")
        val waits = (1..2).map { round ->
            // the guard refuses 5.5 s after the line (4.5 s of its own + a slow round trip); `|| exit 1` ends the shell
            loseShell(5_500)
            val started = System.currentTimeMillis()
            reconnect(o, t)
            assertEquals(round + 1, lines.size, "round $round sent its line")
            waitedBefore(started)
        }
        val early = logLines.filter { "early death" in it }
        assertTrue(early.size == 2 && "early death 1 in a row" in early[0] && "waiting 4000ms" in early[0] &&
            "early death 2 in a row" in early[1] && "waiting 8000ms" in early[1], "both losses were early deaths, 4 s then 8 s: $early")
        assertTrue(waits[0] >= 4_000, "the second attempt waited at least 4 s: ${waits[0]} ms")
        assertTrue(waits[1] >= 8_000 && waits[1] > waits[0], "and the wait grew: $waits")
    }

    @Test fun theBrakeCountsFromTheLineNotFromActive() {
        val o = orchestrator(tmuxAlive = false)
        val t = tab()
        settleMs = 1_000                     // ACTIVE comes 1 s after the line (a slow link)
        reconnect(o, t)
        // the connection held 0.5 s past the launch line's window, counted from the LINE: a fresh start, no wait
        loseShell(SessionOrchestrator.LAUNCH_EARLY_DEATH_MS + 500)
        val started = System.currentTimeMillis()
        reconnect(o, t)
        assertTrue(logLines.none { "early death" in it }, "not an early death: ${logLines.filter { "early death" in it }}")
        assertTrue(waitedBefore(started) < 2_000, "attempt 1 went at once: ${waitedBefore(started)} ms")
    }

    @Test fun anAttachLineKeepsTheShortWindow() {
        val o = orchestrator(tmuxAlive = true)
        val t = tab()
        reconnect(o, t)
        assertTrue("new-session" !in lines.single().first && "attach" in lines.single().first, "a plain attach went out: ${lines.single().first}")
        loseShell(SessionOrchestrator.EARLY_DEATH_MS + 500)
        val started = System.currentTimeMillis()
        reconnect(o, t)
        assertTrue(logLines.none { "early death" in it }, "an attach that held 5.5 s is not an early death: ${logLines.filter { "early death" in it }}")
        assertTrue(waitedBefore(started) < 2_000, "attempt 1 went at once: ${waitedBefore(started)} ms")
    }

    /** The window's numbers are the waits of the generated guard: a change there must come here (and to the window) too. */
    @Test fun theRefusalWindowFollowsTheGuardsOwnWaits() {
        val installer = SessionPersistenceService.INSTALL_RESTORE_COMMAND
        val from = installer.indexOf('\n', installer.indexOf("<<'SOCKHEAL_EOF'")) + 1
        val sockheal = installer.substring(from, installer.indexOf("\nSOCKHEAL_EOF\n", from))
        assertTrue("flock -w 2 8 || { sg_log lock" in installer, "a heal try waits 2 s for its lock")
        assertTrue("end=\$(( \$(date +%s%N) / 1000000 + 2000 ))" in installer,
            "or 2 s for the recreated socket")
        assertTrue("then sleep 0.5; sg_run; fi" in sockheal, "one more try 0.5 s later")
        assertTrue("timeout -k 1 5 tmux -S" in installer, "a hung server's one probe: 5 s + 1 s kill")
        assertEquals(2_000L, SessionOrchestrator.SOCKHEAL_HEAL_TRY_MS)
        assertEquals(500L, SessionOrchestrator.SOCKHEAL_RETRY_PAUSE_MS)
        assertEquals(6_000L, SessionOrchestrator.SOCKHEAL_HUNG_PROBE_MS)
        assertEquals(6_000L, SessionOrchestrator.SOCKHEAL_LONGEST_REFUSAL_MS)
        assertEquals(11_000L, SessionOrchestrator.LAUNCH_EARLY_DEATH_MS)
        assertTrue(SessionOrchestrator.LAUNCH_EARLY_DEATH_MS > 4_700 + 5_000, "covers the measured 4.7 s refusal plus a 5 s round trip")
    }
}
