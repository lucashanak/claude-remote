package com.clauderemote.session.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the server-side half of "a closed session stays closed": how the
 * generated `drift.sh` and `restore.sh` treat tombstones.
 *
 * THE INCIDENT this locks down (server logs, 2026-08-31): the user closed four
 * sessions inside 40 seconds. drift.sh's mass-tombstone guard — "a human closes
 * sessions ONE AT A TIME, >2 in a tick means a client mass-forgot after an
 * outage" — refused all four, moved the transient `forgotten` file aside, and
 * the SAME tick's self-heal then relaunched all four from the manifest:
 *
 *   [08:31:49Z] drift: REFUSING 4 tombstones at once (...) — moved aside
 *   [08:31:49Z] drift: self-heal — relaunching missing: ...--tests ...--nalezy
 *                                  ...--merge-foundation ...--files5
 *
 * Two of those panes were still alive hours later, with `created=08:31:53`.
 *
 * The fix rests on provenance: `forgotten.d/<name>` markers are written ONLY by
 * an explicit user close, while the app's remote-scan prune (which merely
 * guesses a pane is gone) writes only the transient file. So the count limit
 * must apply to the transient list and NOT to the durable markers. These tests
 * pin that asymmetry, and pin that neither script can relaunch a marked name.
 *
 * Lives in desktopTest: it shells out to bash/jq and writes temp files.
 */
class DriftTombstoneLogicTest {

    private val installer = SessionPersistenceService.INSTALL_RESTORE_COMMAND

    private fun heredoc(delimiter: String): String {
        val start = installer.indexOf("<<'$delimiter'")
        if (start < 0) fail("heredoc marker <<'$delimiter' not found — delimiter renamed?")
        val bodyStart = installer.indexOf('\n', start) + 1
        val end = installer.indexOf("\n$delimiter\n", bodyStart)
        if (end < 0) fail("heredoc terminator $delimiter not found on its own line")
        return installer.substring(bodyStart, end + 1)
    }

    private val drift by lazy { heredoc("DRIFT_EOF") }
    private val restore by lazy { heredoc("RESTORE_EOF") }

