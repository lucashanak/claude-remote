package com.clauderemote.session.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Jarvis F3: what the Velín reads from Jarvis' CLI and the commands it sends. */
class JarvisDataTest {
    // the shape of `cr-task list --all --json` (tools/jarvis/cr_task.py: fold + status)
    private val tasksJson = """
        [
         {"id": "t-1", "title": "Segmentace TTS", "to": "claude-server-kx--soniox", "ac": "test zelený", "state": "open",
          "created": 3000, "by": "jarvis", "closedAt": null, "note": null, "status": ["čeká na tebe", "soniox čeká na povolení"]},
         {"id": "t-2", "title": "Oprava citací", "to": null, "ac": null, "state": "done", "created": 1000, "by": "lukas",
          "closedAt": 2000, "note": "ok", "status": ["hotovo", null]},
         {"id": "t-3", "title": "Timeout", "to": "claude-server-kx--extrakce", "state": "open", "created": 2000,
          "status": ["běží", null], "future": {"x": 1}},
         {"id": "t-4", "title": "Nový", "state": "open", "created": 4000, "status": ["něco nového", null]},
         {"id": "t-5", "title": "Bez statusu", "state": "failed", "created": 500},
         {"title": "bez id"},
         "garbage"
        ]
    """.trimIndent()

    @Test fun tasksParseTolerantly() {
        val ts = JarvisData.parseTasks(tasksJson)!!
        assertEquals(listOf("t-1", "t-2", "t-3", "t-4", "t-5"), ts.map { it.id })
        val t1 = ts[0]
        assertEquals("čeká na tebe", t1.status)
        assertEquals("soniox čeká na povolení", t1.statusDetail)
        assertEquals("claude-server-kx--soniox", t1.to)
        assertEquals(3000, t1.createdMs)
        assertNull(ts[1].to)
        assertEquals(2000, ts[1].closedMs)
        assertEquals("selhalo", ts[4].status, "no status array: the ledger state decides")
    }

    @Test fun unreadableAnswersAreNullNotEmpty() {
        assertNull(JarvisData.parseTasks(null))
        assertNull(JarvisData.parseTasks(""))
        assertNull(JarvisData.parseTasks("bash: cr-task: command not found"))
        assertNull(JarvisData.parseTasks("""{"id": "t-1"}"""))
        assertNull(JarvisData.parseTasks("""[{"id": "t-1", """)) // cut off
        assertEquals(emptyList(), JarvisData.parseTasks("[]"))
    }

    @Test fun groupsFollowTheUiOrderUnknownLastNewestFirst() {
        val ts = JarvisData.parseTasks(tasksJson)!! +
            JarvisTask("t-9", "x", null, null, "open", "čeká na tebe", null, 9000, null, null, null)
        val g = JarvisData.groupTasks(ts)
        assertEquals(listOf("čeká na tebe", "běží", "selhalo", "hotovo", "něco nového"), g.map { it.first })
        assertEquals(listOf("t-9", "t-1"), g[0].second.map { it.id })
    }

    @Test fun watchesParseWithAReadableWhat() {
        val ws = JarvisData.parseWatches(
            """[{"id":"w-1","kind":"stop","target":"claude-server-kx--a","state":"open","created":5},
               {"id":"w-2","kind":"usage","acctName":"nek","below":40,"state":"open"},
               {"id":"w-3","kind":"ci","ref":"main","state":"fired"}]""",
        )!!
        assertEquals(listOf("claude-server-kx--a doběhne", "limit nek pod 40 %", "CI main"), ws.map { it.what })
        assertEquals("fired", ws[2].state)
    }

    @Test fun onlyAProposalWithItsFullTextShownWholeMayBeSent() {
        val json = """{"messages": [
            {"id": "m-1", "to": "x", "subject": "jen předmět", "state": "proposed", "created": 1},
            {"id": "m-2", "to": "x", "subject": "s", "text": "celé", "state": "proposed", "created": 2}]}"""
        val (subjectOnly, full) = JarvisData.parseProposals(json)!!
        assertFalse(subjectOnly.fullText)
        assertTrue(full.fullText)
        // MED6: an approval sends the whole message; a subject is not the whole message
        assertFalse(JarvisData.canApprove(subjectOnly, seenWhole = true))
        assertFalse(JarvisData.canApprove(full, seenWhole = false))
        assertTrue(JarvisData.canApprove(full, seenWhole = true))
    }

