package com.clauderemote.session.service

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import com.clauderemote.model.TmuxNameParser
import com.clauderemote.session.TabManager
import com.clauderemote.session.TmuxPeerLiveness
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.jcraft.jsch.JSch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D6-K: the session prefix `claude-<server>-` in one place ([TmuxNameParser.prefixFor], [TmuxNameParser.DEFAULT_PREFIX]),
 * and what follows from it for a box named other than `server`: its names parse, and its liveness (the forget of a
 * session closed elsewhere, the prune, the manifest push's guard) counts its own prefix and the default one (kritika D2
 * N21) — a new path to forgetting there (N22), which still needs another live session of ours.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TmuxPrefixTest {
    @BeforeTest fun reset() = CommFlags.resetForTest()
    @AfterTest fun cleanup() = CommFlags.resetForTest()

    @Test fun thePrefixIsSanitizedLikeTheNamesAndTheDefaultIsTheServerBoxs() {
        assertEquals("claude-server-", TmuxNameParser.prefixFor("server"))
        assertEquals(TmuxNameParser.DEFAULT_PREFIX, TmuxNameParser.prefixFor("server"))
        assertEquals("claude-box-", TmuxNameParser.prefixFor("box"))
        assertEquals("claude-a_b-", TmuxNameParser.prefixFor("a.b"), "tmux turns . and : into _")
        assertEquals("claude-h_1-", TmuxNameParser.prefixFor("h:1"))
        assertEquals(setOf("claude-box-", "claude-server-"), TmuxNameParser.livePrefixes("box"))
        assertEquals(setOf("claude-server-"), TmuxNameParser.livePrefixes("server"))
        assertEquals(setOf("claude-server-"), TmuxNameParser.livePrefixes(null), "an unknown server: the default only, as before")
    }

    /** kritika D2 N2: parse took the prefix unsanitized, so a name built for `a.b` (`claude-a_b-…`) never lost it. */
    @Test fun aNameBuiltForABoxWithADotParsesBack() {
        val name = TmuxNameParser.build("a.b", "/home/u/proj", isYolo = true, alias = "fix it")
        assertEquals("claude-a_b-proj-yolo--fix-it", name)
        assertEquals(TmuxNameParser.Parsed("proj", isYolo = true, alias = "fix it"), TmuxNameParser.parse(name, "a.b"))
    }

    /** The box `server`: the same names and the same parse as before. */
    @Test fun theServerBoxKeepsItsNames() {
        assertEquals("claude-server-proj", TmuxNameParser.build("server", "/home/u/proj", isYolo = false))
        assertEquals("claude-server-proj-yolo--alias", TmuxNameParser.build("server", "/x/proj/", isYolo = true, alias = "alias"))
        assertEquals(TmuxNameParser.Parsed("proj", isYolo = true, alias = "alias"), TmuxNameParser.parse("claude-server-proj-yolo--alias", "server"))
        val long = TmuxNameParser.build("server", "/" + "f".repeat(80), isYolo = true, alias = "a")
        assertEquals("claude-server-" + "f".repeat(64 - "claude-server-".length - "-yolo--a".length) + "-yolo--a", long)
    }

    @Test fun aBoxsLivenessCountsItsOwnPrefixAndTheDefaultNothingElse() {
        val box = TmuxNameParser.livePrefixes("box")
        fun live(vararg names: String) = TmuxPeerLiveness.hasOtherLivePeer(names.joinToString("\n"), "claude-box-a", box)
        assertTrue(live("claude-box-b"), "another session of the box")
        assertTrue(live("claude-server-x"), "a session of the default prefix")
        assertFalse(live(), "an empty listing never proves anything")
        assertFalse(live("claude-box-a", "__anchor__", "other", "claude-test", "claude-boxer-1"), "only itself, the anchor and strangers")
        // the old predicate: the default only
        assertFalse(TmuxPeerLiveness.hasOtherLivePeer("claude-box-b", "claude-box-a"), "before D6-K a box saw no peer")
        // a dotted box name is a fixed string, not a pattern
        val dotted = TmuxNameParser.livePrefixes("a.b")
        assertTrue(TmuxPeerLiveness.hasOtherLivePeer("claude-a_b-x", "claude-a_b-y", dotted))
        assertFalse(TmuxPeerLiveness.hasOtherLivePeer("claude-aXb-x", "claude-a_b-y", dotted))
    }

    // ---- the forget of a session closed elsewhere on a box named "box": the wiring of SessionPersistenceService ----

    private val boxServer = SshServer(id = "s-box", name = "box", host = "unreachable.invalid", username = "u")
    private val boxTab = ClaudeSession(
        id = "t1", server = boxServer, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-box-a", connectionType = ConnectionType.SSH,
    )

    /** [ManifestCommands.reconcileAll] output: [tomb]stones, the [manifest], the [tmux] listing, empty rest. */
    private fun reconcileOut(tomb: List<String>, tmux: List<String>, manifest: String = "[]") =
        (tomb + "---" + manifest).joinToString("\n") + "\n__END_snap__ rc=0\n" +
            tmux.joinToString("\n") + "\n__END_tmux__ rc=0\n" +
            "\n__END_panes__ rc=0\n\n__END_ps__ rc=0\n\n__END_claude__ rc=0\n"

    private class Exec(var out: String) : RemoteExec {
        override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace) =
            ExecResult(out, 0)
    }

    /** The reconciler SessionPersistenceService builds, over [boxTab] (tombstoned): the names it forgot after one round of [listing]. */
    private fun forgets(vararg listing: String): List<String> = reconciled(listing.toList(), viaCheckClosed = false).first

    /**
     * The same reconciler over [boxTab] (tombstoned), the tab's own close check ([ServerReconciler.checkClosed], the
     * path after a lost tab, review D2 test N-T2) run before the first round of the loop: the names it forgot and the
     * tab's claude session id after it. [manifestUuid] = the manifest's record of `claude-box-a` carries this id.
     */
    private fun checkClosedForgets(vararg listing: String, manifestUuid: String? = null): Pair<List<String>, String?> =
        reconciled(listing.toList(), viaCheckClosed = true, manifestUuid = manifestUuid)

    private fun reconciled(listing: List<String>, viaCheckClosed: Boolean, manifestUuid: String? = null): Pair<List<String>, String?> {
        var lastForgotten: List<String> = emptyList()
        var uuid: String? = null
        runTest {
            val tabs = TabManager().also { it.addTab(boxTab) }
            val registry = ConnectionRegistry(ServerStorage(FakeKeyValueStore()), tabs)
            val manifest = if (manifestUuid == null) "[]"
                else """[{"id":"t1","serverId":"s-box","folder":"/work","tmuxSessionName":"claude-box-a","claudeSessionId":"$manifestUuid"}]"""
            registry.remoteExec = Exec(reconcileOut(tomb = listOf("claude-box-a"), tmux = listing, manifest = manifest))
            registry.execSessions = object : ExecSessionProvider {
                override fun session(serverId: String) = JSch().getSession("u", "localhost", 22)
                override suspend fun await(serverId: String, ms: Long) = session(serverId)
            }
            val servers = ServerStorage(FakeKeyValueStore()).also { it.addServer(boxServer) }
            val storage = SessionStorage(FakeKeyValueStore()).also { it.upsert(SessionStorage.fromClaudeSession(boxTab)) }
            val forgotten = mutableListOf<String>()
            val transcript = TranscriptService(
                scope = backgroundScope, registry = registry, tabManager = tabs, isBackground = { false },
                onContextUpdate = { _, _ -> }, sessionStorage = storage, readRealSessionId = { _, _ -> null },
            )
            val sps = SessionPersistenceService(
                scope = backgroundScope, registry = registry, tabManager = tabs, serverStorage = servers,
                sessionStorage = storage, transcript = transcript, terminalIO = TerminalIOService(registry),
                isBackground = { false }, readRealSessionId = { _, _ -> null }, probeTmuxAlive = { _, _ -> true },
                onActivityUpdate = { _, _ -> }, disconnectSession = {}, onForgotten = { _, name -> forgotten += name },
            )
            val rec = sps.newExecReconciler()
            rec.register("t1")
            if (viaCheckClosed) {
                rec.checkClosed("t1", delayMs = 0)
                runCurrent()
                // decided by checkClosed alone: the loop's first round (1 s after register) has not run
                assertTrue(currentTime < 1_000 || "claude-box-a" in listing, "the loop ran first: ${currentTime} ms")
            } else {
                advanceTimeBy(1_001)
            }
            lastForgotten = forgotten.toList()
            uuid = tabs.getTab("t1")?.claudeSessionId
            rec.unregister("t1")
        }
        return lastForgotten to uuid
    }

    @Test fun onABoxNamedBoxAClosedSessionIsForgottenOnlyWhenAnotherSessionOfOursLives() {
        assertEquals(listOf("claude-box-a"), forgets("claude-box-b", "__anchor__"), "another session of the box: closed elsewhere")
        assertEquals(listOf("claude-box-a"), forgets("claude-server-x"), "a session of the default prefix proves it too")
        assertEquals(emptyList(), forgets(), "an empty listing (tmux dead) never forgets")
        assertEquals(emptyList(), forgets("__anchor__", "other", "claude-test"), "the anchor and strangers prove nothing")
        assertEquals(emptyList(), forgets("claude-box-a", "claude-box-b"), "still there: kept")
    }

    /**
     * Review D2 test N-T2: the same rule through the tab's own close check (`ExecServerReconciler.doCheckClosed`), not
     * only through the loop's round. With only strangers alive it does nothing at all — no forget, and no action on the
     * tab either (it does not hand the closed session's manifest record to the tab as if the session were still ours).
     */
    @Test fun onABoxNamedBoxTheTabsOwnCloseCheckCountsOnlyTheBoxsPrefixAndTheDefault() {
        assertEquals(listOf("claude-box-a"), checkClosedForgets("claude-box-b", "__anchor__").first, "another session of the box")
        assertEquals(listOf("claude-box-a"), checkClosedForgets("claude-server-x").first, "a session of the default prefix")
        assertEquals(emptyList<String>() to null, checkClosedForgets(), "an empty listing never forgets")
        val stale = "0f6c2a4e-1b2c-4d5e-8f90-123456789abc"
        assertEquals(emptyList<String>() to null, checkClosedForgets("__anchor__", "other", "claude-test", "claude-boxer-1", manifestUuid = stale),
            "the anchor and strangers prove nothing, and the check touches nothing")
        assertEquals(emptyList(), checkClosedForgets("claude-box-a", "claude-box-b").first, "still there: kept")
    }
}
