package com.clauderemote.session.service

import com.clauderemote.model.SessionActivity
import com.clauderemote.session.PromptType
import com.clauderemote.session.service.ReplyGuard.Reply
import com.clauderemote.session.service.ReplyGuard.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A reply from the phone notification or the watch is typed into the session only while the
 * session still shows the prompt it was written for. Every session runs in yolo mode, so the
 * permission dialog is the last safety gate: an "Ano" for command A must not approve command B.
 */
class ReplyGuardTest {
    private val permA = NotifyKeys.prompt(NotifyKeys.hook("perm", "claude-server-x", 1_000L))!!
    private val permB = NotifyKeys.prompt(NotifyKeys.hook("perm", "claude-server-x", 2_000L))!!
    private val askQ = NotifyKeys.prompt(NotifyKeys.hook("ask", "claude-server-x", 3_000L))!!
    private val screenD = NotifyKeys.prompt(NotifyKeys.detection(PromptType.APPROVAL_NEEDED, 7))!!
    private val stop1 = NotifyKeys.prompt(NotifyKeys.stop("claude-server-x", 10L))!!
    private val stop2 = NotifyKeys.prompt(NotifyKeys.stop("claude-server-x", 20L))!!
    private val instruction = PromptFingerprint(ReplyGuard.INSTRUCTION_KEY, PromptKind.INSTRUCTION)

    private fun cur(p: PromptFingerprint?, vararg aliases: PromptFingerprint) =
        p?.let { PromptTokens.Current(it, aliases.map { a -> a.key }.toSet()) }

    private fun check(
        notified: PromptFingerprint?,
        current: PromptFingerprint?,
        activity: SessionActivity?,
        reply: Reply = Reply.TEXT,
        deliverable: Boolean = true,
    ) = ReplyGuard.check(notified, cur(current), activity, deliverable, reply)

    private fun Verdict.reason() = (this as Verdict.Refuse).reason

    // ---------------------------------------------------------------- dialogs

    @Test fun theAnswerToTheDialogStillOpenIsSentAndTheServerMustStillShowThatEvent() {
        assertEquals(Verdict.Send(ServerExpectation.Dialog("perm", 1_000L)), check(permA, permA, SessionActivity.APPROVAL_NEEDED, Reply.APPROVE))
        assertEquals(Verdict.Send(ServerExpectation.Dialog("ask", 3_000L)), check(askQ, askQ, SessionActivity.APPROVAL_NEEDED, Reply.APPROVE))
    }

    @Test fun freeTextNeverAnswersAPermissionDialog() {
        // Enter in the selector picks its default, "Yes"
        assertEquals(RefuseReason.YES_NO_ONLY, check(permA, permA, SessionActivity.APPROVAL_NEEDED, Reply.TEXT).reason())
        assertEquals(RefuseReason.YES_NO_ONLY, check(screenD, screenD, SessionActivity.APPROVAL_NEEDED, Reply.TEXT).reason(), "a screen selector counts as a permission")
    }

    @Test fun aTypedAnswerToAQuestionStaysAllowed() {
        assertEquals(Verdict.Send(ServerExpectation.Dialog("ask", 3_000L)), check(askQ, askQ, SessionActivity.APPROVAL_NEEDED, Reply.TEXT))
    }

    @Test fun anAnswerForDialogAIsNotSentToDialogB() {
        assertEquals(RefuseReason.PROMPT_CHANGED, check(permA, permB, SessionActivity.APPROVAL_NEEDED, Reply.APPROVE).reason())
    }

    @Test fun anAnswerForADialogIsNotSentOnceTheSessionLeftIt() {
        assertIs<Verdict.Refuse>(check(permA, permA, SessionActivity.WAITING_FOR_INPUT, Reply.APPROVE))
        assertIs<Verdict.Refuse>(check(permA, null, SessionActivity.WORKING, Reply.APPROVE))
        // back to work and into an approval nobody announced: the key is gone, so no match
        assertEquals(RefuseReason.PROMPT_CHANGED, check(permA, null, SessionActivity.APPROVAL_NEEDED, Reply.APPROVE).reason())
    }