    @Test fun proposalsAreOnlyTheProposedMessagesOldestFirstTextPreferred() {
        val json = """
            {"off": false, "messages": [
              {"id": "m-2", "to": "claude-server-kx--b", "subject": "krátce", "text": "Celé znění zprávy", "state": "proposed", "created": 20},
              {"id": "m-1", "to": "claude-server-kx--a", "subject": "jen předmět", "state": "proposed", "created": 10, "task": "t-1"},
              {"id": "m-3", "to": "claude-server-kx--c", "subject": "už ve frontě", "state": "queued", "created": 5},
              {"id": "bad id", "to": "x", "subject": "x", "state": "proposed"}
            ]}
        """.trimIndent()
        val ps = JarvisData.parseProposals(json)!!
        assertEquals(listOf("m-1", "m-2"), ps.map { it.id })
        assertEquals("jen předmět", ps[0].text)
        assertEquals("t-1", ps[0].task)
        assertEquals("Celé znění zprávy", ps[1].text)
        assertFalse(JarvisData.mailOff(json))
        assertTrue(JarvisData.mailOff("""{"off": true, "messages": []}"""))
        assertNull(JarvisData.parseProposals("[]"))
        assertNull(JarvisData.parseProposals("""{"off": false}"""))
    }

    // ---- F4: every proposal through cr-jarvis ---------------------------------------------------------------------

    // the F4 contract: `cr-jarvis proposals --json` = [{id, kind, target, title, detail, created, expires}]
    private val jarvisProposalsJson = """
        [
         {"id": "x-20261108T101500Z-ab12", "kind": "close", "target": "claude-server-kx-yolo--soniox",
          "title": "Zavřít soniox: hotovo, čistý strom.", "detail": "INFO: nečinná 3 dny\nVAROVÁNÍ: 2 necommitnuté soubory",
          "created": 30, "expires": 630},
         {"id": "m-20261108-0001", "kind": "mail", "target": "claude-server-kx--a", "title": "Zpráva pro a",
          "detail": "Celé znění", "created": 10, "expires": 99999},
         {"id": "x-20261108T101600Z-cd34", "kind": "open", "target": "/home/u/kontexta", "title": "Otevřít kontexta",
          "detail": "účet nekrachni (výchozí podle politiky)", "created": 40, "expires": 640},
         {"id": "x-20261108T101700Z-ef56", "kind": "restore", "target": "claude-server-kx--pdf", "title": "Obnovit pdf",
          "created": 50},
         {"id": "x-1", "kind": "reboot", "target": "box", "title": "?", "detail": "?"},
         {"id": "x-2; rm -rf ~", "kind": "close", "target": "y", "detail": "d"},
         {"id": "m-9", "kind": "close", "target": "y", "detail": "a mail id is not an action id"},
         {"id": "x-3", "kind": "close", "detail": "no target"},
         "garbage"
        ]
    """.trimIndent()

    @Test fun jarvisProposalsMapEveryKnownKindAndDropTheRest() {
        val ps = JarvisData.parseJarvisProposals(jarvisProposalsJson)!!
        assertEquals(listOf("m-20261108-0001", "x-20261108T101500Z-ab12", "x-20261108T101600Z-cd34", "x-20261108T101700Z-ef56"), ps.map { it.id })
        assertEquals(
            listOf(JarvisProposalKind.MAIL, JarvisProposalKind.CLOSE, JarvisProposalKind.OPEN, JarvisProposalKind.RESTORE),
            ps.map { it.kind },
        )
        assertTrue(ps.all { it.via == JarvisProposalSource.JARVIS })
        val close = ps[1]
        assertEquals("claude-server-kx-yolo--soniox", close.to)
        assertEquals("Zavřít soniox: hotovo, čistý strom.", close.title)
        assertEquals("INFO: nečinná 3 dny\nVAROVÁNÍ: 2 necommitnuté soubory", close.text)
        assertEquals(630, close.expiresMs)
        assertTrue(close.fullText)
        // no detail: the title is shown, but it is not the whole of what an approval does
        val restore = ps[3]
        assertFalse(restore.fullText)
        assertEquals("Obnovit pdf", restore.text)
        assertNull(restore.expiresMs)
        assertFalse(JarvisData.canApprove(restore, seenWhole = true))
        assertNull(JarvisData.parseJarvisProposals("usage: cr-jarvis {mode,status} ..."))
        assertNull(JarvisData.parseJarvisProposals("""{"messages": []}"""))
        assertEquals(emptyList(), JarvisData.parseJarvisProposals("[]"))
    }

