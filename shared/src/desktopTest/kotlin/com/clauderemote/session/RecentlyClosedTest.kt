package com.clauderemote.session

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.session.service.ClosedRecord
import com.clauderemote.session.service.ClosedRecords
import com.clauderemote.session.service.OpenedMarker
import com.clauderemote.session.service.RestoreResult
import com.clauderemote.storage.PersistedSession
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure rules of "Nedávno zavřené", the restore reactions and the `opened.d` adoption (Jarvis F0b/F0c). */
class RecentlyClosedTest {
    private val sid = "11111111-1111-1111-1111-111111111111"
    private val sid2 = "22222222-2222-2222-2222-222222222222"
    private val own = "app:dev-1"
    private val now = 1_800_000_000L // 2027-01-15T08:00:00Z

    private fun iso(epochS: Long) = java.time.Instant.ofEpochSecond(epochS).toString()

    private fun rec(
        name: String = "claude-box-kontexta--pdf-sloupce",
        at: Long? = now - 600,
        sessionId: String? = sid,
        launch: String? = null,
        cwd: String? = "/home/u/kontexta",
        by: String? = own,
        reason: String? = null,
        title: String? = null,
        account: String? = null,
    ): ClosedRecord = ClosedRecord(
        v = 1, at = at?.let(::iso), tmuxName = name, sessionId = sessionId, launchSessionId = launch, transcript = null,
        cwd = cwd, folder = cwd, account = account, model = null, mode = null, flags = emptyList(), effort = null,
        claudeVersion = null, title = title, pid = null, by = by, durable = true, reason = reason,
    )

    // ---- who closed it ------------------------------------------------------------------------------

    @Test fun byIsReadAgainstThisDevicesOwnInstallId() {
        assertEquals(ClosedBy.ME, RecentlyClosed.closedBy(own, own))
        assertEquals(ClosedBy.OTHER_DEVICE, RecentlyClosed.closedBy("app:phone-9", own))
        assertEquals(ClosedBy.JARVIS, RecentlyClosed.closedBy("jarvis", own))
        assertEquals(ClosedBy.PRUNE, RecentlyClosed.closedBy("prune", own))
        assertEquals(ClosedBy.APP, RecentlyClosed.closedBy("app", own))
        assertEquals(ClosedBy.UNKNOWN, RecentlyClosed.closedBy(null, own))
        assertEquals(ClosedBy.UNKNOWN, RecentlyClosed.closedBy("someone", own))
        // every kind has its own words, the prune says it was not a person
        val labels = ClosedBy.entries.filter { it != ClosedBy.UNKNOWN }.map { RecentlyClosed.closedByLabel(it) }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue("automatika" in RecentlyClosed.closedByLabel(ClosedBy.PRUNE))
    }

    @Test fun theWhoLineCarriesTheReason() {
        assertEquals("zavřel Jarvis: hotovo, úkol #12", RecentlyClosed.closedByLine(rec(by = "jarvis", reason = "hotovo, úkol #12"), own))
        assertEquals("zavřeno tady", RecentlyClosed.closedByLine(rec(), own))
        assertEquals("", RecentlyClosed.closedByLine(rec(by = null), own))
    }

    @Test fun theRowIsNamedByAliasElseByFolder() {
        assertEquals("pdf sloupce", RecentlyClosed.label(rec(), "box"))
        assertEquals("kontexta", RecentlyClosed.label(rec(name = "claude-box-kontexta-yolo"), "box"))
        assertEquals("kontexta", RecentlyClosed.folderLeaf("/home/u/kontexta/"))
        assertNull(RecentlyClosed.folderLeaf(null))
    }

    @Test fun whenIsRelativeAndUsesTheCalendarForYesterday() {
        val z = ZoneOffset.UTC
        assertNull(RecentlyClosed.whenLabel(null, now, z))
        assertEquals("právě teď", RecentlyClosed.whenLabel(now - 30, now, z))
        assertEquals("právě teď", RecentlyClosed.whenLabel(now + 300, now, z), "a server clock ahead is not the future")
        assertEquals("před 12 min", RecentlyClosed.whenLabel(now - 12 * 60, now, z))
        assertEquals("před 3 h", RecentlyClosed.whenLabel(now - 3 * 3600 - 59, now, z))
        // now = 08:00 UTC: 10 h ago is yesterday 22:00
        assertEquals("včera 22:00", RecentlyClosed.whenLabel(now - 10 * 3600, now, z))
        assertEquals("před 3 dny", RecentlyClosed.whenLabel(now - 3 * 86400, now, z))
    }