    @Test fun aDialogTheScreenAloneSawCannotBeConfirmedByTheServer() {
        assertEquals(RefuseReason.NO_HOOKS, check(screenD, screenD, SessionActivity.APPROVAL_NEEDED, Reply.APPROVE).reason())
    }

    @Test fun anAliasAnswersForTheSameDialogAndTheServerChecksTheHookEvent() {
        // screen first, then the hook of the same dialog: the screen's alert stays answerable
        val v = ReplyGuard.check(screenD, cur(permA, screenD), SessionActivity.APPROVAL_NEEDED, true, Reply.APPROVE)
        assertEquals(Verdict.Send(ServerExpectation.Dialog("perm", 1_000L)), v)
        val h = ReplyGuard.check(permA, cur(screenD, permA), SessionActivity.APPROVAL_NEEDED, true, Reply.APPROVE)
        assertEquals(Verdict.Send(ServerExpectation.Dialog("perm", 1_000L)), h, "the hook key among the aliases")
    }

    // ---------------------------------------------------------------- unknown state fails closed

    @Test fun anUnknownStateFailsClosed() {
        assertEquals(RefuseReason.NO_PROMPT, check(null, permA, SessionActivity.APPROVAL_NEEDED, Reply.APPROVE).reason())
        assertEquals(RefuseReason.NO_PROMPT, check(null, null, SessionActivity.WAITING_FOR_INPUT, Reply.TEXT).reason())
        assertIs<Verdict.Refuse>(check(permA, permA, null, Reply.APPROVE), "no activity known")
        assertIs<Verdict.Refuse>(check(stop1, stop1, null, Reply.TEXT), "no activity known")
        assertIs<Verdict.Refuse>(check(stop1, stop1, SessionActivity.IDLE, Reply.TEXT), "the session ended")
        assertEquals(
            RefuseReason.NOT_CONNECTED,
            check(permA, permA, SessionActivity.APPROVAL_NEEDED, Reply.APPROVE, deliverable = false).reason(),
        )
    }

    // ---------------------------------------------------------------- free text to an idle session

    @Test fun freeTextToAnIdleSessionIsSentWhenAStopMarkerNamesIt() {
        assertEquals(Verdict.Send(ServerExpectation.Ready(10L)), check(stop1, stop1, SessionActivity.WAITING_FOR_INPUT))
        assertEquals(Verdict.Send(ServerExpectation.Ready(10L)), check(stop1, stop2, SessionActivity.WAITING_FOR_INPUT), "a later completion, the server checks it is idle")
    }

    @Test fun freeTextForAReadyPromptNoStopMarkerNamesIsRefused() {
        val screenIdle = NotifyKeys.prompt(NotifyKeys.detection(PromptType.INPUT_PROMPT, 3))!!
        assertEquals(PromptKind.READY, screenIdle.kind)
        assertEquals(RefuseReason.NOT_VERIFIABLE, check(screenIdle, screenIdle, SessionActivity.WAITING_FOR_INPUT).reason())
    }

    @Test fun freeTextForACompletionNeverLandsInADialog() {
        assertIs<Verdict.Refuse>(check(stop1, permB, SessionActivity.APPROVAL_NEEDED))
        assertIs<Verdict.Refuse>(check(stop1, stop1, SessionActivity.APPROVAL_NEEDED), "a dialog no event named")
        assertIs<Verdict.Refuse>(check(stop1, permB, SessionActivity.WAITING_FOR_INPUT), "the state contradicts itself: a dialog was announced last")
        assertIs<Verdict.Refuse>(check(stop1, null, SessionActivity.WORKING))
    }

    @Test fun aDialogAnswerIsNeverSentForACompletionOrAnInstruction() {
        assertIs<Verdict.Refuse>(check(stop1, stop1, SessionActivity.WAITING_FOR_INPUT, Reply.APPROVE))
        assertIs<Verdict.Refuse>(check(instruction, null, SessionActivity.WORKING, Reply.APPROVE))
    }

