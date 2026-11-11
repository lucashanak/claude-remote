package com.clauderemote.session.service

import com.clauderemote.model.ClaudeAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F5d: the `jarvis/accounts` file the app writes, and `jarvisAccount` of Jarvis' status.json. */
class JarvisAccountsTest {
    // ── the file ──

    @Test fun twoOrMoreSlotsAreWrittenOnePerLine() {
        assertEquals("default\nhanakl@nekrachni.cz\n", JarvisAccountsFile.serialize(listOf("default", "hanakl@nekrachni.cz")))
        assertTrue(JarvisAccountsFile.enabled(listOf("default", "hanakl@nekrachni.cz")))
    }

    @Test fun fewerThanTwoIsAnEmptyFileAndOff() {
        assertEquals("", JarvisAccountsFile.serialize(listOf("default")))
        assertEquals("", JarvisAccountsFile.serialize(emptyList()))
        assertFalse(JarvisAccountsFile.enabled(listOf("default")))
        // a duplicate or an invalid slot does not make two
        assertEquals("", JarvisAccountsFile.serialize(listOf("default", "default")))
        assertEquals("", JarvisAccountsFile.serialize(listOf("default", "../x")))
        assertFalse(JarvisAccountsFile.enabled(listOf("a", " a ")))
        assertEquals("Automatický výběr účtu potřebuje aspoň 2 účty", JarvisAccountsFile.TOO_FEW)
    }

    @Test fun onlyValidSlotsEverReachTheFile() {
        val bad = listOf("", ".", "..", ".hidden", "a/b", "a..b", "a b", "a;rm", "a'b", "\$(x)", "x".repeat(65), "a\nb")
        bad.forEach { assertFalse(JarvisAccountsFile.validSlot(it), it) }
        listOf("default", "hanakl@nekrachni.cz", "lukas_k-2", "a.b").forEach { assertTrue(JarvisAccountsFile.validSlot(it), it) }
        assertEquals("a\nb\n", JarvisAccountsFile.serialize(listOf("a", "a'b", "b", "a")))
    }

    @Test fun theFileReadsBackAsWritten() {
        val chosen = listOf("default", "hanakl@nekrachni.cz", "lukas@kontexta.cz")
        assertEquals(chosen, JarvisAccountsFile.parse(JarvisAccountsFile.serialize(chosen)))
        assertEquals(listOf("a", "b"), JarvisAccountsFile.parse("# komentář\n\n a \nb\n../x\na\n"))
        assertEquals(emptyList(), JarvisAccountsFile.parse(""))
        assertEquals(listOf("a", "b"), JarvisAccountsFile.parseRead("a\nb\n__cr_end__\n"))
        assertEquals(emptyList(), JarvisAccountsFile.parseRead("__cr_end__\n"), "a missing file is off, read fine")
        assertNull(JarvisAccountsFile.parseRead("a\nb"), "no end marker = the read did not finish")
        assertNull(JarvisAccountsFile.parseRead(null))
    }

    @Test fun theWriteIsAtomicAndAShortArgument() {
        val worst = (1..40).map { "x$it" + "y".repeat(60) }
        val cmd = JarvisAccountsFile.writeCommand(worst)
        assertTrue(cmd.encodeToByteArray().size < 4096, "${cmd.length} B")
        assertTrue(".accounts.tmp" in cmd && "mv -f" in cmd && cmd.trimEnd().endsWith("echo __cr_ok__"))
        assertEquals(JarvisAccountsFile.MAX_SLOTS, JarvisAccountsFile.normalize(worst).size)
        assertTrue(JarvisAccountsFile.writeOk("__cr_ok__\n"))
        assertFalse(JarvisAccountsFile.writeOk(""))
        assertFalse(JarvisAccountsFile.writeOk(null))
    }

    @Test fun theDefaultLoginIsTheDefaultSlot() {
        assertEquals("default", JarvisAccountsFile.slotOf(ClaudeAccount("default", "lukashanak@nekrachni.cz", isDefault = true)))
        assertEquals("hanakl@nekrachni.cz", JarvisAccountsFile.slotOf(ClaudeAccount("hanakl@nekrachni.cz", "hanakl@nekrachni.cz")))
    }

    // ── jarvisAccount ──

