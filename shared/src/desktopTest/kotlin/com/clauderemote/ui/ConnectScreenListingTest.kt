package com.clauderemote.ui

import com.clauderemote.connection.TmuxManager
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.SshServer
import com.clauderemote.model.TmuxSession
import com.clauderemote.session.ClaudeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * C2 (dávka C): the Connect screen tells a listing that FAILED from a server with no sessions ("0 on server" only for a
 * listing that answered), offers Retry (a fresh connection only without an exec transport, off while a launch or a
 * listing runs), lets a NEW session launch after a failed listing with a warning (kolo 2), keeps Launch visible with its
 * reason, shows in "Will run" what the launch runs, and quick connect reads this device's own names once its listing
 * answered (N7-3).
 */
class ConnectScreenListingTest {
    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)

    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p", defaultFolder = "/work")
    private val base = "claude-box-work"

    private fun listing(vararg live: String, manifest: Set<String> = emptySet()) =
        TmuxManager.FreshNameListing(live.map { TmuxSession(it, 1, false, "0") }, manifest)

    private fun loaded(cl: ConnectListing, timeoutMs: Long = 5_000, list: suspend () -> TmuxManager.FreshNameListing): ConnectListing {
        val op = cl.open(setOf("x"))
        runBlocking { withTimeout(10_000) { cl.load(op, setOf("x"), list) } }
        return cl
    }

    // ---- 1: Loading | Loaded | Failed ----

    @Test fun aListingThatThrowsIsFailedNotAnEmptyServer() {
        val cl = loaded(ConnectListing()) { throw java.io.IOException("channel is not opened.") }
        val st = assertIs<ListingState.Failed>(cl.state)
        assertTrue("channel is not opened." in st.reason, st.reason)
        assertFalse(cl.loading)
        assertEquals(emptyList(), cl.sessions)
        assertEquals(setOf("x"), cl.taken, "the other taken names stay")
        assertEquals("Tmux · list unavailable", tmuxSectionLabel(cl.loading, (cl.state as? ListingState.Failed)?.reason, cl.sessions.size))
    }

    @Test fun aListingThatDoesNotAnswerInTimeIsFailedToo() {
        val cl = loaded(ConnectListing(timeoutMs = 200)) { awaitCancellation() }
        assertIs<ListingState.Failed>(cl.state)
        assertFalse(cl.loading, "Launch of an existing session is not held")
    }

    @Test fun onlyAListingThatAnsweredEmptyIsZeroOnServer() {
        val cl = loaded(ConnectListing()) { listing() }
        assertEquals(ListingState.Loaded(emptyList()), cl.state)
        assertEquals("Tmux · 0 on server", tmuxSectionLabel(cl.loading, null, cl.sessions.size))
        val two = loaded(ConnectListing()) { listing(base, "other", manifest = setOf("m")) }
        assertEquals(listOf(base, "other"), two.sessions.map { it.name })
        assertEquals(setOf("x", "m"), two.taken)
        assertEquals("Tmux · listing…", tmuxSectionLabel(listing = true, listingFailed = null, count = 0))
    }

    @Test fun aCancelledListingNeverLeavesTheScreenListingForever() {
        val cl = ConnectListing()
        val op = cl.open(emptySet())
        runBlocking {
            val job = async(Dispatchers.Default) { cl.load(op, emptySet()) { throw CancellationException("composition left") } }
            assertFailsWith<CancellationException> { withTimeout(5_000) { job.await() } }
        }
        assertFalse(cl.loading)
        assertIs<ListingState.Failed>(cl.state)
    }

    @Test fun aRetryIsANewOpeningThatListsAgain() {
        val cl = loaded(ConnectListing()) { throw java.io.IOException("boom") }
        assertIs<ListingState.Failed>(cl.state)
        val op = cl.open(setOf("x"))
        assertTrue(cl.loading, "Retry shows the listing running")
        runBlocking { withTimeout(5_000) { cl.load(op, setOf("x")) { listing(base) } } }
        assertEquals(listOf(base), cl.sessions.map { it.name })
    }

    // ---- 2 (kolo 2) and 4: Launch after a failed listing, and why it is off ----

    @Test fun aNewSessionAfterAFailedListingCanAlwaysBeLaunched() {
        // kolo 2 (adv 1): V16 checks the name on the server at creation on any transport; held, there was no way out
        assertTrue(canLaunch(launching = false, newSession = true, listing = false), "after a failed listing (no listing runs)")
        assertFalse(canLaunch(launching = false, newSession = true, listing = true), "a listing still runs: its name is picked from it")
        assertTrue(canLaunch(launching = false, newSession = false, listing = true), "an existing session needs no listing")
        assertFalse(canLaunch(launching = true, newSession = false, listing = false), "a launch under way")
    }

    @Test fun anInactiveLaunchSaysWhy() {
        assertEquals("Listing sessions…", launchLabel(false, newSession = true, listing = true))
        assertEquals("Connecting…", launchLabel(true, newSession = true, listing = false))
        for (l in listOf(false, true)) for (n in listOf(false, true)) for (busy in listOf(false, true)) {
            val off = !canLaunch(busy, n, l)
            assertEquals(off, launchLabel(busy, n, l) != "▶  Launch Claude", "an off Launch names its reason: launching=$busy new=$n listing=$l")
        }
    }

    // ---- 3: Will run = the builders ----

    @Test fun willRunShowsTheLaunchBuildersOutputVerbatim() {
        for ((mode, model, acct) in listOf(Triple(ClaudeMode.YOLO, ClaudeModel.DEFAULT, null), Triple(ClaudeMode.NORMAL, ClaudeModel.SONNET, "work-acct"))) {
            val preview = willRunPreview(true, "claude-box-it's", "/srv/my proj", mode, model, acct)
            val built = ClaudeConfig.buildLaunchCommand("/srv/my proj", mode, model, accountSlug = acct)
            assertTrue(built in preview, "the builder's command, verbatim:\n$preview\n---\n$built")
            assertTrue(preview.startsWith("$ tmux new-session -d -s 'claude-box-it'\\''s'\n"), preview)
        }
        assertEquals("$ tmux attach-session -t '=claude-box-x'", willRunPreview(false, "claude-box-x", "/w", ClaudeMode.YOLO, ClaudeModel.DEFAULT, null))
    }

    // ---- H16: Retry and a launch in flight ----

    @Test fun retryIsOffWhileALaunchOrAListingRuns() {
        assertFalse(canRetryListing(launching = true, listing = false))
        assertFalse(canRetryListing(launching = false, listing = true), "one listing at a time: no storm of retries")
        assertTrue(canRetryListing(launching = false, listing = false))
    }

    @Test fun retryOpensAFreshConnectionOnlyWithoutAnExecTransport() {   // kolo 2 (adv 1)
        assertFalse(connectRetryAllowsFresh(execUp = true), "the exec transport lists: no handshake of its own (H18)")
        assertTrue(connectRetryAllowsFresh(execUp = false), "without it a retry may connect, or it could never succeed")
    }

    @Test fun theInFlightNamesAreTheLaunchesNotTheTabs() {   // N3
        assertEquals(setOf("creating"), inFlightTmuxNames(own = setOf("creating", "tab-a"), tabNames = setOf("tab-a", "tab-b")))
    }

    @Test fun aKillReListsOnlyTheScreenOfItsOwnServer() {   // kolo 2 (adv 2)
        assertTrue(reloadAfterKill(connectShown = true, shownServerId = "a", killedServerId = "a"))
        assertFalse(reloadAfterKill(connectShown = true, shownServerId = "b", killedServerId = "a"), "another server's screen is shown now")
        assertFalse(reloadAfterKill(connectShown = false, shownServerId = "a", killedServerId = "a"), "the screen was left")
    }

    @Test fun aPickedSessionThatIsGoneIsNoLongerPicked() {   // kolo 2 (adv 6)
        val listed = listOf(TmuxSession("a", 1, false, "0"))
        assertTrue(keepsExistingSelection(true, "a", listed, listing = false))
        assertFalse(keepsExistingSelection(true, "b", listed, listing = false), "killed: back to New session")
        assertFalse(keepsExistingSelection(true, "a", emptyList(), listing = false), "the listing failed: nothing shows it")
        assertTrue(keepsExistingSelection(true, "a", emptyList(), listing = true), "a listing still runs: wait for it")
        assertTrue(keepsExistingSelection(false, "x", emptyList(), listing = false))
    }

    @Test fun aLateFailureOfAnEarlierOpeningNeverOverwritesTheCurrentOne() {   // N2
        val cl = ConnectListing(timeoutMs = 10_000)
        val first = kotlinx.coroutines.CompletableDeferred<Unit>()
        runBlocking {
            val opA = cl.open(setOf("a"))
            val a = async(Dispatchers.Default) { cl.load(opA, setOf("a")) { first.await(); throw java.io.IOException("late failure of A") } }
            val opB = cl.open(setOf("b"))
            withTimeout(5_000) { cl.load(opB, setOf("b")) { listing(base) } }
            first.complete(Unit)
            withTimeout(5_000) { a.await() }
        }
        assertEquals(ListingState.Loaded(listOf(TmuxSession(base, 1, false, "0"))), cl.state, "B's answer stands")
        assertEquals(setOf("b"), cl.taken)
    }

    @Test fun aNameThisDeviceIsCreatingStaysSoASecondLaunchJoinsIt() {
        val listed = listOf(TmuxSession(base, 1, false, "0"))
        assertEquals(base, newSessionName(server, "/work", false, "", listed, emptySet(), inFlight = setOf(base)),
            "the listing shows the session our launch is creating: Launch keeps its name and joins it (launchSession, R6-1)")
        assertEquals("$base-2", newSessionName(server, "/work", false, "", listed, emptySet()), "control: someone else's session")
    }

    // ---- 5: N7-3 ----

    @Test fun quickConnectReadsItsOwnNamesOnceTheListingAnswered() {
        val own = java.util.concurrent.CopyOnWriteArraySet<String>()
        val name = runBlocking {
            withTimeout(10_000) {
                pickQuickConnectName(server, { own.toSet() }, emptyList()) {
                    own += base // a launch of this device starts creating `base` while the listing runs …
                    listing(base) // … and the listing already shows it
                }
            }
        }
        assertEquals(base, name, "joins the launch begun meanwhile instead of naming a second session around it")
    }

    // ---- wiring, in the source ----

    private val app by lazy { java.io.File("src/commonMain/kotlin/com/clauderemote/ui/App.kt").readText() }
    private val screen by lazy { java.io.File("src/commonMain/kotlin/com/clauderemote/ui/ConnectScreen.kt").readText() }

    @Test fun execUpNeedsTheLinkUpAndTheChannelsActive_andReadsTheChannelsOnlyForAnUpLink() {
        var reads = 0
        assertTrue(connectExecUp(true) { reads++; true })
        assertFalse(connectExecUp(true) { reads++; false })
        assertEquals(2, reads)
        assertFalse(connectExecUp(false) { reads++; true })
        assertEquals(2, reads, "a link that is not Up is not asked")
    }

    @Test fun theInFlightNamesOfAServerAreItsLaunchesNotItsTabsNorAnotherServers() {
        val other = server.copy(id = "srv-2")
        fun tab(s: SshServer, name: String) = com.clauderemote.model.ClaudeSession(id = "t-$name", server = s, folder = "/w", mode = ClaudeMode.NORMAL,
            model = ClaudeModel.DEFAULT, tmuxSessionName = name, connectionType = com.clauderemote.model.ConnectionType.SSH)
        val own = setOf("creating", "tab-a", "tab-b")
        assertEquals(setOf("creating", "tab-b"), connectInFlightNames(own, listOf(tab(server, "tab-a"), tab(other, "tab-b")), server.id))
        assertEquals(own, connectInFlightNames(own, emptyList(), server.id))
    }

    @Test fun retryListsWithoutAHandshakeOfItsOwnAndIsOffDuringALaunch() {   // H18, H16
        assertTrue("onRetryListing = { loadConnectListing(server, allowFresh = connectRetryAllowsFresh(execUp)) }," in app, "Retry: fresh only without exec")
        assertTrue("withSession(server, allowFresh = allowFresh) { sess -> TmuxManager.listForFreshName(sess) }" in app, "the listing honours it")
        assertTrue("TextButton(onClick = onRetryListing, enabled = canRetryListing(launching, tmuxListing))" in screen, "Retry is off while a launch or a listing runs")
    }

    @Test fun theScreenGetsTheFailureTheExecStateAndTheInFlightNames() {
        assertTrue("listingFailed = (connectListing.state as? ListingState.Failed)?.reason," in app)
        assertTrue("val execUp = connectExecUp(execState is com.clauderemote.session.service.LinkState.Up) {\n" +
            "                            sessionOrchestrator.connectionRegistry.execActive(server.id)\n                        }" in app, "execUp: the whole expression")
        assertTrue("inFlightNames = { connectInFlightNames(sessionOrchestrator.ownTmuxNames(server.id), tabManager.tabs.value, server.id) }," in app,
            "the in-flight names are the launches', not the tabs (S4b)")
        assertTrue("    LaunchedEffect(tmuxSessions, tmuxListing, listingFailed) {\n        if (!keepsExistingSelection(useExistingTmux, tmuxSessionName, tmuxSessions, tmuxListing)) useExistingTmux = false" in screen,
            "the reset re-runs on the listing and its failure (G2)")
        assertTrue("if (!keepsExistingSelection(useExistingTmux, tmuxSessionName, tmuxSessions, tmuxListing)) useExistingTmux = false" in screen)
        assertTrue("SectionLabel(tmuxSectionLabel(tmuxListing, listingFailed, tmuxSessions.size), c.textDim)" in screen, "the section label")
        assertTrue("val willRunPreview = willRunPreview(!useExistingTmux, tmuxSessionName, folder, selectedMode, selectedModel, selectedAccountSlug)" in screen)
        assertTrue("disabledContainerColor = c.surface2," in screen, "an off Launch stays visible (N2)")
    }

    @Test fun killingASessionListsAgainInsteadOfReadingAFailureAsNoSessions() {
        val start = app.indexOf("onKillTmux = { sessionName ->")
        val block = app.substring(start, app.indexOf("onLoadAccounts", start))
        assertFalse("listSessions" in block, "TmuxManager.listSessions reads a failure as an empty list: $block")
        assertTrue("if (reloadAfterKill(currentScreen == Screen.CONNECT, selectedServer?.id, server.id)) {" in block, block)
        assertTrue("loadConnectListing(server, allowFresh = connectRetryAllowsFresh(execUp))" in block, "the re-list may connect without exec (N2): $block")
        assertFalse("allowFresh = false" in block || "allowFresh = true" in block, block)
    }
}
