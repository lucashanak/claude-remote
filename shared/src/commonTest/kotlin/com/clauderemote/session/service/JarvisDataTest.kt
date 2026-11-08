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

    @Test fun modeCommandTakesUntilOnlyForDnd() {
        assertTrue(JarvisData.setMode(JarvisMode.Kind.DND, "07:00").startsWith("~/.local/bin/cr-jarvis mode dnd --until 07:00 "))
        assertTrue(JarvisData.setMode(JarvisMode.Kind.DND, null).startsWith("~/.local/bin/cr-jarvis mode dnd 2>&1"))
        assertTrue(JarvisData.setMode(JarvisMode.Kind.NORMAL, "07:00").startsWith("~/.local/bin/cr-jarvis mode normal 2>&1"))
        assertFailsWith<IllegalArgumentException> { JarvisData.setMode(JarvisMode.Kind.DND, "7:00; id") }
        assertFailsWith<IllegalArgumentException> { JarvisData.setMode(JarvisMode.Kind.DND, "25:00") }
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