    @Test fun oneExecCarriesBothListsAndAnOlderServerFallsBackToMail() {
        val mail = """{"off": true, "messages": [{"id": "m-1", "to": "x", "subject": "s", "text": "t", "state": "proposed"}]}"""
        // new server: cr-jarvis answers, the mail half only says whether mail is off
        val both = "[{\"id\": \"x-1\", \"kind\": \"close\", \"target\": \"t\", \"detail\": \"d\"}]\n__cr_mail__\n$mail"
        assertEquals(listOf("x-1"), JarvisData.parseCombinedProposals(both)!!.map { it.id })
        assertTrue(JarvisData.mailOff(JarvisData.mailHalf(both)))
        // old server: no `proposals` (usage on stderr, nothing on stdout) → the F3 mail list, decided by cr-mail
        val old = "\n__cr_mail__\n$mail"
        val ps = JarvisData.parseCombinedProposals(old)!!
        assertEquals(listOf("m-1"), ps.map { it.id })
        assertEquals(JarvisProposalSource.MAIL, ps[0].via)
        assertTrue(JarvisData.mailOff(JarvisData.mailHalf(old)))
        assertNull(JarvisData.parseCombinedProposals("\n__cr_mail__\n"))
        assertNull(JarvisData.parseCombinedProposals(null))
        assertTrue(JarvisData.PROPOSALS.startsWith("~/.local/bin/cr-jarvis proposals --json"))
        assertTrue(JarvisData.PROPOSALS.contains("; ~/.local/bin/cr-mail status --json"))
    }

    @Test fun decisionGoesThroughTheCliThatListedIt() {
        val close = JarvisData.parseJarvisProposals(jarvisProposalsJson)!!.first { it.kind == JarvisProposalKind.CLOSE }
        assertEquals(
            "~/.local/bin/cr-jarvis approve x-20261108T101500Z-ab12 --by app:android-1 2>&1; echo __cr_rc=\$?",
            JarvisData.decideProposal(close, true, "android-1"),
        )
        assertTrue(JarvisData.decideProposal(close, false, "d").contains("cr-jarvis reject x-20261108T101500Z-ab12 --by app:d"))
        // mail listed by cr-jarvis is decided by cr-jarvis too (it hands it on to cr-mail itself)
        val mailViaJarvis = JarvisData.parseJarvisProposals(jarvisProposalsJson)!!.first { it.kind == JarvisProposalKind.MAIL }
        assertTrue(JarvisData.decideProposal(mailViaJarvis, true, "d").contains("cr-jarvis approve m-20261108-0001"))
        // the F3 fallback keeps cr-mail
        val fallback = JarvisData.parseProposals("""{"messages": [{"id": "m-1", "to": "x", "text": "t", "state": "proposed"}]}""")!![0]
        assertTrue(JarvisData.decideProposal(fallback, true, "d").contains("cr-mail approve m-1 --by app:d"))
        assertFailsWith<IllegalArgumentException> { JarvisData.decideProposal(close.copy(id = "x-1 \$(id)"), true, "d") }
        assertFailsWith<IllegalArgumentException> { JarvisData.decideProposal(close.copy(id = "t-1"), true, "d") }
        assertFailsWith<IllegalArgumentException> { JarvisData.decideProposal(close, true, "a;b") }
        // opening and restoring wait for claude to start; a close or a reject answers at once
        val open = close.copy(kind = JarvisProposalKind.OPEN)
        assertEquals(35_000L, JarvisData.decideTimeoutMs(open, true))
        assertEquals(35_000L, JarvisData.decideTimeoutMs(close.copy(kind = JarvisProposalKind.RESTORE), true))
        assertEquals(10_000L, JarvisData.decideTimeoutMs(open, false))
        assertEquals(10_000L, JarvisData.decideTimeoutMs(close, true))
    }

