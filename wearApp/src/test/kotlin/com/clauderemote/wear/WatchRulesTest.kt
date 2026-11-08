package com.clauderemote.wear

import com.clauderemote.wear.WatchNotifyDecision.Action
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Jarvis-only watch (2026-11-08): which notifications the wrist shows, what its Čeká page lists, the wire. */
class WatchRulesTest {
    private fun row(
        id: String = "s1",
        activity: String = "APPROVAL_NEEDED",
        prompt: String? = "PERMISSION:perm-1",
        at: Long = 100,
        text: String? = "Bash: rm -rf build",
    ) = WearSessionInfo(id, "proj", "ACTIVE", activity, text, at, prompt = prompt)

    private fun decide(prev: NotifyBaseline?, r: WearSessionInfo?, body: Int = 1) = WatchNotifyDecision.decide(prev, r, body)
    private fun seen(activity: String, prompt: String? = "PERMISSION:perm-1", body: Int = 1) =
        NotifyBaseline(activity = activity, notifiedPrompt = prompt, notifiedBodyHash = body)

    // ── notifications ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun aNewDialogVibratesOnce() {
        val r = decide(seen("WORKING", prompt = null), row())
        assertEquals(Action.Post(alert = true), r.action)
        assertEquals("PERMISSION:perm-1", r.baseline!!.notifiedPrompt)
        // the same push again (re-delivered DataItem, restarted process with the stored baseline): nothing
        assertEquals(Action.Skip, decide(r.baseline, row()).action)
    }

    @Test fun aSecondDialogInTheSameTurnVibratesToo() {
        val r = decide(seen("APPROVAL_NEEDED"), row(prompt = "PERMISSION:perm-2"))
        assertEquals(Action.Post(alert = true), r.action)
    }

    @Test fun aQuestionIsActionableToo() {
        assertEquals(Action.Post(alert = true), decide(seen("WORKING", null), row(prompt = "QUESTION:ask-1")).action)
    }

    @Test fun aFinishedTurnNeverReachesTheWrist() {
        // a completion with a brand-new message, whatever the phone's settings said: no notification
        for (prompt in listOf("READY:stop-1", "INSTRUCTION:instruction", null)) {
            val r = decide(seen("WORKING", null), row(activity = "WAITING_FOR_INPUT", prompt = prompt, at = 999))
            assertEquals(Action.Skip, r.action, "$prompt")
        }
    }

    @Test fun anApprovalNobodyCanVouchForIsNotPosted() {
        // the phone sends no dialog identity (screen-only, older phone): Ano/Ne could not be checked, Jarvis' block covers it
        assertEquals(Action.Skip, decide(seen("WORKING", null), row(prompt = null)).action)
        assertEquals(Action.Skip, decide(seen("WORKING", null), row(prompt = "PERMISSION:")).action)
    }

    @Test fun aDialogThatEndsIsTakenDown() {
        assertEquals(Action.Cancel, decide(seen("APPROVAL_NEEDED"), row(activity = "WORKING", prompt = "INSTRUCTION:instruction")).action)
        assertEquals(Action.Cancel, decide(seen("APPROVAL_NEEDED"), row(activity = "WAITING_FOR_INPUT", prompt = "READY:s")).action)
        // the tab closed: gone from the push, notification down, baseline forgotten
        val gone = decide(seen("APPROVAL_NEEDED"), null)
        assertEquals(Action.Cancel, gone.action)
        assertNull(gone.baseline)
        assertEquals(Action.Skip, decide(seen("WORKING", null), null).action)
    }

    @Test fun aSessionNeverSeenIsRecordedAsAnnounced() {
        val r = decide(null, row())
        assertEquals(Action.Skip, r.action)
        assertEquals(Action.Skip, decide(r.baseline, row()).action, "and stays quiet on the next push")
    }

    @Test fun aBaselineFromTheOldWatchAnnouncesAWaitingDialogOnce() {
        // stored by the previous build: notifiedMessageAt instead of notifiedPrompt
        val old = Json { ignoreUnknownKeys = true }.decodeFromString<NotifyBaseline>(
            """{"activity":"APPROVAL_NEEDED","notifiedMessageAt":100,"notifiedBodyHash":5,"spokenHash":7}""",
        )
        assertNull(old.notifiedPrompt)
        val r = decide(old, row())
        assertEquals(Action.Post(alert = true), r.action)
        assertEquals(7, r.baseline!!.spokenHash)
    }

    @Test fun aLostPhoneLinkChangesNothing() {
        val d = decide(seen("APPROVAL_NEEDED"), row(activity = "DISCONNECTED", prompt = null))
        assertEquals(Action.Skip, d.action, "no cancel into DISCONNECTED")
        // back with the same dialog: shown again quietly, no second vibration
        assertEquals(Action.Post(alert = false), decide(d.baseline, row()).action)
        // back with a new one: it is new
        assertEquals(Action.Post(alert = true), decide(d.baseline, row(prompt = "PERMISSION:perm-9")).action)
    }

    @Test fun theSameDialogBackIsQuietAndACorrectedTextIsARepair() {
        val left = decide(seen("APPROVAL_NEEDED"), row(activity = "WAITING_FOR_INPUT", prompt = "READY:s")).baseline
        assertEquals(Action.Post(alert = false), decide(left, row()).action)
        assertEquals(Action.Repair, decide(seen("APPROVAL_NEEDED", body = 1), row(), body = 2).action)
    }

    // ── the Čeká page ───────────────────────────────────────────────────────────────────────────────────────

    private fun proposal(id: String) = WearJarvisProposal(id, "mail", "Návrh zprávy → x", null, "Text", true, "Poslat")

    @Test fun waitingShowsOnlyDialogsThenProposals() {
        val sessions = listOf(
            row("a", activity = "WORKING", prompt = "INSTRUCTION:instruction"),
            row("b", activity = "WAITING_FOR_INPUT", prompt = "READY:x"),
            row("c", prompt = "PERMISSION:p1", at = 10),
            row("d", prompt = "QUESTION:q1", at = 20),
            row("e", prompt = null),
            row("f", activity = "IDLE", prompt = null),
        )
        val items = WaitingRules.items(sessions, listOf(proposal("m-1"), proposal("x-2")))
        assertEquals(listOf("ask:d:QUESTION:q1", "ask:c:PERMISSION:p1", "prop:m-1", "prop:x-2"), items.map { it.key })
        val ask = items[0] as WaitingRules.Item.Ask
        assertEquals(WaitingRules.DialogKind.QUESTION, ask.kind)
        assertEquals(2, WaitingRules.askCount(sessions))
        assertTrue(WaitingRules.items(sessions.take(2), emptyList()).isEmpty(), "nothing waits: Klid, pane.")
    }

    @Test fun aDialogCardShowsTheWholeText() {
        val r = WearSessionInfo("s", "t", "ACTIVE", "APPROVAL_NEEDED", "live", 1, summary = "krátce", notifyBody = "celý příkaz", prompt = "PERMISSION:p")
        assertEquals("celý příkaz", WaitingRules.askBody(r))
        assertEquals("live", WaitingRules.askBody(r.copy(notifyBody = null)))
        assertEquals("krátce", WaitingRules.askBody(r.copy(notifyBody = null, lastMessage = null)))
    }

    // ── the wire, old and new ───────────────────────────────────────────────────────────────────────────────

    @Test fun anOldPhonePayloadStillDecodes() {
        val old = """{"sessions":[{"id":"s","title":"t","status":"ACTIVE","activity":"WAITING_FOR_INPUT","lastMessage":"x"}],
            "completionAlerts":true,"jarvis":{"pending":2,"text":"▲ a","notify":true}}"""
        val p = WearDataListenerService.WEAR_JSON.decodeFromString<WearSessionsPayload>(old)
        assertNull(p.jarvis!!.sessionId)
        assertTrue(p.jarvis!!.recent.isEmpty())
        assertNull(p.sessions[0].prompt)
    }

    @Test fun aNewPhonePayloadDecodesWithFieldsFromTheFuture() {
        val new = """{"sessions":[{"id":"s","title":"t","status":"ACTIVE","activity":"WORKING","lastMessage":null,"lastMessageAt":0,
            "prompt":"INSTRUCTION:instruction"}],"completionAlerts":false,
            "jarvis":{"pending":1,"text":"✓ b","notify":true,"sessionId":"tab-j",
              "recent":[{"id":"a1","kind":"done","text":"Hotovo.","atMs":5,"future":1}],"future":{"x":1}},"future":[]}"""
        val p = WearDataListenerService.WEAR_JSON.decodeFromString<WearSessionsPayload>(new)
        assertEquals("tab-j", p.jarvis!!.sessionId)
        assertEquals(listOf(WearJarvisRecent("a1", "done", "Hotovo.", 5)), p.jarvis!!.recent)
    }

    @Test fun proposalsDecodeWithMissingOptionalsAndFutureFields() {
        val json = Json { ignoreUnknownKeys = true }
        val r = json.decodeFromString<WearJarvisProposals>(
            """{"ok":true,"items":[{"id":"m-1","kind":"mail","heading":"h","text":"t","future":2}],"future":1}""",
        )
        val p = r.items.single()
        assertEquals(false, p.fullText, "a phone that does not say it is the full text: not approvable")
        assertNull(p.expiresInMs)
        val err = json.decodeFromString<WearJarvisProposals>("""{"ok":false,"error":"Návrhy nejdou načíst"}""")
        assertEquals("Návrhy nejdou načíst", err.error)
    }

    @Test fun recentAlertsMergeByIdNewestFirst() {
        val a = WearJarvisRecent("a", "done", "A", 1)
        val b = WearJarvisRecent("b", "block", "B", 2)
        val merged = JarvisRecentStore.merge(listOf(a), listOf(b, a.copy(text = "A2")))
        assertEquals(listOf("b", "a"), merged.map { it.id })
        assertEquals("A2", merged[1].text)
        val many = (1..9).map { WearJarvisRecent("r$it", "info", "$it", it.toLong()) }
        assertEquals(JarvisRecentStore.MAX, JarvisRecentStore.merge(emptyList(), many).size)
    }

    @Test fun anExpiredProposalIsJudgedOnTheWatchClockAlone() {
        val p = proposal("x").copy(expiresInMs = 1_000)
        assertEquals(false, JarvisWatchStore.expired(p, atElapsed = 5_000, nowElapsed = 5_999))
        assertEquals(true, JarvisWatchStore.expired(p, atElapsed = 5_000, nowElapsed = 6_000))
        assertEquals(false, JarvisWatchStore.expired(p.copy(expiresInMs = null), 0, Long.MAX_VALUE))
    }
}
