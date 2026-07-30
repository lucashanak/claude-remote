package com.clauderemote.session

import com.clauderemote.model.ClaudeEffort
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [ClaudeConfig] — pure shell-command builders that launch/restart
 * Claude Code inside tmux over SSH. This file is edited frequently, and a
 * silent quoting or flag regression here breaks session launch for every
 * user, so assertions are on exact strings/substrings, never `isNotEmpty()`.
 */
class ClaudeConfigTest {

    // ======================== buildLaunchCommand: flag ordering ========================

    @Test
    fun buildLaunchCommand_normalModeDefaultModelNoSession_producesBaselineCommand() {
        val cmd = ClaudeConfig.buildLaunchCommand("/home/user/proj", ClaudeMode.NORMAL, ClaudeModel.DEFAULT)
        assertEquals("cd '/home/user/proj' && claude --allow-dangerously-skip-permissions", cmd)
    }

    @Test
    fun buildLaunchCommand_modelWithCliValue_addsModelFlagBeforePermissionFlags() {
        val cmd = ClaudeConfig.buildLaunchCommand("/proj", ClaudeMode.NORMAL, ClaudeModel.OPUS)
        assertEquals("cd '/proj' && claude --model opus --allow-dangerously-skip-permissions", cmd)
    }

    @Test
    fun buildLaunchCommand_localModelWithoutCliValue_usesClaudeLocalBinaryNoModelFlag() {
        // ClaudeModel.LOCAL has cliValue == null on purpose (see Enums.kt) — must
        // NOT emit a "--model null" or similar; the wrapper picks its own default.
        val cmd = ClaudeConfig.buildLaunchCommand("/proj", ClaudeMode.NORMAL, ClaudeModel.LOCAL)
        assertEquals("cd '/proj' && claude-local --allow-dangerously-skip-permissions", cmd)
    }

    @Test
    fun buildLaunchCommand_localModelWithCliValue_usesClaudeLocalWithModelFlag() {
        val cmd = ClaudeConfig.buildLaunchCommand("/proj", ClaudeMode.NORMAL, ClaudeModel.LOCAL_QWEN)
        assertEquals("cd '/proj' && claude-local --model qwen3-coder --allow-dangerously-skip-permissions", cmd)
    }

    @Test
    fun buildLaunchCommand_autoAcceptMode_addsPermissionModeAcceptEdits() {
        val cmd = ClaudeConfig.buildLaunchCommand("/proj", ClaudeMode.AUTO_ACCEPT, ClaudeModel.DEFAULT)
        assertEquals("cd '/proj' && claude --permission-mode acceptEdits --allow-dangerously-skip-permissions", cmd)
    }

    @Test
    fun buildLaunchCommand_autoMode_addsPermissionModeAuto() {
        val cmd = ClaudeConfig.buildLaunchCommand("/proj", ClaudeMode.AUTO, ClaudeModel.DEFAULT)
        assertEquals("cd '/proj' && claude --permission-mode auto --allow-dangerously-skip-permissions", cmd)
    }

    @Test
    fun buildLaunchCommand_planMode_addsNoPermissionModeFlag() {
        val cmd = ClaudeConfig.buildLaunchCommand("/proj", ClaudeMode.PLAN, ClaudeModel.DEFAULT)
        assertEquals("cd '/proj' && claude --allow-dangerously-skip-permissions", cmd)
    }

    @Test
    fun buildLaunchCommand_yoloMode_usesDangerousSkipPermissionsDirectly() {
        // YOLO starts already unlocked: --dangerously-skip-permissions, NOT the
        // --allow- prefixed variant used by every other mode.
        val cmd = ClaudeConfig.buildLaunchCommand("/proj", ClaudeMode.YOLO, ClaudeModel.DEFAULT)
        assertEquals("cd '/proj' && claude --dangerously-skip-permissions", cmd)
        assertTrue("--dangerously-skip-permissions" in cmd)
        assertTrue("--allow-dangerously-skip-permissions" !in cmd)
    }

    @Test
    fun buildLaunchCommand_nonYoloModes_allUseAllowDangerousSkipPermissions() {
        for (mode in listOf(ClaudeMode.NORMAL, ClaudeMode.PLAN, ClaudeMode.AUTO, ClaudeMode.AUTO_ACCEPT)) {
            val cmd = ClaudeConfig.buildLaunchCommand("/proj", mode, ClaudeModel.DEFAULT)
            assertTrue(
                "--allow-dangerously-skip-permissions" in cmd,
                "mode $mode should keep the startup permission gate"
            )
        }
    }

