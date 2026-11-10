package com.clauderemote.session.wear

import com.clauderemote.session.QuickAccess
import com.clauderemote.session.service.JarvisAlert
import com.clauderemote.session.service.JarvisAlertKind
import com.clauderemote.session.service.JarvisProposal
import com.clauderemote.session.service.JarvisProposalKind
import com.clauderemote.session.service.JarvisProposalSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The watch as a Jarvis remote (2026-11-08): what the phone sends the wrist and checks before it acts for it. */
class WatchJarvisTest {
    private fun action(text: String = "Zavřít x: nečinná 3 h.", full: Boolean = true, expires: Long? = 10_000) = JarvisProposal(
        id = "x-20261108T101010Z-ab12", to = "claude-server-kx--x", text = text, createdMs = 1_000, task = null,
        fullText = full, kind = JarvisProposalKind.CLOSE, title = "Zavřu x.", expiresMs = expires, via = JarvisProposalSource.JARVIS,
    )

    @Test fun selfNameFallsBackToTheFixedName() {
        assertEquals("claude-server-jarvis--j2", WatchJarvis.selfName("claude-server-jarvis--j2\n"))
        assertEquals(QuickAccess.JARVIS_TMUX_NAME, WatchJarvis.selfName(null))
        assertEquals(QuickAccess.JARVIS_TMUX_NAME, WatchJarvis.selfName("\n"))
        // not a session name the app could own: a path, a shell word, another prefix
        assertEquals(QuickAccess.JARVIS_TMUX_NAME, WatchJarvis.selfName("/etc/passwd"))
        assertEquals(QuickAccess.JARVIS_TMUX_NAME, WatchJarvis.selfName("claude-server-x;rm -rf ~"))
        assertEquals(QuickAccess.JARVIS_TMUX_NAME, WatchJarvis.selfName("jarvis"))
    }

    @Test fun newsPeeksAndNeverMovesTheCursor() {
        assertTrue("delta --peek --say" in WatchJarvis.NEWS)
        assertTrue(WatchJarvis.NEWS.endsWith("echo __cr_rc=\$?"))
    }

    @Test fun onlyAnApprovalCarriesItsText() {
        assertTrue(WatchJarvis.carriesText("APPROVAL_NEEDED"))
        for (a in listOf("WAITING_FOR_INPUT", "WORKING", "IDLE", "DISCONNECTED")) assertFalse(WatchJarvis.carriesText(a), a)
    }

    @Test fun aCardCarriesTheWholeTextAndARelativeExpiry() {
        val c = WatchJarvis.card(action(), serverNowMs = 4_000)
        assertEquals("close", c.kind)
        assertEquals("Návrh: zavřít x", c.heading)
        assertEquals("Provést", c.approveLabel)
        assertTrue(c.fullText)
        assertEquals(6_000, c.expiresInMs, "by the server's clock, relative: the watch clock never matters")
        assertNull(WatchJarvis.card(action(), serverNowMs = null).expiresInMs)
        assertNull(WatchJarvis.card(action(expires = null), serverNowMs = 4_000).expiresInMs)
    }

    @Test fun aTooLongOrSubjectOnlyCardCannotBeApprovedOnTheWrist() {
        val long = WatchJarvis.card(action(text = "a".repeat(WatchJarvis.TEXT_MAX + 1)), 0)
        assertFalse(long.fullText)
        assertEquals(WatchJarvis.TEXT_MAX + 1, long.text.length, "cut, with an ellipsis")
        assertFalse(WatchJarvis.card(action(full = false), 0).fullText)
    }

    @Test fun anApprovalIsRefusedUnlessItIsExactlyWhatTheWristShowed() {
        val p = action()
        val h = p.text.hashCode()
        assertNull(WatchJarvis.refusal(p, approve = true, textHash = h, serverNowMs = 9_999))
        assertEquals("Návrh už nečeká", WatchJarvis.refusal(null, true, h, 0))
        assertEquals("Návrh se změnil – otevři ho znovu", WatchJarvis.refusal(p, true, h + 1, 0))
        assertEquals("Návrh vypršel", WatchJarvis.refusal(p, true, h, 10_000))
        assertEquals("Celý text je jen v aplikaci – schval ho tam", WatchJarvis.refusal(action(full = false), true, h, 0))
        // a rejection changes nothing but the proposal: always allowed while it waits
        assertNull(WatchJarvis.refusal(p, approve = false, textHash = 0, serverNowMs = 99_999))
        assertEquals("Návrh už nečeká", WatchJarvis.refusal(null, false, 0, 0))
    }

    @Test fun unreadProposalsAreNotNothing() {
        val r = WatchJarvis.proposals(null, 0)
        assertFalse(r.ok)
        assertTrue(r.items.isEmpty())
        assertEquals(true, WatchJarvis.proposals(emptyList(), 0).ok)
    }

