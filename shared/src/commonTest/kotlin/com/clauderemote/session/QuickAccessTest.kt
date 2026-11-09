package com.clauderemote.session

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.RemoteSession
import com.clauderemote.model.SessionStatus
import com.clauderemote.model.SshServer
import com.clauderemote.model.TmuxSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Quick access to Jarvis and the Velín: intent → target, and where a tap on Jarvis goes. */
class QuickAccessTest {

    private val a = SshServer(id = "a", name = "server", host = "a.example", username = "u")
    private val b = SshServer(id = "b", name = "other", host = "b.example", username = "u")
    private val jarvis = QuickAccess.JARVIS_TMUX_NAME

    private fun tab(id: String, server: SshServer, name: String = jarvis, status: SessionStatus = SessionStatus.ACTIVE) =
        ClaudeSession(
            id = id, server = server, folder = "jarvis", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
            tmuxSessionName = name, connectionType = ConnectionType.SSH, status = status,
        )

    private fun remote(server: SshServer, name: String = jarvis) =
        RemoteSession(server, TmuxSession(name = name, windows = 1, attached = false, created = "0"))

    // ── intent → target ──────────────────────────────────────────────────────

    @Test
    fun actionsMapToTheirTargets() {
        assertEquals(QuickTarget.JARVIS, QuickAccess.targetOf(QuickAccess.ACTION_JARVIS, null))
        assertEquals(QuickTarget.VELIN, QuickAccess.targetOf(QuickAccess.ACTION_VELIN, null))
    }

    @Test
    fun theExtraWorksUnderTheMainActionAndRoundTrips() {
        QuickTarget.entries.forEach {
            assertEquals(it, QuickAccess.targetOf("android.intent.action.MAIN", QuickAccess.extraValue(it)))
        }
        assertEquals(QuickTarget.VELIN, QuickAccess.targetOf(null, " Velín "))
        assertEquals(QuickTarget.JARVIS, QuickAccess.targetOf(null, "JARVIS"))
    }

    @Test
    fun theActionWinsOverAConflictingExtra() {
        assertEquals(QuickTarget.VELIN, QuickAccess.targetOf(QuickAccess.ACTION_VELIN, "jarvis"))
    }

    @Test
    fun anOrdinaryLaunchOrANotificationIsNoQuickAccess() {
        assertNull(QuickAccess.targetOf("android.intent.action.MAIN", null))
        assertNull(QuickAccess.targetOf(null, null))
        assertNull(QuickAccess.targetOf(null, "launcher"))
        assertNull(QuickAccess.targetOf("com.clauderemote.action.OTHER", ""))
    }

    // ── route ────────────────────────────────────────────────────────────────

    @Test
    fun aConnectedTabIsShownWithoutAScan() {
        val tabs = listOf(tab("x", a, name = "claude-server-other"), tab("j", a))
        val r = QuickAccess.route(tabs, emptySet(), emptyList(), emptySet())
        assertEquals(JarvisRoute.SwitchTab("j", reclaim = false), r)
        assertFalse(QuickAccess.needsFreshScan(r, tabs))
    }

    @Test
    fun aTabInItsUndoWindowIsReclaimed() {
        val r = QuickAccess.route(listOf(tab("j", a)), setOf("j"), emptyList(), emptySet())
        assertEquals(JarvisRoute.SwitchTab("j", reclaim = true), r)
    }

    @Test
    fun aDisconnectedTabIsShownButCheckedFirst() {
        listOf(SessionStatus.DISCONNECTED, SessionStatus.ERROR).forEach { st ->
            val tabs = listOf(tab("j", a, status = st))
            val r = QuickAccess.route(tabs, emptySet(), emptyList(), emptySet())
            assertEquals(JarvisRoute.SwitchTab("j", reclaim = false), r)
            assertTrue(QuickAccess.needsFreshScan(r, tabs), "$st")
        }
    }

    @Test
    fun aConnectingTabIsShownAtOnce() {
        // a cold start from the tile: the restored tab is still connecting, the user should not wait for a scan
        val tabs = listOf(tab("j", a, status = SessionStatus.CONNECTING))
        val r = QuickAccess.route(tabs, emptySet(), emptyList(), emptySet())
        assertEquals(JarvisRoute.SwitchTab("j", reclaim = false), r)
        assertFalse(QuickAccess.needsFreshScan(r, tabs))
    }

    @Test
    fun aDisconnectedTabWhoseServerAnsweredWithoutJarvisIsNotRunning() {
        val tabs = listOf(tab("j", a, status = SessionStatus.DISCONNECTED))
        val r = QuickAccess.route(tabs, emptySet(), listOf(remote(a, "claude-server-x")), setOf("a"))
        assertEquals(JarvisRoute.NotRunning, r)
    }

    @Test
    fun aConnectedTabStaysEvenIfTheScanMissedIt() {
        // the scan can be older than the tab; a connected shell is the better witness
        val r = QuickAccess.route(listOf(tab("j", a)), emptySet(), emptyList(), setOf("a"))
        assertEquals(JarvisRoute.SwitchTab("j", reclaim = false), r)
    }