    // ---------------------------------------------------------------- instructions

    @Test fun anInstructionGoesToAWorkingOrIdleSessionUnderTheServersWatch() {
        assertEquals(Verdict.Send(ServerExpectation.Working), check(instruction, null, SessionActivity.WORKING))
        assertEquals(Verdict.Send(ServerExpectation.Idle), check(instruction, null, SessionActivity.WAITING_FOR_INPUT))
        assertEquals(Verdict.Send(ServerExpectation.Idle), check(instruction, stop1, SessionActivity.WAITING_FOR_INPUT))
        assertIs<Verdict.Refuse>(check(instruction, permA, SessionActivity.APPROVAL_NEEDED))
        assertIs<Verdict.Refuse>(check(instruction, permA, SessionActivity.WAITING_FOR_INPUT), "a dialog announced last")
        assertIs<Verdict.Refuse>(check(instruction, null, SessionActivity.IDLE))
    }

    // ---------------------------------------------------------------- identities

    @Test fun eventKeysNameTheirPrompt() {
        assertEquals(PromptKind.READY, stop1.kind)
        assertEquals(PromptKind.PERMISSION, permA.kind)
        assertEquals(PromptKind.QUESTION, askQ.kind)
        assertEquals(PromptKind.PERMISSION, screenD.kind)
        assertNull(NotifyKeys.prompt(NotifyKeys.hook("work", "t", 1L)), "not a prompt")
        assertNull(NotifyKeys.prompt("Claude čeká na vstup#msg-1"), "the message-id fallback names no prompt")
        assertNull(NotifyKeys.prompt(null))
        assertEquals("perm" to 1_000L, NotifyKeys.hookEvent(permA.key))
        assertEquals("ask" to 5L, NotifyKeys.hookEvent(NotifyKeys.hook("ask", "a#b", 5L)), "a # in the pane name")
        assertNull(NotifyKeys.hookEvent(stop1.key))
        assertEquals(10L, NotifyKeys.stopEpochSec(stop1.key))
        assertNull(NotifyKeys.stopEpochSec(permA.key))
    }

    @Test fun theEncodingSurvivesTheTripThroughAnExtraAndTheWatch() {
        assertEquals(permA, PromptFingerprint.decode(permA.encode()))
        assertEquals(stop1, PromptFingerprint.decode(stop1.encode()))
        assertEquals(instruction, PromptFingerprint.decode(instruction.encode()))
        val colon = PromptFingerprint("hook#perm#a:b#1", PromptKind.PERMISSION)
        assertEquals(colon, PromptFingerprint.decode(colon.encode()))
        assertNull(PromptFingerprint.decode(null))
        assertNull(PromptFingerprint.decode(""))
        assertNull(PromptFingerprint.decode("PERMISSION:"))
        assertNull(PromptFingerprint.decode("BOGUS:hook#perm#t#1"))
        assertNull(PromptFingerprint.decode("hook#perm#t#1"))
    }

    @Test fun aViewOffersOnlyThePromptItsActivityShows() {
        assertEquals(permA, ReplyGuard.promptShown(permA, SessionActivity.APPROVAL_NEEDED))
        assertEquals(askQ, ReplyGuard.promptShown(askQ, SessionActivity.APPROVAL_NEEDED))
        assertNull(ReplyGuard.promptShown(stop1, SessionActivity.APPROVAL_NEEDED), "Ano/Ne bound to a completion")
        assertNull(ReplyGuard.promptShown(null, SessionActivity.APPROVAL_NEEDED), "a dialog nobody announced")
        assertEquals(stop1, ReplyGuard.promptShown(stop1, SessionActivity.WAITING_FOR_INPUT))
        assertEquals(instruction, ReplyGuard.promptShown(permA, SessionActivity.WAITING_FOR_INPUT))
        assertEquals(instruction, ReplyGuard.promptShown(null, SessionActivity.WAITING_FOR_INPUT))
        assertEquals(instruction, ReplyGuard.promptShown(null, SessionActivity.WORKING), "an instruction for a working session")
        assertNull(ReplyGuard.promptShown(stop1, SessionActivity.IDLE))
        assertNull(ReplyGuard.promptShown(stop1, null))
    }

