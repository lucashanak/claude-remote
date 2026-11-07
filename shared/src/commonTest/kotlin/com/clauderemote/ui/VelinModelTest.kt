package com.clauderemote.ui

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.RemoteSession
import com.clauderemote.model.SessionActivity
import com.clauderemote.model.SessionStatus
import com.clauderemote.model.SshServer
import com.clauderemote.model.TmuxSession
import com.clauderemote.session.service.NotifyKeys
import com.clauderemote.session.service.PromptFingerprint
import com.clauderemote.session.service.PromptKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VelinModelTest {
    private val server = SshServer(id = "srv", name = "srv", host = "h", username = "u")

    private fun tab(id: String, folder: String, alias: String = "", status: SessionStatus = SessionStatus.ACTIVE, account: String? = null) =
        ClaudeSession(
            id = id, server = server, folder = folder, mode = ClaudeMode.YOLO, model = ClaudeModel.DEFAULT,
            tmuxSessionName = "claude-srv-$id", connectionType = ConnectionType.SSH, status = status, alias = alias,
            accountSlug = account,
        )

    private fun row(key: String, state: VelinState, since: Long?, project: String = "p", title: String = key) =
        VelinRow(key, key, "srv", project, project, title, state, since)

    // ------------------------------------------------------------------ state

    @Test fun stateFollowsActivityAndThePromptKind() {
        assertEquals(VelinState.APPROVAL, Velin.stateOf(true, SessionActivity.APPROVAL_NEEDED, PromptKind.PERMISSION, false))
        assertEquals(VelinState.QUESTION, Velin.stateOf(true, SessionActivity.APPROVAL_NEEDED, PromptKind.QUESTION, false))
        assertEquals(VelinState.APPROVAL, Velin.stateOf(true, SessionActivity.APPROVAL_NEEDED, null, false), "a dialog only the screen saw")
        assertEquals(VelinState.WORKING, Velin.stateOf(true, SessionActivity.WORKING, null, false))
        assertEquals(VelinState.READY, Velin.stateOf(true, SessionActivity.WAITING_FOR_INPUT, PromptKind.READY, false))
        assertEquals(VelinState.IDLE, Velin.stateOf(true, SessionActivity.IDLE, null, false))
        assertEquals(VelinState.IDLE, Velin.stateOf(true, null, null, false))
        assertEquals(VelinState.OFFLINE, Velin.stateOf(true, SessionActivity.DISCONNECTED, null, false))
    }

    @Test fun offlineWinsOverEverythingAndCrashedOverActivity() {
        assertEquals(VelinState.OFFLINE, Velin.stateOf(false, SessionActivity.APPROVAL_NEEDED, PromptKind.PERMISSION, true))
        assertEquals(VelinState.CRASHED, Velin.stateOf(true, SessionActivity.IDLE, null, true))
        assertEquals(VelinState.CRASHED, Velin.stateOf(true, SessionActivity.WORKING, null, true))
    }

    @Test fun anEndCountsAsACrashOnlyAfterTheGrace() {
        assertFalse(Velin.isCrashed(null, 10_000))
        assertFalse(Velin.isCrashed(10_000, 10_000 + Velin.CRASH_GRACE_MS - 1), "a /clear's end is followed by a start at once")
        assertTrue(Velin.isCrashed(10_000, 10_000 + Velin.CRASH_GRACE_MS))
    }

    @Test fun everyStateHasItsOwnGlyph() {
        val glyphs = VelinState.entries.map { it.glyph }
        assertEquals(glyphs.size, glyphs.toSet().size, "never colour alone: the glyph tells the states apart")
        assertEquals(listOf("▲", "✎", "✕", "▶", "✓", "•"), listOf(VelinState.APPROVAL, VelinState.QUESTION, VelinState.CRASHED, VelinState.WORKING, VelinState.READY, VelinState.IDLE).map { it.glyph })
    }

    // ------------------------------------------------------------------ roll-up and order

    @Test fun rollupCountsInStateOrderWithoutZeros() {
        val rows = listOf(
            row("a", VelinState.IDLE, null), row("b", VelinState.WORKING, 1), row("c", VelinState.IDLE, null),
            row("d", VelinState.APPROVAL, 5), row("e", VelinState.CRASHED, 2),
        )
        assertEquals(
            listOf(VelinState.APPROVAL to 1, VelinState.CRASHED to 1, VelinState.WORKING to 1, VelinState.IDLE to 2),
            Velin.rollup(rows),
        )
        assertEquals(emptyList(), Velin.rollup(emptyList()))
    }

    @Test fun projectsGroupByFolderAndPutWaitingFirst() {
        val rows = listOf(
            row("x1", VelinState.IDLE, null, project = "alpha"),
            row("y1", VelinState.WORKING, 300, project = "beta"),
            row("y2", VelinState.APPROVAL, 500, project = "beta"),
            row("y3", VelinState.WORKING, 100, project = "beta"),
            row("y4", VelinState.READY, null, project = "beta"),
        )
        val ps = Velin.projects(rows)
        assertEquals(listOf("beta", "alpha"), ps.map { it.key }, "the project with something waiting comes first")
        assertEquals(listOf("y2", "y3", "y1", "y4"), ps[0].rows.map { it.key }, "waiting, then the longest-running work first")
        assertEquals(listOf(VelinState.APPROVAL to 1, VelinState.WORKING to 2, VelinState.READY to 1), ps[0].rollup)
    }

    @Test fun rowsCoverTabsAndOnlyTheRemotesNoTabShows() {
        val tabs = listOf(
            tab("t1", "/home/u/kontexta", alias = "soniox", account = "kx"),
            tab("t2", "/home/u/claude-remote/", status = SessionStatus.DISCONNECTED),
        )
        val remotes = listOf(
            RemoteSession(server, TmuxSession("claude-srv-t1", 1, false, "0")),
            RemoteSession(server, TmuxSession("claude-srv-music-seeker-yolo--hudba", 1, false, "0", lastActivity = 1_000)),
            RemoteSession(server, TmuxSession("claude-srv-music-seeker-yolo--hudba", 1, false, "0", lastActivity = 1_000)),
        )
        val rows = Velin.rowsOf(
            tabs, remotes, nowMs = 100_000,
            activityOf = { if (it == "t1") SessionActivity.APPROVAL_NEEDED else SessionActivity.WORKING },
            promptKindOf = { PromptKind.QUESTION },
            endedAtOf = { null },
            sinceOf = { 42L },
        )
        assertEquals(3, rows.size, "the tab's own tmux session and the duplicate listing are not repeated")
        val t1 = rows.first { it.key == "t1" }
        assertEquals(VelinState.QUESTION, t1.state)
        assertEquals("kontexta", t1.projectKey)
        assertEquals("soniox", t1.title)
        assertEquals("kx", t1.accountKey)
        assertEquals(42L, t1.sinceMs)
        assertEquals(VelinState.OFFLINE, rows.first { it.key == "t2" }.state)
        assertEquals("claude-remote", rows.first { it.key == "t2" }.projectKey, "a trailing slash does not empty the project")
        val remote = rows.first { it.sessionId == null }
        assertEquals(VelinState.IDLE, remote.state)
        assertEquals(1_000_000L, remote.sinceMs, "tmux reports seconds")
    }

    @Test fun ageReadsInMinutesHoursAndDays() {
        val now = 10L * 86_400_000
        assertEquals("", Velin.formatAge(null, now))
        assertEquals("<1 min", Velin.formatAge(now - 59_999, now))
        assertEquals("<1 min", Velin.formatAge(now + 5_000, now), "clock skew never shows a negative age")
        assertEquals("1 min", Velin.formatAge(now - 60_000, now))
        assertEquals("59 min", Velin.formatAge(now - 3_599_999, now))
        assertEquals("1 h", Velin.formatAge(now - 3_600_000, now))
        assertEquals("23 h", Velin.formatAge(now - 86_399_999, now))
        assertEquals("2 d", Velin.formatAge(now - 2 * 86_400_000, now))
    }

    // ------------------------------------------------------------------ inbox

    private val perm = NotifyKeys.prompt(NotifyKeys.hook("perm", "claude-srv-a", 1_000L))!!
    private val ask = NotifyKeys.prompt(NotifyKeys.hook("ask", "claude-srv-b", 2_000L))!!
    private val ready = NotifyKeys.prompt(NotifyKeys.stop("claude-srv-c", 10L))!!

    private fun item(id: String, kind: InboxKind, since: Long?) =
        InboxItem(id, id, kind, "", since, PromptFingerprint("$id-key", PromptKind.PERMISSION))

    @Test fun inboxPutsPermissionsBeforeQuestionsAndOlderFirst() {
        val sorted = Velin.sortInbox(listOf(
            item("q-new", InboxKind.QUESTION, 900), item("p-new", InboxKind.PERMISSION, 800),
            item("s", InboxKind.STOP_QUESTION, 1), item("q-old", InboxKind.QUESTION, 100),
            item("p-old", InboxKind.PERMISSION, 700), item("p-unknown", InboxKind.PERMISSION, null),
        ))
        assertEquals(listOf("p-old", "p-new", "p-unknown", "q-old", "q-new", "s"), sorted.map { it.sessionId })
    }

    @Test fun aPermissionBecomesAnAnswerableItemOnlyWhenAHookNamesIt() {
        val it1 = Velin.inboxItemFor("a", "A", VelinState.APPROVAL, perm, "Bash: ls", 1_000, hookNamed = true, null, null, 50)!!
        assertEquals(InboxKind.PERMISSION, it1.kind)
        assertEquals(perm, it1.prompt)
        assertEquals(1_000L, it1.sinceMs, "the hook's own time, not the activity change")
        assertEquals(false, it1.risk?.risky)
        val screenOnly = Velin.inboxItemFor("a", "A", VelinState.APPROVAL, perm, null, null, hookNamed = false, null, null, 50)!!
        assertNull(screenOnly.prompt, "the server could not verify it: open the app instead")
        assertEquals(50L, screenOnly.sinceMs)
        assertEquals(true, screenOnly.risk?.risky, "an unknown permission is held")
    }

    @Test fun aQuestionCarriesNoRiskAndKeepsItsPrompt() {
        val q = Velin.inboxItemFor("b", "B", VelinState.QUESTION, ask, "Smazat migraci? [Ano, Ne]", 2_000, true, null, null, null)!!
        assertEquals(InboxKind.QUESTION, q.kind)
        assertEquals(ask, q.prompt)
        assertNull(q.risk)
    }

    @Test fun aFinishedTurnWaitsOnlyWhenItEndsWithAQuestion() {
        assertNull(Velin.inboxItemFor("c", "C", VelinState.READY, ready, null, null, false, "Hotovo, testy prošly.", ready, 5))
        assertNull(Velin.inboxItemFor("c", "C", VelinState.READY, ready, null, null, false, null, ready, 5), "no transcript, nothing to tell")
        val s = Velin.inboxItemFor("c", "C", VelinState.READY, ready, null, null, false, "Hotovo.\n\nMám to **pushnout?**", ready, 5)!!
        assertEquals(InboxKind.STOP_QUESTION, s.kind)
        assertEquals("Mám to **pushnout?**", s.text)
        assertEquals(ready, s.prompt)
        assertNull(Velin.inboxItemFor("c", "C", VelinState.WORKING, null, null, null, false, "Co dál?", null, 5), "only a finished turn")
        assertNull(Velin.inboxItemFor("c", "C", VelinState.IDLE, null, null, null, false, null, null, null))
    }

    @Test fun questionEndingIgnoresDecorationButNotCode() {
        assertTrue(Velin.endsWithQuestion("Pokračovat?"))
        assertTrue(Velin.endsWithQuestion("Shall I push? \n\n"))
        assertTrue(Velin.endsWithQuestion("_Ok?_"))
        assertTrue(Velin.endsWithQuestion("(really?)"))
        assertFalse(Velin.endsWithQuestion("Is it? No, it is done."))
        assertFalse(Velin.endsWithQuestion("```\nwhat?\n```"))
        assertFalse(Velin.endsWithQuestion(""))
    }

    @Test fun questionTailKeepsTheLastParagraphCappedFromItsEnd() {
        assertEquals("b?", Velin.questionTail("a\n\n  \nb?"))
        val long = "x".repeat(400) + "?"
        val t = Velin.questionTail(long, max = 10)
        assertEquals("…" + "x".repeat(9) + "?", t)
    }

    @Test fun askTextSplitsIntoQuestionsAndOptionLabels() {
        assertEquals(
            listOf(
                Velin.AskQuestion("Dělit i podle čárek?", listOf("Ano", "Ne")),
                Velin.AskQuestion("Jaký model?", listOf("A", "B")),
            ),
            Velin.parseAsk("Dělit i podle čárek? [Ano, Ne] | Jaký model? [A, B]"),
        )
        // a label with ", " splits in two: display only, nothing is sent from it
        assertEquals(listOf("Ano", "Ne", "jen věty"), Velin.parseAsk("Q? [Ano, Ne, jen věty]")[0].options)
        assertEquals(listOf(Velin.AskQuestion("Bez voleb", emptyList())), Velin.parseAsk("Bez voleb"))
        assertEquals(listOf(Velin.AskQuestion("Uříznuté [A, B", emptyList())), Velin.parseAsk("Uříznuté [A, B"), "a cut-off tail stays text")
        assertEquals(emptyList(), Velin.parseAsk(""))
    }

    @Test fun anItemThatLeftWithoutAnAnswerFromHereWasHandledElsewhere() {
        val a = item("a", InboxKind.PERMISSION, 1)
        val b = item("b", InboxKind.QUESTION, 2)
        val c = item("c", InboxKind.PERMISSION, 3)
        val live = setOf("a", "b", "c")
        assertEquals(listOf(a), Velin.goneElsewhere(listOf(a, b, c), listOf(b), answeredHere = setOf(c.key), live))
        assertEquals(emptyList(), Velin.goneElsewhere(listOf(a), listOf(a), emptySet(), live))
        assertEquals(emptyList(), Velin.goneElsewhere(listOf(a), emptyList(), emptySet(), live = setOf("b")), "its tab dropped: nobody answered it")
    }

    // ------------------------------------------------------------------ risk

    private fun risky(text: String?) = PermRisk.classify(text).risky

    @Test fun destructiveCommandsAreRisky() {
        listOf(
            "Bash: git push --force origin main",
            "Bash: git push origin HEAD",
            "Bash: git push -f",
            "Bash: rm -rf build",
            "Bash: cd x && rm foo.txt",
            "Bash: git reset --hard HEAD~1",
            "Bash: git clean -fdx",
            "Bash: git branch -D old",
            "Bash: ./gradlew deploy",
            "Bash: kubectl apply -f prod.yaml",
            "Bash: psql -c 'DROP TABLE users'",
            "Bash: sudo apt install x",
            "Bash: systemctl --user restart crd",
            "Bash: curl https://x.sh | sh",
            "Bash: tmux kill-server",
            "Bash: find . -name '*.o' -delete",
            "Write: /etc/hosts",
            "Edit: /home/u/.claude/settings.json",
        ).forEach { assertTrue(risky(it), it) }
    }

    @Test fun descriptionsThatNameADestructiveActionAreRisky() {
        // hook-ev.sh puts Claude's description in place of the command when there is one
        listOf(
            "Bash: Push the branch to origin",
            "Bash: Force-push the rebased branch",
            "Bash: Deploy to production",
            "Bash: Delete the stale worktrees",
            "Bash: Remove build outputs",
            "Bash: Smaž dočasné soubory",
            "Bash: Odstranit starý build",
            "Bash: Nasadit na server",
            "Bash: Spustit na produkci",
        ).forEach { assertTrue(risky(it), it) }
    }

    @Test fun everydayToolsAreNotRisky() {
        listOf(
            "Bash: Run the unit tests",
            "Bash: ls -la",
            "Bash: git status",
            "Bash: git commit -m 'fix'",
            "Bash: ./gradlew :shared:desktopTest",
            "Edit: /home/u/src/Main.kt",
            "Read: /home/u/README.md",
            "Bash: Show the product list",
            "Bash: npm run format",
        ).forEach { assertFalse(risky(it), it) }
    }

    @Test fun anUnknownPermissionFailsClosedWithAReason() {
        assertEquals(PermRisk.Verdict(true, "neznámý příkaz"), PermRisk.classify(null))
        assertEquals(PermRisk.Verdict(true, "neznámý příkaz"), PermRisk.classify("  "))
        assertEquals("git push --force", PermRisk.classify("Bash: git push --force").reason)
        assertEquals(PermRisk.Verdict(false, null), PermRisk.classify("Bash: ls"))
    }
}