    @Test fun whatARecordAllows() {
        assertTrue(RecentlyClosed.canResume(rec()))
        assertTrue(RecentlyClosed.canResume(rec(sessionId = null, launch = sid2)), "the launch id is enough")
        assertFalse(RecentlyClosed.canResume(rec(sessionId = null)))
        assertFalse(RecentlyClosed.canResume(rec(sessionId = "not a uuid")))
        assertFalse(RecentlyClosed.canResume(rec(cwd = null)))
        assertTrue(RecentlyClosed.canStartFresh(rec(sessionId = null)))
        assertFalse(RecentlyClosed.canStartFresh(rec(cwd = null)))
    }

    // ---- the rows -----------------------------------------------------------------------------------

    @Test fun mergeKeepsTheNewestCloseOfANameDropsOldAndLiveAndSortsNewestFirst() {
        val a1 = rec(name = "claude-box-a", at = now - 5000)
        val a2 = rec(name = "claude-box-a", at = now - 100)          // the same name closed again later
        val b = rec(name = "claude-box-b", at = now - 2000)
        val old = rec(name = "claude-box-old", at = now - RecentlyClosed.MAX_AGE_S - 1)
        val live = rec(name = "claude-box-live", at = now - 50)      // restored meanwhile: runs again
        val noTime = rec(name = "claude-box-t", at = null)
        val other = rec(name = "claude-box-a", at = now - 300)       // the same name on ANOTHER server
        val rows = RecentlyClosed.merge(
            listOf(("s1" to "box") to listOf(a2, live, b, a1, old, noTime), ("s2" to "two") to listOf(other)),
            live = setOf("s1" to "claude-box-live"),
            nowEpochS = now,
        )
        assertEquals(
            listOf("s1:claude-box-a", "s2:claude-box-a", "s1:claude-box-b", "s1:claude-box-t"),
            rows.map { "${it.serverId}:${it.record.tmuxName}" },
        )
        assertEquals(iso(now - 100), rows.first().record.at)
        assertEquals(2, RecentlyClosed.merge(listOf(("s1" to "box") to listOf(a2, b, noTime)), emptySet(), now, limit = 2).size)
    }

    @Test fun mergeReadsWhatTheParserGives() {
        // the parser hands the file NEWEST FIRST; merge must not depend on that order
        val out = listOf(rec(name = "claude-box-x", at = now - 900), rec(name = "claude-box-y", at = now - 60)).joinToString("\n") {
            """{"v":1,"at":"${it.at}","tmuxName":"${it.tmuxName}","sessionId":"$sid","cwd":"/w","by":"jarvis"}"""
        } + "\n__CR_CLOSED_END__\n"
        val parsed = ClosedRecords.parseRead(out)!!
        val rows = RecentlyClosed.merge(listOf(("s1" to "box") to parsed.asReversed()), emptySet(), now)
        assertEquals(listOf("claude-box-y", "claude-box-x"), rows.map { it.record.tmuxName })
    }

    @Test fun reopenUndoesAPendingCloseFirstElseRestoresTheNewestRestorableRow() {
        val rows = RecentlyClosed.merge(
            listOf(("s1" to "box") to listOf(rec(name = "claude-box-n", at = now - 10, cwd = null), rec(name = "claude-box-r", at = now - 20))),
            emptySet(), now,
        )
        assertEquals(ReopenTarget.Undo("t2"), RecentlyClosed.reopenTarget(listOf("t1", "t2"), rows, own))
        val r = assertIs<ReopenTarget.Restore>(RecentlyClosed.reopenTarget(emptyList(), rows, own))
        assertEquals("claude-box-r", r.entry.record.tmuxName, "a row that cannot be restored is skipped")
        assertEquals(ReopenTarget.Nothing, RecentlyClosed.reopenTarget(emptyList(), emptyList(), own))
    }