    // ---------------------------------------------------------------- watch identity

    @Test fun aWatchReplyWithoutAnyIdentityComesFromAnOutdatedWatchApp() {
        assertEquals(RefuseReason.WATCH_OUTDATED, ReplyGuard.watchRefusal(null))
        assertEquals(RefuseReason.NO_PROMPT, ReplyGuard.watchRefusal(""), "a current watch on a row that offered nothing")
        assertEquals(RefuseReason.NO_PROMPT, ReplyGuard.watchRefusal("garbage"))
        assertNull(ReplyGuard.watchRefusal(permA.encode()))
        assertEquals("Aktualizuj aplikaci na hodinkách", RefuseReason.WATCH_OUTDATED.message)
    }

    @Test fun everyRefusalHasItsOwnMessage() {
        assertEquals(RefuseReason.entries.size, RefuseReason.entries.map { it.message }.toSet().size)
    }

    // ---------------------------------------------------------------- server result

    @Test fun theServerResultFailsClosedOnAnythingButSent() {
        assertNull(ReplyGuard.serverResult("CR_REPLY SENT\n"))
        assertNull(ReplyGuard.serverResult("motd noise\nCR_REPLY SENT"))
        assertEquals(RefuseReason.PROMPT_CHANGED, ReplyGuard.serverResult("CR_REPLY STALE"))
        assertEquals(RefuseReason.NO_HOOKS, ReplyGuard.serverResult("CR_REPLY NOHOOKS"))
        assertEquals(RefuseReason.NOT_VERIFIABLE, ReplyGuard.serverResult("CR_REPLY FAILED"))
        assertEquals(RefuseReason.NOT_VERIFIABLE, ReplyGuard.serverResult(""))
        assertEquals(RefuseReason.NOT_VERIFIABLE, ReplyGuard.serverResult(null), "the exec failed")
        assertEquals(RefuseReason.NOT_VERIFIABLE, ReplyGuard.serverResult("SENT"))
    }

    // ---------------------------------------------------------------- one answer per prompt

    @Test fun aPromptIsAnsweredOnceFromOutsideTheApp() {
        val l = ReplyLedger()
        assertEquals(true, l.begin("k"))
        assertEquals(false, l.begin("k"), "phone and watch at once: the second waits for nothing, it is refused")
        l.finish("k", null)
        assertEquals(false, l.begin("k"), "sent: a repeated Ano would approve whatever comes next")
    }

    @Test fun aPromptThatMayHaveBeenAnsweredStaysTakenAndARefusalReleasesIt() {
        val l = ReplyLedger()
        l.begin("maybe"); l.finish("maybe", RefuseReason.MAYBE_SENT)
        assertEquals(false, l.begin("maybe"), "after Nepotvrzeno a second Ano must not go out")
        l.begin("typed"); l.finish("typed", RefuseReason.TYPED_NOT_SENT)
        assertEquals(false, l.begin("typed"), "the text waits in the input: a retry would type it twice")
        l.begin("stale"); l.finish("stale", RefuseReason.NOT_VERIFIABLE)
        assertEquals(true, l.begin("stale"), "nothing was sent: try again")
        val small = ReplyLedger(capacity = 2)
        for (k in listOf("a", "b", "c")) { small.begin(k); small.finish(k, null) }
        assertEquals(true, small.begin("a"), "bounded: the oldest answer is forgotten")
    }

    // ---------------------------------------------------------------- tokens

