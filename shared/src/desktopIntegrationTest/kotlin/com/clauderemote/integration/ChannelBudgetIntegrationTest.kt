package com.clauderemote.integration

import com.clauderemote.connection.SshManager
import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.connection.TransportRole
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.PortForward
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecUnavailable
import com.clauderemote.session.service.LinkState
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The channel budget of the tab transports against a real sshd with the default `MaxSessions` 10 (davka B, B1).
 * The probes n1-n3 of the W2 adversarial round, turned into tests: with the exec transport down or switched off and
 * the shells packed on their transports, the tabs' own loops (transcript streams, the status poller, the per-tab
 * session-id loop) and the one-off ops of `withSession` must never ask sshd for a channel it refuses. Plus K09: the
 * port forwards go back to the exec transport when it is switched on again (asserted on the sessions, not on a port).
 */
class ChannelBudgetIntegrationTest {
    @get:org.junit.Rule(order = 0) val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(300)

    @get:org.junit.Rule(order = 1) val dumpOnFailure = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable?, description: org.junit.runner.Description?) {
            println("---- log lines of ${description?.methodName} ----\n" + logLines.takeLast(200).joinToString("\n"))
        }
    }

    private var fixture: SshdFixture? = null
    private val storage = ServerStorage(InMemoryKeyValueStore())
    private val tabs = TabManager()
    private lateinit var orchestrator: SessionOrchestrator
    private val managers = CopyOnWriteArrayList<SshManager>()
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedPlatformLog = FileLogger.platformLog
    private val savedExecChannels = SshSessionHelper.execChannelsProvider
    private val savedLiveSession = SshSessionHelper.liveSessionProvider

    @Before fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
    }

    @After fun tearDown() {
        if (::orchestrator.isInitialized) {
            orchestrator.shutdownForTest()
            orchestrator.connectionRegistry.teardownAllTransports()
            runCatching { orchestrator.execManager.close(serverId) }
        }
        managers.forEach { it.shutdownForTest() }
        FileLogger.platformLog = savedPlatformLog
        SshSessionHelper.execChannelsProvider = savedExecChannels
        SshSessionHelper.liveSessionProvider = savedLiveSession
        CommFlags.resetForTest()
        fixture?.close()
    }

    private val serverId get() = fixture!!.server.id
    private val registry get() = orchestrator.connectionRegistry
    private val pool get() = registry.transportPool(serverId)

    private fun boot(reapDelayMs: Long = 0): SshdFixture {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        val f = SshdFixture.start(reapDelayMs = reapDelayMs).also { fixture = it }
        storage.addServer(f.server)
        orchestrator = SessionOrchestrator(storage, tabs, SessionStorage(InMemoryKeyValueStore()))
        return f
    }

    private fun tab(f: SshdFixture, id: String, n: Int) = ClaudeSession(
        id = id, server = f.server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-server-$id", connectionType = ConnectionType.SSH,
        claudeSessionId = "00000000-0000-4000-8000-%012d".format(n),
    )

    private fun addTabs(f: SshdFixture, n: Int) = (1..n).map { i -> tab(f, "t$i", i).also { tabs.addTab(it) } }

    private fun execUp() = runBlocking {
        assertNotNull(withTimeout(30_000) { orchestrator.execManager.await(serverId, 25_000) }, "exec transport did not come up")
    }

    private fun connectShells(f: SshdFixture, ids: List<String>) = runBlocking {
        val ms = ids.map { id -> SshManager(storage, transportPool = pool).also { m -> managers += m; registry.putSsh(id, m) } }
        withTimeout(120_000) { ms.map { m -> async(Dispatchers.IO) { m.connect(f.server, {}, {}) } }.awaitAll() }
        ms
    }

    /** The tabs' own loops: a transcript stream per tab and the status poller of the active one. */
    private fun startTabLoops(ts: List<ClaudeSession>) {
        ts.forEach { orchestrator.transcriptFlow(it.id) }
        orchestrator.remoteStatusFlow(ts.first().id)
    }

    private fun skippedLines() = logLines.count { it.contains("poll skipped (no room)") }

    // ---- n1: the kill switch with 8 shells packed on one transport ---------------------------------------------------

    @Test fun n1_theKillSwitchWithEightPackedShellsRefusesNoChannelToTheTabsOwnLoops() {
        val f = boot()
        val t = addTabs(f, 8)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        val ms = connectShells(f, t.map { it.id })
        assertEquals(1, pool.liveCount(TransportRole.SHELL), "8 shells ride ONE transport (cap 8)")
        CommFlags.set(CommFlags.Raw(exec = false))
        waitUntil(20_000, "the exec transport closed by the kill switch") { orchestrator.execManager.state(serverId).value is LinkState.Closed }
        Thread.sleep(1_000)
        val r0 = f.refusedChannelOpens()
        startTabLoops(t)
        t.zip(ms).forEach { (tab, m) -> orchestrator.persistence.startSessionIdRefresh(tab.id, tab.tmuxSessionName, m) }
        Thread.sleep(10_000)
        val refused = f.refusedChannelOpens() - r0
        println("MEASURED n1 refused=$refused skippedLines=${skippedLines()}")
        assertEquals(0, refused, "sshd refused channels of the tabs' own loops (8 shells + uncounted channels > MaxSessions 10)")
    }

    // ---- n4 (R2, V9): the same kill switch must not starve the tabs: every tab still polls ------------------------------
    // (Adversarial review, round 1: with the whole FALLBACK_RESERVE kept free, 8 shells + 1 + 2 > 10 refused every
    // counted channel, so for 20 s not one poll of any tab ran, silently. A short poll may take ONE slot of the reserve.)

    /** The commands the fixture's sshd ran (its wrapper logs every one), from line [from] on. */
    private fun remoteCommands(f: SshdFixture, from: Int = 0): List<String> =
        java.io.File(f.root, "remote-commands.log").readTextOrEmpty().lines().filter { it.isNotBlank() }.drop(from)

    @Test fun n4_theKillSwitchWithEightPackedShellsStillLetsEveryTabPollWithoutARefusal() {
        val f = boot()
        val t = addTabs(f, 8)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        val ms = connectShells(f, t.map { it.id })
        assertEquals(1, pool.liveCount(TransportRole.SHELL), "8 shells ride ONE transport (cap 8)")
        CommFlags.set(CommFlags.Raw(exec = false))
        waitUntil(20_000, "the exec transport closed by the kill switch") { orchestrator.execManager.state(serverId).value is LinkState.Closed }
        Thread.sleep(1_000)
        val r0 = f.refusedChannelOpens()
        val c0 = remoteCommands(f).size
        startTabLoops(t)
        t.zip(ms).forEach { (tab, m) -> orchestrator.persistence.startSessionIdRefresh(tab.id, tab.tmuxSessionName, m) }
        // 30 s: the polls of 8 tabs (plus their session-id ticks) share ONE channel, one at a time (measured 1-2 transcript
        // polls per tab in 20 s at load 2, 2-3 in 30 s); the reviewer's 20 s would leave too little margin for a loaded box
        Thread.sleep(30_000)
        val refused = f.refusedChannelOpens() - r0
        val cmds = remoteCommands(f, c0)
        val polls = t.associate { tab -> tab.id to cmds.count { it.contains("${tab.claudeSessionId}.jsonl") } }
        println("MEASURED n4 refused=$refused commands=${cmds.size} transcriptPollsPerTab=$polls skippedLines=${skippedLines()}")
        assertEquals(0, refused, "sshd refused channels (the reserve may lend ONE slot to a short poll, not more)")
        assertEquals(emptyList(), polls.filterValues { it == 0 }.keys.toList(), "tabs whose transcript was not polled once in 30 s: $polls")
    }

    // ---- n5 (V12, round 3): transfers take no reserve slot, so a transfer cannot freeze the 8 tabs' polls ---------------
    // (Adversarial review, round 2, N2: a 3 MB upload held the one reserve slot for 15.7 s and not one poll of the 8 tabs
    // ran meanwhile. Now an upload or SFTP download on the packed transport has no room: it fails at once, readably.)

    /** One upload of [bytes] and one SFTP download from [t]'s tab: outcome and duration of each. */
    private fun transferOnce(t: ClaudeSession, bytes: ByteArray): List<Triple<String, Long, String>> {
        var t0 = System.currentTimeMillis()
        val up = runCatching { runBlocking { orchestrator.uploadFile(t.id, bytes, "n5.bin") } }
        val upMs = System.currentTimeMillis() - t0
        t0 = System.currentTimeMillis()
        val down = runBlocking { orchestrator.downloadFile(t.id, "~/n5-download.bin") }
        val downMs = System.currentTimeMillis() - t0
        return listOf(
            Triple("upload", upMs, up.exceptionOrNull()?.message ?: "uploaded ${up.getOrNull()}"),
            Triple("download", downMs, if (down == null) "null" else "downloaded ${down.size} bytes"),
        )
    }

    @Test fun n5_theKillSwitchWithEightPackedShellsLetsNoTransferTakeTheReserveAndEveryTabStillPolls() {
        val f = boot()
        java.io.File(f.home, "n5-download.bin").writeBytes(ByteArray(3 * 1024 * 1024) { (it % 251).toByte() })
        java.io.File(f.home, "n5-stat.txt").writeText("x")
        val t = addTabs(f, 8)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        val ms = connectShells(f, t.map { it.id })
        assertEquals(1, pool.liveCount(TransportRole.SHELL), "8 shells ride ONE transport (cap 8)")
        CommFlags.set(CommFlags.Raw(exec = false))
        waitUntil(20_000, "the exec transport closed by the kill switch") { orchestrator.execManager.state(serverId).value is LinkState.Closed }
        Thread.sleep(1_000)
        startTabLoops(t)
        t.zip(ms).forEach { (tab, m) -> orchestrator.persistence.startSessionIdRefresh(tab.id, tab.tmuxSessionName, m) }
        val r0 = f.refusedChannelOpens()
        val c0 = remoteCommands(f).size
        // 30 s of transfers, one after another, from every tab in turn: on the round-2 code each upload held the slot
        // for its whole run, so the polls stopped; here none gets room
        val bytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val outcomes = mutableListOf<Triple<String, Long, String>>()
        val end = System.currentTimeMillis() + 30_000
        var i = 0
        while (System.currentTimeMillis() < end) {
            outcomes += transferOnce(t[i++ % t.size], bytes)
            Thread.sleep(500)
        }
        val polls = t.associate { tab -> tab.id to remoteCommands(f, c0).count { it.contains("${tab.claudeSessionId}.jsonl") } }
        // T1 with V12: the SFTP stat (a chat's link check) is short and still finds the slot between the polls
        val stat = (1..10).firstNotNullOfOrNull { runBlocking { orchestrator.statFiles(t[0].id, listOf("~/n5-stat.txt")) }.takeIf { it.isNotEmpty() } ?: run { Thread.sleep(300); null } }
        val refused = f.refusedChannelOpens() - r0
        println("MEASURED n5 refused=$refused transfers=${outcomes.size} slowest=${outcomes.maxOf { it.second }}ms " +
            "outcomes=${outcomes.groupingBy { "${it.first}: ${it.third.take(60)}" }.eachCount()} pollsPerTab=$polls stat=$stat")
        assertEquals(0, refused, "sshd refused channels")
        assertEquals(emptyList(), polls.filterValues { it == 0 }.keys.toList(), "tabs not polled once in 30 s of transfers: $polls")
        val ups = outcomes.filter { it.first == "upload" }
        assertTrue(ups.all { it.third.contains("No free SSH channel") }, "every upload failed readably for want of room: $ups")
        // R3-1 (round 4): 8 shells and the kill switch = no room can come, so at once, and the message says no timeout
        assertTrue(ups.none { it.third.contains("timeout") }, "an upload that failed at once does not call it a timeout: $ups")
        assertTrue(outcomes.filter { it.first == "download" }.all { it.third == "null" }, "no download took the reserve: $outcomes")
        assertTrue(outcomes.all { it.second < 5_000 }, "a transfer without room ends at once, it does not hang: ${outcomes.map { it.second }}")
        assertEquals(setOf("~/n5-stat.txt"), stat, "the SFTP stat still runs in the reserve slot")
    }

    // ---- n2: exec transport down (flag on), 7 shells ------------------------------------------------------------------

    @Test fun n2_withTheExecTransportDownAndSevenShellsNeitherWithSessionNorTheStreamsAreRefused() {
        val f = boot()
        val t = addTabs(f, 7)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        connectShells(f, t.map { it.id })
        orchestrator.execManager.close(serverId)
        Thread.sleep(800)
        val r0 = f.refusedChannelOpens()
        // seven one-off ops at once through the helper (the history scan, the folder browser, ...): each holds its channel
        val res = runBlocking {
            t.map {
                async(Dispatchers.IO) {
                    runCatching {
                        SshSessionHelper.withSession(f.server, timeout = 10_000, allowFresh = false) { sess ->
                            sess.execCapture("sleep 2; echo x", timeoutMs = 10_000).out.trim()
                        }
                    }
                }
            }.awaitAll()
        }
        val ran = res.count { it.getOrNull() == "x" }
        val skipped = res.count { it.exceptionOrNull() is ExecUnavailable }
        assertEquals(7, ran + skipped, "every op either ran or was told 'no room': ${res.map { it.exceptionOrNull() ?: it.getOrNull() }}")
        assertTrue(ran >= 1, "control: the transport had room for some: $res")
        startTabLoops(t)
        Thread.sleep(10_000)
        val refused = f.refusedChannelOpens() - r0
        println("MEASURED n2 withSession ran=$ran skipped=$skipped refused=$refused skippedLines=${skippedLines()}")
        assertEquals(0, refused, "sshd refused channels")
    }

    // ---- n3: exec transport down, 12 tabs on transports of 8 + 4 ------------------------------------------------------

    @Test fun n3_withTheExecTransportDownTwelveTabsOnEightPlusFourAreNotRefused() = n3(reapDelayMs = 0)

    /**
     * n3 with sshd reaping every command 400 ms after its output ended (dávka E, H1): the client saw EOF, closed the
     * channel and gave its slot back while sshd still held the session, so the next open was refused (refused=9 on
     * 1bdcbd5, first try). A counted slot now comes back only after sshd closed the channel and the grace passed.
     */
    @Test fun n3r_withTheExecTransportDownAndSlowReapingTwelveTabsOnEightPlusFourAreNotRefused() = n3(reapDelayMs = 400)

    private fun n3(reapDelayMs: Long) {
        val f = boot(reapDelayMs)
        val t = addTabs(f, 12)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        connectShells(f, t.map { it.id })
        orchestrator.execManager.close(serverId)
        Thread.sleep(800)
        val r0 = f.refusedChannelOpens()
        startTabLoops(t)
        Thread.sleep(12_000)
        val refused = f.refusedChannelOpens() - r0
        val polls = pollsSince(f, t, 0).values
        println("MEASURED n3 reapDelayMs=$reapDelayMs shellTransports=${pool.liveCount(TransportRole.SHELL)} refused=$refused " +
            "skippedLines=${skippedLines()} transcriptPolls=${polls.sum()} minPerTab=${polls.min()}")
        assertEquals(0, refused, "sshd refused channels")
    }

    // ---- n6 (dávka E, H1 bod 6): the exec transport's gate against slow reaping -----------------------------------------

    /**
     * The exec transport is up and sshd reaps every command 400 ms after its output ended. Five waves of six one-offs at
     * once through `withSession` (SHORT permits of the gate; each block reads its own exec to EOF the production way,
     * `execReadWithWatchdog`): on 1bdcbd5 a wave opened its six while sshd still held the previous six (6 + 6 > 10), the
     * refusals then looked like orphans to the refusal rule (JSch had forgotten the channels) and the transport was
     * replaced. A permit of the gate now comes back only after sshd closed the channel and the grace passed.
     */
    @Test fun n6_withTheExecTransportUpAndSlowReapingBackToBackOneOffsAreNotRefused() {
        val f = boot(reapDelayMs = 400)
        addTabs(f, 1)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        val exec0 = orchestrator.execManager.session(serverId)
        val r0 = f.refusedChannelOpens()
        val res = (1..5).flatMap {
            runBlocking {
                (1..6).map {
                    async(Dispatchers.IO) {
                        runCatching {
                            SshSessionHelper.withSession(f.server, timeout = 10_000, allowFresh = false) { sess ->
                                com.clauderemote.session.service.execReadWithWatchdog(sess, "echo x", totalMs = 10_000).trim()
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        val refused = f.refusedChannelOpens() - r0
        val ran = res.count { it.getOrNull() == "x" }
        val sameTransport = orchestrator.execManager.session(serverId) === exec0
        println("MEASURED n6 refused=$refused ran=$ran of ${res.size} sameExecTransport=$sameTransport " +
            "failures=${res.mapNotNull { it.exceptionOrNull()?.message?.take(80) }.groupingBy { it }.eachCount()}")
        assertEquals(0, refused, "sshd refused channels of the exec transport")
        assertEquals(res.size, ran, "every one-off ran (the gate waits, it does not fail)")
        assertTrue(sameTransport, "the exec transport was not replaced (refusals taken for orphans)")
    }

    // ---- n7 (dávka E, N2-1): concurrent one-offs and the tabs' polls on a packed transport with slow reaping ------------

    /**
     * 8 shells on ONE transport (one short slot), the exec transport down, sshd reaping each command 400 ms late, the
     * tabs' own polls running — and six one-offs at once (`execRun`, 8 s each) the moment a first one-off's slot starts
     * settling (so each of them finds no room only because of it, the condition for waiting; a one-off that finds the slot
     * held by a channel still running gets "no room" at once, as on 1bdcbd5). A waiting one-off takes the slot under the
     * lock that saw it free and waits again when a poll or a sibling was first: each one runs, or says "no room" only
     * once its wait is over (its budget minus the 1.5 s floor). sshd refuses nothing.
     */
    @Test fun n7_concurrentOneOffsAndPollsOnAPackedTransportWithSlowReapingRunOrFailOnlyAtTheirDeadline() {
        val f = boot(reapDelayMs = 400)
        val t = addTabs(f, 8)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        connectShells(f, t.map { it.id })
        assertEquals(1, pool.liveCount(TransportRole.SHELL), "8 shells ride ONE transport (cap 8)")
        orchestrator.execManager.close(serverId)
        Thread.sleep(800)
        val r0 = f.refusedChannelOpens()
        startTabLoops(t)
        Thread.sleep(1_000)
        val budgetMs = 8_000L
        val res = runBlocking {
            assertEquals("p", registry.execRun(serverId, "echo p", totalMs = budgetMs)?.stdout?.trim(), "the first one-off ran")
            (1..6).map { i ->
                async(Dispatchers.IO) {
                    val t0 = System.nanoTime()
                    val r = runCatching { registry.execRun(serverId, "echo x$i", totalMs = budgetMs)?.stdout?.trim() }
                    Triple(i, r, (System.nanoTime() - t0) / 1_000_000)
                }
            }.awaitAll()
        }
        val refused = f.refusedChannelOpens() - r0
        val ok = res.count { (i, r, _) -> r.getOrNull() == "x$i" }
        val early = res.filter { (i, r, ms) -> r.getOrNull() != "x$i" && ms < budgetMs - 1_500 - 200 }
        println("MEASURED n7 ok=$ok of ${res.size} refused=$refused tookMs=${res.map { it.third }.sorted()} " +
            "notOk=${res.filter { (i, r, _) -> r.getOrNull() != "x$i" }.map { "${it.first}:${it.second.exceptionOrNull()?.message ?: it.second.getOrNull()}" }} " +
            "transcriptPolls=${pollsSince(f, t, 0).values.sum()}")
        assertEquals(0, refused, "sshd refused channels")
        assertEquals(emptyList(), early.map { it.first }, "a one-off said no room before its wait was over: $early")
        assertTrue(ok >= 1, "control: the one-offs ran")
    }

    // ---- K09: the forwards ride the exec transport, the shells carry none ---------------------------------------------

    @Test fun k09_theForwardsAreOnTheExecSessionAndOnNoShellAndComeBackThereAfterTheKillSwitch() {
        val f = boot()
        val local = java.net.ServerSocket(0).use { it.localPort }
        storage.updateServer(f.server.copy(portForwards = listOf(PortForward("L", local, "127.0.0.1", 9))))
        addTabs(f, 2)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        val shells = connectShells(f, listOf("t1", "t2"))
        fun execForwards(): List<String> = orchestrator.execManager.session(serverId)?.portForwardingL?.toList().orEmpty()
        fun shellForwards(): List<String> = shells.mapNotNull { it.getSession() }.distinct().flatMap { it.portForwardingL.toList() }
        fun onLocal(l: List<String>) = l.any { it.startsWith("$local:") }

        waitUntil(10_000, "the forward on the exec session") { onLocal(execForwards()) }
        assertTrue(!onLocal(shellForwards()), "a shell session must not carry the forward: ${shellForwards()}")

        CommFlags.set(CommFlags.Raw(exec = false))
        waitUntil(20_000, "the exec transport closed") { orchestrator.execManager.state(serverId).value is LinkState.Closed }
        waitUntil(30_000, "the forward on a shell session") { onLocal(shellForwards()) }

        CommFlags.set(CommFlags.Raw())
        waitUntil(30_000, "the exec transport again") { orchestrator.execManager.state(serverId).value is LinkState.Up }
        waitUntil(30_000, "the forward back on the exec session") { onLocal(execForwards()) }
        waitUntil(10_000, "and gone from every shell session") { !onLocal(shellForwards()) }
        assertEquals(0, f.refusedChannelOpens())
    }

    // ---- R3-1 (round 4): a transfer without room waits for it when room can come --------------------------------------

    /** [n] tabs whose shells ride ONE transport (cap 8 while the exec transport was up), then the kill switch. */
    private fun killSwitchShells(f: SshdFixture, n: Int): Pair<List<ClaudeSession>, List<SshManager>> {
        val t = addTabs(f, n)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        val ms = connectShells(f, t.map { it.id })
        assertEquals(1, pool.liveCount(TransportRole.SHELL), "$n shells ride ONE transport (cap 8)")
        CommFlags.set(CommFlags.Raw(exec = false))
        waitUntil(20_000, "the exec transport closed by the kill switch") { orchestrator.execManager.state(serverId).value is LinkState.Closed }
        Thread.sleep(1_000)
        startTabLoops(t)
        t.zip(ms).forEach { (tab, m) -> orchestrator.persistence.startSessionIdRefresh(tab.id, tab.tmuxSessionName, m) }
        return t to ms
    }

    /**
     * adv12 of review round 3: 7 shells (room for one transfer only while no poll is in flight), the tabs' loops polling,
     * 6 workers moving files up and down at once. Round 3 failed 30 of 36 uploads and 30 of 36 downloads at their first
     * try without room; waiting for the room (it holds nothing meanwhile) every transfer ends well. The transport has
     * room for ONE transfer at a time, so the load must fit the deadline: 1 MB downloads (~20 ms each here, an inline
     * image) and 64 KB uploads. A 1 MB upload on the tab's session takes ~5.2 s here, so 12 of them in one go are 62 s
     * of the one slot and some must end at their 20 s deadline (measured, round 4: a limit of the budget, not of the wait).
     */
    @Test fun r31a_sevenShellsWithPollsRunningAndConcurrentTransfersAllSucceed() {
        val f = boot()
        val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val upBytes = bytes.copyOf(64 * 1024)
        java.io.File(f.home, "r31a.bin").writeBytes(bytes)
        val (t, ms) = killSwitchShells(f, 7)
        Thread.sleep(3_000)
        val r0 = f.refusedChannelOpens()
        val c0 = remoteCommands(f).size
        val names = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val took = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val outcomes = try {
            runBlocking {
                (0 until 6).map { w -> async(Dispatchers.IO) { (0 until 4).map { j ->
                    val tab = t[(w + j) % t.size]
                    val t0 = System.nanoTime()
                    if ((w + j) % 2 == 0) {
                        val name = "r31a-${System.nanoTime()}-$w-$j.bin".also { names += it }
                        runCatching { orchestrator.uploadFile(tab.id, upBytes, name); "up-ok" }.getOrElse { "up-fail: ${it.message}" }
                    } else {
                        orchestrator.downloadFile(tab.id, "~/r31a.bin")?.let { if (it.contentEquals(bytes)) "down-ok" else "down-bad ${it.size}" } ?: "down-null"
                    }.also { took += (System.nanoTime() - t0) / 1_000_000 }
                } } }.awaitAll().flatten()
            }
        } finally {
            names.forEach { java.io.File("/tmp/claude-uploads", it).delete() }
        }
        val polls = pollsSince(f, t, c0)
        val refused = f.refusedChannelOpens() - r0
        orchestrator.shutdownForTest()
        val s = ms[0].getSession()!!
        val longFree = (1..100).any { Thread.sleep(100); pool.fallbackHeadroom(s, short = false) }
        println("MEASURED r31a outcomes=${outcomes.groupingBy { it.take(60) }.eachCount()} refused=$refused pollsPerTab=$polls longHeadroomAfter=$longFree tookMs=${took.sorted()}")
        assertEquals(24, outcomes.count { it == "up-ok" || it == "down-ok" }, "every transfer ended well: $outcomes")
        assertEquals(0, refused, "sshd refused channels")
        assertTrue(longFree, "no counted slot leaked: the transfer slot is free again")
    }

    private fun pollsSince(f: SshdFixture, t: List<ClaudeSession>, from: Int) =
        remoteCommands(f, from).let { cmds -> t.associate { tab -> tab.id to cmds.count { it.contains("${tab.claudeSessionId}.jsonl") } } }

    /**
     * adv11 of review round 3: exec routing on, 8 shells on one transport (a transfer never fits there), and the exec
     * transport dies (down, NOT closed: it comes back by itself). Round 3 failed the upload at once; it waits for the
     * exec transport and goes through it.
     */
    @Test fun r31b_anUploadWaitsForAnExecTransportThatIsBrieflyDownAndGoesThroughIt() {
        val f = boot()
        val t = addTabs(f, 8)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        connectShells(f, t.map { it.id })
        assertEquals(1, pool.liveCount(TransportRole.SHELL), "8 shells ride ONE transport (cap 8)")
        val r0 = f.refusedChannelOpens()
        val name = "r31b-${System.nanoTime()}.bin"
        val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        try {
            orchestrator.execManager.session(serverId)!!.disconnect()
            assertEquals(null, orchestrator.execManager.session(serverId), "control: the exec transport is down when the upload starts")
            val t0 = System.nanoTime()
            val up = runCatching { runBlocking { orchestrator.uploadFile(t[0].id, bytes, name) } }
            val tookMs = (System.nanoTime() - t0) / 1_000_000
            val state = orchestrator.execManager.state(serverId).value
            println("MEASURED r31b upload=${up.getOrNull() ?: up.exceptionOrNull()?.message} took=${tookMs}ms execState=$state")
            assertEquals("/tmp/claude-uploads/$name", up.getOrNull(), "uploaded once the exec transport was back: ${up.exceptionOrNull()?.message}")
            assertTrue(state is LinkState.Up, "through the exec transport that came back: $state")
            assertEquals(bytes.size.toLong(), java.io.File("/tmp/claude-uploads", name).length(), "the whole file arrived")
            assertEquals(0, f.refusedChannelOpens() - r0, "sshd refused channels")
        } finally {
            java.io.File("/tmp/claude-uploads", name).delete()
        }
    }
}