    /** The shortcut reopens what THIS device closed; another device's or Jarvis's closes come from the list only. */
    @Test fun reopenTakesOnlyThisDevicesOwnCloses() {
        val rows = RecentlyClosed.merge(
            listOf(("s1" to "box") to listOf(
                rec(name = "claude-box-j", at = now - 5, by = "jarvis"),
                rec(name = "claude-box-p", at = now - 6, by = "app:phone-9"),
                rec(name = "claude-box-mine", at = now - 30),
            )),
            emptySet(), now,
        )
        assertEquals("claude-box-j", rows.first().record.tmuxName, "the list still shows every close, newest first")
        val r = assertIs<ReopenTarget.Restore>(RecentlyClosed.reopenTarget(emptyList(), rows, own))
        assertEquals("claude-box-mine", r.entry.record.tmuxName)
        assertEquals(ReopenTarget.Nothing, RecentlyClosed.reopenTarget(emptyList(), rows.filter { it.record.by != own }, own))
    }

    /**
     * An empty record (written by an older build over a pane already gone) must not shadow the restorable one of the
     * same name before it; with no restorable one the newest is shown.
     */
    @Test fun mergePrefersTheRestorableRecordOfAName() {
        val real = rec(name = "claude-box-a", at = now - 300)
        val empty = rec(name = "claude-box-a", at = now - 10, sessionId = null, cwd = null, by = "prune")
        val rows = RecentlyClosed.merge(listOf(("s1" to "box") to listOf(empty, real)), emptySet(), now)
        assertEquals(listOf(real), rows.map { it.record })
        val onlyEmpty = RecentlyClosed.merge(listOf(("s1" to "box") to listOf(empty)), emptySet(), now)
        assertEquals(listOf(empty), onlyEmpty.map { it.record })
        // the order of the section follows the record shown
        val b = rec(name = "claude-box-b", at = now - 100)
        assertEquals(listOf("claude-box-b", "claude-box-a"),
            RecentlyClosed.merge(listOf(("s1" to "box") to listOf(empty, b, real)), emptySet(), now).map { it.record.tmuxName })
    }

    // ---- every restore result has its reaction ------------------------------------------------------

    @Test fun everyRestoreResultMapsToAReaction() {
        val r = rec()
        fun act(x: RestoreResult) = RecentlyClosed.actionFor(x, r, "box")
        assertEquals(RestoreAction.OpenTab(false), act(RestoreResult.Restored(12, sid2, true)))
        assertEquals(RestoreAction.OpenTab(false), act(RestoreResult.Restored(null, null, false)))
        assertEquals(RestoreAction.OpenTab(true), act(RestoreResult.LoginRequired))
        assertEquals(RestoreAction.OfferName("claude-box-kontexta--pdf-sloupce-2"), act(RestoreResult.Exists("claude-box-kontexta--pdf-sloupce-2")))
        assertIs<RestoreAction.Message>(act(RestoreResult.Exists(null)))
        assertEquals(RestoreAction.GoTo("claude-server-other"), act(RestoreResult.RunningElsewhere("claude-server-other")))
        // not one of ours: no "Přejít na ni" (a tab there would push it into the manifest, its close would kill it)
        assertEquals(RestoreAction.GoTo(null), act(RestoreResult.RunningElsewhere("claude-box-other")))
        assertEquals(RestoreAction.GoTo(null), act(RestoreResult.RunningElsewhere(null)))
        assertEquals(RestoreAction.OfferCandidate(sid2), act(RestoreResult.NoTranscript(sid2)))
        assertEquals(RestoreAction.OfferCandidate(null), act(RestoreResult.NoTranscript(null)))
        val self = assertIs<RestoreAction.Message>(act(RestoreResult.PendingSelfHeal))
        assertTrue("minutu" in self.text, self.text)
        val out = assertIs<RestoreAction.Message>(act(RestoreResult.LoggedOut("lukas-kontexta")))
        assertTrue("lukas-kontexta" in out.text && "Účtech" in out.text, out.text)
        assertTrue("socket guard 2" in assertIs<RestoreAction.Message>(act(RestoreResult.Refused("socket guard 2"))).text)
        assertTrue("rc=1" in assertIs<RestoreAction.Message>(act(RestoreResult.Failed("rc=1"))).text)
        val unknown = assertIs<RestoreAction.Message>(act(RestoreResult.Unknown))
        assertTrue("možná" in unknown.text, "an unknown result must not read as a failure: ${unknown.text}")
    }

    // ---- adoption of opened.d -----------------------------------------------------------------------

    private fun marker(name: String, mtime: Long = now - 60, by: String? = "jarvis") =
        OpenedMarker(name, mtime, by, null, "/w", null, sid, null)