    @Test fun leavingThePromptStatesForgetsThePrompt() {
        val t = PromptTokens()
        t.note("s", permA)
        t.onActivity("s", SessionActivity.APPROVAL_NEEDED)
        assertEquals(permA, t.current("s")?.prompt)
        t.onActivity("s", SessionActivity.WORKING)
        assertNull(t.current("s"), "answered: an approval without a new event must not revive A")
        t.note("s", stop1)
        t.onActivity("s", SessionActivity.WAITING_FOR_INPUT)
        assertEquals(stop1, t.current("s")?.prompt)
        t.onActivity("s", SessionActivity.IDLE)
        assertNull(t.current("s"))
        t.note("s", permB)
        t.forget("s")
        assertNull(t.current("s"))
    }

    @Test fun theSameDialogFromTheOtherSourceBecomesAnAlias() {
        val t = PromptTokens()
        // hook first: the screen's later sighting joins it
        t.note("s", permA, atMs = 1_000)
        t.sameDialog("s", screenD.key)
        assertEquals(setOf(permA.key, screenD.key), t.current("s")!!.let { it.aliases + it.prompt.key })
        // screen first: the hook that fired before the screen saw the dialog brings the screen key back
        t.forget("s")
        t.note("s", screenD, atMs = 5_000)
        t.note("s", permB, atMs = 4_800)
        t.sameDialog("s", permB.key)
        assertEquals(true, t.current("s")!!.names(screenD.key))
        assertEquals(permB, t.current("s")!!.prompt)
    }

    @Test fun aHookThatFiredAfterTheScreenSawADialogIsANewDialog() {
        val t = PromptTokens()
        t.note("s", screenD, atMs = 5_000)
        // dialog A on screen was answered at the desktop (no event); B's hook fired 4 s later and
        // the dedup window holds it back as "the same dialog"
        t.note("s", permB, atMs = 9_000)
        t.sameDialog("s", permB.key)
        assertEquals(false, t.current("s")!!.names(screenD.key), "an Ano for A must not approve B")
    }

    @Test fun reNotingTheSamePromptKeepsTheLinkToTheOneItReplaced() {
        val t = PromptTokens()
        t.note("s", screenD, atMs = 5_000)
        t.note("s", permA, atMs = 4_900)
        t.note("s", permA, atMs = 5_300) // fireNeedsInput notes the hook key again
        t.sameDialog("s", permA.key)
        assertEquals(true, t.current("s")!!.names(screenD.key))
    }

    @Test fun theTextOfAPromptIsKeptAndPublished() {
        val t = PromptTokens()
        t.note("s", permA, atMs = 1_000, text = "Bash: rm -rf build")
        assertEquals("Bash: rm -rf build", t.current("s")?.text)
        assertEquals(PromptTokens.Shown(t.current("s")!!, 1_000), t.shown.value["s"], "the published state mirrors current()")
        // fireNeedsInput notes the same prompt again without a text: the text stays
        t.note("s", permA, atMs = 1_200)
        assertEquals("Bash: rm -rf build", t.shown.value["s"]?.current?.text)
        assertEquals(1_000, t.shown.value["s"]?.atMs, "re-noting keeps when it appeared")
        // a screen-first note gains the hook's text when the hook names the same prompt later
        t.forget("s")
        t.note("s", screenD, atMs = 5_000)
        t.note("s", screenD, atMs = 5_100, text = "late text")
        assertEquals("late text", t.current("s")?.text)
        // the alias merge keeps the text
        t.note("s", permB, atMs = 4_900, text = "Edit: a.kt")
        t.sameDialog("s", permB.key)
        assertEquals("Edit: a.kt", t.shown.value["s"]?.current?.text)
        assertEquals(true, t.shown.value["s"]?.current?.names(screenD.key))
    }

    @Test fun thePublishedStateFollowsEveryRemoval() {
        val t = PromptTokens()
        t.note("a", permA, text = "x")
        t.note("b", permB, text = "y")
        assertEquals(setOf("a", "b"), t.shown.value.keys)
        t.onActivity("a", SessionActivity.WORKING)
        assertEquals(setOf("b"), t.shown.value.keys)
        t.onActivity("b", SessionActivity.APPROVAL_NEEDED)
        assertEquals(setOf("b"), t.shown.value.keys, "staying in a prompt state keeps it")
        t.forget("b")
        assertEquals(emptyMap(), t.shown.value)
    }