    @Test
    fun buildLaunchCommand_sessionIdWithResumeTrue_appendsResumeFlagNotSessionId() {
        val cmd = ClaudeConfig.buildLaunchCommand(
            "/proj", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, claudeSessionId = "abc-123", resume = true
        )
        assertEquals("cd '/proj' && claude --allow-dangerously-skip-permissions --resume abc-123", cmd)
    }

    @Test
    fun buildLaunchCommand_sessionIdWithResumeFalse_appendsSessionIdFlagNotResume() {
        val cmd = ClaudeConfig.buildLaunchCommand(
            "/proj", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, claudeSessionId = "abc-123", resume = false
        )
        assertEquals("cd '/proj' && claude --allow-dangerously-skip-permissions --session-id abc-123", cmd)
    }

    @Test
    fun buildLaunchCommand_nullSessionId_omitsBothResumeAndSessionIdFlags() {
        // resume=true with a null id must not produce a bare "--resume" with no
        // argument — the flag pair is only emitted when claudeSessionId != null.
        val cmd = ClaudeConfig.buildLaunchCommand(
            "/proj", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, claudeSessionId = null, resume = true
        )
        assertEquals("cd '/proj' && claude --allow-dangerously-skip-permissions", cmd)
    }

    // ======================== shellEscape (via cd prefix) ========================

    @Test
    fun buildLaunchCommand_plainFolder_singleQuotesWholePath() {
        val cmd = ClaudeConfig.buildLaunchCommand("/home/user/my project", ClaudeMode.NORMAL, ClaudeModel.DEFAULT)
        assertTrue(cmd.startsWith("cd '/home/user/my project' && "))
    }

    @Test
    fun buildLaunchCommand_folderWithEmbeddedSingleQuote_escapesWithQuoteBackslashQuoteQuoteIdiom() {
        // Injection-safety lock-in: a literal ' inside a '...' shell string must
        // become '\'' — NOT a bare \' (which does not close/reopen quoting).
        val cmd = ClaudeConfig.buildLaunchCommand("/home/user/it's mine", ClaudeMode.NORMAL, ClaudeModel.DEFAULT)
        assertTrue(cmd.startsWith("cd '/home/user/it'\\''s mine' && "))
    }

    @Test
    fun buildLaunchCommand_folderWithShellMetacharacters_stayLiteralInsideSingleQuotes() {
        // $, backticks, && and newlines have no special meaning inside single
        // quotes, so they must survive completely untouched (no backslashing).
        val malicious = "/tmp/\$(rm -rf ~)`whoami`&&echo pwned\nend"
        val cmd = ClaudeConfig.buildLaunchCommand(malicious, ClaudeMode.NORMAL, ClaudeModel.DEFAULT)
        assertEquals("cd '$malicious' && claude --allow-dangerously-skip-permissions", cmd)
    }

    @Test
    fun buildLaunchCommand_tildeAloneFolder_leftUnquotedForExpansion() {
        // A bare "~" must NOT be quoted, or the shell would treat it as a
        // literal filename instead of expanding to $HOME.
        val cmd = ClaudeConfig.buildLaunchCommand("~", ClaudeMode.NORMAL, ClaudeModel.DEFAULT)
        assertTrue(cmd.startsWith("cd ~ && "))
    }

    @Test
    fun buildLaunchCommand_tildeSlashFolder_tildePrefixUnquotedRemainderQuoted() {
        // "~/x" keeps "~/" bare (for expansion) and single-quotes only the tail.
        val cmd = ClaudeConfig.buildLaunchCommand("~/my project", ClaudeMode.NORMAL, ClaudeModel.DEFAULT)
        assertTrue(cmd.startsWith("cd ~/'my project' && "))
    }

    @Test
    fun buildLaunchCommand_tildeSlashFolderWithEmbeddedQuote_escapesTailCorrectly() {
        val cmd = ClaudeConfig.buildLaunchCommand("~/it's mine", ClaudeMode.NORMAL, ClaudeModel.DEFAULT)
        assertTrue(cmd.startsWith("cd ~/'it'\\''s mine' && "))
    }

    // ======================== buildTmuxLaunchCommand ========================

    @Test
    fun buildTmuxLaunchCommand_sessionNameWithSingleQuote_escapedInKillAndNewSessionTargets() {
        val cmd = ClaudeConfig.buildTmuxLaunchCommand(
            "bob's session", "/proj", ClaudeMode.NORMAL, ClaudeModel.DEFAULT
        )
        assertTrue(
            "tmux kill-session -t '=bob'\\''s session' 2>/dev/null;" in cmd,
            "kill-session target must use the '\\'' escaped session name, with the '=' exact-match prefix inside the quotes"
        )
        assertTrue(
            "tmux new-session -x 200 -y 50 -s 'bob'\\''s session' " in cmd,
            "new-session target must use the '\\'' escaped session name, and must NOT get an '=' prefix (it's a name, not a target)"
        )
    }