    @Test fun recentAlertsAreCappedAndKeepTheirOrder() {
        val pending = (1..7).map { i ->
            JarvisAlert("a$i", JarvisAlertKind.DONE, i.toLong(), 100L - i, "Hotovo $i " + "x".repeat(300), null, "s1")
        }
        val r = WatchJarvis.recent(pending)
        assertEquals(listOf("a1", "a2", "a3", "a4", "a5"), r.map { it.id })
        assertTrue(r.all { it.text.length <= 240 })
        assertEquals("done", r[0].kind)
    }

    // The watch mirrors decode these by field name (wearApp WearJarvis.kt): the names are the contract.
    @Test fun wireFieldNamesAreTheContract() {
        val json = Json { encodeDefaults = true }
        val o = json.parseToJsonElement(json.encodeToString(WatchProposals(true, null, listOf(WatchJarvis.card(action(), 0))))).jsonObject
        assertEquals(setOf("ok", "error", "items"), o.keys)
        val card = (o["items"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertEquals(setOf("id", "kind", "heading", "title", "text", "fullText", "approveLabel", "expiresInMs"), card.keys)
        assertEquals(setOf("id", "approve", "textHash"), json.parseToJsonElement(json.encodeToString(WatchDecideRequest("x", true, 1))).jsonObject.keys)
        assertEquals(setOf("id", "ok", "text"), json.parseToJsonElement(json.encodeToString(WatchDecideResult("x", true, "t"))).jsonObject.keys)
        assertEquals(setOf("ok", "text"), json.parseToJsonElement(json.encodeToString(WatchNews(true, "t"))).jsonObject.keys)
        assertEquals(
            setOf("id", "kind", "text", "atMs"),
            json.parseToJsonElement(json.encodeToString(WatchJarvis.Recent("a", "done", "t", 1))).jsonObject.keys,
        )
    }

    // review L6: the result of a decision names the proposal (it may arrive as a notification long after the tap)
    @Test fun aDecisionSentenceNamesTheProposal() {
        val p = action()
        assertEquals("Zahozeno – Návrh: zavřít x", WatchJarvis.decideText(p, approve = false, ok = true, cliMessage = "Hotovo"))
        assertEquals("Návrh: zavřít x: už vyřízeno", WatchJarvis.decideText(p, approve = true, ok = false, cliMessage = "už vyřízeno"))
        assertEquals("Provedeno: zavřít x", WatchJarvis.decideText(p, approve = true, ok = true, cliMessage = "Hotovo"))
        assertEquals("Návrh: zavřít x: Zavřeno.", WatchJarvis.decideText(p, approve = true, ok = true, cliMessage = "Zavřeno."))
        assertEquals("Návrh: zavřít x: Návrh vypršel", WatchJarvis.refusalText(p, "Návrh vypršel"))
        assertEquals("Návrh už nečeká", WatchJarvis.refusalText(null, "Návrh už nečeká"))
    }

    // review L8: the watch's minute tick reads with a poll permit; an older watch sends no body
    @Test fun theProposalsRequestSaysWhetherItIsTheTick() {
        assertTrue(WatchJarvis.proposalsRequest("""{"periodic":true}""".encodeToByteArray()).periodic)
        assertFalse(WatchJarvis.proposalsRequest("{}".encodeToByteArray()).periodic)
        assertFalse(WatchJarvis.proposalsRequest(ByteArray(0)).periodic, "an older watch: interactive")
        assertFalse(WatchJarvis.proposalsRequest("garbage".encodeToByteArray()).periodic)
    }

    // ── F5c ──

    @Test fun recentMergesHisOwnSentencesNewestFirst() {
        val pending = listOf(JarvisAlert("t1", JarvisAlertKind.DONE, 1, 50, "šablona", null, "s1"))
        val said = listOf(
            JarvisAlert("j2", JarvisAlertKind.JASK, 1, 90, "Mám ji pustit dál? Ano?", null, "s1"),
            JarvisAlert("j1", JarvisAlertKind.JSAY, 1, 10, "Ráno.", null, "s1"),
        )
        assertEquals(listOf("j2", "t1", "j1"), WatchJarvis.recent(pending, said + pending.map { it }).map { it.id })
        assertEquals("jask", WatchJarvis.recent(pending, said)[0].kind)
    }

    @Test fun theWristShowsHisOwnSentencesWhateverTheRoutingTemplatesOnlyWhenCarried() {
        val feed = com.clauderemote.session.service.JarvisFeed(
            pending = listOf(
                JarvisAlert("j", JarvisAlertKind.JSAY, 1, 2, "Citace pushnula.", null, "s1"),
                JarvisAlert("t", JarvisAlertKind.BLOCK, 1, 1, "šablona", null, "s1"),
            ),
        )
        assertEquals(listOf("j", "t"), WatchJarvis.shownFeed(feed, carried = true).pending.map { it.id })
        assertEquals(listOf("j"), WatchJarvis.shownFeed(feed, carried = false).pending.map { it.id })
    }

    @Test fun theYesRequestsFieldNameIsTheContract() {
        val json = Json { encodeDefaults = true }
        assertEquals(setOf("id"), json.parseToJsonElement(json.encodeToString(WatchYesRequest("j-1"))).jsonObject.keys)
    }
}