    // ---------------------------------------------------------------- Jarvis' conversation mode (F5a, 14d/14e)

    @Test fun aSpokenPromptGoesInOnlyWhileJarvisIsIdleAtHisPrompt() {
        assertEquals(
            Verdict.Send(ServerExpectation.Idle),
            ReplyGuard.voiceCheck(cur(stop1), SessionActivity.WAITING_FOR_INPUT, deliverable = true),
        )
        assertEquals(Verdict.Send(ServerExpectation.Idle), ReplyGuard.voiceCheck(null, SessionActivity.WAITING_FOR_INPUT, true))
    }

    @Test fun aSpokenPromptIsNeverQueuedBehindAWorkingTurn() {
        // the watch's instruction would be (Claude queues it); the read-aloud answer would then belong to another turn
        assertEquals(RefuseReason.PROMPT_CHANGED, ReplyGuard.voiceCheck(null, SessionActivity.WORKING, true).reason())
        assertIs<Verdict.Send>(check(instruction, null, SessionActivity.WORKING))
    }

    @Test fun aSpokenPromptNeverAnswersADialog() {
        assertEquals(RefuseReason.PROMPT_CHANGED, ReplyGuard.voiceCheck(cur(permA), SessionActivity.APPROVAL_NEEDED, true).reason())
        assertEquals(RefuseReason.PROMPT_CHANGED, ReplyGuard.voiceCheck(cur(askQ), SessionActivity.WAITING_FOR_INPUT, true).reason())
        assertEquals(RefuseReason.PROMPT_CHANGED, ReplyGuard.voiceCheck(null, SessionActivity.APPROVAL_NEEDED, true).reason())
    }

    @Test fun aSpokenPromptNeedsARouteAndAKnownState() {
        assertEquals(RefuseReason.NOT_CONNECTED, ReplyGuard.voiceCheck(null, SessionActivity.WAITING_FOR_INPUT, false).reason())
        assertEquals(RefuseReason.PROMPT_CHANGED, ReplyGuard.voiceCheck(null, null, true).reason())
        // IDLE after a reconnect claims nothing: the server's hook check (stop/start) and empty box decide
        assertEquals(Verdict.Send(ServerExpectation.Idle), ReplyGuard.voiceCheck(null, SessionActivity.IDLE, true))
        assertEquals(RefuseReason.PROMPT_CHANGED, ReplyGuard.voiceCheck(cur(permA), SessionActivity.IDLE, true).reason())
        assertEquals(RefuseReason.NOT_CONNECTED, ReplyGuard.voiceCheck(null, SessionActivity.IDLE, false).reason())
    }

    @Test fun aDraftTokenIsTheDraftRefusal() {
        assertEquals(RefuseReason.DRAFT_IN_INPUT, ReplyGuard.serverResult("CR_REPLY DRAFT"))
    }

    @Test fun aTimedSentIsSentAndCarriesTheServersMs() {
        val out = "CR_REPLY SENDING\nCR_REPLY SENT 1762700000123\n"
        assertNull(ReplyGuard.serverResult(out))
        assertEquals(1_762_700_000_123L, ReplyGuard.sentAtServerMs(out))
        assertNull(ReplyGuard.sentAtServerMs("CR_REPLY SENT"), "the watch's untimed SENT")
        assertNull(ReplyGuard.sentAtServerMs("CR_REPLY SENT %3N"), "a date without ms")
        assertNull(ReplyGuard.serverResult("CR_REPLY SENT "), "a date that printed nothing is still SENT")
        assertEquals(RefuseReason.PROMPT_CHANGED, ReplyGuard.serverResult("CR_REPLY STALE"))
    }
}
