package com.clauderemote.connection

import com.clauderemote.model.SshServer
import com.clauderemote.session.service.ExecChannels
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecUnavailable
import com.clauderemote.session.service.ExecUnavailableReason
import com.clauderemote.session.status.SessionStatusPoller
import com.clauderemote.session.transcript.TranscriptStream
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C1 (kritika K-1 bod 2, H12, H13): the periodic polls take a permit of the poll class ([ExecKind.POLL]) and, without
 * one, skip the tick quietly — no `poll error` line, no backoff; `pollNow` (the Stop path of a notification) is not
 * periodic and takes an interactive permit. On 18faa58 every poll asks for [ExecKind.SHORT] and a refused permit is an
 * error line.
 */
class PollPriorityTest {
    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val server = SshServer(id = "s1", name = "n", host = "127.0.0.1", port = 1, username = "u")
    private val savedLog = FileLogger.platformLog
    private val lines = CopyOnWriteArrayList<String>()

    /** A gate that has no poll permit: POLL = GATE_TIMEOUT; an interactive permit = the exec transport is not up. */
    private class NoPollPermits : ExecChannels {
        val kinds = CopyOnWriteArrayList<ExecKind>()
        override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Channel) -> T): T =
            throw UnsupportedOperationException()
        override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (Session) -> T): T {
            kinds += kind
            throw ExecUnavailable(if (kind == ExecKind.POLL) ExecUnavailableReason.GATE_TIMEOUT else ExecUnavailableReason.NOT_UP)
        }
    }

    @BeforeTest fun setUp() {
        FileLogger.platformLog = { _, tag, message, _ -> lines += "$tag: $message" }
        com.clauderemote.session.service.PollSkipLog.resetForTest()
    }

    @AfterTest fun tearDown() {
        FileLogger.platformLog = savedLog
        SshSessionHelper.execChannelsProvider = null
        SshSessionHelper.fallbackRunner = null
        SshSessionHelper.liveSessionProvider = null
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && !cond()) Thread.sleep(20)
        return cond()
    }

    @Test fun aTranscriptTickWithoutAPollPermitIsSkippedQuietlyAndPollNowIsInteractive() = runBlocking {
        val gate = NoPollPermits()
        SshSessionHelper.execChannelsProvider = { gate }
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            val s = TranscriptStream(server, "/work", scope, liveSession = { null }, viaExec = { true })
            s.start("11111111-1111-1111-1111-111111111111")
            assertTrue(waitFor(10_000) { gate.kinds.count { it == ExecKind.POLL } >= 2 }, "the ticks ask for poll permits: ${gate.kinds}")
            assertTrue(gate.kinds.all { it == ExecKind.POLL }, "every tick is of the poll class: ${gate.kinds}")
            gate.kinds.clear()
            // R14: the skipped ticks are counted where they happen
            assertTrue((com.clauderemote.session.service.PollSkipLog.counts(server.id)["transcript"] ?: 0) >= 2,
                "${com.clauderemote.session.service.PollSkipLog.counts(server.id)}")
            // adv 6: a skipped tick polled nothing: the view must not say it is connected
            assertTrue(s.status.value?.startsWith("connected") != true, "status after skipped ticks: ${s.status.value}")
            s.pollNow()
            assertTrue(ExecKind.SHORT in gate.kinds, "pollNow (the Stop path) is interactive, never skipped for want of a poll permit: ${gate.kinds}")
            s.stop()
            assertTrue(lines.none { "transcript poll error" in it }, "a skipped tick is no error: ${lines.filter { "error" in it }}")
            assertEquals(null, s.status.value?.takeIf { it.startsWith("retry") || it.startsWith("reconnecting") }, "and no retry/backoff state")
        } finally { scope.cancel() }
    }

    @Test fun aStatusTickWithoutAPollPermitIsSkippedQuietly() = runBlocking {
        val gate = NoPollPermits()
        SshSessionHelper.execChannelsProvider = { gate }
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            val p = SessionStatusPoller(server, "/work", { null }, scope, liveSession = { null }, viaExec = { true })
            p.start()
            assertTrue(waitFor(10_000) { gate.kinds.isNotEmpty() }, "the status poll asked for a permit")
            p.stop()
            assertTrue(gate.kinds.all { it == ExecKind.POLL }, "${gate.kinds}")
            assertTrue((com.clauderemote.session.service.PollSkipLog.counts(server.id)["status"] ?: 0) >= 1, "R14: counted")
            assertTrue(lines.none { "status poll error" in it }, "${lines.filter { "error" in it }}")
        } finally { scope.cancel() }
    }

    /** Doplněk 1, bod 2: the skips are counted and written as one line per server and window, measurable against a baseline. */
    @Test fun skippedTicksAreCountedIntoOneLinePerServerAndWindow() {
        com.clauderemote.session.service.PollSkipLog.resetForTest()
        val log = com.clauderemote.session.service.PollSkipLog
        val t0 = 1_000_000L
        repeat(3) { log.skipped("srvA", log.TRANSCRIPT, now = t0 + it) }
        log.skipped("srvA", log.STATUS, now = t0 + 10)
        log.skipped("srvB", log.PROBE, now = t0 + 10)
        assertTrue(lines.none { "poll skipped" in it }, "nothing within the window: $lines")
        log.skipped("srvA", log.PROBE, now = t0 + log.WINDOW_MS)
        val line = lines.single { "poll skipped" in it }
        assertTrue("server=srvA transcript=3 status=1 probe=1 in 60s" in line, line)
        log.skipped("srvA", log.STATUS, now = t0 + log.WINDOW_MS + 1)
        assertEquals(1, lines.count { "poll skipped" in it }, "a new window starts empty")
        // R14b: the counts of the closed window are gone from the new one
        assertEquals(mapOf("transcript" to 0, "status" to 1, "probe" to 0), log.counts("srvA"))
        log.skipped("srvA", log.PROBE, now = t0 + 2 * log.WINDOW_MS + 1)
        assertTrue(lines.any { "server=srvA transcript=0 status=1 probe=1" in it }, "${lines.filter { "poll skipped" in it }}")
        com.clauderemote.session.service.PollSkipLog.resetForTest()
    }

    /**
     * R13: the restore-service install, the manifest push and the sessions.json sync are interactive work (SHORT, the
     * reserve may serve them): the exec helpers default to SHORT and none of those files asks for the poll class.
     */
    @Test fun installPushAndSyncStayInTheInteractiveClass() = runBlocking {
        val registry = com.clauderemote.session.service.ConnectionRegistry(
            com.clauderemote.storage.ServerStorage(com.clauderemote.storage.FakeKeyValueStore()), com.clauderemote.session.TabManager())
        val kinds = CopyOnWriteArrayList<ExecKind>()
        registry.execSessions = object : com.clauderemote.session.service.ExecSessionProvider {
            override fun session(serverId: String): Session? = null
            override suspend fun await(serverId: String, ms: Long): Session? = null
        }
        registry.remoteExec = object : com.clauderemote.session.service.RemoteExec {
            override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind,
                                     paceStdin: com.clauderemote.session.service.Pace): com.clauderemote.session.service.ExecResult {
                kinds += kind
                return com.clauderemote.session.service.ExecResult("", 0)
            }
        }
        registry.execRead("s1", "true")
        registry.execRun("s1", "true")
        assertEquals(listOf(ExecKind.SHORT, ExecKind.SHORT), kinds.toList(), "the defaults the persistence code relies on")
        val files = listOf("SessionPersistenceService.kt", "ManifestCommands.kt", "ServerReconciler.kt", "ServerBootstrap.kt")
            .map { java.io.File("../shared/src/commonMain/kotlin/com/clauderemote/session/service/$it") }
        assertTrue(files.all { it.isFile }, "the sources are where this test looks: $files")
        assertTrue(files.none { "ExecKind.POLL" in it.readText() }, "install / push / sync never ask for the poll class")
    }
}
