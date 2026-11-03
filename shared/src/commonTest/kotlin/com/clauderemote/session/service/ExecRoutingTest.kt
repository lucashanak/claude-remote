package com.clauderemote.session.service

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import com.clauderemote.session.TabManager
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** What the exec-routing helpers of [ConnectionRegistry] do with the seam slots (fakes only, no network). */
class ExecRoutingTest {
    private val s1 = SshServer(id = "s1", name = "one", host = "h1", username = "u")
    private val s2 = SshServer(id = "s2", name = "two", host = "h2", username = "u")

    @BeforeTest fun reset() = CommFlags.resetForTest()
    @AfterTest fun cleanup() = CommFlags.resetForTest()

    private fun tab(id: String, server: SshServer) = ClaudeSession(
        id = id, server = server, folder = "/x", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-server-$id", connectionType = ConnectionType.SSH,
    )

    private fun newSession(): Session = JSch().getSession("u", "localhost", 22)

    private class Provider(val byServer: Map<String, Session?>) : ExecSessionProvider {
        override fun session(serverId: String): Session? = byServer[serverId]
        override suspend fun await(serverId: String, ms: Long): Session? = byServer[serverId]
    }

    internal class Call(val serverId: String, val cmd: String, val stdin: ByteArray?, val kind: ExecKind)

    private class FakeExec(val calls: MutableList<Call> = mutableListOf(), val fail: ExecUnavailable? = null) : RemoteExec {
        override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace): ExecResult {
            calls += Call(serverId, cmd, stdin, kind)
            fail?.let { throw it }
            return ExecResult("out:$cmd", 0)
        }
    }

    private fun registry(vararg tabs: ClaudeSession): ConnectionRegistry {
        val tm = TabManager()
        tabs.forEach { tm.addTab(it) }
        return ConnectionRegistry(ServerStorage(FakeKeyValueStore()), tm)
    }

    @Test fun theExecSessionIsNeverHandedOutWithoutAPermit() {
        val reg = registry(tab("a", s1), tab("b", s2), tab("c", s2))
        reg.execSessions = Provider(mapOf("s1" to newSession(), "s2" to newSession()))
        reg.remoteExec = FakeExec()
        // no tab has a connected shell: the answer is null, never the exec session of the (busiest) server
        assertNull(reg.liveServerSession("s1"))
        assertNull(reg.liveServerSession("s2"))
        assertNull(reg.anyLiveSession())
    }

    @Test fun slotsNotWiredMeansLegacyLookup() {
        val reg = registry(tab("a", s1))
        assertNull(reg.liveServerSession("s1"))
        assertEquals(false, reg.execActive("s1"))
        reg.execSessions = Provider(mapOf("s1" to newSession())) // provider without remoteExec: still legacy
        assertEquals(false, reg.execActive("s1"))
        reg.remoteExec = FakeExec()
        assertEquals(true, reg.execActive("s1"))
    }

    @Test fun serverOffExecTurnsRoutingOffForThatServerOnly() = runBlocking {
        val reg = registry(tab("a", s1), tab("b", s2))
        val fake = FakeExec()
        reg.execSessions = Provider(mapOf("s1" to newSession(), "s2" to newSession()))
        reg.remoteExec = fake
        CommFlags.applyServerOff("s1", setOf("exec"))
        assertEquals(false, reg.execActive("s1"))
        assertEquals(true, reg.execActive("s2"))
        assertNull(reg.execRun("s1", "x")) // legacy: no tab shell, nothing to run on
        assertEquals("out:x", reg.execRun("s2", "x")?.stdout)
        assertEquals(listOf("s2"), fake.calls.map { it.serverId })
    }

    @Test fun keepAliveReachesTheExecTransportsToo() {
        val reg = registry(tab("a", s1))
        val mgr = PooledExecTransportManager { null }
        reg.execSessions = mgr
        reg.setKeepAliveIntervalAll(60_000)
        assertEquals(60_000, mgr.keepAliveMs)
    }

    @Test fun execRunGoesThroughRemoteExecWithKindAndStdin() = runBlocking {
        val reg = registry(tab("a", s1))
        val fake = FakeExec()
        reg.execSessions = Provider(mapOf("s1" to newSession()))
        reg.remoteExec = fake
        val r = reg.execRun("s1", "echo hi", stdin = byteArrayOf(1, 2), kind = ExecKind.LONG)
        assertEquals("out:echo hi", r?.stdout)
        val c = fake.calls.single()
        assertEquals(ExecKind.LONG, c.kind)
        assertEquals(2, c.stdin?.size)
        assertEquals("out:echo", reg.execRead("s1", "echo"))
    }

    @Test fun execNotUpWithoutAnyTabSessionIsNullButEveryOtherFailureIsThrown() = runBlocking {
        val reg = registry(tab("a", s1))
        reg.execSessions = Provider(emptyMap())
        reg.remoteExec = FakeExec(fail = ExecUnavailable(ExecUnavailableReason.NOT_UP))
        assertNull(reg.execRun("s1", "x"))
        assertNull(reg.execRead("s1", "x"))
        for (reason in listOf(ExecUnavailableReason.TIMEOUT, ExecUnavailableReason.REFUSED, ExecUnavailableReason.IO, ExecUnavailableReason.GATE_TIMEOUT)) {
            reg.remoteExec = FakeExec(fail = ExecUnavailable(reason))
            val e = assertFailsWith<ExecUnavailable> { reg.execRun("s1", "x") }
            assertEquals(reason, e.reason)
        }
    }

    @Test fun legacyRoutingReturnsNullWithoutATabSession() = runBlocking {
        val reg = registry(tab("a", s1))
        assertNull(reg.execRun("s1", "x"))
        assertNull(reg.execRead("s1", "x"))
        val e = assertFailsWith<ExecUnavailable> { reg.useChannel("s1", ExecKind.SHORT) { 1 } }
        assertEquals(ExecUnavailableReason.NOT_UP, e.reason)
    }

    @Test fun useChannelRunsTheBlockOnceEvenWhenItThrowsNotUpItself() = runBlocking {
        val reg = registry(tab("a", s1))
        var runs = 0
        reg.execSessions = Provider(mapOf("s1" to newSession()))
        reg.remoteExec = FakeExec()
        reg.execChannels = object : ExecChannels {
            override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Channel) -> T): T =
                block(com.jcraft.jsch.ChannelExec())
            override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (Session) -> T): T =
                throw UnsupportedOperationException()
        }
        assertFailsWith<ExecUnavailable> {
            reg.useChannel("s1", ExecKind.SHORT) { runs++; throw ExecUnavailable(ExecUnavailableReason.NOT_UP) }
        }
        assertEquals(1, runs)
        assertNotNull(reg.activeExecChannels("s1"))
        CommFlags.applyServerOff("s1", setOf("exec"))
        assertNull(reg.activeExecChannels("s1"))
    }

    @Test fun gatedChannelsAreNeverUsedWhenTheNotUpComesFromTheGate() = runBlocking {
        val reg = registry(tab("a", s1))
        reg.execSessions = Provider(emptyMap())
        reg.remoteExec = FakeExec()
        reg.execChannels = object : ExecChannels {
            override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Channel) -> T): T =
                throw ExecUnavailable(ExecUnavailableReason.GATE_TIMEOUT)
            override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (Session) -> T): T =
                throw UnsupportedOperationException()
        }
        val e = assertFailsWith<ExecUnavailable> { reg.useChannel("s1", ExecKind.SFTP) { 1 } }
        assertEquals(ExecUnavailableReason.GATE_TIMEOUT, e.reason) // not swallowed into the tab fallback
    }
}
