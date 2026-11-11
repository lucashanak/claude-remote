package com.clauderemote.session.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F5d switch contract: the request file, the poll, the result — pure parts. */
class JarvisSwitchTest {
    private fun req(
        id: String = "s-20261111T1620Z-ab12",
        tmux: String = "claude-server-jarvis",
        to: String = "default",
        at: Long = 1_794_000_000_000,
        v: Int = 1,
    ) = """{"v":$v,"id":"$id","at":$at,"expires":${at + 600_000},"tmux":"$tmux","claudeSessionId":"c0ffee-1","from":"hanakl@nekrachni.cz","to":"$to","why":"7d 95 %"}"""

    @Test fun aValidRequestParses() {
        val r = JarvisSwitch.parseRequest(req())
        assertNotNull(r)
        assertEquals("s-20261111T1620Z-ab12", r.id)
        assertEquals(1_794_000_600_000, r.expires, "ms, as the server writes them")
        assertEquals("default", r.to)
        assertEquals("claude-server-jarvis", r.tmux)
        assertEquals("hanakl@nekrachni.cz", r.from)
    }

    @Test fun anythingOddIsNoRequest() {
        listOf(
            req(v = 2), req(id = "x-1"), req(id = "s-1;rm"), req(tmux = "x;y"), req(tmux = "claude-server-a b"), req(tmux = ""),
            req(to = "../x"), req(to = ".hidden"), "{torn", "", "[]", """{"v":1,"id":"s-1"}""",
        ).forEach { assertNull(JarvisSwitch.parseRequest(it), it) }
        assertNull(JarvisSwitch.parseRequest(null))
    }

    @Test fun thePollSplitsRequestAccountAndServerTime() {
        val out = req() + "\n__cr_req__\n" + """{"slot":"hanakl@nekrachni.cz","lastSwitch":{"id":"s-0","at":1794000000000}}""" +
            "\n__cr_acct__\n__cr_now__ 1794000100000\n"
        val p = JarvisSwitch.parsePoll(out)!!
        assertEquals("s-20261111T1620Z-ab12", p.request?.id)
        assertEquals(1_794_000_100_000, p.serverNowMs)
        val a = assertIs<JarvisAccountView.Read.Ok>(p.account).info
        assertEquals("s-0", a.lastSwitch?.id)
        assertEquals(1_794_000_000_000, a.lastSwitch?.atMs, "ms taken as ms")
        // no request, and a torn poll
        assertNull(JarvisSwitch.parsePoll("\n__cr_req__\nnull\n__cr_acct__\n__cr_now__ 1794000100000\n")!!.request)
        assertNull(JarvisSwitch.parsePoll("{\"v\":1}\n__cr_req__\n"))
        assertNull(JarvisSwitch.parsePoll(null))
        assertNull(JarvisSwitch.parsePoll("\n__cr_req__\nnull\n__cr_acct__\n__cr_now__ 1794000100%3N\n")!!.serverNowMs, "no ms clock = unknown")
    }

    @Test fun theResultIsTheContractsShape() {
        assertEquals(
            """{"id":"s-1","by":"app:abc-1","at":5,"ok":true}""",
            JarvisSwitch.resultJson("s-1", "abc-1", 5, true, null),
        )
        assertEquals(
            """{"id":"s-1","by":"app:abc","at":5,"ok":false,"error":"busy \"x\""}""",
            JarvisSwitch.resultJson("s-1", "a b'c", 5, false, "busy \"x\""),
        )
        assertTrue(JarvisSwitch.resultCommand("{}").contains("mv -f .switch-result.tmp switch-result.json"))
        assertEquals("""{"id":"s-1","by":"app:a","at":5,"ok":null,"error":"unknown"}""", JarvisSwitch.resultJson("s-1", "a", 5, null, "unknown"))
    }

    @Test fun commandsTakeOnlyAValidId() {
        assertFailsWith<IllegalArgumentException> { JarvisSwitch.claimCommand("s-1;rm -rf ~") }
        assertFailsWith<IllegalArgumentException> { JarvisSwitch.returnCommand("x") }
        assertTrue("mv switch-request.json switch-request.s-1.taken" in JarvisSwitch.claimCommand("s-1"))
        assertTrue("mv -n switch-request.s-1.taken switch-request.json" in JarvisSwitch.returnCommand("s-1"))
    }

