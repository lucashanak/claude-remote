package com.clauderemote.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SessionGrouping] decides what a 52-session list shows. Almost every rule
 * here exists to stop a session from disappearing, so the tests are written
 * around that: what must stay visible, not what looks tidy.
 */
class SessionGroupingTest {

    private fun entry(
        folder: String,
        alias: String,
        attention: Boolean = false,
        active: Boolean = false,
        server: String = "srv",
    ) = SessionGrouping.Entry(
        id = "$server/$folder/$alias",
        serverId = server,
        folderKey = folder.lowercase(),
        folderLabel = folder,
        alias = alias,
        searchText = "$folder $alias".lowercase(),
        needsAttention = attention,
        isActive = active,
    )

    /** The real shape from the reported screenshot, trimmed. */
    private fun fleet() = listOf(
        entry("kontexta", "deploy"),
        entry("kontexta", "citace63"),
        entry("kontexta", "migrace"),
        entry("backendV2", "fff"),
        entry("backendV2", "DB PROD"),
        entry("actions", ""),
        entry("iam-notbroke", ""),
    )

    private fun List<SessionGrouping.Row>.headers() =
        filterIsInstance<SessionGrouping.Row.FolderHeader>()

    private fun List<SessionGrouping.Row>.items() =
        filterIsInstance<SessionGrouping.Row.Item>()

    private fun List<SessionGrouping.Row>.aliases() = items().map { it.entry.alias }

    // ---- grouping shape ----------------------------------------------------

    @Test
    fun foldersWithSeveralSessionsGetAHeaderAndSingletonsDoNot() {
        val rows = SessionGrouping.build(fleet(), collapsed = emptySet())
        assertEquals(listOf("backendV2", "kontexta"), rows.headers().map { it.label })
        // The singletons are still there, just without a header of their own.
        assertTrue(rows.items().any { it.entry.folderKey == "actions" })
        assertTrue(rows.items().any { it.entry.folderKey == "iam-notbroke" })
    }

    /**
     * Groups first, then the loose ones under their own header. They used to be
     * interleaved alphabetically, which reads better but makes the sticky
     * header lie: a pinned "backendV2" would sit above an unrelated
     * "iam-notbroke" row, claiming it. Every section owns a header now.
     */
    @Test
    fun groupsComeFirstAndLooseSessionsGetTheirOwnSection() {
        val rows = SessionGrouping.build(fleet(), collapsed = emptySet())
        val shape = rows.map {
            when (it) {
                is SessionGrouping.Row.FolderHeader -> "H:" + it.label.lowercase()
                is SessionGrouping.Row.OtherHeader -> "OTHER"
                is SessionGrouping.Row.Item -> if (it.inGroup) "  in" else "  loose"
                is SessionGrouping.Row.AttentionHeader -> "ATTN"
            }
        }
        assertEquals(
            listOf("H:backendv2", "  in", "  in", "H:kontexta", "  in", "  in", "  in",
                   "OTHER", "  loose", "  loose"),
            shape,
        )
    }

    /** Rows under a header show the alias alone — that is the width win. */
    @Test
    fun groupedRowsAreMarkedSoTheyCanDropTheFolderPrefix() {
        val rows = SessionGrouping.build(fleet(), collapsed = emptySet())
        val grouped = rows.items().filter { it.inGroup }.map { it.entry.folderKey }.distinct()
        assertEquals(listOf("backendv2", "kontexta"), grouped.sorted())
        assertTrue(rows.items().filterNot { it.inGroup }.all { it.entry.folderKey in setOf("actions", "iam-notbroke") })
    }

    @Test
    fun sessionsAreOrderedByFolderThenAlias() {
        val rows = SessionGrouping.build(fleet(), collapsed = emptySet())
        val kontexta = rows.items().filter { it.entry.folderKey == "kontexta" }.map { it.entry.alias }
        assertEquals(listOf("citace63", "deploy", "migrace"), kontexta)
    }

