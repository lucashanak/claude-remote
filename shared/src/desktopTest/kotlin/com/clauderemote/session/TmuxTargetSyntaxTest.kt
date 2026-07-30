package com.clauderemote.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-level guard against tmux PREFIX-MATCHING targets.
 *
 * tmux resolves `-t name` as exact → **prefix** → fnmatch, so an operation
 * aimed at session `x` silently hits `x-2` when `x` itself doesn't exist.
 * Measured on tmux 3.5a: `kill-session -t 'proj--cashy'` killed the session
 * named `proj--cashy-2`. In this app that means the user's WRONG Claude session
 * gets killed, renamed, scrolled or reported on. `=` forces an exact match.
 *
 * Why a source scan rather than ordinary unit tests: this defect has now been
 * found and fixed FIVE separate times across the codebase (has-session,
 * kill-session, rename-session ×2, copy-mode/send-keys, display-message). Most
 * of those command strings are built inline inside functions that need a live
 * SSH session, so they are not reachable from a unit test — and no test of the
 * existing strings can catch the NEXT one somebody writes. This scan can.
 *
 * Correct forms, all verified against real tmux 3.5a:
 *  - target-SESSION (`has-session`, `kill-session`, `rename-session`,
 *    `list-panes`) → `-t '=name'`
 *  - target-PANE (`send-keys`, `respawn-pane`, `copy-mode`, `display-message`)
 *    → `-t '=name:'`. Note `'=name'` WITHOUT the trailing colon is rejected
 *    outright for a pane target ("can't find pane"), so the colon is required,
 *    not cosmetic.
 */
class TmuxTargetSyntaxTest {

    /** tmux subcommands whose `-t` resolves a session/window/pane by NAME. */
    private val nameTargetedVerbs = listOf(
        "has-session", "kill-session", "rename-session", "new-window",
        "send-keys", "respawn-pane", "copy-mode", "display-message",
        "list-panes", "attach-session", "list-clients", "resize-window",
    )

    /**
     * Lines that legitimately use a plain `-t`, with the reason. Matched as a
     * substring of the offending line. Keep this list SHORT and justified — it
     * is the only thing standing between this test and being switched off.
     */
    private val allowed = listOf(
        // Targets a client TTY / client name, not a session name — `=` is a
        // session-name qualifier and does not apply.
        "detach-client -t \\\"\\\$CRT\\\"",
        "refresh-client -t \\\"\\\$c\\\"",
        // Inside the embedded bash: `$s` is produced by `tmux list-sessions`
        // itself, so it is exact by construction.
        "list-panes -t \"\${'\$'}s\"",
        "display-message -p -t \"\${'\$'}s\"",
        // Literal internal keepalive name in the embedded installer. Changing
        // the script body would require a MARKER version bump, and the script's
        // own comments warn that the resulting reinstall correlates with the
        // tmux-server SIGSEGV. Not worth it for a theoretical `__anchor__*`
        // collision.
        "kill-session -t __anchor__",
    )