    @Test
    fun buildTmuxLaunchCommand_killsExistingSessionBeforeCreatingNewOne() {
        // Prevents `-A` re-attach semantics from sending keystrokes into an
        // already-running program under a reused session name.
        val cmd = ClaudeConfig.buildTmuxLaunchCommand("sess", "/proj", ClaudeMode.NORMAL, ClaudeModel.DEFAULT)
        val killIdx = cmd.indexOf("tmux kill-session -t '=sess'")
        val newIdx = cmd.indexOf("tmux new-session -x 200 -y 50 -s 'sess'")
        assertTrue(killIdx >= 0 && newIdx >= 0 && killIdx < newIdx)
    }

    @Test
    fun buildTmuxLaunchCommand_killSessionTargetStartsWithEqualsForExactMatch() {
        // Locks in the tmux-prefix-match data-loss bug fix: plain `-t 'name'`
        // on kill-session prefix-matches, so killing "proj--cashy" also killed
        // a live "proj--cashy-2" sibling (measured on real tmux 3.5a). `-t
        // '=name'` forces an exact match for a target-session.
        val cmd = ClaudeConfig.buildTmuxLaunchCommand("proj--cashy", "/proj", ClaudeMode.NORMAL, ClaudeModel.DEFAULT)
        assertTrue("tmux kill-session -t '=proj--cashy' " in cmd)
    }

    @Test
    fun buildTmuxLaunchCommand_newSessionNameDoesNotGetEqualsPrefix() {
        // Guard against an over-eager future "make everything consistent" fix:
        // `new-session -s` takes a NAME, not a target, so it must never be
        // prefixed with '='.
        val cmd = ClaudeConfig.buildTmuxLaunchCommand("proj--cashy", "/proj", ClaudeMode.NORMAL, ClaudeModel.DEFAULT)
        assertTrue("tmux new-session -x 200 -y 50 -s 'proj--cashy' " in cmd)
        assertTrue("-s '=proj--cashy'" !in cmd)
    }

    @Test
    fun buildTmuxLaunchCommand_sendsClaudeCommandSingleQuoteWrappedForSendKeys() {
        val cmd = ClaudeConfig.buildTmuxLaunchCommand("sess", "/proj", ClaudeMode.NORMAL, ClaudeModel.DEFAULT)
        val claudeCmd = ClaudeConfig.buildLaunchCommand("/proj", ClaudeMode.NORMAL, ClaudeModel.DEFAULT)
        // claudeCmd itself contains a single-quoted 'cd ...' — sq() must
        // re-escape those embedded quotes when wrapping the whole thing for
        // send-keys, per the '\'' idiom documented at ClaudeConfig.kt:92-97.
        val expectedSendKeys = "send-keys 'cd '\\''/proj'\\'' && claude --allow-dangerously-skip-permissions' Enter"
        assertTrue(claudeCmd == "cd '/proj' && claude --allow-dangerously-skip-permissions")
        assertTrue(cmd.endsWith(expectedSendKeys), "actual tail: ${cmd.takeLast(120)}")
    }

    @Test
    fun buildTmuxLaunchCommand_sameInputsProduceIdenticalOutput() {
        val a = ClaudeConfig.buildTmuxLaunchCommand("sess", "/proj", ClaudeMode.AUTO, ClaudeModel.OPUS, "uuid-1", true)
        val b = ClaudeConfig.buildTmuxLaunchCommand("sess", "/proj", ClaudeMode.AUTO, ClaudeModel.OPUS, "uuid-1", true)
        assertEquals(a, b)
    }

    // ======================== buildRestartCommand ========================

    @Test
    fun buildRestartCommand_cdsBackIntoSessionFolder_notHome() {
        // Locks in commit 035832e ("respawn starts in $HOME" fix): respawn-pane
        // restarts the pane's command in its ORIGINAL start dir, not the cwd
        // claude was running in, so the restart command must explicitly cd
        // back into the session folder before re-launching claude.
        val cmd = ClaudeConfig.buildRestartCommand(
            "sess", "/home/user/proj", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, "uuid-1"
        )
        val homeIdx = cmd.indexOf("\$HOME")
        val folderIdx = cmd.indexOf("/home/user/proj")
        assertTrue(homeIdx >= 0 && folderIdx >= 0, "expected both \$HOME and the folder in: $cmd")
        assertTrue(homeIdx < folderIdx, "must cd \$HOME BEFORE cd-ing back into the folder; actual: $cmd")
    }