    @Test
    fun ofTwoTabsTheConnectedOneThenTheLiveOneWins() {
        val dead = tab("j1", a, status = SessionStatus.DISCONNECTED)
        val live = tab("j2", b, status = SessionStatus.CONNECTING)
        // a's scan did not answer, b's saw Jarvis live
        assertEquals(
            JarvisRoute.SwitchTab("j2", reclaim = false),
            QuickAccess.route(listOf(dead, live), emptySet(), listOf(remote(b)), setOf("b")),
        )
        val active = tab("j3", a)
        assertEquals(
            JarvisRoute.SwitchTab("j3", reclaim = false),
            QuickAccess.route(listOf(live, active), emptySet(), listOf(remote(b)), setOf("b")),
        )
    }

    @Test
    fun noTabAttachesWhereTheScanSawJarvis() {
        val r = QuickAccess.route(
            tabs = listOf(tab("x", a, name = "claude-server-other")),
            hiddenIds = emptySet(),
            remote = listOf(remote(a, "claude-server-other"), remote(b)),
            scannedServerIds = setOf("a", "b"),
        )
        assertEquals(JarvisRoute.Attach(remote(b)), r)
        assertTrue(QuickAccess.needsFreshScan(r, emptyList()))
    }

    @Test
    fun ofTwoServersTheFirstInScanOrderIsAttached() {
        val r = QuickAccess.route(emptyList(), emptySet(), listOf(remote(b), remote(a)), setOf("a", "b"))
        assertEquals(JarvisRoute.Attach(remote(b)), r)
    }

    @Test
    fun onlyTheExactNameCounts() {
        val r = QuickAccess.route(
            tabs = listOf(tab("x", a, name = "$jarvis-2")),
            hiddenIds = emptySet(),
            remote = listOf(remote(a, "claude-server-jarvis"), remote(a, "${jarvis}x")),
            scannedServerIds = setOf("a"),
        )
        assertEquals(JarvisRoute.NotRunning, r)
    }

    @Test
    fun nothingKnownBeforeAnyScanAnsweredThenNotRunningAfterIt() {
        val r = QuickAccess.route(emptyList(), emptySet(), emptyList(), emptySet())
        assertEquals(JarvisRoute.Unknown, r)
        assertTrue(QuickAccess.needsFreshScan(r, emptyList()))
        assertEquals(JarvisRoute.NotRunning, QuickAccess.settle(r))
        assertEquals(JarvisRoute.NotRunning, QuickAccess.route(emptyList(), emptySet(), emptyList(), setOf("a")))
    }

    @Test
    fun settleKeepsEveryRealAnswer() {
        val routes = listOf(JarvisRoute.SwitchTab("j", false), JarvisRoute.Attach(remote(a)), JarvisRoute.NotRunning)
        routes.forEach { assertEquals(it, QuickAccess.settle(it)) }
    }

    @Test
    fun theNoticeSaysWhetherAnyServerAnswered() {
        assertEquals("Jarvis neběží", QuickAccess.notRunningNotice(anyServerAnswered = true))
        assertTrue(QuickAccess.notRunningNotice(anyServerAnswered = false).contains("neodpověděl"))
    }

    // review MED (F5a): MainActivity is exported; the microphone mode needs this process' own token
    @Test fun talkFromOutsideIsOnlyOpeningJarvis() {
        assertEquals(QuickTarget.JARVIS_TALK, QuickAccess.targetOf("android.intent.action.MAIN", "jarvis-talk"))
        assertEquals(QuickTarget.JARVIS, QuickAccess.authorize(QuickTarget.JARVIS_TALK, null), "no token")
        assertEquals(QuickTarget.JARVIS, QuickAccess.authorize(QuickTarget.JARVIS_TALK, "guess"), "a guessed token")
        val t = QuickAccess.issueTalkToken()
        assertEquals(QuickTarget.JARVIS, QuickAccess.authorize(QuickTarget.JARVIS_TALK, "x$t"))
        assertEquals(QuickTarget.JARVIS, QuickAccess.authorize(QuickTarget.JARVIS_TALK, t), "a wrong try used the token up")
    }

    @Test fun talkWithTheTokenStartsOnceAndOtherTargetsNeedNone() {
        val t = QuickAccess.issueTalkToken()
        assertEquals(QuickTarget.JARVIS_TALK, QuickAccess.authorize(QuickTarget.JARVIS_TALK, t))
        assertEquals(QuickTarget.JARVIS, QuickAccess.authorize(QuickTarget.JARVIS_TALK, t), "a replayed intent")
        assertEquals(QuickTarget.VELIN, QuickAccess.authorize(QuickTarget.VELIN, null))
        val old = QuickAccess.issueTalkToken()
        val new = QuickAccess.issueTalkToken()
        assertEquals(QuickTarget.JARVIS, QuickAccess.authorize(QuickTarget.JARVIS_TALK, old), "only the newest is live")
        assertEquals(QuickTarget.JARVIS, QuickAccess.authorize(QuickTarget.JARVIS_TALK, new), "and the failed try used it")
    }
}