    private fun kotlinSources(): List<File> =
        listOf("shared/src", "androidApp/src", "wearApp/src", "desktopApp/src")
            .map { File("../$it") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
            // Test sources legitimately assert on both the correct and the old
            // buggy forms, so they must not be scanned.
            .filterNot { it.path.contains("Test/") || it.name.endsWith("Test.kt") }

    @Test
    fun everyTmuxNameTargetUsesExactMatchSyntax() {
        val sources = kotlinSources()
        // Guard the guard: if the path resolution breaks, an empty file list
        // would make this test pass vacuously forever.
        assertTrue(
            sources.size > 20,
            "expected to scan the Kotlin sources but found only ${sources.size} files — " +
                "the working-directory assumption in kotlinSources() is probably wrong, " +
                "which would make this test pass without checking anything"
        )

        val offenders = mutableListOf<String>()
        for (file in sources) {
            file.readLines().forEachIndexed { idx, line ->
                if (allowed.any { it in line }) return@forEachIndexed
                for (verb in nameTargetedVerbs) {
                    // Match `tmux <verb> ... -t <quote><something-not-=>`.
                    //
                    // Requiring WHITESPACE after `-t` is deliberate: tmux's own
                    // exact-match spelling `-t=name` (used by the embedded bash as
                    // `-t="$VAR"`) puts the `=` BEFORE the quote and is already
                    // correct. An earlier `-t[= ]+` here flagged all five of those
                    // as offenders — a false positive that would have driven someone
                    // to "fix" working code.
                    val re = Regex("""tmux\s+$verb\b[^\n]*?-t\s+(['"])(?!=)""")
                    if (re.containsMatchIn(line)) {
                        offenders += "${file.path}:${idx + 1}  [$verb]  ${line.trim().take(140)}"
                    }
                }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "tmux target(s) without the `=` exact-match prefix — these PREFIX-MATCH and can " +
                "hit the wrong session (verified on tmux 3.5a).\n" +
                "Use `-t '=name'` for session targets, `-t '=name:'` for pane targets " +
                "(send-keys / respawn-pane / copy-mode / display-message).\n" +
                "If a target genuinely isn't a session name (a client tty, for example), add it " +
                "to `allowed` in this test WITH a reason.\n\n" +
                offenders.joinToString("\n")
        )
    }

    @Test
    fun paneTargetedVerbsCarryTheTrailingColon() {
        // `-t '=name'` is silently WRONG for a pane target: tmux rejects it with
        // "can't find pane", so a restart/scroll would fail outright rather than
        // mis-target. Catch an over-eager "consistency" fix that strips the colon.
        val paneVerbs = listOf("send-keys", "respawn-pane", "copy-mode", "display-message")
        val offenders = mutableListOf<String>()
        for (file in kotlinSources()) {
            file.readLines().forEachIndexed { idx, line ->
                if (allowed.any { it in line }) return@forEachIndexed
                for (verb in paneVerbs) {
                    // An `=`-prefixed pane target that ends the quote with no `:`
                    // before it. Skip lines interpolating a pre-built target var.
                    val re = Regex("""tmux\s+$verb\b[^\n]*?-t\s+(['"])=[^'":]*\1""")
                    if (re.containsMatchIn(line)) {
                        offenders += "${file.path}:${idx + 1}  [$verb]  ${line.trim().take(140)}"
                    }
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "pane-targeted tmux verb using `=name` without the required trailing `:` — " +
                "tmux rejects this with \"can't find pane\" (verified on 3.5a):\n" +
                offenders.joinToString("\n")
        )
    }

    @Test
    fun windowScopedSetOptionCarriesTheTrailingColon() {
        // `set-option -w` (and `set-window-option`) target a WINDOW, which —
        // like a pane target — needs the trailing colon: `-t '=name'` without
        // it is REJECTED outright ("no such window", verified live on tmux
        // 3.5a). Unlike the pane-target case above, this one fails SILENTLY
        // when chained with `2>/dev/null`: buildAttachCommand's window-size
        // un-pin shipped for a while as exactly this — a no-op that looked
        // fine because the error was swallowed.
        //
        // Deliberately NOT flagged (these are correct as-is):
        //  - plain `set-option -t '=name'` (no `-w`/`-p`) is a SESSION target,
        //    which correctly has NO colon.
        //  - `set-option -g`/`-s` (global/server scope) take no `-t` at all.
        val offenders = mutableListOf<String>()
        for (file in kotlinSources()) {
            file.readLines().forEachIndexed { idx, line ->
                if (allowed.any { it in line }) return@forEachIndexed
                val isSetWindowOption = Regex("""tmux\s+set-window-option\b""").containsMatchIn(line)
                val isWindowScopedSetOption = isSetWindowOption ||
                    (Regex("""tmux\s+set-option\b""").containsMatchIn(line) &&
                        Regex("""(?<=\s)-w(?=\s)""").containsMatchIn(line))
                if (!isWindowScopedSetOption) return@forEachIndexed
                // Same no-colon-before-closing-quote check as the pane-target
                // test above: a `-t` target of the form `'=something'` with no
                // `:` anywhere before the closing quote.
                val noColonTarget = Regex("""-t\s+(['"])=[^'":]*\1""")
                if (noColonTarget.containsMatchIn(line)) {
                    offenders += "${file.path}:${idx + 1}  [window set-option]  ${line.trim().take(140)}"
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "window-scoped tmux set-option using `=name` without the required trailing `:` — " +
                "tmux rejects this with \"no such window\" (verified on 3.5a), and a trailing " +
                "`2>/dev/null` swallows that failure silently:\n" +
                offenders.joinToString("\n")
        )
    }
}