    @Test
    fun buildRestartCommand_alwaysUsesResumeFlagNeverSessionId() {
        // buildRestartCommand hardcodes resume = true when calling
        // buildLaunchCommand internally — restarting must always reuse the
        // existing conversation, never mint/force a fresh --session-id.
        val cmd = ClaudeConfig.buildRestartCommand(
            "sess", "/proj", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, "uuid-1"
        )
        assertTrue("--resume uuid-1" in cmd)
        assertTrue("--session-id" !in cmd)
    }

    @Test
    fun buildRestartCommand_respawnsPaneThenSendsLoginShellWithEscapedSessionName() {
        val cmd = ClaudeConfig.buildRestartCommand(
            "bob's session", "/proj", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, "uuid-1"
        )
        // Expected value derived by hand-tracing sq() applied twice (once for
        // the inner `bash -lc` wrap, once for the outer `send-keys` wrap) over
        // a session name that itself contains a single quote — the worst-case
        // nesting this builder produces. The target is now the pane form
        // '=name:' (exact-match '=' plus the trailing ':' pane-target requires).
        assertEquals(
            "tmux respawn-pane -k -t '=bob'\\''s session:' 2>/dev/null; sleep 0.4; " +
                "tmux send-keys -t '=bob'\\''s session:' 'bash -lc '\\''cd \"\$HOME\"; cd " +
                "'\\''\\'\\'''\\''/proj'\\''\\'\\'''\\'' && claude --allow-dangerously-skip-permissions " +
                "--resume uuid-1'\\''' Enter",
            cmd
        )
    }

    @Test
    fun buildRestartCommand_respawnAndSendKeysTargetsUseExactMatchPaneFormWithTrailingColon() {
        // Locks in the tmux-prefix-match data-loss bug fix for the pane-target
        // commands: `respawn-pane`/`send-keys` take a target-PANE, not a
        // target-session. `-t '=name'` alone FAILS OUTRIGHT ("can't find
        // pane") for a pane target — the trailing ':' is required for the
        // command to work at all, and '=' makes it exact-match rather than
        // prefix-match (measured on real tmux 3.5a).
        val cmd = ClaudeConfig.buildRestartCommand(
            "proj--cashy", "/proj", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, "uuid-1"
        )
        assertTrue("tmux respawn-pane -k -t '=proj--cashy:' " in cmd)
        assertTrue("tmux send-keys -t '=proj--cashy:' " in cmd)
    }

    @Test
    fun buildRestartCommand_runsViaLoginShellForPath() {
        // `bash -lc` is required so ~/.profile puts ~/.local/bin (claude) on
        // PATH for the respawned pane's shell.
        val cmd = ClaudeConfig.buildRestartCommand("sess", "/proj", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, "uuid-1")
        assertTrue("bash -lc " in cmd)
    }

    // ======================== model / effort switch commands ========================

    @Test
    fun modelSwitchCommand_fallsBackToDefaultWhenCliValueIsNull() {
        assertEquals("/model default", ClaudeConfig.modelSwitchCommand(ClaudeModel.DEFAULT))
        assertEquals("/model default", ClaudeConfig.modelSwitchCommand(ClaudeModel.LOCAL))
    }

    @Test
    fun modelSwitchCommand_usesCliValueForEveryNamedModel() {
        for (model in ClaudeModel.entries) {
            val expected = "/model ${model.cliValue ?: "default"}"
            assertEquals(expected, ClaudeConfig.modelSwitchCommand(model), "mismatch for $model")
        }
    }

    @Test
    fun effortSwitchCommand_mapsEachEffortLevelToItsCliValue() {
        assertEquals("/effort low", ClaudeConfig.effortSwitchCommand(ClaudeEffort.LOW))
        assertEquals("/effort medium", ClaudeConfig.effortSwitchCommand(ClaudeEffort.MEDIUM))
        assertEquals("/effort high", ClaudeConfig.effortSwitchCommand(ClaudeEffort.HIGH))
        assertEquals("/effort xhigh", ClaudeConfig.effortSwitchCommand(ClaudeEffort.XHIGH))
        assertEquals("/effort max", ClaudeConfig.effortSwitchCommand(ClaudeEffort.MAX))
        // Cover every enum entry, even if new levels get added later.
        for (effort in ClaudeEffort.entries) {
            assertEquals("/effort ${effort.cliValue}", ClaudeConfig.effortSwitchCommand(effort))
        }
    }

    @Test
    fun escapeSequence_returnsTheEscapeConstant() {
        assertEquals(ClaudeConfig.ESCAPE, ClaudeConfig.escapeSequence())
        assertEquals("\u001B", ClaudeConfig.escapeSequence())
    }
}