    @Test fun aLapsedProposalIsNotOfferedAndTheCardWordsEachKind() {
        val ps = JarvisData.parseJarvisProposals(jarvisProposalsJson)!!
        val close = ps.first { it.kind == JarvisProposalKind.CLOSE }
        assertTrue(JarvisData.canApprove(close, seenWhole = true, nowMs = 629))
        assertFalse(JarvisData.canApprove(close, seenWhole = true, nowMs = 630))
        assertFalse(JarvisData.canApprove(close, seenWhole = false, nowMs = 0))
        assertTrue(JarvisData.canApprove(close, seenWhole = true), "no clock, no expiry judged: the server decides")
        assertEquals("Návrh: zavřít soniox", JarvisData.heading(close))
        assertEquals("Návrh: otevřít kontexta", JarvisData.heading(ps.first { it.kind == JarvisProposalKind.OPEN }))
        assertEquals("Návrh: obnovit pdf", JarvisData.heading(ps.first { it.kind == JarvisProposalKind.RESTORE }))
        assertEquals("Návrh zprávy → a", JarvisData.heading(ps.first { it.kind == JarvisProposalKind.MAIL }))
        assertEquals("Provést", JarvisData.approveLabel(close))
        assertEquals("Poslat", JarvisData.approveLabel(ps.first { it.kind == JarvisProposalKind.MAIL }))
        assertEquals("Zavřel jsem soniox.", JarvisData.approvedNotice(close, "Zavřel jsem soniox."))
        assertEquals("Provedeno: zavřít soniox", JarvisData.approvedNotice(close, "Hotovo"))
        assertEquals("Odesláno do fronty: a", JarvisData.approvedNotice(ps.first { it.kind == JarvisProposalKind.MAIL }, ""))
    }

    @Test fun expiryIsJudgedByTheServersClock() {
        // LOW-2: `expires` is server time; a phone 5 min ahead must not see a live proposal as lapsed
        val close = JarvisData.parseJarvisProposals(jarvisProposalsJson)!!.first { it.kind == JarvisProposalKind.CLOSE }
        val ahead = 300_000L
        assertFalse(JarvisData.expired(close, nowMs = 629 + ahead, skewMs = ahead))
        assertTrue(JarvisData.canApprove(close, seenWhole = true, nowMs = 629 + ahead, skewMs = ahead))
        assertTrue(JarvisData.expired(close, nowMs = 630 + ahead, skewMs = ahead))
        // a phone behind: lapsed on the server although its own clock says not yet
        assertTrue(JarvisData.expired(close, nowMs = 640 - 60_000, skewMs = -60_000))
        assertFalse(JarvisData.expired(close, nowMs = null, skewMs = 0), "no server time, not judged")
        // the read carries the server's clock after the mail half; the halves still split
        val mail = """{"off": true, "messages": []}"""
        val out = "[]\n__cr_mail__\n$mail\n__cr_now__ 1700000000123\n"
        assertEquals(1_700_000_000_123L, JarvisData.serverNowMs(out))
        assertEquals(5_000L, JarvisData.skewMs(out, 1_700_000_005_123L))
        assertTrue(JarvisData.mailOff(JarvisData.mailHalf(out)))
        assertEquals(emptyList(), JarvisData.parseCombinedProposals(out))
        assertNull(JarvisData.serverNowMs("[]\n__cr_mail__\n$mail\n__cr_now__ 1700000000N\n"), "a date without %3N")
        assertNull(JarvisData.skewMs("[]\n__cr_mail__\n$mail", 1))
        assertTrue(JarvisData.PROPOSALS.endsWith("printf '\\n__cr_now__ %s\\n' \"\$(date +%s%3N)\""))
    }

    @Test fun modeParsesAndUnknownIsNormal() {
        assertEquals(JarvisMode(JarvisMode.Kind.NORMAL), JarvisData.parseMode("normal\n"))
        assertEquals(JarvisMode(JarvisMode.Kind.BLOCKING), JarvisData.parseMode("blocking"))
        assertEquals(JarvisMode(JarvisMode.Kind.DND, null), JarvisData.parseMode("dnd"))
        assertEquals(JarvisMode(JarvisMode.Kind.DND, 1234), JarvisData.parseMode("dnd:1234"))
        assertEquals(JarvisMode(JarvisMode.Kind.NORMAL), JarvisData.parseMode(""))
        assertEquals(JarvisMode(JarvisMode.Kind.NORMAL), JarvisData.parseMode(null))
        assertEquals(JarvisMode(JarvisMode.Kind.NORMAL), JarvisData.parseMode("party"))
        assertEquals(JarvisMode(JarvisMode.Kind.NORMAL), JarvisData.effectiveMode(JarvisMode(JarvisMode.Kind.DND, 100), 100))
        assertEquals(JarvisMode(JarvisMode.Kind.DND, 101), JarvisData.effectiveMode(JarvisMode(JarvisMode.Kind.DND, 101), 100))
    }