    @Test fun defaultIsNullInTheModel() {
        assertNull(JarvisSwitch.accountSlugOf("default"))
        assertNull(JarvisSwitch.accountSlugOf(null))
        assertEquals("hanakl@nekrachni.cz", JarvisSwitch.accountSlugOf("hanakl@nekrachni.cz"))
    }

    @Test fun theSwitchRunsOutcomeIsReadFromItsTokens() {
        fun done(out: String) = out + "\n__CR_END__0\n"
        assertEquals(JarvisSwitch.SwitchRun.Ok, JarvisSwitch.parseSwitch(done("CR_REPLY IDLE\nCR_SWITCH RESPAWNED")))
        assertEquals(JarvisSwitch.SwitchRun.Failed("typefail"), JarvisSwitch.parseSwitch(done("CR_REPLY IDLE\nCR_SWITCH TYPEFAIL")))
        assertEquals(JarvisSwitch.SwitchRun.Failed("notlisted"), JarvisSwitch.parseSwitch(done("CR_SWITCH NOTLISTED")))
        assertEquals(JarvisSwitch.SwitchRun.Failed("nologin"), JarvisSwitch.parseSwitch(done("CR_SWITCH NOLOGIN")))
        assertEquals(JarvisSwitch.SwitchRun.Busy("PROMPT_CHANGED"), JarvisSwitch.parseSwitch(done("CR_REPLY STALE")))
        assertEquals(JarvisSwitch.SwitchRun.Busy("DRAFT_IN_INPUT"), JarvisSwitch.parseSwitch(done("CR_REPLY DRAFT")))
        assertEquals(JarvisSwitch.SwitchRun.Failed("no_hooks"), JarvisSwitch.parseSwitch(done("CR_REPLY NOHOOKS")))
        assertEquals(JarvisSwitch.SwitchRun.Failed("no answer after idle"), JarvisSwitch.parseSwitch(done("CR_REPLY IDLE")))
        // no end marker: not seen to the end, whatever it printed — unknown, never a failure (it may have respawned)
        assertEquals(JarvisSwitch.SwitchRun.Unknown, JarvisSwitch.parseSwitch("CR_REPLY IDLE\nCR_SWITCH RESPAWNED\n"))
        assertEquals(JarvisSwitch.SwitchRun.Unknown, JarvisSwitch.parseSwitch("CR_REPLY IDLE\n"))
        assertEquals(JarvisSwitch.SwitchRun.Unknown, JarvisSwitch.parseSwitch(null))
    }

    @Test fun theSwitchScriptChecksBeforeItRespawns() {
        val sc = JarvisSwitch.switchScript("claude-server-jarvis", "~/jarvis", "c-1", "default")
        val listed = sc.indexOf("jarvis/accounts")
        val login = sc.indexOf("emailAddress")
        val idle = sc.indexOf("CR_REPLY IDLE")
        val respawn = sc.indexOf("respawn-pane -k")
        assertTrue(listed in 0 until login && login < idle && idle < respawn, "listed → login → idle → respawn, in one run")
        assertTrue("respawn-pane -k -t \"\$P\"" in sc, "the pane the check read (rev LOW3)")
        assertTrue("unset CLAUDE_CONFIG_DIR" in sc)
        assertFalse(Regex("""(^|[\s'])--dangerously-skip-permissions""").containsMatchIn(sc))
        assertFailsWith<IllegalArgumentException> { JarvisSwitch.switchScript("claude-server-jarvis", "~/jarvis", "c-1", "../x") }
        val other = JarvisSwitch.switchScript("claude-server-jarvis", "~/jarvis", "c-1", "hanakl@nekrachni.cz")
        assertFalse("unset CLAUDE_CONFIG_DIR" in other)
        assertTrue("CLAUDE_CONFIG_DIR=" in other)
    }
}
