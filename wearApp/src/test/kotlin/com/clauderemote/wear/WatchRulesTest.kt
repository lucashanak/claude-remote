package com.clauderemote.wear

import com.clauderemote.wear.WatchNotifyDecision.Action
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // review H1: an approval the phone cannot name (screen-only, hookev.off, no hooks) still reaches the wrist
    @Test fun anApprovalNobodyCanNameIsShownWithoutButtons() {
        for (p in listOf(null, "PERMISSION:", "READY:stop-1")) {
            val r = decide(seen("WORKING", null), row(prompt = p))
            assertEquals(Action.Post(alert = true), r.action, "$p")
            assertEquals(WatchNotifyDecision.UNNAMED, r.baseline!!.activity)
            assertEquals(Action.Skip, decide(r.baseline, row(prompt = p)).action, "$p: once")
            assertEquals(Action.Cancel, decide(r.baseline, row(activity = "WORKING", prompt = "INSTRUCTION:instruction")).action)
            assertEquals(Action.Cancel, decide(r.baseline, null).action, "tab gone")
        }
        val r = decide(seen("WORKING", null), row(prompt = null))
        assertEquals(Action.Repair, decide(r.baseline, row(prompt = null), body = 9).action, "a corrected text")
    }

    // review M3: a push without the identity in the middle of an open dialog must not lose its notification
    @Test fun aNamedDialogThatLosesItsNameForAPushStaysOnTheWrist() {
        val a = decide(seen("WORKING", null), row(prompt = "PERMISSION:A"))
        assertEquals(Action.Post(alert = true), a.action)
        val unnamed = decide(a.baseline, row(prompt = null))
        assertEquals(Action.Post(alert = false), unnamed.action, "turned into the info form quietly, not cancelled")
        val back = decide(unnamed.baseline, row(prompt = "PERMISSION:A"))
        assertEquals(Action.Post(alert = false), back.action, "the same dialog back with its buttons, quietly")
        assertEquals(Action.Skip, decide(back.baseline, row(prompt = "PERMISSION:A")).action)
        // seen first unnamed, then the phone names it: Lukáš was alerted already
        val u = decide(seen("WORKING", null), row(prompt = null))
        assertEquals(Action.Post(alert = false), decide(u.baseline, row(prompt = "PERMISSION:B")).action)
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

    @Test fun aSessionNeverSeenIsRecordedAsAnnouncedOnlyWhileSeeding() {
        val r = WatchNotifyDecision.decide(null, row(), 1, seeding = true)
        assertEquals(Action.Skip, r.action)
        assertEquals(Action.Skip, decide(r.baseline, row()).action, "and stays quiet on the next push")
        val u = WatchNotifyDecision.decide(null, row(prompt = null), 1, seeding = true)
        assertEquals(Action.Skip, u.action)
        assertEquals(Action.Skip, decide(u.baseline, row(prompt = null)).action)
    }

    // review M4: outside a fresh install, a session never seen whose first push is a dialog is announced
    @Test fun aNewSessionWhoseFirstPushIsADialogIsAnnounced() {
        assertEquals(Action.Post(alert = true), decide(null, row()).action)
        assertEquals(Action.Post(alert = true), decide(null, row(prompt = null)).action, "unnamed too")
        assertEquals(Action.Skip, decide(null, row(activity = "WORKING", prompt = null)).action)
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
        // back with the same dialog: the notification never went, nothing to do
        assertEquals(Action.Skip, decide(d.baseline, row()).action)
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
        assertEquals(listOf("ask:e:null", "ask:d:QUESTION:q1", "ask:c:PERMISSION:p1", "prop:m-1", "prop:x-2"), items.map { it.key })
        assertNull((items[0] as WaitingRules.Item.Ask).kind, "an unnamed approval: a card without buttons (H1)")
        val ask = items[1] as WaitingRules.Item.Ask
        assertEquals(WaitingRules.DialogKind.QUESTION, ask.kind)
        assertEquals(3, WaitingRules.askCount(sessions))
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

    // ── review fixes L6–L8, M5 ──────────────────────────────────────────────────────────────────────────────

    @Test fun aProposalWhoseTextChangedMustBeScrolledAgain() {
        val p = proposal("m-1")
        val seen = listOf(WaitingRules.endKey(p))
        assertTrue(WaitingRules.canApprove(p, seen, expired = false, busy = false))
        val changed = p.copy(text = "Jiný text")
        assertFalse(WaitingRules.canApprove(changed, seen, expired = false, busy = false), "same id, new text: not seen")
        assertFalse(WaitingRules.canApprove(p, seen, expired = true, busy = false))
        assertFalse(WaitingRules.canApprove(p, seen, expired = false, busy = true))
        assertFalse(WaitingRules.canApprove(p.copy(fullText = false), listOf(WaitingRules.endKey(p.copy(fullText = false))), false, false))
    }

    @Test fun aProposalsReadNobodyAnswersStopsLoading() {
        JarvisWatchStore.proposals.value = null
        val t = JarvisWatchStore.beginProposals(periodic = false)
        assertTrue(JarvisWatchStore.proposalsLoading.value)
        JarvisWatchStore.proposalsTimedOut(t)
        assertFalse(JarvisWatchStore.proposalsLoading.value)
        assertEquals(JarvisWatchStore.NO_ANSWER, JarvisWatchStore.proposals.value!!.error)
        // an older read's timeout does not touch a newer one
        val t1 = JarvisWatchStore.beginProposals(periodic = false)
        val t2 = JarvisWatchStore.beginProposals(periodic = false)
        JarvisWatchStore.proposalsTimedOut(t1)
        assertTrue(JarvisWatchStore.proposalsLoading.value)
        JarvisWatchStore.proposalsTimedOut(t2)
        // a failed minute tick with cards on screen and no warning changes nothing
        JarvisWatchStore.proposals.value = JarvisWatchStore.Proposals(listOf(proposal("m-1")), 5, null)
        JarvisWatchStore.proposalsTimedOut(JarvisWatchStore.beginProposals(periodic = true))
        assertNull(JarvisWatchStore.proposals.value!!.error)
        assertEquals(1, JarvisWatchStore.proposals.value!!.items.size)
        assertFalse(JarvisWatchStore.proposalsLoading.value)
    }

    @Test fun completionsOfTheOlderBuildAreRecognised() {
        assertTrue(WearNotifier.isStaleCompletion(WearNotifier.CHANNEL_WAITING, 0x2000_0001))
        assertFalse(WearNotifier.isStaleCompletion("claude_approval", 0x2000_0001), "a dialog stays")
        assertFalse(WearNotifier.isStaleCompletion("jarvis_quiet", 0x2A11_0001), "Jarvis' own")
        assertFalse(WearNotifier.isStaleCompletion(WearNotifier.CHANNEL_WAITING, 0x1000_0000), "not a session id")
        assertFalse(WearNotifier.isStaleCompletion(null, 0x2000_0001))
    }

    @Test fun aDecisionResultReachesTheWatchWithTheAppClosed() {
        assertTrue(WatchMessageRouting.notifyDecision(appVisible = false))
        assertFalse(WatchMessageRouting.notifyDecision(appVisible = true), "the open app shows it on the Čeká page")
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue("android:path=\"/jarvis-decide-result\"" in manifest, "the background service is woken for it")
        val r = JarvisWatchStore.decideResult("""{"id":"m-1","ok":true,"text":"Zahozeno – Návrh zprávy → x","future":1}""".toByteArray())
        assertEquals("m-1", r!!.id)
    }

    @Test fun theMinuteTickAsksForAPollRead() {
        val json = Json { encodeDefaults = true }
        assertEquals("""{"periodic":true}""", json.encodeToString(WearJarvisProposalsRequest.serializer(), WearJarvisProposalsRequest(true)))
        assertEquals("""{"periodic":false}""", json.encodeToString(WearJarvisProposalsRequest.serializer(), WearJarvisProposalsRequest()))
    }
}
