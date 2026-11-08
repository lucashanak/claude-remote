package com.clauderemote.session

import com.clauderemote.connection.SshManager
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
import com.clauderemote.session.service.execReadWithWatchdog
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.jcraft.jsch.FakeSession
import com.jcraft.jsch.RecordingExecChannel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C5 kolo 4 (review MEDIUM-1, adv N3): the rest of the JSch writes the UI could start on Android's main thread — the
 * notify watcher's CLOSE when the last tab of a server is closed, the Reconnect button's `reconnectSession`, the CLOSE of
 * `execReadWithWatchdog` and of the legacy has-session probe. Each is called here from a single thread named
 * "main-like" and must write elsewhere. (In tests the coroutine debug mode appends " @coroutine#n" to a thread's name.)
 */
class MainThreadWritesTest {
    private val main = Executors.newSingleThreadExecutor { r -> Thread(r, "main-like") }
    private val storage = ServerStorage(FakeKeyValueStore())
    private val tabs = TabManager()
    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")
    private var orchestrator: SessionOrchestrator? = null

    @AfterTest fun tearDown() {
        orchestrator?.shutdownForTest()
        main.shutdown()
        CommFlags.resetForTest()
    }

    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(main.asCoroutineDispatcher()) { block() } }

    private fun offMain(threads: List<String>, what: String) {
        assertTrue(threads.isNotEmpty(), "$what: the write happened")
        assertTrue(threads.none { it.startsWith("main-like") }, "$what: never on the caller's (main) thread: $threads")
    }

    private fun orchestrator(): SessionOrchestrator {
        storage.addServer(server)
        return SessionOrchestrator(storage, tabs).also {
            orchestrator = it
            it.connectionRegistry.remoteExec = object : RemoteExec {
                override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace) = ExecResult("", 0)
            }
        }
    }

    private fun <T> field(o: Any, name: String): T {
        val f = o.javaClass.getDeclaredField(name).also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST") return f.get(o) as T
    }

    @Test fun closingTheLastTabOfAServerClosesTheNotifyWatchersChannelOffTheMainThread() {
        val o = orchestrator()
        val notifications: Any = field(o, "notificationService")
        val watcherClass = Class.forName("com.clauderemote.session.service.NotificationService\$ServerNotifyWatcher")
        val w = watcherClass.getDeclaredConstructor(String::class.java).also { it.isAccessible = true }.newInstance("srv-1")
        val ch = RecordingExecChannel()
        watcherClass.getDeclaredMethod("setChannel", com.jcraft.jsch.ChannelExec::class.java).also { it.isAccessible = true }.invoke(w, ch)
        @Suppress("UNCHECKED_CAST")
        val map = watcherClass.getDeclaredMethod("getTmuxToSession").also { it.isAccessible = true }.invoke(w) as MutableMap<String, String>
        map["claude-box-t1"] = "t1"
        @Suppress("UNCHECKED_CAST")
        (field<Any>(notifications, "serverNotifyWatchers") as MutableMap<String, Any>)["srv-1"] = w
        val unregister = notifications.javaClass.getDeclaredMethod("unregisterNotifyWatcher", String::class.java, String::class.java)
        main.submit { unregister.invoke(notifications, "srv-1", "t1") }.get()
        val end = System.currentTimeMillis() + 5_000
        while (ch.disconnectThreads.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        offMain(ch.disconnectThreads, "the watcher's CLOSE")
    }

    @Test fun theReconnectButtonsReconnectRunsOffTheMainThread() {
        val o = orchestrator()
        val threads = CopyOnWriteArrayList<String>()
        o.connectSeam = { _, _, _, _ -> threads += Thread.currentThread().name; true }
        o.tmuxCommandSeam = { _, _, _ -> true }
        tabs.addTab(ClaudeSession(
            id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
            tmuxSessionName = "claude-box-t1", connectionType = ConnectionType.SSH, status = SessionStatus.ERROR,
        ))
        onMain { o.reconnectSession("t1") }
        offMain(threads, "reconnectSession's connect")
    }

    @Test fun aWatchdogExecClosesItsChannelOffTheMainThread() {
        val ch = RecordingExecChannel()
        val session = FakeSession(channels = { ch })
        val r = onMain { runCatching { execReadWithWatchdog(session, "true", totalMs = 2_000) } }
        assertTrue(r.isFailure, "the refused open fails the exec")
        offMain(ch.disconnectThreads, "execReadWithWatchdog's CLOSE")
    }

    /** Kolo 5 (V12): a read that hangs until the bound: the watchdog's CLOSE at `totalMs` is written off the main thread too. */
    @Test fun aWatchdogExecWhoseReadHangsIsClosedAtItsBoundOffTheMainThread() {
        val ch = com.jcraft.jsch.SilentExecChannel()
        val session = FakeSession(channels = { ch })
        val t0 = System.nanoTime()
        val out = onMain { execReadWithWatchdog(session, "sleep 600", totalMs = 800) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals("", out, "the read ended by the watchdog's close")
        assertTrue(ms in 700..5_000, "at its bound: $ms ms")
        offMain(ch.disconnectThreads, "the watchdog's and the finally's CLOSE")
    }

    /** Kolo 5 (adv N5): an exec through the gate (`PooledRemoteExec` + `GatedExecChannels.use`) closes its channel off the main thread. */
    @Test fun aGatedExecClosesItsChannelOffTheMainThread() {
        val ch = RecordingExecChannel()
        val session = FakeSession(channels = { ch })
        val gate = com.clauderemote.session.service.GatedExecChannels(com.clauderemote.session.service.PooledExecTransportManager { session })
        val exec = com.clauderemote.session.service.PooledRemoteExec(gate)
        val r = onMain { runCatching { exec.run("srv-1", "true", null, 3_000, ExecKind.SHORT, Pace()) } }
        assertTrue(r.isFailure, "the refused open fails the exec: $r")
        offMain(ch.disconnectThreads, "the exec's and the gate's CLOSE")
    }

    @Test fun theLegacyHasSessionProbeRunsOffTheMainThread() {
        val o = orchestrator()
        val ch = RecordingExecChannel()
        val m = SshManager(storage)
        SshManager::class.java.getDeclaredField("session").also { it.isAccessible = true }.set(m, FakeSession(channels = { ch }))
        val probes: com.clauderemote.session.service.TmuxProbes = field(o, "tmuxProbes")
        val alive = onMain { probes.probeTmuxSession(m, "claude-box-t1") }
        assertEquals(true, alive, "a refused probe fails open")
        offMain(ch.disconnectThreads, "the probe's CLOSE")
    }
}