    private fun entry(name: String) = PersistedSession(
        id = "remote-$name", serverId = "s1", folder = "/home/u/w", mode = ClaudeMode.YOLO, model = ClaudeModel.OPUS,
        tmuxSessionName = name, claudeSessionId = sid2, accountSlug = "acc",
    )

    private fun select(
        markers: List<OpenedMarker>?,
        live: Set<String>? = setOf("claude-a", "claude-b", "claude-c"),
        manifest: List<PersistedSession>? = listOf(entry("claude-a"), entry("claude-b")),
        tomb: Set<String>? = emptySet(),
        own: Set<String> = emptySet(),
        hidden: HiddenAdoptions = HiddenAdoptions.EMPTY,
    ) = OpenedAdoption.select("s1", markers, live, manifest, tomb, own, hidden, now)

    @Test fun adoptionNeedsEverythingKnown() {
        val m = listOf(marker("claude-a"))
        assertEquals(1, select(m).size)
        assertTrue(select(null).isEmpty())
        assertTrue(select(m, live = null).isEmpty())
        assertTrue(select(m, manifest = null).isEmpty())
        assertTrue(select(m, tomb = null).isEmpty())
    }

    @Test fun adoptionTakesYoungLiveForeignMarkersAndTheManifestEntryAsItIs() {
        val picks = select(listOf(
            marker("claude-a"),
            marker("claude-a", mtime = now - 30),                    // a duplicate line: one tab
            marker("claude-b", mtime = now - OpenedAdoption.MAX_AGE_S), // 24 h old
            marker("claude-c"),                                       // live, but drift has not written it yet
            marker("claude-gone"),                                    // not live
        ))
        assertEquals(listOf("claude-a"), picks.map { it.marker.tmuxName })
        assertEquals(entry("claude-a"), picks.single().entry, "mode, model, account and id come from the manifest")
        assertTrue(select(listOf(marker("claude-a")), own = setOf("claude-a")).isEmpty(), "already a tab (or a restore of this device)")
        assertTrue(select(listOf(marker("claude-a")), tomb = setOf("claude-a")).isEmpty(), "being closed")
    }

    @Test fun aHideWinsOverTheMarkerItSawButNotOverALaterOne() {
        val hidden = HiddenAdoptions.EMPTY.with("s1", "claude-a", HiddenAdoptions.stamp(now, now - 60))
        assertTrue(select(listOf(marker("claude-a", mtime = now - 60)), hidden = hidden).isEmpty())
        assertTrue(select(listOf(marker("claude-a", mtime = now)), hidden = hidden).isEmpty(), "the same second")
        assertEquals(1, select(listOf(marker("claude-a", mtime = now + 1)), hidden = hidden).size, "a new open/restore is a new event")
        // another server's hide of the same name does not count
        assertEquals(1, OpenedAdoption.select("s2", listOf(marker("claude-a")), setOf("claude-a"),
            listOf(entry("claude-a")), emptySet(), emptySet(), hidden, now).size)
    }

    @Test fun aHideStampsTheLaterClockSoAServerAheadCannotBringTheTabBack() {
        assertEquals(now + 120, HiddenAdoptions.stamp(now, now + 120))
        assertEquals(now, HiddenAdoptions.stamp(now, now - 120))
        assertEquals(now, HiddenAdoptions.stamp(now, null))
    }

    @Test fun theHideListSurvivesItsOwnSerializationAndForgetsOldEntries() {
        val h = HiddenAdoptions.EMPTY.with("s1", "claude-a", now).with("s1", "claude-a", now - 100).with("s2", "claude-b", now - 9 * 86400)
        val back = HiddenAdoptions.parse(h.serialize() + "\ngarbage\n\ts\t1\ns3\tx\tnope\n")
        assertEquals(2, back.size)
        assertTrue(back.isSuppressed("s1", "claude-a", now), "with() keeps the later stamp")
        assertTrue(back.isSuppressed("s2", "claude-b", now - 9 * 86400))
        val pruned = back.pruned(now)
        assertEquals(1, pruned.size)
        assertFalse(pruned.isSuppressed("s2", "claude-b", now - 9 * 86400))
        assertEquals(0, HiddenAdoptions.parse(null).size)
    }

    @Test fun theBadgeSaysWhereTheTabCameFrom() {
        assertEquals("od Jarvise", OpenedAdoption.badge("jarvis"))
        assertEquals("obnoveno", OpenedAdoption.badge("restore"))
        assertEquals("přidáno", OpenedAdoption.badge(null))
    }
}
