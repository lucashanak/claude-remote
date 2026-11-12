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
        // HNED (navrh §3 bod 10, contract 2026-11-08): block, done and incident vibrate; only info is silent.
        // F5c: Jarvis' own three may sound on the phone, of them only jblock vibrates the wrist.
        assertEquals(listOf(true, true, false, true, true, true, true, true), JarvisAlertKind.entries.map { it.urgent })
        assertEquals(listOf(true, true, false, true, false, true, false, true), JarvisAlertKind.entries.map { it.wristBuzz })
        assertEquals(listOf(false, false, false, false, true, true, true, true), JarvisAlertKind.entries.map { it.own })
        // kolo 3: jblockask = blocking like jblock AND a question like jask
        assertEquals(listOf(false, false, false, false, false, false, true, true), JarvisAlertKind.entries.map { it.asks })
        assertEquals(listOf(true, false, false, true, false, true, false, true), JarvisAlertKind.entries.map { it.blocking })
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

    // ── F5c: Jarvis' own sentences (jsay | jblock | jask) ──

    @Test fun theNewKindsParseAndTheOldOnesAreUnchanged() {
        for ((wire, kind) in listOf("jsay" to JarvisAlertKind.JSAY, "jblock" to JarvisAlertKind.JBLOCK, "jask" to JarvisAlertKind.JASK)) {
            val a = JarvisAlertLine.parse(line("j-1", wire, 1_000, "Citace stojí na limitu. Mám ji po obnově pustit dál? Ano?", "claude-server-citace-380"))
            assertIs<JarvisAlertLine.Parsed.Alert>(a, wire)
            assertEquals(kind, a.kind)
            assertEquals("claude-server-citace-380", a.tmux)
        }
        assertEquals(JarvisAlertKind.BLOCK, (JarvisAlertLine.parse(line("x", "block", 1, "t")) as JarvisAlertLine.Parsed.Alert).kind)
        // a near miss of a new kind is not understood (never guessed into one)
        for (bad in listOf("JSAY", "j-say", "jsay2", "jok", "ask")) assertNull(JarvisAlertLine.parse(line("x", bad, 1, "t")), bad)
        // a damaged own line: a torn text, a missing field
        assertNull(JarvisAlertLine.parse("claude-remote-jarvis j-1 jsay 1000 !!! -"))
        assertNull(JarvisAlertLine.parse("claude-remote-jarvis j-1 jask 1000 ${b64("Ano?")}"))
    }

    @Test fun hisWordsAreKeptAsHistoryNewestFirstEvenWhenSeenOrReplayed() {
        var now = 100 * min
        val hub = JarvisAlertHub { now }
        val rec = Rec().also { hub.sink = it }
        hub.connected("s")
        // the first connection's replay: too old to raise, still what he said
        hub.onLine("s", line("j-old", "jsay", now - 60 * min, "Ráno jsem pustil relay dál."), 0)
        hub.onLine("s", line("j-ancient", "jsay", now - 13 * 60 * min, "Včera."), 0)
        hub.onLine("s", line("t-old", "done", now - 60 * min, "šablona"), 0)
        assertEquals(emptyList(), rec.alerts)
        assertEquals(listOf("j-old"), hub.state.value.said.map { it.id }, "templates and >12 h are not his history")
        hub.onLine("s", line("j-new", "jblock", now - 5_000, "Soniox čeká na povolení."), 0)
        assertEquals(listOf("j-new"), rec.alerts.map { it.id })
        hub.acknowledge()
        assertEquals(emptyList(), hub.state.value.pending)
        assertEquals(listOf("j-new", "j-old"), hub.state.value.said.map { it.id }, "seen is not forgotten")
        // a replay changes nothing
        hub.onLine("s", line("j-new", "jblock", now - 5_000, "Soniox čeká na povolení."), 0)
        assertEquals(2, hub.state.value.said.size)
        now += 12 * 60 * min
        hub.tick()
        assertEquals(emptyList(), hub.state.value.said.map { it.id }, "expired after 12 h")
    }

    @Test fun hisHistoryIsCapped() {
        val now = 100 * min
        val hub = JarvisAlertHub { now }
        hub.connected("s"); hub.connected("s")
        (1..30).forEach { hub.onLine("s", line("j$it", "jsay", now - (40 - it) * 1_000L, "věta $it"), 0) }
        val said = hub.state.value.said
        assertEquals(JarvisAlertHub.SAID_MAX, said.size)
        assertEquals("j30", said.first().id)
    }

    @Test fun hisOwnSentencesArePostedWhateverTheRoutingAndTheLease() {
        var now = 10 * min
        val b = JarvisAlertBatcher { now }
        val say = alert(JarvisAlertKind.JSAY)
        assertNotNull(b.decide(say, AlertRouting.PER_SESSION, alive = false, notificationsEnabled = true))
        assertNull(b.decide(say, AlertRouting.VIA_JARVIS, alive = true, notificationsEnabled = false), "notifications off silences him too")
        // the template kinds are unchanged
        assertNull(b.decide(alert(JarvisAlertKind.DONE), AlertRouting.PER_SESSION, alive = true, notificationsEnabled = true))
        // the 60 s spacing of sounds holds for his sentences as well
        now += 5 * min
        assertTrue(b.decide(alert(JarvisAlertKind.JBLOCK), AlertRouting.PER_SESSION, false, true)!!.buzz)
        now += 10_000
        assertFalse(b.decide(alert(JarvisAlertKind.JASK), AlertRouting.PER_SESSION, false, true)!!.buzz)
    }

    @Test fun loudnessFollowsTheKindAndTheSpacing() {
        assertEquals(JarvisSays.Loudness.HIGH, JarvisSays.loudness(JarvisAlertKind.JBLOCK, buzz = true))
        assertEquals(JarvisSays.Loudness.NORMAL, JarvisSays.loudness(JarvisAlertKind.JSAY, buzz = true))
        assertEquals(JarvisSays.Loudness.NORMAL, JarvisSays.loudness(JarvisAlertKind.JASK, buzz = true))
        assertEquals(JarvisSays.Loudness.QUIET, JarvisSays.loudness(JarvisAlertKind.JBLOCK, buzz = false))
    }

    private fun said(vararg a: JarvisAlert) = JarvisFeed(said = a.toList())
    private fun j(id: String, kind: JarvisAlertKind, atMs: Long) = JarvisAlert(id, kind, atMs, atMs, "t", null, "s")

    @Test fun anoIsOfferedOnlyForAJaskThatIsTheNewestThingHeSaid() {
        val now = 100 * min
        assertEquals("q", JarvisSays.ask(said(j("q", JarvisAlertKind.JASK, now - min)), now)?.id)
        assertNull(JarvisSays.ask(said(j("s", JarvisAlertKind.JSAY, now)), now), "a jsay asks nothing")
        assertNull(JarvisSays.ask(said(j("b", JarvisAlertKind.JBLOCK, now)), now), "a jblock asks nothing")
        assertNull(JarvisSays.ask(said(j("s", JarvisAlertKind.JSAY, now), j("q", JarvisAlertKind.JASK, now - min)), now), "said something after")
        assertNull(JarvisSays.ask(said(j("q", JarvisAlertKind.JASK, now - 31 * min)), now), "too old")
        assertNull(JarvisSays.ask(JarvisFeed(), now))
    }

    @Test fun anoIsRefusedUnlessItIsThatVeryQuestion() {
        val now = 100 * min
        val q = said(j("q", JarvisAlertKind.JASK, now - min))
        assertNull(JarvisSays.yesRefusal(q, "q", now))
        assertEquals(JarvisSays.NOT_A_QUESTION, JarvisSays.yesRefusal(q, null, now))
        assertEquals(JarvisSays.STALE, JarvisSays.yesRefusal(q, "nope", now), "an id it does not know as the question")
        val after = said(j("q2", JarvisAlertKind.JASK, now), j("q", JarvisAlertKind.JASK, now - min))
        assertEquals(JarvisSays.STALE, JarvisSays.yesRefusal(after, "q", now), "a newer question takes the old one's Ano")
        val sayAfter = said(j("s", JarvisAlertKind.JSAY, now), j("q", JarvisAlertKind.JASK, now - min))
        assertEquals(JarvisSays.STALE, JarvisSays.yesRefusal(sayAfter, "q", now))
        assertEquals(JarvisSays.LAPSED, JarvisSays.yesRefusal(q, "q", now + 30 * min), "lapsed (kolo 3 L2: said as such)")
        // a jsay or jblock id is never answered with "ano"
        assertEquals(JarvisSays.NOT_A_QUESTION, JarvisSays.yesRefusal(said(j("s", JarvisAlertKind.JSAY, now)), "s", now))
        assertEquals(JarvisSays.NOT_A_QUESTION, JarvisSays.yesRefusal(said(j("b", JarvisAlertKind.JBLOCK, now)), "b", now))
        assertEquals(JarvisSays.STALE, JarvisSays.yesRefusal(JarvisFeed(), "x", now), "nothing known (after a restart)")
    }

    @Test fun theMergedLinesHaveAGlyphForEveryKind() {
        val feed = JarvisFeed(pending = JarvisAlertKind.entries.map { k -> JarvisAlert(k.wire, k, 1, 1, k.wire, null, "s") })
        assertEquals(
            listOf("▲ block", "✓ done", "• info", "⚠ incident", "» jsay"),
            JarvisMerged.lines(feed).take(5),
        )
        assertEquals("? jask", "${JarvisMerged.glyph(JarvisAlertKind.JASK)} jask")
        assertEquals("▲", JarvisMerged.glyph(JarvisAlertKind.JBLOCK))
    }

    // ── kolo 3 (rev-f5c-app) ──

    @Test fun jblockaskIsLoudOnTheWristAndAQuestion() {
        val a = JarvisAlertLine.parse(line("j-ba", "jblockask", 1_000, "Soniox čeká na povolení a relay na tvé ano. Ano?"))
        assertIs<JarvisAlertLine.Parsed.Alert>(a)
        assertEquals(JarvisAlertKind.JBLOCKASK, a.kind)
        assertEquals(JarvisSays.Loudness.HIGH, JarvisSays.loudness(JarvisAlertKind.JBLOCKASK, buzz = true))
        assertTrue(JarvisAlertKind.JBLOCKASK.wristBuzz && JarvisAlertKind.JBLOCKASK.asks && JarvisAlertKind.JBLOCKASK.own)
        val now = 100 * min
        assertEquals("q", JarvisSays.ask(said(j("q", JarvisAlertKind.JBLOCKASK, now)), now)?.id)
        assertNull(JarvisSays.yesRefusal(said(j("q", JarvisAlertKind.JBLOCKASK, now)), "q", now))
        assertEquals("▲?", JarvisMerged.glyph(JarvisAlertKind.JBLOCKASK))
        assertNull(JarvisAlertLine.parse(line("x", "jblock-ask", 1, "t")))
    }

    private class MemStore(var ids: List<String> = emptyList()) : JarvisAnsweredStore {
        override fun load() = ids
        override fun save(ids: List<String>) { this.ids = ids }
    }

    @Test fun h1AnAnsweredQuestionIsNeverOfferedAgainAlsoAfterARestart() {
        val now = 100 * min
        val store = MemStore()
        val hub = JarvisAlertHub { now }.also { it.useAnsweredStore(store) }
        hub.connected("s"); hub.connected("s")
        hub.onLine("s", line("q", "jask", now - 1_000, "Mám pustit citaci dál? Ano?"), 0)
        assertEquals("q", JarvisSays.ask(hub.state.value, now)?.id)
        hub.markAnswered("q")
        assertNull(JarvisSays.ask(hub.state.value, now), "answered: the offer is gone")
        assertEquals(JarvisSays.ANSWERED, JarvisSays.yesRefusal(hub.state.value, "q", now))
        assertEquals(listOf("q"), store.ids, "kept for a restart")
        // a restart: the tail's replay brings the same question back, the store says it was answered
        val again = JarvisAlertHub { now }.also { it.useAnsweredStore(store) }
        again.connected("s")
        again.onLine("s", line("q", "jask", now - 1_000, "Mám pustit citaci dál? Ano?"), 0)
        assertNull(JarvisSays.ask(again.state.value, now))
        // the prefs store round-trips
        val kv = com.clauderemote.storage.FakeKeyValueStore()
        JarvisAnsweredPrefs(kv).save(listOf("a-1", "a-2"))
        assertEquals(listOf("a-1", "a-2"), JarvisAnsweredPrefs(kv).load())
        assertEquals(emptyList(), JarvisAnsweredPrefs(com.clauderemote.storage.FakeKeyValueStore()).load())
    }

    @Test fun m2AnyLaterAlertTakesTheOfferAwayLikeThePhonesNotification() {
        val now = 100 * min
        val hub = JarvisAlertHub { now }
        hub.connected("s"); hub.connected("s")
        hub.onLine("s", line("q", "jask", now - 10_000, "Ano?"), 0)
        assertEquals("q", JarvisSays.ask(hub.state.value, now)?.id)
        // a template line after it (not his own words, not in `said`)
        hub.onLine("s", line("t", "block", now - 5_000, "iss242 čeká na povolení"), 0)
        assertNull(JarvisSays.ask(hub.state.value, now))
        assertEquals(JarvisSays.STALE, JarvisSays.yesRefusal(hub.state.value, "q", now))
        // an OLDER line replayed later does not
        val h2 = JarvisAlertHub { now }
        h2.connected("s"); h2.connected("s")
        h2.onLine("s", line("q", "jask", now - 10_000, "Ano?"), 0)
        h2.onLine("s", line("old", "done", now - 20 * min, "starší"), 0)
        assertEquals("q", JarvisSays.ask(h2.state.value, now)?.id)
    }

    @Test fun l2ALapsedQuestionSaysSo() {
        val now = 100 * min
        val q = said(j("q", JarvisAlertKind.JASK, now - 31 * min))
        assertEquals(JarvisSays.LAPSED, JarvisSays.yesRefusal(q, "q", now))
    }

    @Test fun l3ALineFromTheFutureCountsAsNow() {
        val now = 100 * min
        val hub = JarvisAlertHub { now }
        hub.connected("s"); hub.connected("s")
        hub.onLine("s", line("f", "jsay", now + 60 * min, "z budoucnosti"), 0)
        assertEquals(now, hub.state.value.said.single().atMs)
        hub.onLine("s", line("n", "jsay", now - 1_000 + 2_000, "teď"), 0) // a second later than now: within the slack
        assertEquals("n", hub.state.value.said.first().id)
    }

    @Test fun l5ALesserSoundNeverSilencesABlock() {
        var now = 10 * min
        val b = JarvisAlertBatcher { now }
        assertTrue(b.decide(alert(JarvisAlertKind.JSAY), AlertRouting.PER_SESSION, false, true)!!.buzz)
        now += 10_000
        assertTrue(b.decide(alert(JarvisAlertKind.JBLOCK), AlertRouting.PER_SESSION, false, true)!!.buzz, "a jsay just before does not mute it")
        now += 10_000
        assertFalse(b.decide(alert(JarvisAlertKind.JBLOCKASK), AlertRouting.PER_SESSION, false, true)!!.buzz, "blocks are spaced among themselves")
        assertFalse(b.decide(alert(JarvisAlertKind.JASK), AlertRouting.PER_SESSION, false, true)!!.buzz, "and a block spends the lesser gap too")
        // a template done (fallback) just before does not mute his jblock either; template kinds keep their one gap
        val t = JarvisAlertBatcher { now }
        assertTrue(t.decide(alert(JarvisAlertKind.DONE), AlertRouting.VIA_JARVIS, true, true)!!.buzz)
        now += 5_000
        assertTrue(t.decide(alert(JarvisAlertKind.JBLOCK), AlertRouting.VIA_JARVIS, true, true)!!.buzz)
    }

    // ── 2026-11-12: one line per session in the merged notification ──

    private fun a(id: String, kind: JarvisAlertKind, at: Long, text: String, tmux: String? = "claude-server-moje-prace-1b", server: String = "s") =
        JarvisAlert(id, kind, at, at, text, tmux, server)

    @Test fun threeTemplateLinesOfOneSessionAreOne() {
        val feed = JarvisFeed(
            pending = listOf(
                a("4", JarvisAlertKind.INFO, 400, "moje-prace-1b pracuje 40 min bez známky života"),
                a("3", JarvisAlertKind.BLOCK, 300, "moje-prace-1b čeká na tebe."),
                a("2", JarvisAlertKind.BLOCK, 200, "moje-prace-1b chce povolení."),
                a("1", JarvisAlertKind.BLOCK, 100, "soniox chce povolení.", tmux = "claude-server-soniox"),
            ),
        )
        // the newer "stuck" (INFO) never hides that the session waits for him
        assertEquals(listOf("▲ moje-prace-1b čeká na tebe.", "▲ soniox chce povolení."), JarvisMerged.lines(feed))
        assertEquals("Jarvis · 2", JarvisMerged.title(feed), "the title counts the lines shown")
        assertEquals(2, JarvisMerged.count(feed))
    }

    @Test fun linesAboutNoSessionAllStayAndTheSameNameOnAnotherServerIsAnotherSession() {
        val feed = JarvisFeed(
            pending = listOf(
                a("5", JarvisAlertKind.INCIDENT, 500, "8 sessions skončilo", tmux = null),
                a("4", JarvisAlertKind.INFO, 400, "3 věci čekají", tmux = null),
                a("3", JarvisAlertKind.BLOCK, 300, "x na jiném boxu", server = "s2"),
                a("2", JarvisAlertKind.BLOCK, 200, "x tady"),
            ),
        )
        assertEquals(4, JarvisMerged.count(feed))
    }

    @Test fun hisNewerSentenceReplacesTheTemplateAndANewerTemplateReplacesHisOlderOne() {
        val newerOwn = JarvisFeed(
            pending = listOf(
                a("t", JarvisAlertKind.BLOCK, 100, "moje-prace-1b chce povolení."),
                a("j", JarvisAlertKind.JBLOCK, 200, "Moje-prace čeká na povolení k pushi, Ano?"),
            ),
        )
        assertEquals(listOf("▲ Moje-prace čeká na povolení k pushi, Ano?"), JarvisMerged.lines(newerOwn), "newest first even out of order")
        val newerTemplate = JarvisFeed(
            pending = listOf(
                a("t", JarvisAlertKind.DONE, 300, "moje-prace-1b je hotová."),
                a("j", JarvisAlertKind.JSAY, 200, "Moje-prace pracuje."),
            ),
        )
        assertEquals(listOf("✓ moje-prace-1b je hotová."), JarvisMerged.lines(newerTemplate))
        val sameInstant = JarvisFeed(
            pending = listOf(
                a("t", JarvisAlertKind.BLOCK, 200, "moje-prace-1b chce povolení."),
                a("j", JarvisAlertKind.JSAY, 200, "Moje-prace chce povolit push."),
            ),
        )
        assertEquals(listOf("» Moje-prace chce povolit push."), JarvisMerged.lines(sameInstant), "at the same instant his own wins")
    }

    @Test fun theOverflowLineCountsSessionsNotAlerts() {
        val pending = (1..8).flatMap { n ->
            listOf(
                a("b$n", JarvisAlertKind.BLOCK, n * 10L + 1, "s$n chce povolení.", tmux = "claude-server-s$n"),
                a("w$n", JarvisAlertKind.BLOCK, n * 10L, "s$n čeká na tebe.", tmux = "claude-server-s$n"),
            )
        }
        val lines = JarvisMerged.lines(JarvisFeed(pending = pending))
        assertEquals(JarvisMerged.MAX_LINES + 1, lines.size)
        assertEquals("… a 3 dalších", lines.last())
        assertEquals("Jarvis · 8", JarvisMerged.title(JarvisFeed(pending = pending)))
    }

    @Test fun aWaitingLineGivesWayOnlyToANewerWaitingOrFinishedLine() {
        fun one(vararg xs: JarvisAlert) = JarvisMerged.shown(xs.toList()).single().id
        // INFO (stuck, the account) never hides a waiting line, whatever their order in the feed
        assertEquals("b", one(a("i", JarvisAlertKind.INFO, 200, "stuck"), a("b", JarvisAlertKind.BLOCK, 100, "perm")))
        assertEquals("j", one(a("j", JarvisAlertKind.JBLOCKASK, 100, "Ano?"), a("i", JarvisAlertKind.INFO, 200, "stuck")))
        assertEquals("q", one(a("q", JarvisAlertKind.JASK, 100, "Ano?"), a("i", JarvisAlertKind.INFO, 300, "účet")))
        // a newer waiting line, or a newer done/jsay (answered, finished), does
        assertEquals("b2", one(a("b1", JarvisAlertKind.BLOCK, 100, "perm"), a("b2", JarvisAlertKind.JBLOCK, 200, "čeká")))
        assertEquals("d", one(a("b", JarvisAlertKind.BLOCK, 100, "perm"), a("d", JarvisAlertKind.DONE, 200, "hotovo")))
        assertEquals("s", one(a("b", JarvisAlertKind.JBLOCK, 100, "perm"), a("s", JarvisAlertKind.JSAY, 200, "Pustil jsem ji dál.")))
        // an OLDER done does not
        assertEquals("b", one(a("d", JarvisAlertKind.DONE, 100, "hotovo"), a("b", JarvisAlertKind.BLOCK, 200, "perm")))
        // a non-waiting line gives way to anything newer, an INFO included
        assertEquals("i", one(a("d", JarvisAlertKind.DONE, 100, "hotovo"), a("i", JarvisAlertKind.INFO, 200, "stuck")))
        // block, then stuck, then done: the done (it finished) — then a later stuck may replace that
        assertEquals("d", one(a("b", JarvisAlertKind.BLOCK, 100, "p"), a("i", JarvisAlertKind.INFO, 200, "s"), a("d", JarvisAlertKind.DONE, 300, "h")))
        assertEquals(
            "i2",
            one(a("b", JarvisAlertKind.BLOCK, 100, "p"), a("d", JarvisAlertKind.DONE, 300, "h"), a("i2", JarvisAlertKind.INFO, 400, "s")),
        )
    }
}