    private fun toolAvailable(vararg probe: String): Boolean = try {
        ProcessBuilder(*probe).start().waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private fun bash(script: String, home: File): String {
        val f = File.createTempFile("drift-logic", ".sh")
        try {
            f.writeText(script)
            val pb = ProcessBuilder("bash", f.absolutePath)
            pb.environment()["HOME"] = home.absolutePath
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            return out.trim()
        } finally {
            f.delete()
        }
    }

    private fun tempHome(): File =
        File.createTempFile("drift-home", "").apply { delete(); mkdirs() }

    /** Pull one logical shell statement out of a script by its distinctive prefix. */
    private fun statement(script: String, startsWith: String, endsWith: String, label: String): String {
        val lines = script.lines()
        val from = lines.indexOfFirst { it.trim().startsWith(startsWith) }
        if (from < 0) fail("$label: no line starting with `$startsWith` — renamed or removed?")
        val to = (from until lines.size).firstOrNull { lines[it].trimEnd().endsWith(endsWith) }
            ?: fail("$label: statement starting at line ${from + 1} never ends with `$endsWith`")
        return lines.subList(from, to + 1).joinToString("\n")
    }

    // ---- structure: both scripts must consult the durable markers -----------

    /**
     * The count limit is the whole reason a legitimate multi-session close was
     * thrown away, so the durable set must be unioned in AFTER the guard has
     * had its say. If that order ever flips, four real closes get rejected
     * again.
     */
    @Test
    fun durableMarkersAreUnionedAfterTheMassTombstoneGuard() {
        val guard = drift.indexOf("REFUSING")
        val union = drift.indexOf("--argjson d \"\$DFG\"")
        assertTrue(guard > 0, "mass-tombstone guard (REFUSING) not found in drift.sh")
        assertTrue(union > 0, "durable-set union (--argjson d \"\$DFG\") not found in drift.sh")
        assertTrue(
            union > guard,
            "the durable markers are unioned BEFORE the mass-tombstone guard — the guard would " +
                "clamp real user closes again (that is the 2026-08-31 incident)",
        )
        // Ordering by index is not enough on its own: the union must also sit
        // OUTSIDE the guard's if-branch, or it would only run on the very ticks
        // where the guard fired.
        val guardBranchEnd = drift.indexOf("\n    fi", drift.indexOf("mv -f \"${'$'}FORGOTTEN\""))
        assertTrue(guardBranchEnd > 0, "could not locate the end of the mass-tombstone guard branch")
        assertTrue(
            union > guardBranchEnd,
            "the durable-set union sits INSIDE the guard's if-branch — it would only apply on ticks " +
                "where the guard happened to fire",
        )
    }

    /** Self-heal is the code that actually relaunched the closed panes. */
    @Test
    fun selfHealSkipsDurablyTombstonedNames() {
        val heal = drift.substringAfter("# SELF-HEAL:", "")
        assertTrue(heal.isNotEmpty(), "SELF-HEAL section not found in drift.sh")
        assertTrue(
            heal.contains("\$FORGOTTEN_D/\$n"),
            "self-heal does not consult forgotten.d — a rejected transient batch leaves it free to " +
                "relaunch every session the user just closed",
        )
        assertTrue(heal.contains("\$FORGOTTEN\""), "self-heal no longer consults the transient tombstone file")
    }

    /**
     * The never-blank guard must key on "the tombstones explain every entry
     * that vanished", not on "some tombstone exists" — durable markers stand
     * for up to TOMBSTONE_WINDOW days, and gating on their mere presence would
     * leave the whole-server-outage protection off for a fortnight after any
     * close.
     */
    @Test
    fun neverBlankGuardUsesDropExplainedNotTombstonePresence() {
        assertTrue(drift.contains("DROP_EXPLAINED"), "never-blank guard does not compute DROP_EXPLAINED")
        assertTrue(
            !drift.contains("""[ "${"$"}NEWLEN" -eq 0 ] && [ "${"$"}OLDLEN" -gt 0 ] && [ "${"$"}FGLEN" -eq 0 ]"""),
            "never-blank guard still gates on FGLEN==0, which a lingering durable marker switches off",
        )
    }

    /** Every restore path — including the .bak / highwater.json fallbacks. */
    @Test
    fun restoreScriptGatesEveryRestorePathOnTombstones() {
        assertTrue(restore.contains("is_tombstoned()"), "restore.sh defines no is_tombstoned helper")
        val calls = restore.split("is_tombstoned \"${'$'}TMUX_NAME\"").size - 1
        assertEquals(
            2, calls,
            "expected the tombstone gate in BOTH restore paths (jq + line-parser fallback), found $calls — " +
                "an ungated path resurrects closed sessions at boot from highwater.json, which never shrinks",
        )
    }

    // ---- behaviour: the extracted statements, executed -----------------------

    /**
     * restore.sh's real `is_tombstoned` against a temp HOME: a durable marker
     * inside the window counts, an expired one does not, and the transient file
     * counts too (so a close by an older client is still honoured).
     */
    @Test
    fun isTombstonedHonoursDurableMarkersWithinTheWindowOnly() {
        if (!toolAvailable("bash", "-c", "exit 0")) {
            println("bash not on PATH — skipping isTombstonedHonoursDurableMarkersWithinTheWindowOnly")
            return
        }
        val home = tempHome()
        try {
            val d = File(home, ".claude-remote/forgotten.d").apply { mkdirs() }
            File(d, "claude-fresh").createNewFile()
            File(d, "claude-expired").apply {
                createNewFile()
                setLastModified(System.currentTimeMillis() - 20L * 24 * 3600 * 1000)
            }
            File(home, ".claude-remote/forgotten").writeText("claude-transient\n")

            // The helper as it really ships, plus the vars its body needs.
            val helper = restore.substringAfter("FORGOTTEN=\"\$HOME/.claude-remote/forgotten\"")
                .substringBefore("\n}\n") // through the end of is_tombstoned
            val prelude = "FORGOTTEN=\"\$HOME/.claude-remote/forgotten\"$helper\n}\n"

            fun ask(name: String) = bash(
                prelude + "if is_tombstoned '$name'; then echo YES; else echo NO; fi\n",
                home,
            )
            assertEquals("YES", ask("claude-fresh"), "a marker inside the window must count as closed")
            assertEquals("NO", ask("claude-expired"), "an expired marker must NOT block a reused tmux name")
            assertEquals("YES", ask("claude-transient"), "the transient tombstone file must still be honoured")
            assertEquals("NO", ask("claude-unknown"), "an unmarked session must never read as closed")
        } finally {
            home.deleteRecursively()
        }
    }

    /**
     * The two decisive drift.sh statements, extracted verbatim and executed:
     * with a transient batch rejected (FORGET=[]), four durable markers must
     * still reach FORGET, and DROP_EXPLAINED must separate "user closed
     * everything" from "whole-server outage".
     */
    @Test
    fun rejectedTransientBatchStillHonoursTheFourDurableCloses() {
        if (!toolAvailable("bash", "-c", "command -v jq >/dev/null")) {
            println("jq not on PATH — skipping rejectedTransientBatchStillHonoursTheFourDurableCloses")
            return
        }
        val home = tempHome()
        try {
            val d = File(home, ".claude-remote/forgotten.d").apply { mkdirs() }
            listOf("a", "b", "c", "d").forEach { File(d, "claude-$it").createNewFile() }
            File(d, "claude-stale").apply {
                createNewFile()
                setLastModified(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
            }

            val durableSet = statement(drift, "DFG=\$(find", "2>/dev/null)", "durable set")
            val dropExplained = statement(drift, "DROP_EXPLAINED=\$(jq", "2>/dev/null)", "DROP_EXPLAINED")
            val old = listOf("a", "b", "c", "d")
                .joinToString(",", "[", "]") { """{"tmuxSessionName":"claude-$it"}""" }

            val out = bash(
                """
                set -u
                FORGOTTEN_D="${'$'}HOME/.claude-remote/forgotten.d"
                TOMBSTONE_WINDOW=14
                $durableSet
                # The guard has just rejected the transient batch:
                FORGET="[]"
                DFGLEN=${'$'}(echo "${'$'}DFG" | jq 'length')
                if [ "${'$'}DFGLEN" -gt 0 ]; then
                  FORGET=${'$'}(jq -n --argjson t "${'$'}FORGET" --argjson d "${'$'}DFG" '${'$'}t + ${'$'}d | unique')
                fi
                OLD='$old'
                $dropExplained
                echo "FORGET=${'$'}(echo "${'$'}FORGET" | jq -c .)"
                echo "EXPLAINED=${'$'}DROP_EXPLAINED"
                FORGET='[]'
                $dropExplained
                echo "OUTAGE_EXPLAINED=${'$'}DROP_EXPLAINED"
                """.trimIndent(),
                home,
            )

            assertTrue(
                out.contains("""FORGET=["claude-a","claude-b","claude-c","claude-d"]"""),
                "the four durable closes did not survive a rejected transient batch:\n$out",
            )
            assertTrue(!out.contains("claude-stale"), "an expired marker leaked into the tombstone set:\n$out")
            assertTrue(
                out.contains("EXPLAINED=true"),
                "closing every session must be allowed to empty the manifest:\n$out",
            )
            assertTrue(
                out.contains("OUTAGE_EXPLAINED=false"),
                "with no tombstones the manifest must NEVER be blanked — that lost 23 live sessions once:\n$out",
            )
        } finally {
            home.deleteRecursively()
        }
    }
}
