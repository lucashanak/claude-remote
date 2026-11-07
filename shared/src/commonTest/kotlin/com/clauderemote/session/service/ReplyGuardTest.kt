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
    private val stop1 = NotifyKeys.prompt(NotifyKeys.stop("claude-server-x", 10L))!!
    private val stop2 = NotifyKeys.prompt(NotifyKeys.stop("claude-server-x", 20L))!!

    private fun check(
        notified: PromptFingerprint?,
        current: PromptFingerprint?,
        activity: SessionActivity?,
        reply: Reply = Reply.TEXT,
        deliverable: Boolean = true,
    ) = ReplyGuard.check(notified, current, activity, deliverable, reply)

    // ---------------------------------------------------------------- dialogs

    @Test fun theAnswerToTheDialogStillOpenIsSent() {
        assertEquals(Verdict.Send, check(permA, permA, SessionActivity.APPROVAL_NEEDED, Reply.APPROVE))
        assertEquals(Verdict.Send, check(permA, permA, SessionActivity.APPROVAL_NEEDED, Reply.TEXT), "\"1\" typed for this dialog")
    }

    @Test fun anAnswerForDialogAIsNotSentToDialogB() {
        assertIs<Verdict.Refuse>(check(permA, permB, SessionActivity.APPROVAL_NEEDED, Reply.APPROVE))
        assertIs<Verdict.Refuse>(check(permA, permB, SessionActivity.APPROVAL_NEEDED, Reply.TEXT))
    }

    @Test fun anAnswerForADialogIsNotSentOnceTheSessionLeftIt() {
        // answered elsewhere: the session is ready (or working) again
        assertIs<Verdict.Refuse>(check(permA, permA, SessionActivity.WAITING_FOR_INPUT, Reply.TEXT))
        assertIs<Verdict.Refuse>(check(permA, null, SessionActivity.WORKING, Reply.APPROVE))
        // back to work and into an approval nobody announced: the key is gone, so no match
        assertIs<Verdict.Refuse>(check(permA, null, SessionActivity.APPROVAL_NEEDED, Reply.APPROVE))
    }

    // ---------------------------------------------------------------- unknown state fails closed

    @Test fun anUnknownStateFailsClosed() {
        assertIs<Verdict.Refuse>(check(null, permA, SessionActivity.APPROVAL_NEEDED, Reply.APPROVE), "the reply names no prompt (old watch build)")
        assertIs<Verdict.Refuse>(check(null, null, SessionActivity.WAITING_FOR_INPUT, Reply.TEXT), "no identity even for an idle session")
        assertIs<Verdict.Refuse>(check(permA, permA, null, Reply.APPROVE), "no activity known")
        assertIs<Verdict.Refuse>(check(stop1, stop1, null, Reply.TEXT), "no activity known")
        assertIs<Verdict.Refuse>(check(stop1, stop1, SessionActivity.IDLE, Reply.TEXT), "the session ended")
        assertIs<Verdict.Refuse>(
            check(permA, permA, SessionActivity.APPROVAL_NEEDED, Reply.APPROVE, deliverable = false),
            "offline: the queued input would land in whatever state the reconnect finds",
        )
    }

    // ---------------------------------------------------------------- free text to an idle session

    @Test fun freeTextToAnIdleSessionIsSentEvenAfterALaterCompletion() {
        assertEquals(Verdict.Send, check(stop1, stop1, SessionActivity.WAITING_FOR_INPUT))
        assertEquals(Verdict.Send, check(stop1, stop2, SessionActivity.WAITING_FOR_INPUT), "another completion, still idle")
        assertEquals(Verdict.Send, check(stop1, null, SessionActivity.WAITING_FOR_INPUT), "ready by silence, no event")
    }

    @Test fun freeTextForACompletionNeverLandsInADialog() {
        assertIs<Verdict.Refuse>(check(stop1, permB, SessionActivity.APPROVAL_NEEDED))
        assertIs<Verdict.Refuse>(check(stop1, stop1, SessionActivity.APPROVAL_NEEDED), "a dialog no event named")
        assertIs<Verdict.Refuse>(check(stop1, permB, SessionActivity.WAITING_FOR_INPUT), "the state contradicts itself: a dialog was announced last")
        assertIs<Verdict.Refuse>(check(stop1, null, SessionActivity.WORKING))
    }

    @Test fun aDialogAnswerIsNeverSentForACompletion() {
        assertIs<Verdict.Refuse>(check(stop1, stop1, SessionActivity.WAITING_FOR_INPUT, Reply.APPROVE))
    }

    // ---------------------------------------------------------------- identities

    @Test fun eventKeysNameTheirPrompt() {
        assertEquals(PromptKind.READY, stop1.kind)
        assertEquals(PromptKind.DIALOG, permA.kind)
        assertEquals(PromptKind.DIALOG, NotifyKeys.prompt(NotifyKeys.hook("ask", "t", 1L))?.kind)
        assertEquals(PromptKind.DIALOG, NotifyKeys.prompt(NotifyKeys.detection(PromptType.APPROVAL_NEEDED, 3))?.kind)
        assertEquals(PromptKind.READY, NotifyKeys.prompt(NotifyKeys.detection(PromptType.INPUT_PROMPT, 3))?.kind)
        assertNull(NotifyKeys.prompt(NotifyKeys.hook("work", "t", 1L)), "not a prompt")
        assertNull(NotifyKeys.prompt("Claude čeká na vstup#msg-1"), "the message-id fallback names no prompt")
        assertNull(NotifyKeys.prompt(null))
    }

    @Test fun theEncodingSurvivesTheTripThroughAnExtraAndTheWatch() {
        assertEquals(permA, PromptFingerprint.decode(permA.encode()))
        assertEquals(stop1, PromptFingerprint.decode(stop1.encode()))
        val colon = PromptFingerprint("hook#perm#a:b#1", PromptKind.DIALOG)
        assertEquals(colon, PromptFingerprint.decode(colon.encode()))
        assertNull(PromptFingerprint.decode(null))
        assertNull(PromptFingerprint.decode(""))
        assertNull(PromptFingerprint.decode("DIALOG:"))
        assertNull(PromptFingerprint.decode("BOGUS:hook#perm#t#1"))
        assertNull(PromptFingerprint.decode("hook#perm#t#1"))
    }

    @Test fun aViewOffersOnlyThePromptItsActivityShows() {
        assertEquals(permA, ReplyGuard.promptShown(permA, SessionActivity.APPROVAL_NEEDED))
        assertNull(ReplyGuard.promptShown(stop1, SessionActivity.APPROVAL_NEEDED), "Ano/Ne bound to a completion")
        assertNull(ReplyGuard.promptShown(null, SessionActivity.APPROVAL_NEEDED), "a dialog nobody announced")
        assertEquals(stop1, ReplyGuard.promptShown(stop1, SessionActivity.WAITING_FOR_INPUT))
        assertEquals(PromptKind.READY, ReplyGuard.promptShown(permA, SessionActivity.WAITING_FOR_INPUT)?.kind)
        assertEquals(PromptKind.READY, ReplyGuard.promptShown(null, SessionActivity.WAITING_FOR_INPUT)?.kind)
        assertNull(ReplyGuard.promptShown(stop1, SessionActivity.WORKING))
        assertNull(ReplyGuard.promptShown(stop1, null))
    }

    // ---------------------------------------------------------------- tokens

    @Test fun leavingThePromptStatesForgetsThePrompt() {
        val t = PromptTokens()
        t.note("s", permA)
        t.onActivity("s", SessionActivity.APPROVAL_NEEDED)
        assertEquals(permA, t.current("s"))
        t.onActivity("s", SessionActivity.WORKING)
        assertNull(t.current("s"), "answered: an approval without a new event must not revive A")
        t.note("s", stop1)
        t.onActivity("s", SessionActivity.WAITING_FOR_INPUT)
        assertEquals(stop1, t.current("s"))
        t.onActivity("s", SessionActivity.IDLE)
        assertNull(t.current("s"))
        t.note("s", permB)
        t.forget("s")
        assertNull(t.current("s"))
    }
}
