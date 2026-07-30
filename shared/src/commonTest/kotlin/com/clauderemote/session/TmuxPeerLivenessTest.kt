package com.clauderemote.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [TmuxPeerLiveness] — the parsing half of the liveness gate that
 * decides whether a missing tmux session was closed on ANOTHER device (peers
 * still alive ⇒ forget it locally) or whether the whole tmux server died
 * (no peers ⇒ rebuild, never forget).
 *
 * The asymmetry is the point: answering "peers are alive" when they are not
 * DELETES a user's session. Answering "no peers" when some exist merely
 * rebuilds one unnecessarily. Every ambiguous input must therefore read false.
 */
class TmuxPeerLivenessTest {

    private val self = "claude-server-myproj-yolo"

    private fun check(vararg lines: String) =
        TmuxPeerLiveness.hasOtherLivePeer(lines.joinToString("\n"), self)

    @Test
    fun anotherClaudeSessionCountsAsALivePeer() {
        assertTrue(check(self, "claude-server-other-yolo"))
    }

    @Test
    fun onlyOurselfIsNotAPeer() {
        // The session being probed must never count as its own peer, or the
        // closed-elsewhere branch would fire for a session that simply died.
        assertFalse(check(self))
    }

    @Test
    fun emptyOutputIsNotAPeer() {
        // A dead tmux server prints nothing. This is THE whole-server-outage
        // case: it must read false so the caller rebuilds instead of forgetting
        // every session (the repeated whole-server session loss).
        assertFalse(TmuxPeerLiveness.hasOtherLivePeer("", self))
    }

    @Test
    fun blankAndWhitespaceOnlyOutputIsNotAPeer() {
        assertFalse(TmuxPeerLiveness.hasOtherLivePeer("\n\n   \n", self))
    }

    @Test
    fun anchorKeepaliveIsNotAPeer() {
        // The restore scripts park an __anchor__ session on the server purely to
        // keep the tmux server alive. Counting it would make a server holding
        // ONLY the keepalive look populated, and every real session would then
        // be forgotten as "closed elsewhere".
        assertFalse(check(TmuxPeerLiveness.ANCHOR))
        assertFalse(check(self, TmuxPeerLiveness.ANCHOR))
    }

    @Test
    fun unrelatedUserSessionIsNotAPeer() {
        // Only our own `claude-server-*` sessions count. A developer's own tmux
        // session on the same box must not make us believe the app's sessions
        // are alive.
        assertFalse(check("my-editor", "scratch", "irssi"))
    }

    @Test
    fun peerIsFoundAmongUnrelatedSessions() {
        assertTrue(check("my-editor", self, "claude-server-other-yolo", "irssi"))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        // tmux output is trimmed per line before matching; a stray \r from a
        // pty-ish channel must not hide a live peer (which would cause an
        // unnecessary rebuild) nor invent one.
        assertTrue(TmuxPeerLiveness.hasOtherLivePeer("  claude-server-other-yolo  \n", self))
        assertFalse(TmuxPeerLiveness.hasOtherLivePeer("   $self   \n", self))
    }

    @Test
    fun prefixMatchIsNotSubstringMatch() {
        // A name merely CONTAINING the prefix later in the string is not ours.
        assertFalse(check("not-claude-server-other"))
    }

    @Test
    fun aSessionWhoseNameExtendsOursStillCountsAsAPeer() {
        // `exclude` is compared by EXACT equality, not prefix — `<self>--second`
        // is a genuinely different session and must count as a live peer.
        // (This is the same exact-vs-prefix distinction that the tmux `-t '=name'`
        // fixes are about; getting it wrong here would under-count peers and
        // cause needless rebuilds rather than data loss.)
        assertTrue(check("$self--second"))
    }

    @Test
    fun errorTextIsNotMistakenForAPeer() {
        // stderr is redirected to /dev/null, but a transport that merges streams
        // could still surface a message. Nothing that isn't a claude-server-*
        // name may read as liveness.
        assertFalse(check("no server running on /tmp/tmux-1000/default"))
        assertFalse(check("error connecting to /tmp/tmux-1000/default"))
    }
}