    /**
     * With no groups there is nothing for an "Other" header to distinguish the
     * loose sessions FROM, and no folder header can pin above them and
     * mislabel them — so it would just be two header rows for one session.
     */
    @Test
    fun looseSessionsGetNoHeaderWhenThereAreNoGroupsAtAll() {
        val rows = SessionGrouping.build(
            listOf(entry("actions", ""), entry("iam-notbroke", "")),
            collapsed = emptySet(),
        )
        assertTrue(rows.filterIsInstance<SessionGrouping.Row.OtherHeader>().isEmpty())
        assertEquals(2, rows.items().size)
    }

/**
     * The case the first version of the guard missed: no folder groups at all,
     * but an approval prompt. Without a header the loose sessions render inside
     * "Needs attention" with nothing between them and it, so the section never
     * ends and claims sessions that are not waiting for anything.
     */
    @Test
    fun looseSessionsGetTheirHeaderWhenAnAttentionSectionIsAboveThem() {
        val rows = SessionGrouping.build(
            listOf(entry("a", "calm"), entry("b", "waiting", attention = true)),
            collapsed = emptySet(),
        )
        val shape = rows.map {
            when (it) {
                is SessionGrouping.Row.AttentionHeader -> "ATTN"
                is SessionGrouping.Row.OtherHeader -> "OTHER"
                is SessionGrouping.Row.FolderHeader -> "H"
                is SessionGrouping.Row.Item -> "  " + it.entry.alias
            }
        }
        assertEquals(listOf("ATTN", "  waiting", "OTHER", "  calm"), shape)
    }

        // ---- collapsing: what must survive it -----------------------------------

    @Test
    fun collapsingAFolderHidesItsRowsButKeepsTheHeaderAndTheCount() {
        val key = SessionGrouping.groupKey("srv", "kontexta")
        val rows = SessionGrouping.build(fleet(), collapsed = setOf(key))
        val header = rows.headers().single { it.label == "kontexta" }
        assertTrue(header.collapsed)
        assertEquals(3, header.total, "the count must still tell the truth when collapsed")
        assertTrue(rows.items().none { it.entry.folderKey == "kontexta" })
        // Other folders are untouched.
        assertEquals(2, rows.items().count { it.entry.folderKey == "backendv2" })
    }

    /**
     * THE rule that makes collapsing safe: a session waiting for input is
     * hoisted out of its folder, so collapsing can never swallow it.
     */
    @Test
    fun aSessionNeedingAttentionIsHoistedOutOfACollapsedFolder() {
        val entries = fleet() + entry("kontexta", "waiting", attention = true)
        val key = SessionGrouping.groupKey("srv", "kontexta")
        val rows = SessionGrouping.build(entries, collapsed = setOf(key))

        assertEquals(1, rows.filterIsInstance<SessionGrouping.Row.AttentionHeader>().single().count)
        val hoisted = rows.items().first()
        assertTrue(hoisted.entry.needsAttention)
        assertEquals("waiting", hoisted.entry.alias)
        assertTrue(!hoisted.inGroup, "a hoisted row is not under a folder header")
    }

    /** The count includes hoisted sessions, so the header never under-reports. */
    @Test
    fun aFolderHeaderCountsTheSessionsHoistedOutOfIt() {
        val entries = fleet() + entry("kontexta", "waiting", attention = true)
        val rows = SessionGrouping.build(entries, collapsed = emptySet())
        val header = rows.headers().single { it.label == "kontexta" }
        assertEquals(4, header.total)
        assertEquals(1, header.hoisted)
    }

    /** A folder whose only session was hoisted must not vanish silently. */
    @Test
    fun aFolderLeftEmptyByHoistingStillReportsItsSessions() {
        val entries = listOf(
            entry("solo", "a", attention = true),
            entry("solo", "b", attention = true),
            entry("other", "x"),
            entry("other", "y"),
        )
        val rows = SessionGrouping.build(entries, collapsed = emptySet())
        assertEquals(2, rows.filterIsInstance<SessionGrouping.Row.AttentionHeader>().single().count)
        assertEquals(listOf("other"), rows.headers().map { it.label })
        assertTrue(rows.items().count { it.entry.folderKey == "solo" } == 2, "hoisted, not lost")
    }

