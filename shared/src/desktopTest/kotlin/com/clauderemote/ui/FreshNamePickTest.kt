package com.clauderemote.ui

import com.clauderemote.connection.TmuxManager
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.SshServer
import com.clauderemote.model.TmuxSession
import com.jcraft.jsch.FakeSession
import com.jcraft.jsch.JSchException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V15 (dávka B, round 5): the name of a NEW session — quick connect ([pickQuickConnectName]) and the Connect screen
 * ([newSessionName], [canLaunch]) — skips every name in use: the live listing (tmux sessions and the restore manifest),
 * the launcher's last scan, and on the Connect screen this device's tabs. A listing that fails, hangs or is cut short
 * never reads as "nothing is taken" (M2, K4-2); cancellation is passed on (L5). A name taken since is found taken when
 * the launch creates its session (V16). The listing's channel is never given up in the middle of its OPEN (K5-1), the
 * server ends a hung listing itself (K5-2), and the Connect screen's listing is bounded and only its last opening
 * writes (K5-4).
 */
class FreshNamePickTest {
    // a listing that is never ended must fail its test, not hang the suite
    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)

    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p", defaultFolder = "/work")
    private val base = "claude-box-work"

    private fun listing(vararg live: String, manifest: Set<String> = emptySet()) =
        TmuxManager.FreshNameListing(live.map { TmuxSession(it, 1, false, "0") }, manifest)

    private fun pick(ownTabs: Set<String> = emptySet(), lastScan: List<String> = emptyList(), timeoutMs: Long = 3_000, list: suspend () -> TmuxManager.FreshNameListing) =
        runBlocking { withTimeout(10_000) { pickQuickConnectName(server, { ownTabs }, lastScan, timeoutMs, list) } }

    // ---- quick connect ----

    @Test fun quickConnectSkipsANameTheListingShows() {   // P9
        assertEquals("$base-2", pick { listing(base) })
        assertEquals(base, pick { listing("other") }, "control: a free base name stays")
    }

    @Test fun quickConnectSwitchesToItsOwnTabRatherThanStartingADuplicate() {   // P10
        assertEquals(base, pick(ownTabs = setOf(base)) { listing(base) }, "the name of this device's open tab: launchSession switches to it")
    }

    @Test fun quickConnectSkipsANameOnlyTheManifestHas() {   // R2: another device's session not back yet after a reboot
        assertEquals("$base-2", pick { listing(manifest = setOf(base)) })
    }

    @Test fun quickConnectSkipsANameOfTheLastScanEvenWhenTheListingAnswered() {   // V15: taken = listing ∪ last scan
        assertEquals("$base-2", pick(lastScan = listOf(base)) { listing() })
    }

    @Test fun aFailedListingFallsBackToTheLastScanNotToNothing() {   // M2
        assertEquals("$base-2", pick(lastScan = listOf(base)) { throw java.io.IOException("channel is not opened.") })
    }

    @Test fun aHungListingFallsBackToTheLastScanWithinItsTimeout() {   // K4-2
        val t0 = System.currentTimeMillis()
        val name = pick(lastScan = listOf(base), timeoutMs = 300) { awaitCancellation() }
        assertEquals("$base-2", name)
        assertTrue(System.currentTimeMillis() - t0 < 2_000, "the timeout ends the wait (${System.currentTimeMillis() - t0} ms)")
    }

    @Test fun aHungBlockingReadOnTheChannelIsInterruptedByTheTimeout() {   // K4-2 on a real (fake) jsch channel read
        val ch = com.jcraft.jsch.HangingExecChannel()
        val s = FakeSession { _ -> ch }
        val t0 = System.currentTimeMillis()
        val name = pick(lastScan = listOf(base), timeoutMs = 400) { TmuxManager.listForFreshName(s) }
        assertEquals("$base-2", name)
        assertTrue(System.currentTimeMillis() - t0 < 3_000, "the blocking read was interrupted (${System.currentTimeMillis() - t0} ms)")
        assertTrue(ch.disconnected, "and its channel closed")
    }

    @Test fun cancellingTheQuickConnectCancelsItNeverYieldsAName() {   // L5
        val started = CompletableDeferred<Unit>()
        runBlocking {
            val job = async(Dispatchers.Default) {
                pickQuickConnectName(server, { emptySet() }, emptyList(), 10_000) { started.complete(Unit); throw CancellationException("composition left") }
            }
            started.await()
            assertFailsWith<CancellationException> { withTimeout(3_000) { job.await() } }
        }
    }

    // ---- the listing itself (TmuxManager) ----

    @Test fun aListingThatCannotOpenItsChannelThrowsInsteadOfSayingNothingIsThere() {   // M2
        val s = FakeSession { _ -> throw JSchException("channel is not opened.") }
        assertEquals(emptyList(), runBlocking { TmuxManager.listSessions(s) }, "control: the launcher's listing still reads a failure as empty")
        assertFailsWith<Exception> { runBlocking { TmuxManager.listForFreshName(s) } }
    }

    @Test fun aListingCutShortThrowsAndNoServerIsAnAnswer() {
        val end = "\n${TmuxManager.LISTING_RC} 0\n"
        assertFailsWith<java.io.IOException> { TmuxManager.parseFreshNameListing("$base|1|0|0|0\n") }
        assertFailsWith<java.io.IOException>("ended before the manifest was read") { TmuxManager.parseFreshNameListing("$base|1|0|0|0\n${TmuxManager.LISTING_END} 0\n") }
        assertFailsWith<java.io.IOException>("the server's limit cut it short (K5-2)") {
            TmuxManager.parseFreshNameListing("$base|1|0|0|0\n${TmuxManager.LISTING_END} 0\n${TmuxManager.LISTING_RC} 124\n")
        }
        assertEquals(emptySet(), TmuxManager.parseFreshNameListing("${TmuxManager.LISTING_END} 1$end").names, "no tmux server: no sessions")
        val l = TmuxManager.parseFreshNameListing(
            "$base|1|0|0|0\n${TmuxManager.LISTING_END} 0\n[{\"id\":\"a\",\"serverId\":\"s\",\"folder\":\"/x\",\"tmuxSessionName\":\"claude-box-x\"}]$end",
        )
        assertEquals(setOf(base, "claude-box-x"), l.names)
        assertEquals(setOf(base), TmuxManager.parseFreshNameListing("$base|1|0|0|0\n${TmuxManager.LISTING_END} 0\nnot json$end").names,
            "an unreadable manifest drops only its names")
    }

    @Test fun aManifestWithoutAFinalNewlineStillListsTheSessionsAndItsNames() {   // N1: the app writes sessions.json via printf '%s'
        val l = TmuxManager.parseFreshNameListing(
            "$base|1|0|0|0\n${TmuxManager.LISTING_END} 0\n[\n  {\"tmuxSessionName\": \"claude-box-x\"}\n]${TmuxManager.LISTING_RC} 0\n",
        )
        assertEquals(setOf(base, "claude-box-x"), l.names)
        assertFailsWith<java.io.IOException>("the server's limit cut the manifest short") {
            TmuxManager.parseFreshNameListing("$base|1|0|0|0\n${TmuxManager.LISTING_END} 0\n[{\"tmuxSess${TmuxManager.LISTING_RC} 124\n")
        }
    }

    @Test fun theRealListingCommandPutsItsStatusOnALineOfItsOwnAfterAManifestWithoutAFinalNewline() {   // N1, on a real sh
        val dir = java.nio.file.Files.createTempDirectory("n1-srv").toFile()
        try {
            // a fake tmux first on PATH and a HOME of its own: never the real tmux or the user's manifest
            java.io.File(dir, "tmux").apply {
                writeText("#!/bin/sh\ncase \"${'$'}*\" in *list-sessions*) echo '$base|1|0|0|0' ;; esac\nexit 0\n")
                setExecutable(true)
            }
            java.io.File(dir, ".claude-remote").mkdirs()
            java.io.File(dir, ".claude-remote/sessions.json").writeText("[\n  {\"tmuxSessionName\": \"claude-box-x\"}\n]")
            val pb = ProcessBuilder("sh", "-c", TmuxManager.freshNameListingCommand()).redirectErrorStream(true)
            pb.environment()["PATH"] = "${dir.absolutePath}:${System.getenv("PATH")}"
            pb.environment()["HOME"] = dir.absolutePath
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(out.lines().any { it.startsWith("${TmuxManager.LISTING_RC} ") }, "status on a line of its own:\n$out")
            assertEquals(setOf(base, "claude-box-x"), TmuxManager.parseFreshNameListing(out).names, out)
        } finally {
            com.clauderemote.testsupport.safeDeleteTree(dir)
        }
    }

    @Test fun aTimedOutListingNeverGivesUpItsChannelInTheMiddleOfTheOpenOnlyItsRead() {   // K5-1
        // the server confirms the OPEN only after 800 ms; quick connect gives up after 300 ms
        val ch = com.jcraft.jsch.SlowOpenExecChannel(openMs = 800)
        val s = FakeSession { _ -> ch }
        val name = pick(lastScan = listOf(base), timeoutMs = 300) { TmuxManager.listForFreshName(s) }
        assertEquals("$base-2", name, "the last scan stands in")
        assertTrue(!ch.openInterrupted, "the OPEN was not interrupted: a channel given up before its confirmation is never closed on sshd")
        assertTrue(ch.opened && ch.closedAfterOpen, "it completed and was then closed (CLOSE goes out): opened=${ch.opened} closed=${ch.closedAfterOpen}")
    }

    @Test fun theServerEndsAHungListingItselfAndItReadsAsAFailure() {   // K5-2
        val dir = java.nio.file.Files.createTempDirectory("k5-2").toFile()
        try {
            val pidFile = java.io.File(dir, "pid")
            java.io.File(dir, "tmux").apply {
                writeText("#!/bin/sh\necho \$\$ > '${pidFile.absolutePath}'\nexec sleep 8\n")
                setExecutable(true)
            }
            val env = mapOf("PATH" to "${dir.absolutePath}:${System.getenv("PATH")}", "HOME" to dir.absolutePath)
            val s = FakeSession { _ -> com.jcraft.jsch.LocalExecChannel(env) }
            val t0 = System.currentTimeMillis()
            assertFailsWith<java.io.IOException>("a listing the server cut short is a failure, never 'no sessions'") {
                runBlocking { withTimeout(20_000) { TmuxManager.listForFreshName(s) } }
            }
            val took = System.currentTimeMillis() - t0
            assertTrue(took < TmuxManager.LISTING_SERVER_LIMIT_S * 1000L + 1_500, "the server's own limit ended it ($took ms)")
            val pid = pidFile.readText().trim()
            assertTrue(waitGone(pid, 2_000), "the hung tmux client is gone on the server, so sshd's session is too (pid $pid)")
        } finally {
            com.clauderemote.testsupport.safeDeleteTree(dir)
        }
    }

    private fun waitGone(pid: String, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && java.io.File("/proc/$pid").exists()) Thread.sleep(50)
        return !java.io.File("/proc/$pid").exists()
    }

    // ---- the Connect screen's listing (App: ConnectListing) ----

    @Test fun openingTheConnectScreenHoldsANewSessionUntilItsListingAnswers() {   // replaces the source check of tmuxLoading
        val cl = ConnectListing(timeoutMs = 5_000)
        val op = cl.open(setOf("x"))
        assertTrue(cl.loading, "the listing runs: a new session waits")
        assertEquals(setOf("x"), cl.taken, "the other taken names count from the start")
        val answer = CompletableDeferred<TmuxManager.FreshNameListing>()
        runBlocking {
            val job = async(Dispatchers.Default) { cl.load(op, setOf("x")) { answer.await() } }
            Thread.sleep(200)
            assertTrue(cl.loading, "still running")
            answer.complete(listing(base, manifest = setOf("m")))
            withTimeout(5_000) { job.await() }
        }
        assertTrue(!cl.loading, "answered: Launch opens")
        assertEquals(listOf(base), cl.sessions.map { it.name })
        assertEquals(setOf("x", "m"), cl.taken)
    }

    @Test fun aHungListingOnTheConnectScreenEndsWithinItsLimitAndLeavesTheOtherTakenNames() {   // K5-4 a
        val cl = ConnectListing(timeoutMs = 300)
        val op = cl.open(setOf("x"))
        val t0 = System.currentTimeMillis()
        runBlocking { withTimeout(5_000) { cl.load(op, setOf("x")) { awaitCancellation() } } }
        assertTrue(System.currentTimeMillis() - t0 < 2_000, "bounded (${System.currentTimeMillis() - t0} ms)")
        assertTrue(!cl.loading, "Launch of a new session opens again")
        assertEquals(setOf("x"), cl.taken, "named from the other taken names, never from nothing")
    }

    @Test fun aLateAnswerOfAnEarlierOpeningNeverOverwritesTheCurrentOne() {   // K5-4 b
        val cl = ConnectListing(timeoutMs = 10_000)
        val first = CompletableDeferred<TmuxManager.FreshNameListing>()
        val second = CompletableDeferred<TmuxManager.FreshNameListing>()
        runBlocking {
            val opA = cl.open(setOf("a"))
            val a = async(Dispatchers.Default) { cl.load(opA, setOf("a")) { first.await() } }
            val opB = cl.open(setOf("b"))                     // back, and the screen of another server
            val b = async(Dispatchers.Default) { cl.load(opB, setOf("b")) { second.await() } }
            first.complete(listing("from-a", manifest = setOf("ma")))   // the earlier opening answers first, late
            withTimeout(5_000) { a.await() }
            assertTrue(cl.loading, "the current opening's listing still runs")
            assertEquals(setOf("b"), cl.taken, "and the earlier answer did not land: ${cl.taken}")
            second.complete(listing("from-b", manifest = setOf("mb")))
            withTimeout(5_000) { b.await() }
        }
        assertTrue(!cl.loading)
        assertEquals(listOf("from-b"), cl.sessions.map { it.name })
        assertEquals(setOf("b", "mb"), cl.taken)
    }

    @Test fun aFailedListingOnTheConnectScreenLeavesItsCauseInTheLog() {   // N2
        val saved = com.clauderemote.util.FileLogger.platformLog
        val logLines = java.util.Collections.synchronizedList(mutableListOf<String>())
        com.clauderemote.util.FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
        try {
            val cl = ConnectListing(timeoutMs = 5_000)
            val op = cl.open(setOf("x"))
            runBlocking { withTimeout(5_000) { cl.load(op, setOf("x")) { throw java.io.IOException("tmux listing cut short (no status line)") } } }
            assertTrue(!cl.loading)
            assertEquals(setOf("x"), cl.taken)
            assertEquals(1, logLines.count { "tmux listing cut short (no status line)" in it }, "one line with the cause: $logLines")
        } finally {
            com.clauderemote.util.FileLogger.platformLog = saved
        }
    }

    // ---- the Connect screen ----

    @Test fun theConnectScreensNewSessionSkipsListedAndOtherwiseTakenNames() {   // P11
        assertEquals("$base-2", newSessionName(server, "/work", false, "", listOf(TmuxSession(base, 1, false, "0")), emptySet()))
        assertEquals("$base-2", newSessionName(server, "/work", false, "", emptyList(), setOf(base)), "a failed listing leaves the other names")
        assertEquals(base, newSessionName(server, "/work", false, "", emptyList(), emptySet()), "control")
    }

    @Test fun aNewSessionWaitsForTheListingAnExistingOneDoesNot() {   // R1
        assertEquals(false, canLaunch(launching = false, newSession = true, listing = true))
        assertEquals(true, canLaunch(launching = false, newSession = false, listing = true))
        assertEquals(true, canLaunch(launching = false, newSession = true, listing = false))
        assertEquals(false, canLaunch(launching = true, newSession = false, listing = false))
    }

    // ---- wiring, in the source ----

    private val app by lazy { java.io.File("src/commonMain/kotlin/com/clauderemote/ui/App.kt").readText() }
    private val screen by lazy { java.io.File("src/commonMain/kotlin/com/clauderemote/ui/ConnectScreen.kt").readText() }

    @Test fun theConnectScreenRecomputesTheNameWhenNewSessionIsPickedAgainAndGatesLaunch() {   // M1, R1
        val effect = Regex("""LaunchedEffect\(([^)]*)\) \{\s*if \(!useExistingTmux\) \{\s*tmuxSessionName = newSessionName\(server, folder, selectedMode == ClaudeMode\.YOLO, sessionAlias, tmuxSessions, takenNames, inFlightNames\(\)\)""")
            .find(screen)
        assertTrue(effect != null, "the new-session name comes from newSessionName(…, tmuxSessions, takenNames)")
        val keys = effect!!.groupValues[1].split(",").map { it.trim() }.toSet()
        assertTrue(setOf("useExistingTmux", "tmuxSessions", "takenNames", "folder", "selectedMode", "sessionAlias").all { it in keys },
            "the effect re-runs on each input, the radio included (M1): $keys")
        assertEquals(1, Regex("""val launchAllowed = canLaunch\(launching, newSession = !useExistingTmux, listing = tmuxListing\)\n""").findAll(screen).count(),
            "one canLaunch verdict")
        assertTrue("if (launchAllowed) {" in screen && "enabled = launchAllowed," in screen, "both the button and the keyboard Go go through it")
        assertTrue("tmuxListing = connectListing.loading," in app && "takenNames = connectListing.taken," in app, "App hands the screen its listing state and taken names")
    }

    @Test fun quickConnectNamesThroughPickQuickConnectNameWithoutAHandshakeOfItsOwn() {
        val start = app.indexOf("suspend fun quickConnectName(server: SshServer): String = pickQuickConnectName(")
        assertTrue(start >= 0, "quickConnectName delegates to pickQuickConnectName")
        val fn = app.substring(start, app.indexOf("\n\n", start))
        assertTrue("SshSessionHelper.withSession(server, allowFresh = false) { sess -> TmuxManager.listForFreshName(sess) }" in fn,
            "the listing opens no fresh connection and throws on failure: $fn")
        assertTrue("lastScan = remoteSessions.filter { it.server.id == server.id }" in fn, "the launcher's last scan of this server")
    }
}
