package com.clauderemote.session.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Jarvis F3: the alert line contract, the hub's dedup / first sighting / lease, and the app's batching. */
class JarvisAlertsTest {
    private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())
    private fun line(id: String, kind: String, ms: Long, text: String, tmux: String? = null) =
        "claude-remote-jarvis $id $kind $ms ${b64(text)} ${tmux?.let(::b64) ?: "-"}"

    // ── parser ──

    @Test fun parsesAnAlertAndAHeartbeat() {
        val a = JarvisAlertLine.parse(line("a-1", "block", 1_000, "iss242 čeká na povolení", "claude-server-kx--iss242"))
        assertIs<JarvisAlertLine.Parsed.Alert>(a)
        assertEquals("a-1", a.id)
        assertEquals(JarvisAlertKind.BLOCK, a.kind)
        assertEquals(1_000, a.serverMs)
        assertEquals("iss242 čeká na povolení", a.text)
        assertEquals("claude-server-kx--iss242", a.tmux)
        assertEquals(JarvisAlertLine.Parsed.Heartbeat(5_000), JarvisAlertLine.parse("claude-remote-jarvis hb 5000"))
        val noTmux = JarvisAlertLine.parse(line("a-2", "incident", 7, "8 sessions skončilo"))
        assertIs<JarvisAlertLine.Parsed.Alert>(noTmux)
        assertNull(noTmux.tmux)
    }

    @Test fun theContractsExampleLineParsesAndADashIsNoSession() {
        // the 6th field is b64(tmux) or a literal "-": an incident, a batch of several items, a reminder
        val a = JarvisAlertLine.parse("claude-remote-jarvis a-20261108T120000-ab12 block 1794000000000 ${b64("3 věci čekají")} -")
        assertIs<JarvisAlertLine.Parsed.Alert>(a)
        assertEquals("a-20261108T120000-ab12", a.id)
        assertEquals(1794000000000, a.serverMs)
        assertNull(a.tmux)
    }

    @Test fun everyKindOfTheContractParses() {
        for (k in listOf("block", "done", "info", "incident")) {
            assertNotNull(JarvisAlertLine.parse(line("x", k, 1, "t")), k)
        }
        // HNED (navrh §3 bod 10, contract 2026-11-08): block, done and incident vibrate; only info is silent
        assertEquals(listOf(true, true, false, true), JarvisAlertKind.entries.map { it.urgent })
    }

    @Test fun malformedLinesAreNotUnderstood() {
        val bad = listOf(
            "claude-remote-jarvis",
            "claude-remote-jarvis hb",
            "claude-remote-jarvis hb abc",
            "claude-remote-jarvis hb 0",
            "claude-remote-jarvis a-1 block 1000 ${b64("x")}", // five tokens
            line("a-1", "panic", 1000, "x"), // unknown kind
            line("a-1", "block", -5, "x"),
            line("a 1", "block", 1000, "x"), // a space splits the id: seven tokens
            "claude-remote-jarvis a-1 block 1000 !!!notb64 -",
            line("a-1", "block", 1000, "   "), // blank text
            line("hb", "block", 1000, "x"), // the heartbeat's own token is no id
            line("a".repeat(65), "block", 1000, "x"),
            "claude-remote-notify sess 123",
            line("a-1", "block", 1000, "x") + " extra",
        )
        bad.forEach { assertNull(JarvisAlertLine.parse(it), it) }
    }

    @Test fun controlCharactersOfTheTextBecomeSpacesAndItIsCapped() {
        val a = JarvisAlertLine.parse(line("a", "info", 1, "první\nřádek\u001b[31m" + "x".repeat(700))) as JarvisAlertLine.Parsed.Alert
        assertFalse(a.text.any { it < ' ' })
        assertTrue(a.text.startsWith("první řádek [31m"))
        assertEquals(600, a.text.length)
    }

    // ── hub ──

    private class Rec : JarvisAlertSink {
        val alerts = ArrayList<JarvisAlert>()
        val edges = ArrayList<JarvisLeaseEdge>()
        val leases get() = edges.map { it.alive }
        var cleared = 0
        var routing = 0
        override fun onAlert(alert: JarvisAlert, feed: JarvisFeed) { alerts += alert }
        override fun onCleared() { cleared++ }
        override fun onLeaseChanged(edge: JarvisLeaseEdge) { edges += edge }
        override fun onRoutingChanged() { routing++ }
    }

    private val min = 60_000L

    @Test fun replayedIdsAreRaisedOnce() {
        var now = 100 * min
        val hub = JarvisAlertHub { now }
        val rec = Rec().also { hub.sink = it }
        hub.connected("s")
        hub.connected("s") // a reconnect: warm from now on
        val l = line("a-1", "block", now - 10_000, "x")
        assertTrue(hub.onLine("s", l, 0))
        assertTrue(hub.onLine("s", l, 0)) // the tail's replay after a reconnect
        now += min
        hub.onLine("s", l, 0)
        assertEquals(1, rec.alerts.size)
        assertEquals(1, hub.state.value.pending.size)
    }

    @Test fun theFirstConnectionOnlyPrimesOldAlertsAWarmOneRaisesThemUpToMaxAge() {
        val now = 100 * min
        val hub = JarvisAlertHub { now }
        val rec = Rec().also { hub.sink = it }
        hub.connected("s")
        hub.onLine("s", line("old", "block", now - 5 * min, "seen on an earlier run"), 0)
        hub.onLine("s", line("new", "block", now - 30_000, "fresh"), 0)
        assertEquals(listOf("new"), rec.alerts.map { it.id })
        hub.connected("s") // reconnect: a frozen socket's late alert still counts…
        hub.onLine("s", line("late", "done", now - 10 * min, "late"), 0)
        // …but not a stale one (navrh §3 bod 6)
        hub.onLine("s", line("stale", "done", now - 31 * min, "stale"), 0)
        assertEquals(listOf("new", "late"), rec.alerts.map { it.id })
        // and "old" was recorded as seen: a later replay does not raise it either
        hub.onLine("s", line("old", "block", now - 5 * min, "x"), 0)
        assertEquals(2, rec.alerts.size)
    }

    @Test fun ageIsJudgedOnTheDeviceClockThroughTheSkew() {
        val now = 100 * min
        val hub = JarvisAlertHub { now }
        val rec = Rec().also { hub.sink = it }
        hub.connected("s")
        // the server's clock is 10 min behind: its "now - 10 min" is our "now"
        hub.onLine("s", line("a", "block", now - 10 * min, "x"), skewMs = 10 * min)
        assertEquals(1, rec.alerts.size)
        assertEquals(now, rec.alerts[0].atMs)
    }

    @Test fun leaseLivesThreeMinutesFromTheHeartbeatAndLapsesWithoutAnyLine() {
        var now = 100 * min
        val hub = JarvisAlertHub { now }
        val rec = Rec().also { hub.sink = it }
        assertFalse(hub.alive())
        hub.onLine("s", "claude-remote-jarvis hb ${now - 30_000}", 0)
        assertTrue(hub.alive())
        assertEquals(listOf(true), rec.leases)
        assertEquals("s", hub.state.value.serverId)
        now += 2 * min
        hub.tick()
        assertTrue(hub.state.value.alive)
        now += 31_000 // 3 min 1 s after the heartbeat
        hub.tick()
        assertFalse(hub.state.value.alive)
        assertFalse(hub.alive())
        assertEquals(listOf(true, false), rec.leases)
        hub.tick()
        assertEquals(listOf(true, false), rec.leases, "edges only")
    }

    @Test fun aReplayedOldHeartbeatNeverMakesADeadDaemonLookAlive() {
        val now = 100 * min
        val hub = JarvisAlertHub { now }
        hub.onLine("s", "claude-remote-jarvis hb ${now - 4 * min}", 0)
        assertFalse(hub.alive())
        // an older heartbeat arriving after a newer one does not rewind it
        hub.onLine("s", "claude-remote-jarvis hb ${now - 10_000}", 0)
        hub.onLine("s", "claude-remote-jarvis hb ${now - 20 * min}", 0)
        assertTrue(hub.alive())
    }

    @Test fun theLeaseIsPerServerAndAnyLiveOneHoldsTheFeed() {
        val now = 100 * min
        val hub = JarvisAlertHub { now }
        hub.onLine("a", "claude-remote-jarvis hb ${now - 20 * min}", 0)
        hub.onLine("b", "claude-remote-jarvis hb ${now - 10_000}", 0)
        assertTrue(hub.alive())
        assertTrue(hub.state.value.alive)
        assertEquals("b", hub.state.value.serverId)
        // MED4: Jarvis on b carries nothing of server a, nor of a server he never spoke from
        assertTrue(hub.alive("b"))
        assertFalse(hub.alive("a"))
        assertFalse(hub.alive("c"))
    }

    @Test fun leaseEdgesArePerServerWithTheLastHeartbeatAndTheWatcherState() {
        var now = 100 * min
        val hub = JarvisAlertHub { now }
        val rec = Rec().also { hub.sink = it }
        hub.connected("a")
        hub.onLine("a", "claude-remote-jarvis hb ${now - 10_000}", 0)
        hub.onLine("b", "claude-remote-jarvis hb ${now - 10_000}", 0)
        assertEquals(setOf("a", "b"), rec.edges.filter { it.alive }.map { it.serverId }.toSet())
        now += 3 * min
        hub.tick()
        val lapsed = rec.edges.filter { !it.alive }.associateBy { it.serverId }
        assertEquals(now - 3 * min - 10_000, lapsed["a"]!!.heartbeatAtMs)
        assertTrue(lapsed["a"]!!.watcherConnected, "a's tail is up and no heartbeat came: Jarvis is silent")
        assertFalse(lapsed["b"]!!.watcherConnected, "b's tail never connected: the network, not Jarvis")
        // LOW: after a dropped tail a lapse is not Jarvis' silence either
        hub.onLine("a", "claude-remote-jarvis hb $now", 0)
        hub.disconnected("a")
        now += 4 * min
        hub.tick()
        assertFalse(rec.edges.last { it.serverId == "a" }.watcherConnected)
    }

    @Test fun theFirstConnectionsWindowEndsAfterTheReplay() {
        var now = 100 * min
        val hub = JarvisAlertHub { now }
        val rec = Rec().also { hub.sink = it }
        hub.connected("s")
        // MED3: a long first connection; a socket frozen for 3 min delivers its alert late — no longer dropped
        now += JarvisAlertHub.COLD_WINDOW_MS
        hub.onLine("s", line("late", "block", now - 3 * min, "x"), 0)
        assertEquals(listOf("late"), rec.alerts.map { it.id })
        // and a fresh heartbeat in the replay ends the window at once (what follows it in the file is younger)
        val hub2 = JarvisAlertHub { now }
        val rec2 = Rec().also { hub2.sink = it }
        hub2.connected("s")
        hub2.onLine("s", line("old", "block", now - 5 * min, "x"), 0)
        hub2.onLine("s", "claude-remote-jarvis hb ${now - 30_000}", 0)
        hub2.onLine("s", line("after", "block", now - 3 * min, "x"), 0)
        assertEquals(listOf("after"), rec2.alerts.map { it.id })
    }

    @Test fun acknowledgeIfPresentOnlyWhenTheUserIsThere() {
        val now = 100 * min
        val hub = JarvisAlertHub { now }
        hub.connected("s")
        hub.onLine("s", line("a", "info", now, "x"), 0)
        var present = false
        hub.presence = { present }
        assertFalse(hub.acknowledgeIfPresent())
        assertEquals(1, hub.state.value.pending.size, "a minimized desktop saw nothing")
        present = true
        assertTrue(hub.acknowledgeIfPresent())
        assertEquals(0, hub.state.value.pending.size)
    }

    @Test fun aRoutingChangeReachesTheSink() {
        val hub = JarvisAlertHub { 1L }
        val rec = Rec().also { hub.sink = it }
        hub.noteRoutingChanged()
        assertEquals(1, rec.routing)
    }

    // ── muted completions (MED1) ──

    @Test fun mutedCompletionsAfterTheLastHeartbeatAreHandedBackOncePerServer() {
        var now = 100 * min
        val m = JarvisMutedCompletions<String> { now }
        m.note("a", "s1", "s1 before hb")
        now += 1_000
        val hb = now
        now += 1_000
        m.note("a", "s2", "s2 old")
        m.note("a", "s2", "s2 newest") // one per session: the newest
        m.note("a", "s3", "s3")
        m.note("b", "s4", "s4 other server")
        assertEquals(listOf("s2 newest", "s3"), m.takeAfter("a", hb))
        assertEquals(emptyList(), m.takeAfter("a", hb), "handed back once")
        assertEquals(listOf("s4 other server"), m.takeAfter("b", null))
    }

    @Test fun mutedCompletionsExpire() {
        var now = 100 * min
        val m = JarvisMutedCompletions<String> { now }
        m.note("a", "s1", "x")
        now += JarvisMutedCompletions.MAX_AGE_MS + 1
        assertEquals(emptyList(), m.takeAfter("a", null))
    }

    @Test fun acknowledgeClearsPendingAndOldPendingExpires() {
        var now = 100 * min
        val hub = JarvisAlertHub { now }
        val rec = Rec().also { hub.sink = it }
        hub.connected("s")
        hub.onLine("s", line("a", "info", now, "x"), 0)
        hub.onLine("s", line("b", "block", now, "y"), 0)
        assertEquals(listOf("b", "a"), hub.state.value.pending.map { it.id }, "newest first")
        hub.acknowledge()
        assertEquals(0, hub.state.value.pending.size)
        assertEquals(1, rec.cleared)
        hub.acknowledge()
        assertEquals(1, rec.cleared, "nothing to clear, nothing said")
        hub.onLine("s", line("c", "done", now, "z"), 0)
        now += JarvisAlertHub.PENDING_TTL_MS + 1
        hub.tick()
        assertEquals(0, hub.state.value.pending.size)
        assertEquals(2, rec.cleared)
    }

    @Test fun pendingIsCapped() {
        val now = 100 * min
        val hub = JarvisAlertHub { now }
        hub.connected("s")
        repeat(JarvisAlertHub.PENDING_MAX + 10) { hub.onLine("s", line("a$it", "info", now, "x"), 0) }
        assertEquals(JarvisAlertHub.PENDING_MAX, hub.state.value.pending.size)
        assertEquals("a${JarvisAlertHub.PENDING_MAX + 9}", hub.state.value.pending.first().id)
    }

    @Test fun unknownLinesAreReportedAndChangeNothing() {
        val hub = JarvisAlertHub { 1L }
        assertFalse(hub.onLine("s", "claude-remote-jarvis what is this", 0))
        assertEquals(JarvisFeed(), hub.state.value)
    }

    // ── batcher ──

    private fun alert(kind: JarvisAlertKind) = JarvisAlert("i", kind, 1, 1, "t", null, "s")

    @Test fun batcherPostsOnlyWhenRoutedThroughALiveJarvisWithNotificationsOn() {
        val b = JarvisAlertBatcher { 0L }
        val a = alert(JarvisAlertKind.BLOCK)
        assertNull(b.decide(a, AlertRouting.PER_SESSION, alive = true, notificationsEnabled = true))
        assertNull(b.decide(a, AlertRouting.VIA_JARVIS, alive = false, notificationsEnabled = true))
        assertNull(b.decide(a, AlertRouting.VIA_JARVIS, alive = true, notificationsEnabled = false))
        assertNotNull(b.decide(a, AlertRouting.VIA_JARVIS, alive = true, notificationsEnabled = true))
    }

    @Test fun batcherSpacesVibrationsBySixtySecondsOnlyNotFiveMinutes() {
        var now = 10 * min
        val b = JarvisAlertBatcher { now }
        fun d(k: JarvisAlertKind) = b.decide(alert(k), AlertRouting.VIA_JARVIS, true, true)!!.buzz
        assertTrue(d(JarvisAlertKind.BLOCK))
        now += 30_000
        assertFalse(d(JarvisAlertKind.BLOCK), "a second batch within a minute: quiet update")
        // MED2: jarvis-d's next batch, 5 min on its clock, may land a little EARLY on the phone's: it still vibrates
        now += 4 * min
        assertTrue(d(JarvisAlertKind.BLOCK))
    }

    @Test fun anIncidentVibratesAtMostOncePerMinute() {
        var now = 10 * min
        val b = JarvisAlertBatcher { now }
        fun d(k: JarvisAlertKind) = b.decide(alert(k), AlertRouting.VIA_JARVIS, true, true)!!.buzz
        assertTrue(d(JarvisAlertKind.INCIDENT))
        now += 10_000
        assertFalse(d(JarvisAlertKind.INCIDENT), "a burst of incidents is one vibration")
        now += 50_000
        assertTrue(d(JarvisAlertKind.INCIDENT))
    }

    @Test fun infoNeverBuzzesAndDoesNotSpendTheInterruption() {
        var now = 10 * min
        val b = JarvisAlertBatcher { now }
        fun d(k: JarvisAlertKind) = b.decide(alert(k), AlertRouting.VIA_JARVIS, true, true)!!.buzz
        assertFalse(d(JarvisAlertKind.INFO))
        now += 1_000
        assertTrue(d(JarvisAlertKind.BLOCK))
    }

    @Test fun doneVibratesLikeABlockAndSharesItsGap() {
        var now = 10 * min
        val b = JarvisAlertBatcher { now }
        fun d(k: JarvisAlertKind) = b.decide(alert(k), AlertRouting.VIA_JARVIS, true, true)!!.buzz
        assertTrue(d(JarvisAlertKind.DONE), "a finished task, a fired watch, a reminder: HNED")
        now += 20_000
        assertFalse(d(JarvisAlertKind.BLOCK), "done counts as the vibration")
        now += 40_000
        assertTrue(d(JarvisAlertKind.DONE))
    }

    @Test fun aClockStepBackDoesNotSilenceTheBatcher() {
        var now = 100 * min
        val b = JarvisAlertBatcher { now }
        b.decide(alert(JarvisAlertKind.BLOCK), AlertRouting.VIA_JARVIS, true, true)
        now -= 60 * min
        assertTrue(b.decide(alert(JarvisAlertKind.BLOCK), AlertRouting.VIA_JARVIS, true, true)!!.buzz)
    }

    // ── merged ──

    @Test fun mergedShowsNewestFirstWithGlyphsAndCountsTheRest() {
        val pending = (1..7).map { JarvisAlert("a$it", if (it == 7) JarvisAlertKind.BLOCK else JarvisAlertKind.DONE, 1, 1, "věc $it", null, "s") }.reversed()
        val feed = JarvisFeed(pending = pending)
        assertEquals("Jarvis · 7", JarvisMerged.title(feed))
        val lines = JarvisMerged.lines(feed)
        assertEquals("▲ věc 7", lines.first())
        assertEquals(JarvisMerged.MAX_LINES + 1, lines.size)
        assertEquals("… a 2 dalších", lines.last())
    }
}
