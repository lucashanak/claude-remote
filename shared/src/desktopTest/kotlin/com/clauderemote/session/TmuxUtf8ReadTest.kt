package com.clauderemote.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-level guard: every tmux command that READS a name or path must pass
 * `-u`.
 *
 * tmux decides whether to emit UTF-8 from the requesting CLIENT's locale. Two of
 * the three environments this app drives tmux from have no usable one:
 *  - SSH **exec** channels (every probe/scan/manifest command) inherit no
 *    locale of their own — on Debian PAM hands them /etc/default/locale, which
 *    is `LANG=C` on the box this was found on.
 *  - the drift/restore **systemd user units** run with no locale at all.
 *
 * A non-UTF-8 client makes tmux run its output through `utf8_sanitize()`, which
 * replaces every non-ASCII character with `_`. Measured on tmux 3.5a:
 *
 *   $ env -i PATH=/usr/bin tmux list-sessions -F '#{session_name}'
 *   claude-server-backendV2-yolo--doporu_en_-kurzy-z-quest_-anal_zy
 *   $ env -i PATH=/usr/bin tmux -u list-sessions -F '#{session_name}'
 *   claude-server-backendV2-yolo--doporučené-kurzy-z-questů-analýzy
 *
 * The mangled string matches no tab and no manifest entry, so it read as an
 * unknown remote session (a duplicate row in the session list), the manifest
 * merge pruned the real session as dead, and the "missing" session was
 * relaunched under the broken name — one live session became two, and the
 * second one's alias was permanently corrupt.
 *
 * WRITING is unaffected: `new-session -s '<utf8>'` stores real UTF-8 and
 * `-t '=<utf8>'` matches it, both verified under `env -i`. So this scan covers
 * the read formats only. `-u` needs no locale to be installed on the server,
 * which is why it is the fix rather than exporting LC_ALL.
 */
class TmuxUtf8ReadTest {

    /** Formats whose value can contain non-ASCII (session aliases, cwd). */
    private val nameBearingFormats = listOf(
        "#{session_name}", "#{pane_current_path}", "#{window_name}", "'#S'",
    )

    private fun kotlinSources(): List<File> =
        listOf("shared/src", "androidApp/src", "wearApp/src", "desktopApp/src")
            .map { File("../$it") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
            .filterNot { it.path.contains("Test/") || it.name.endsWith("Test.kt") }

    @Test
    fun everyTmuxNameReadForcesUtf8() {
        val sources = kotlinSources()
        // Guard the guard: a broken path assumption would make this vacuous.
        assertTrue(
            sources.size > 20,
            "expected to scan the Kotlin sources but found only ${sources.size} files — " +
                "the working-directory assumption in kotlinSources() is probably wrong"
        )

        val offenders = mutableListOf<String>()
        for (file in sources) {
            file.readLines().forEachIndexed { idx, line ->
                if (nameBearingFormats.none { it in line }) return@forEachIndexed
                // Only the tmux invocation that carries the format matters; a line
                // may hold several tmux calls (embedded shell), so require that
                // every `tmux <verb>` on it is the -u form.
                val bareTmux = Regex("""tmux (?!-u\b)[a-z]""").findAll(line).count()
                if (bareTmux > 0) {
                    offenders += "${file.path}:${idx + 1}: ${line.trim()}"
                }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "tmux read without -u — a non-UTF-8 client gets every non-ASCII " +
                "character replaced with '_' (see this test's KDoc):\n" +
                offenders.joinToString("\n")
        )
    }
}