    /** The list must never hide the session that is on screen. */
    @Test
    fun theActiveSessionStaysVisibleInsideACollapsedFolder() {
        val entries = fleet() + entry("kontexta", "onscreen", active = true)
        val key = SessionGrouping.groupKey("srv", "kontexta")
        val rows = SessionGrouping.build(entries, collapsed = setOf(key))
        val shown = rows.items().filter { it.entry.folderKey == "kontexta" }
        assertEquals(listOf("onscreen"), shown.map { it.entry.alias })
    }

    @Test
    fun nothingIsCollapsedUnlessAskedFor() {
        val rows = SessionGrouping.build(fleet(), collapsed = emptySet())
        assertTrue(rows.headers().none { it.collapsed })
        assertEquals(fleet().size, rows.items().size, "first run shows everything, as before")
    }

    // ---- filtering ----------------------------------------------------------

    @Test
    fun aQueryMatchesAnythingInTheSearchText() {
        assertEquals(listOf("citace63"), SessionGrouping.build(fleet(), emptySet(), "cita").aliases())
        assertEquals(
            listOf("citace63", "deploy", "migrace"),
            SessionGrouping.build(fleet(), emptySet(), "kontexta").aliases(),
        )
    }

    /**
     * While searching, a collapsed folder must not hide a match — "it matched
     * but I hid it" is never the answer someone typing a filter wants.
     */
    @Test
    fun aQueryIgnoresCollapsedStateAndHoisting() {
        val entries = fleet() + entry("kontexta", "waiting", attention = true)
        val key = SessionGrouping.groupKey("srv", "kontexta")
        val rows = SessionGrouping.build(entries, collapsed = setOf(key), query = "kontexta")
        assertEquals(listOf("citace63", "deploy", "migrace", "waiting"), rows.aliases())
        assertTrue(rows.headers().isEmpty(), "a filtered view is flat")
        assertTrue(rows.filterIsInstance<SessionGrouping.Row.AttentionHeader>().isEmpty())
    }

    /**
     * A caller that filtered the entries itself asks for the flat view without
     * this model filtering a second time on a narrower field set — that
     * mismatch produced an empty panel with no "no matches" message.
     */
    @Test
    fun flatShowsEveryEntryUngroupedWithoutFilteringAgain() {
        val rows = SessionGrouping.build(fleet(), collapsed = emptySet(), flat = true)
        assertEquals(fleet().size, rows.items().size)
        assertTrue(rows.headers().isEmpty())
        assertTrue(rows.items().none { it.inGroup })
    }

    /** A collapsed folder must not hide anything in the flat view either. */
    @Test
    fun flatIgnoresCollapsedFolders() {
        val key = SessionGrouping.groupKey("srv", "kontexta")
        val rows = SessionGrouping.build(fleet(), collapsed = setOf(key), flat = true)
        assertEquals(3, rows.items().count { it.entry.folderKey == "kontexta" })
    }

    @Test
    fun aQueryThatMatchesNothingProducesNoRows() {
        assertTrue(SessionGrouping.build(fleet(), emptySet(), "zzz").isEmpty())
    }

    @Test
    fun theQueryIsCaseInsensitiveAndTrimmed() {
        assertEquals(listOf("DB PROD", "fff"), SessionGrouping.build(fleet(), emptySet(), "  BACKENDV2 ").aliases())
    }

    // ---- persistence hygiene ------------------------------------------------

    @Test
    fun groupKeysAreScopedPerServerSoTwoServersDoNotShareCollapseState() {
        val entries = listOf(
            entry("shared", "a", server = "one"), entry("shared", "b", server = "one"),
            entry("shared", "c", server = "two"), entry("shared", "d", server = "two"),
        )
        val rows = SessionGrouping.build(entries, collapsed = setOf(SessionGrouping.groupKey("one", "shared")))
        val headers = rows.headers()
        assertEquals(2, headers.size)
        assertEquals(1, headers.count { it.collapsed })
    }
}