    private val full = """{"slot":"hanakl@nekrachni.cz","email":"hanakl@nekrachni.cz","fiveHour":42,"sevenDay":95.4,
        "candidates":[{"slot":"hanakl@nekrachni.cz","email":"hanakl@nekrachni.cz","fiveHour":42,"sevenDay":95.4,"ageS":30},
        {"slot":"default","email":"lukashanak@nekrachni.cz","fiveHour":3,"sevenDay":20,"ageS":900},
        {"email":"bez slotu"}],
        "autopick":"on","lastSwitch":{"at":1794000000,"from":"default","to":"hanakl@nekrachni.cz","why":"došel týden","id":"s-1"},
        "request":{"id":"s-2","to":"default","why":"7d 95 %","expires":1794000600000}}""".replace("\n", "")

    @Test fun parsesTheWholeField() {
        val r = JarvisAccountView.parse(full)
        assertIs<JarvisAccountView.Read.Ok>(r)
        val a = r.info
        assertEquals("hanakl@nekrachni.cz", a.slot)
        assertEquals(42.0, a.fiveHour)
        assertEquals(95.4, a.sevenDay)
        assertTrue(a.autopickOn)
        assertEquals(listOf("hanakl@nekrachni.cz", "default"), a.candidates.map { it.slot }, "a candidate without a slot is skipped")
        assertEquals(900L, a.candidates[1].ageS)
        assertEquals("lukashanak@nekrachni.cz", a.candidates[1].email)
        val sw = a.lastSwitch!!
        assertEquals(1_794_000_000_000L, sw.atMs, "epoch seconds become ms")
        assertEquals("default" to "hanakl@nekrachni.cz", sw.from to sw.to)
        assertEquals("s-1", sw.id)
        assertEquals(JarvisAccountRequest("s-2", "default", "7d 95 %", 1_794_000_600_000L), a.request)
        assertEquals("5h 42 % · 7d 95 %", JarvisAccountView.usage(a.fiveHour, a.sevenDay))
    }

    @Test fun missingFieldsAndOddTypesDoNotBreakIt() {
        val r = JarvisAccountView.parse("""{"slot":"default","fiveHour":"x","autopick":"jen 1 účet v seznamu","lastSwitch":{"at":"2026-11-11T16:20:00Z"}}""")
        assertIs<JarvisAccountView.Read.Ok>(r)
        assertNull(r.info.fiveHour, "a string is no percent")
        assertNull(r.info.sevenDay)
        assertEquals("výchozí účet", r.info.label)
        assertEquals("automatický výběr: jen 1 účet v seznamu", JarvisAccountView.autopickText(r.info.autopick))
        assertEquals("2026-11-11T16:20:00Z", r.info.lastSwitch!!.atText)
        assertNull(r.info.lastSwitch!!.atMs)
        assertEquals("5h ? · 7d ?", JarvisAccountView.usage(null, null))
        assertEquals("automatický výběr: vypnutý (méně než 2 účty)", JarvisAccountView.autopickText("off"))
        // a bare boolean
        assertEquals("off", (JarvisAccountView.parse("""{"autopick":false}""") as JarvisAccountView.Read.Ok).info.autopick)
    }

    @Test fun absentAndUnreadableAreToldApart() {
        assertEquals(JarvisAccountView.Read.Absent, JarvisAccountView.parse("null\n"))
        assertEquals(JarvisAccountView.Read.Unreadable, JarvisAccountView.parse("__cr_none__\n"))
        assertEquals(JarvisAccountView.Read.Unreadable, JarvisAccountView.parse(null))
        assertEquals(JarvisAccountView.Read.Unreadable, JarvisAccountView.parse(""))
        assertEquals(JarvisAccountView.Read.Unreadable, JarvisAccountView.parse("{nedokonč"))
        assertEquals(JarvisAccountView.Read.Unreadable, JarvisAccountView.parse("[1,2]"))
    }

    @Test fun theSwitchLineNamesTheAccounts() {
        val sw = JarvisAccountSwitch(1_000L, null, "default", "hanakl@nekrachni.cz", "došel týden")
        assertEquals(
            "lukashanak@nekrachni.cz → hanakl@nekrachni.cz (došel týden) · před 2 min",
            JarvisAccountView.switchText(sw, 121_000L, mapOf("default" to "lukashanak@nekrachni.cz")),
        )
        assertEquals("výchozí účet → x", JarvisAccountView.switchText(JarvisAccountSwitch(null, null, "default", "x", null), 0))
    }
}
