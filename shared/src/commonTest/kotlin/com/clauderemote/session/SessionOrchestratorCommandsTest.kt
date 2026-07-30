package com.clauderemote.session

import com.clauderemote.session.service.ConnectionRegistry
import com.clauderemote.session.service.TerminalIOService
import com.clauderemote.session.service.TmuxProbes
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the tmux command strings SessionOrchestrator hands to the shell —
 * regressions here have previously left two clients fighting over one
 * session's layout (the SIGSEGV incident) or attached a device to the WRONG
 * session (prefix-match collision). buildAttachCommand was made `internal`
 * (from `private`) purely to let this test call it directly.
 *
 * Constructing a full SessionOrchestrator only needs a ServerStorage (backed
 * by the existing FakeKeyValueStore test fake) and a plain TabManager, so we
 * exercise the real method rather than reimplementing its logic. Its device
 * marker key (`deviceKey`) is randomized per orchestrator instance (see
 * SessionOrchestrator.deviceKey) and settable only via startLogShipping,
 * which also wires a real LogShipper — a global-state side effect we don't
 * want in a unit test. So: the preamble's OWN string logic is verified in
 * full, in isolation, against a known deviceKey (singleClientPreambleTests
 * below); buildAttachCommand's test then checks the parts that are
 * independent of the random deviceKey — that the preamble ran first, and the
 * exact deterministic tail buildAttachCommand appends after it.
 */
class SessionOrchestratorCommandsTest {

    private fun orchestrator() = SessionOrchestrator(ServerStorage(FakeKeyValueStore()), TabManager())

    private fun expectedTail(tmuxSessionName: String): String {
        val escaped = tmuxSessionName.replace("'", "'\\''")
        // The window-size un-pin is a WINDOW target (`-w`) and MUST carry the
        // trailing colon (`'=name:'`) — without it tmux rejects the target
        // ("no such window") and the `2>/dev/null` swallows that silently, so
        // the un-pin never actually runs (verified live on tmux 3.5a). The
        // attach-session line below is a SESSION target, where `=name` WITHOUT
        // a colon is the correct exact-match form — the two deliberately
        // differ, don't "fix" them to match each other.
        return "tmux set-option -w -t '=$escaped:' window-size latest 2>/dev/null; " +
            "tmux set-option -g history-limit 100000 2>/dev/null; " +
            "tmux attach-session -t '=$escaped'"
    }

    /** Un-pinning + history-limit + exact attach target, for a plain session name. */
    @Test
    fun buildAttachCommand_plainName_endsWithExactDeterministicTail() {
        val command = orchestrator().buildAttachCommand("claude-server-abc123")
        assertTrue(command.endsWith(expectedTail("claude-server-abc123")))
        // The single-client preamble ran BEFORE the window-size/attach lines —
        // it must detach this device's own stale client first, or the new
        // attach races it (the two-clients-fighting-over-layout SIGSEGV cause).
        assertTrue(command.contains("mkdir -p"), "preamble must run")
        assertTrue(command.indexOf("mkdir -p") < command.indexOf("window-size latest"))
    }

    /** Same, but the session name contains a single quote (must be shell-escaped consistently). */
    @Test
    fun buildAttachCommand_nameWithSingleQuote_endsWithExactDeterministicTail() {
        val name = "claude-server-o'brien"
        val command = orchestrator().buildAttachCommand(name)
        assertTrue(command.endsWith(expectedTail(name)))
        assertTrue(command.contains("mkdir -p"), "preamble must run")
        assertTrue(command.indexOf("mkdir -p") < command.indexOf("window-size latest"))
    }

    @Test
    fun buildAttachCommand_setsWindowSizeLatest_notManual() {
        // Regression: an older build's `window-size manual` SIGSEGVs the tmux
        // server (see git history) — this un-pins any window a stale build
        // left pinned, back to current-device-wins `latest`.
        val command = orchestrator().buildAttachCommand("claude-server-x")
        assertTrue(command.contains("window-size latest"))
        assertTrue(!command.contains("window-size manual"))
    }