    @Test fun decideCommandCarriesTheDeviceAndRefusesAnythingElse() {
        assertEquals(
            "~/.local/bin/cr-mail approve m-20261108-ab12 --by app:android-1a2b3c4d 2>&1; echo __cr_rc=\$?",
            JarvisData.decide("m-20261108-ab12", true, "android-1a2b3c4d"),
        )
        assertTrue(JarvisData.decide("m-1", false, "desktop-x").contains("cr-mail reject m-1 --by app:desktop-x"))
        assertFailsWith<IllegalArgumentException> { JarvisData.decide("m-1; rm -rf ~", true, "a") }
        assertFailsWith<IllegalArgumentException> { JarvisData.decide("t-1", true, "a") }
        assertFailsWith<IllegalArgumentException> { JarvisData.decide("m-1", true, "a b") }
        assertFailsWith<IllegalArgumentException> { JarvisData.decide("m-1", true, "\$(id)") }
        assertFailsWith<IllegalArgumentException> { JarvisData.taskShow("t-1 && x") }
        assertEquals("~/.local/bin/cr-task show t-1 --json", JarvisData.taskShow("t-1"))
    }

    @Test fun modeCommandSendsADurationNeverAClockTime() {
        // HIGH: the server reads --until HH:MM in UTC; the phone's 7:00 is not its 7:00
        assertTrue(JarvisData.setMode(JarvisMode.Kind.DND, 60).startsWith("~/.local/bin/cr-jarvis mode dnd --for 1h 2>&1"))
        assertTrue(JarvisData.setMode(JarvisMode.Kind.DND, 90).startsWith("~/.local/bin/cr-jarvis mode dnd --for 1h30m 2>&1"))
        assertTrue(JarvisData.setMode(JarvisMode.Kind.DND, null).startsWith("~/.local/bin/cr-jarvis mode dnd 2>&1"))
        assertTrue(JarvisData.setMode(JarvisMode.Kind.NORMAL, 60).startsWith("~/.local/bin/cr-jarvis mode normal 2>&1"))
        assertFalse(JarvisData.setMode(JarvisMode.Kind.DND, 60).contains("--until"))
        assertFailsWith<IllegalArgumentException> { JarvisData.setMode(JarvisMode.Kind.DND, 0) }
        assertFailsWith<IllegalArgumentException> { JarvisData.setMode(JarvisMode.Kind.DND, 24 * 60 + 1) }
        assertEquals("45m", JarvisData.forArg(45))
        assertEquals("2h", JarvisData.forArg(120))
        assertEquals("10h5m", JarvisData.forArg(605))
    }

    @Test fun minutesUntilTheMorningAreLocal() {
        assertEquals(8 * 60, JarvisData.minutesUntil(23, 0, 7))
        assertEquals(30, JarvisData.minutesUntil(6, 30, 7))
        assertEquals(24 * 60 - 1, JarvisData.minutesUntil(7, 1, 7))
        assertEquals(24 * 60, JarvisData.minutesUntil(7, 0, 7))
    }

    @Test fun briefingPicksMorningOrEvening() {
        assertTrue(JarvisData.briefing(7).endsWith("briefing morning --say"))
        assertTrue(JarvisData.briefing(18).endsWith("briefing evening --say"))
    }

    @Test fun runResultReadsTheStatusLineAndTheReason() {
        assertEquals(JarvisRunResult(true, "Hotovo"), JarvisData.parseRun("__cr_rc=0\n"))
        assertEquals(JarvisRunResult(true, "m-1 schválena"), JarvisData.parseRun("m-1 schválena\n__cr_rc=0"))
        assertEquals(
            JarvisRunResult(false, "zpráva m-1 už není navržená"),
            JarvisData.parseRun("cr-mail: zpráva m-1 už není navržená\n__cr_rc=2\n"),
        )
        assertEquals(JarvisRunResult(false, "Odmítnuto (kód 127)"), JarvisData.parseRun("__cr_rc=127"))
        // no status line: cut off, not ok, and not a lie either way
        assertFalse(JarvisData.parseRun("m-1 schválena").ok)
        assertFalse(JarvisData.parseRun(null).ok)
    }

    @Test fun taskMailFromShow() {
        assertEquals(listOf("m-1 replied", "m-2 queued"), JarvisData.parseTaskMail("""{"id":"t-1","mail":["m-1 replied","m-2 queued"]}"""))
        assertEquals(emptyList(), JarvisData.parseTaskMail("nonsense"))
    }
}
