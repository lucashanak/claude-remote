package com.clauderemote.session

import com.clauderemote.connection.ChannelOpen
import com.clauderemote.connection.SshManager
import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecTransportManager
import com.clauderemote.session.service.GatedExecChannels
import com.clauderemote.session.service.LinkState
import com.clauderemote.session.service.PooledRemoteExec
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.FakeSession
import com.jcraft.jsch.FakeShellChannel
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Kolo 2 (review C1-1): the class of the tmux probes on the exec transport.
 *  - the redraw kick's refresh-client over a Cloudflare exec transport still opens its channel (on round 1 its 1.5 s
 *    bound left less than Cloudflare's floor after the permit: it never ran);
 *  - `probeTmuxSession` decides launch/attach and what an ended attach means: interactive, it runs although the poll
 *    class has no permit left.
 */
class TmuxProbeClassTest {
    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val logLines = CopyOnWriteArrayList<String>()
    private val savedLog = FileLogger.platformLog
    private val tabs = TabManager()
    private val storage = ServerStorage(FakeKeyValueStore())
    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")
    private var orchestrator: SessionOrchestrator? = null

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        ChannelOpen.resetForTest()
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
        storage.addServer(server)
    }

    @AfterTest fun tearDown() {
        orchestrator?.shutdownForTest()
        FileLogger.platformLog = savedLog
        SshSessionHelper.execChannelsProvider = null
        SshSessionHelper.liveSessionProvider = null
        SshSessionHelper.fallbackRunner = null
        ChannelOpen.resetForTest()
        CommFlags.resetForTest()
    }

    private class UpManager(private val up: Session) : ExecTransportManager {
        override fun ensure(serverId: String) {}
        override fun session(serverId: String): Session? = up
        override suspend fun await(serverId: String, ms: Long): Session? = up
        override fun state(serverId: String): StateFlow<LinkState> = MutableStateFlow(LinkState.Up("test"))
        override fun close(serverId: String) {}
        override fun onNetworkLost() {}
        override fun onNetworkAvailable() {}
        override fun generation(serverId: String): Long = 1
        override fun setKeepAlive(ms: Int) {}
    }

    /** An exec transport whose channels record their command and answer [answer] at once. */
    private fun execTransport(answer: String, cf: Boolean, opened: CopyOnWriteArrayList<String>): Pair<FakeSession, GatedExecChannels> {
        lateinit var sess: FakeSession
        sess = FakeSession(channels = { object : ChannelExec() {
            @Volatile var cmd = ""
            override fun getSession(): Session = sess
            override fun setCommand(command: String) { cmd = command }
            override fun setInputStream(`in`: java.io.InputStream?) {}
            override fun getInputStream(): java.io.InputStream = answer.byteInputStream()
            override fun getExitStatus(): Int = 0
            override fun connect(connectTimeout: Int) { opened += cmd }
            override fun disconnect() {}
        } })
        if (cf) ChannelOpen.markCloudflare(sess)
        val m = UpManager(sess)
        return sess to GatedExecChannels(m)
    }

    private fun wire(gate: GatedExecChannels, sess: Session): SessionOrchestrator {
        val o = SessionOrchestrator(storage, tabs, SessionStorage(FakeKeyValueStore())).also { orchestrator = it }
        o.connectionRegistry.execSessions = UpManager(sess)
        o.connectionRegistry.execChannels = gate
        o.connectionRegistry.remoteExec = PooledRemoteExec(gate)
        return o
    }

    private fun <T> field(o: SessionOrchestrator, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return SessionOrchestrator::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(o) as T
    }

    @Test fun theRedrawKickOverACloudflareExecTransportOpensItsChannel() {
        val opened = CopyOnWriteArrayList<String>()
        val (sess, gate) = execTransport("OK\n", cf = true, opened = opened)
        val o = wire(gate, sess)
        tabs.addTab(ClaudeSession(id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
            tmuxSessionName = "claude-box-a", connectionType = ConnectionType.SSH))
        val m = SshManager(storage)
        SshManager::class.java.getDeclaredField("session").also { it.isAccessible = true }.set(m, JSch().getSession("u", "127.0.0.1", 1))
        SshManager::class.java.getDeclaredField("channel").also { it.isAccessible = true }.set(m, FakeShellChannel())
        o.connectionRegistry.putSsh("t1", m)
        field<com.clauderemote.session.service.TmuxProbes>(o, "tmuxProbes").kickRedraw("t1", 80, 24)
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end && opened.none { "refresh-client" in it }) Thread.sleep(20)
        assertTrue(opened.any { "refresh-client" in it }, "the kick's channel was opened over Cloudflare: $opened ${logLines.filter { "refresh" in it || "floor" in it }}")
    }

    @Test fun theProbeThatDecidesLaunchOrAttachRunsWithThePollClassFull() {
        val opened = CopyOnWriteArrayList<String>()
        val (sess, gate) = execTransport("NO\n", cf = false, opened = opened)
        val o = wire(gate, sess)
        runBlocking {
            withTimeout(20_000) {
                // 4 of 6 SHORT permits taken: a poll would be skipped now
                val release = CompletableDeferred<Unit>()
                val holders = (1..4).map { async(Dispatchers.IO) { gate.use(server.id, ExecKind.SHORT) { release.await() } } }
                while (gate.held(server.id).first < 4) delay(5)
                val probes = field<com.clauderemote.session.service.TmuxProbes>(o, "tmuxProbes")
                val alive = probes.probeTmuxSession(server.id, "claude-box-gone")
                release.complete(Unit)
                holders.forEach { it.await() }
                assertTrue(opened.any { "has-session" in it }, "the decision's probe ran: $opened")
                assertTrue(!alive, "and its NO was heard (not a fail-open skip)")
            }
        }
    }
}