    @Test
    fun buildAttachCommand_usesExactMatchTarget_notPrefixMatch() {
        // Plain `-t name` prefix-matches in tmux, which could attach to a
        // DIFFERENT session whose name merely starts with ours. `-t '=name'`
        // is the exact-match form.
        val command = orchestrator().buildAttachCommand("claude-server-x")
        assertTrue(command.contains("-t '=claude-server-x'"))
    }

    /**
     * The window `set-option -w` target and the session `attach-session`
     * target look almost identical but are NOT interchangeable: a window
     * target needs the trailing colon (`'=name:'`); a session target must
     * NOT have one (`'=name'`). Assert both forms explicitly, for a plain
     * name and one with an embedded single quote, so this distinction (and
     * the silent-failure regression it fixes — bare `-t '=name'` on a window
     * target exits 1 with "no such window", and the un-pin was swallowed by
     * `2>/dev/null` for however long this shipped without the colon) can't
     * quietly regress.
     */
    @Test
    fun buildAttachCommand_windowTargetHasColon_sessionTargetDoesNot() {
        val command = orchestrator().buildAttachCommand("claude-server-x")
        assertTrue(
            command.contains("set-option -w -t '=claude-server-x:' window-size latest"),
            "window-scoped set-option must target '=name:' (with colon)",
        )
        assertTrue(
            command.contains("attach-session -t '=claude-server-x'"),
            "attach-session must target '=name' (without colon)",
        )
        assertTrue(!command.contains("attach-session -t '=claude-server-x:'"), "session target must NOT carry the colon")
    }

    @Test
    fun buildAttachCommand_windowTargetHasColon_sessionTargetDoesNot_nameWithSingleQuote() {
        val command = orchestrator().buildAttachCommand("claude-server-o'brien")
        val escaped = "claude-server-o'\\''brien"
        assertTrue(
            command.contains("set-option -w -t '=$escaped:' window-size latest"),
            "window-scoped set-option must target '=name:' (with colon), quote-escaped",
        )
        assertTrue(
            command.contains("attach-session -t '=$escaped'"),
            "attach-session must target '=name' (without colon), quote-escaped",
        )
        assertTrue(!command.contains("attach-session -t '=$escaped:'"), "session target must NOT carry the colon")
    }

    // --- singleClientPreamble in isolation, against a KNOWN deviceKey, so the
    // exact preamble string (independent of SessionOrchestrator's randomized
    // deviceKey) is pinned down precisely. ---

    private fun tmuxProbes(): TmuxProbes {
        val tabManager = TabManager()
        val registry = ConnectionRegistry(ServerStorage(FakeKeyValueStore()), tabManager)
        return TmuxProbes(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            registry = registry,
            tabManager = tabManager,
            terminalIO = TerminalIOService(registry),
        )
    }

    @Test
    fun singleClientPreamble_plainNameAndDeviceKey_exactString() {
        val preamble = tmuxProbes().singleClientPreamble("claude-server-abc123", "device-A")
        val expected = "CRD=\"\$HOME/.claude-remote/clients\"; mkdir -p \"\$CRD\" 2>/dev/null; " +
            "CRF=\"\$CRD/device-A-claude-server-abc123\"; CRT=\$(cat \"\$CRF\" 2>/dev/null); " +
            "[ -n \"\$CRT\" ] && tmux list-clients -t '=claude-server-abc123' -F '#{client_tty}' 2>/dev/null " +
            "| grep -qxF -- \"\$CRT\" && tmux detach-client -t \"\$CRT\" 2>/dev/null; " +
            "CRT=\$(tty 2>/dev/null); case \"\$CRT\" in /dev/*) printf '%s' \"\$CRT\" > \"\$CRF\" 2>/dev/null;; esac; "
        assertEquals(expected, preamble)
    }

    @Test
    fun singleClientPreamble_nameWithSingleQuote_escapesConsistently() {
        val preamble = tmuxProbes().singleClientPreamble("claude-server-o'brien", "device-A")
        // tmux target uses the shell-escaped name...
        assertTrue(preamble.contains("-t '=claude-server-o'\\''brien'"))
        // ...but the marker filename sanitizes to filesystem-safe characters
        // (the raw quote can't appear in a path component).
        assertTrue(preamble.contains("CRF=\"\$CRD/device-A-claude-server-o_brien\""))
    }
}
