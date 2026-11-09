package com.clauderemote.voice.conversation

import com.clauderemote.session.service.RefuseReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every transition and timeout of Jarvis' conversation mode, without a microphone, a speaker or a server. */
class TalkMachineTest {
    private var now = 1_000_000L
    private var s = TalkState()
    private var fx: List<TalkEffect> = emptyList()

    private fun on(e: TalkEvent, advanceMs: Long = 0): List<TalkEffect> {
        now += advanceMs
        val step = TalkMachine.reduce(s, e, now)
        s = step.state
        fx = step.effects
        return fx
    }

    private fun tick(advanceMs: Long, routeOk: Boolean = true) = on(TalkEvent.Tick(routeOk), advanceMs)

    private fun listening() {
        on(TalkEvent.Start)
        assertEquals(TalkPhase.LISTENING, s.phase)
    }

    private fun thinking(text: String = "co je nového") {
        listening()
        on(TalkEvent.Heard(text))
        on(TalkEvent.SendDone(TalkSendResult.Sent()), 300)
        assertEquals(TalkPhase.THINKING, s.phase)
    }

    private inline fun <reified T : TalkEffect> has() = fx.filterIsInstance<T>()

    // ---- the loop ----

    @Test fun startListensWithATone() {
        assertEquals(listOf(TalkEffect.Tone(TalkTone.LISTEN), TalkEffect.Listen()), on(TalkEvent.Start))
        assertEquals(TalkPhase.LISTENING, s.phase)
        assertEquals(emptyList(), on(TalkEvent.Start), "a second start changes nothing")
    }

    @Test fun anUtteranceGoesToJarvisWithTheVoicePrefixOnOneLine() {
        listening()
        on(TalkEvent.Heard("  Co je\nnového?  "))
        assertEquals(TalkPhase.SENDING, s.phase)
        assertEquals(listOf(TalkEffect.Send("[hlas] Co je nového?")), fx)
        assertFalse(s.awaiting, "an answer is expected only once the prompt went in")
    }

    @Test fun theWholeRoundTrip() {
        thinking()
        on(TalkEvent.Answer("Nic **nového**, všechno běží.", now + 5_000), 5_000)
        assertEquals(TalkPhase.SPEAKING, s.phase)
        assertEquals(listOf(TalkEffect.Speak("Nic nového, všechno běží.")), fx, "markdown is not read out")
        assertFalse(s.awaiting)
        on(TalkEvent.SpeechDone, 3_000)
        assertEquals(TalkPhase.LISTENING, s.phase)
        assertEquals(listOf(TalkEffect.Tone(TalkTone.LISTEN), TalkEffect.Listen()), fx)
        assertEquals(now, s.quietSinceMs, "the silence clock starts after the reading")
    }

    @Test fun nothingSaidListensAgain() {
        listening()
        assertEquals(listOf(TalkEffect.Listen()), on(TalkEvent.Heard("   ")))
        assertEquals(TalkPhase.LISTENING, s.phase)
    }

    @Test fun anEmptyAnswerIsSaidAsSuch() {
        thinking()
        on(TalkEvent.Answer(null, now))
        assertEquals(listOf(TalkEffect.Speak(TalkMachine.NO_TEXT)), fx)
    }

    // ---- ending ----

    @Test fun endWordsEndTheModeWithOneSentence() {
        for (w in listOf("konec", "Stop.", "Díky, to je vše.", "Jarvisi, konec!", "děkuju, to je vše")) {
            s = TalkState(); listening()
            on(TalkEvent.Heard(w))
            assertEquals(TalkPhase.SPEAKING, s.phase, w)
            assertEquals(TalkEnd.WORD, s.ending, w)
            assertTrue(has<TalkEffect.Send>().isEmpty(), "$w is not sent to Jarvis")
            assertEquals(TalkEffect.Speak(TalkEnd.WORD.sentence!!), fx.last())
            on(TalkEvent.SpeechDone)
            assertEquals(TalkPhase.OFF, s.phase)
            assertEquals(listOf(TalkEffect.Ended(TalkEnd.WORD)), fx)
            assertEquals(TalkEnd.WORD, s.ended)
        }
    }

    @Test fun aSentenceThatOnlyContainsAnEndWordIsSent() {
        listening()
        on(TalkEvent.Heard("stop the build v kontextě"))
        assertEquals(TalkPhase.SENDING, s.phase)
        assertFalse(TalkWords.isEnd("konec týdne mi shrň"))
    }

    @Test fun stopEndsAtOnceFromAnyPhaseWithoutASentence() {
        thinking()
        on(TalkEvent.Stop)
        assertEquals(TalkPhase.OFF, s.phase)
        assertEquals(
            listOf(TalkEffect.CancelSend, TalkEffect.CancelListening, TalkEffect.StopSpeaking, TalkEffect.Ended(TalkEnd.BUTTON)), fx,
        )
        assertEquals(emptyList(), on(TalkEvent.Answer("pozdě", now)), "a late answer after the end changes nothing")
        assertEquals(TalkPhase.OFF, s.phase)
    }

    @Test fun twoMinutesOfSilenceEnd() {
        listening()
        tick(TalkMachine.SILENCE_MS - 1)
        assertEquals(TalkPhase.LISTENING, s.phase)
        tick(1)
        assertEquals(TalkEnd.SILENCE, s.ending)
        assertTrue(fx.contains(TalkEffect.CancelListening))
        on(TalkEvent.SpeechDone)
        assertEquals(TalkEnd.SILENCE, s.ended)
    }

    @Test fun wordsResetTheSilenceClock() {
        listening()
        tick(100_000)
        on(TalkEvent.Heard("ahoj"))
        on(TalkEvent.SendDone(TalkSendResult.Refused("Jarvis teď nemůže přijmout, zkus to za chvíli.")))
        on(TalkEvent.SpeechDone)
        tick(100_000)
        assertEquals(TalkPhase.LISTENING, s.phase)
    }

    @Test fun aLostRouteEndsOnceAfterItsGrace() {
        thinking()
        tick(1_000, routeOk = false)
        tick(TalkMachine.ROUTE_GRACE_MS - 1_001, routeOk = false)
        assertEquals(TalkPhase.THINKING, s.phase, "a blip is not a loss")
        tick(1_001, routeOk = false)
        assertEquals(TalkEnd.ROUTE_LOST, s.ending)
        assertEquals(1, has<TalkEffect.Speak>().size)
        assertEquals(emptyList(), tick(1_000, routeOk = false), "said once")
        on(TalkEvent.SpeechDone)
        assertEquals(TalkPhase.OFF, s.phase)
    }

    @Test fun aRouteThatComesBackIsForgiven() {
        thinking()
        tick(4_000, routeOk = false)
        tick(500, routeOk = true)
        tick(4_000, routeOk = false)
        assertEquals(TalkPhase.THINKING, s.phase)
    }

    @Test fun aClosingSentenceThatNeverEndsStillEnds() {
        listening()
        on(TalkEvent.Heard("konec"))
        tick(TalkMachine.ENDING_MAX_MS)
        assertEquals(TalkPhase.OFF, s.phase)
        assertTrue(fx.contains(TalkEffect.Ended(TalkEnd.WORD)))
    }

    @Test fun threeSttFailuresInARowEnd() {
        listening()
        assertEquals(listOf(TalkEffect.Listen(TalkMachine.STT_RETRY_MS)), on(TalkEvent.SttFailed("x")))
        on(TalkEvent.SttFailed("x"))
        on(TalkEvent.SttFailed("x"))
        assertEquals(TalkEnd.MIC, s.ending)
    }

    // ---- Jarvis is slow ----

    @Test fun aCueAfterThreeSecondsOnce() {
        thinking()
        tick(TalkMachine.CUE_MS - 1)
        assertEquals(emptyList(), fx)
        tick(1)
        assertEquals(listOf(TalkEffect.Tone(TalkTone.WAIT)), fx)
        tick(500)
        assertEquals(emptyList(), fx)
    }

    @Test fun afterAMinuteItSaysSoListensAndStillReadsTheLateAnswer() {
        thinking()
        tick(TalkMachine.GIVE_UP_MS)
        assertEquals(listOf(TalkEffect.Speak(TalkMachine.NOT_ANSWERED)), fx)
        on(TalkEvent.SpeechDone)
        assertEquals(TalkPhase.LISTENING, s.phase)
        assertTrue(s.awaiting)
        on(TalkEvent.Answer("Hotovo.", now), 20_000)
        assertEquals(TalkPhase.SPEAKING, s.phase)
        assertEquals(listOf(TalkEffect.CancelListening, TalkEffect.Speak("Hotovo.")), fx, "the microphone closes first")
    }

    @Test fun anAnswerDuringANoticeFollowsIt() {
        thinking()
        tick(TalkMachine.GIVE_UP_MS)
        on(TalkEvent.Answer("Hotovo.", now))
        assertEquals(emptyList(), fx)
        on(TalkEvent.SpeechDone)
        assertEquals(listOf(TalkEffect.Speak("Hotovo.")), fx)
        on(TalkEvent.SpeechDone)
        assertEquals(TalkPhase.LISTENING, s.phase)
    }

    // ---- which turn is the answer ----

    @Test fun aTurnThatEndedBeforeThePromptIsNotTheAnswer() {
        thinking()
        val sent = s.sentAtMs
        on(TalkEvent.Answer("stará odpověď", sent - TalkMachine.ANSWER_SLACK_MS - 1))
        assertEquals(TalkPhase.THINKING, s.phase)
        on(TalkEvent.Answer("nová", sent - 500))
        assertEquals(TalkPhase.SPEAKING, s.phase, "within the skew slack")
    }

    @Test fun nothingIsReadWhenNothingWasAsked() {
        listening()
        on(TalkEvent.Answer("wake od jarvis-d", now))
        assertEquals(TalkPhase.LISTENING, s.phase)
        assertEquals(emptyList(), fx)
    }

    // review MED: an answer is matched to OUR send, not to a time guess
    @Test fun anAnswerDuringTheSendWaitsForItsResultAndIsReadWhenItIsLaterThanTheEnter() {
        listening()
        on(TalkEvent.Heard("ahoj"))
        on(TalkEvent.Answer("Ahoj.", now + 2_000, serverSec = 1_000_002), 2_000)
        assertEquals(TalkPhase.SENDING, s.phase, "held: the send's result decides")
        assertEquals(emptyList(), fx)
        on(TalkEvent.SendDone(TalkSendResult.Sent(serverAtMs = 1_000_000_500)))
        assertEquals(TalkPhase.SPEAKING, s.phase)
        assertEquals(listOf(TalkEffect.Speak("Ahoj.")), fx)
    }

    @Test fun aWakeThatEndedBeforeTheEnterIsNotTheAnswerEvenWhenItArrivesLater() {
        listening()
        on(TalkEvent.Heard("ahoj"))
        // the Stop of a [jarvis wake] turn, its body still being polled while our keys went in
        on(TalkEvent.Answer("Výsledek úkolu…", now, serverSec = 1_000_000), 1_000)
        on(TalkEvent.SendDone(TalkSendResult.Sent(serverAtMs = 1_000_000_700)))
        assertEquals(TalkPhase.THINKING, s.phase, "same second as the Enter = before it")
        on(TalkEvent.Answer("Výsledek úkolu…", now, serverSec = 1_000_000))
        assertEquals(TalkPhase.THINKING, s.phase)
        on(TalkEvent.Answer("Ahoj.", now, serverSec = 1_000_003))
        assertEquals(TalkPhase.SPEAKING, s.phase)
        assertEquals(listOf(TalkEffect.Speak("Ahoj.")), fx)
    }

    @Test fun anAnswerDuringARefusedSendIsDroppedAndTheRefusalSaid() {
        listening()
        on(TalkEvent.Heard("pošli to"))
        on(TalkEvent.Answer("Cizí tah.", now, serverSec = 9_999_999_999))
        on(TalkEvent.SendDone(TalkWords.refusal(RefuseReason.PROMPT_CHANGED)))
        assertEquals(listOf(TalkEffect.Speak("Jarvis teď nemůže přijmout, zkus to za chvíli.")), fx)
        assertNull(s.queuedAnswer)
        on(TalkEvent.SpeechDone)
        assertEquals(TalkPhase.LISTENING, s.phase, "the dropped turn is never read")
    }

    @Test fun withTheServersEnterTimeAStopWithoutItsOwnSecondIsNotTheAnswer() {
        thinkingAt(serverAtMs = 1_000_000_000)
        on(TalkEvent.Answer("?", now + 5_000, serverSec = null))
        assertEquals(TalkPhase.THINKING, s.phase)
    }

    @Test fun withoutAnyTimeAnAnswerIsNotTakenBlindly() {
        thinking()
        on(TalkEvent.Answer("?", atMs = null, serverSec = null))
        assertEquals(TalkPhase.THINKING, s.phase)
    }

    private fun thinkingAt(serverAtMs: Long) {
        listening()
        on(TalkEvent.Heard("co je nového"))
        on(TalkEvent.SendDone(TalkSendResult.Sent(serverAtMs)), 300)
        assertEquals(TalkPhase.THINKING, s.phase)
    }

    // ---- refusals (Jarvis busy, no route) ----

    @Test fun aRefusalIsSaidAndNothingWaitsToBeSent() {
        listening()
        on(TalkEvent.Heard("pošli to"))
        on(TalkEvent.SendDone(TalkWords.refusal(RefuseReason.PROMPT_CHANGED)))
        assertEquals(TalkPhase.SPEAKING, s.phase)
        assertEquals("Jarvis teď nemůže přijmout, zkus to za chvíli.", (fx.single() as TalkEffect.Speak).text)
        assertFalse(s.awaiting)
        on(TalkEvent.SpeechDone)
        assertEquals(TalkPhase.LISTENING, s.phase)
        assertTrue(has<TalkEffect.Send>().isEmpty(), "never sent again by itself")
        on(TalkEvent.Answer("cizí tah", now))
        assertEquals(TalkPhase.LISTENING, s.phase, "no answer is expected after a refusal")
    }

    @Test fun aRefusalWhileAnOlderAnswerIsDueKeepsWaitingForIt() {
        thinking()
        tick(TalkMachine.GIVE_UP_MS)
        on(TalkEvent.SpeechDone)
        on(TalkEvent.Heard("a ještě"))
        on(TalkEvent.SendDone(TalkWords.refusal(RefuseReason.PROMPT_CHANGED)))
        assertTrue(s.awaiting)
    }

    @Test fun maybeSentWaitsForTheAnswer() {
        listening()
        on(TalkEvent.Heard("ahoj"))
        on(TalkEvent.SendDone(TalkWords.refusal(RefuseReason.MAYBE_SENT)))
        assertIs<TalkEffect.Speak>(fx.single())
        assertTrue(s.awaiting)
    }

    @Test fun refusalSentences() {
        assertEquals(TalkSendResult.Refused("Jarvis má ve vstupu rozepsaný text, neposláno."), TalkWords.refusal(RefuseReason.DRAFT_IN_INPUT))
        assertEquals(TalkSendResult.Refused("Jarvis není připojený, neposláno."), TalkWords.refusal(RefuseReason.NOT_CONNECTED))
        assertIs<TalkSendResult.Refused>(TalkWords.refusal(RefuseReason.NO_HOOKS))
        assertIs<TalkSendResult.Refused>(TalkWords.refusal(RefuseReason.ALREADY_ANSWERED))
    }

    // ---- the button (headset / in-app) ----

    @Test fun theButtonInterruptsTheReadingAndListens() {
        thinking()
        on(TalkEvent.Answer("Dlouhá odpověď.", now))
        on(TalkEvent.Button)
        assertEquals(TalkPhase.LISTENING, s.phase)
        assertEquals(listOf(TalkEffect.StopSpeaking, TalkEffect.Tone(TalkTone.LISTEN), TalkEffect.Listen()), fx)
        assertEquals(emptyList(), on(TalkEvent.SpeechDone), "the stopped reading's done does not listen twice")
    }

    @Test fun theButtonWhileListeningFinishesTheUtterance() {
        listening()
        assertEquals(listOf(TalkEffect.FinishListening), on(TalkEvent.Button))
        assertEquals(TalkPhase.LISTENING, s.phase)
        on(TalkEvent.Heard("ahoj"), 800)
        assertEquals(TalkPhase.SENDING, s.phase)
        assertNull(s.finishingSinceMs)
    }

    @Test fun aDeadSttAfterFinishIsAFailureNotTwoMinutesOfSilence() {
        listening()
        on(TalkEvent.Button)
        tick(TalkMachine.FINISH_TIMEOUT_MS - 1)
        assertEquals(emptyList(), fx)
        tick(1)
        assertEquals(listOf(TalkEffect.CancelListening, TalkEffect.Listen(TalkMachine.STT_RETRY_MS)), fx)
        assertEquals(1, s.sttFailures)
        assertNull(s.finishingSinceMs)
    }

    @Test fun theButtonDuringANoticeReadsTheAnswerWaitingBehindIt() {
        thinking()
        tick(TalkMachine.GIVE_UP_MS)
        on(TalkEvent.Answer("Hotovo.", now))
        on(TalkEvent.Button)
        assertEquals(TalkPhase.SPEAKING, s.phase)
        assertEquals(listOf(TalkEffect.StopSpeaking, TalkEffect.Speak("Hotovo.")), fx)
        assertNull(s.queuedAnswer)
    }

    @Test fun aLostRouteWhileSendingAbandonsTheSend() {
        listening()
        on(TalkEvent.Heard("ahoj"))
        tick(1_000, routeOk = false)
        tick(TalkMachine.ROUTE_GRACE_MS, routeOk = false)
        assertTrue(fx.contains(TalkEffect.CancelSend))
    }

    @Test fun theButtonWhileJarvisThinksListensAndTheAnswerStillComes() {
        thinking()
        on(TalkEvent.Button)
        assertEquals(TalkPhase.LISTENING, s.phase)
        assertTrue(s.awaiting)
    }

    @Test fun theButtonDuringTheClosingSentenceEndsAtOnce() {
        listening()
        on(TalkEvent.Heard("konec"))
        on(TalkEvent.Button)
        assertEquals(TalkPhase.OFF, s.phase)
        assertEquals(listOf(TalkEffect.StopSpeaking, TalkEffect.Ended(TalkEnd.WORD)), fx)
    }

    @Test fun theButtonWhileSendingDoesNothing() {
        listening()
        on(TalkEvent.Heard("ahoj"))
        assertEquals(emptyList(), on(TalkEvent.Button))
        assertEquals(TalkPhase.SENDING, s.phase)
    }

    // ---- words ----

    @Test fun normalize() {
        assertEquals("diky to je vse", TalkWords.normalize("Díky, to je vše!"))
    }

    // review LOW: what send-keys -l would type as it is
    @Test fun controlFormatAndSeparatorCharactersNeverReachThePane() {
        val p = TalkWords.prompt("a\u001b[2Jb\u0007c\u007f\u0085d\u202Ee\u200Bf\u2028g\u2029h\ti\nj")
        assertEquals("[hlas] a [2Jb c d e f g h i j", p)
        assertTrue(p.none { it.isISOControl() || it.category == CharCategory.FORMAT || it == '\u2028' || it == '\u2029' })
    }

    // review LOW: the cap is on the bytes the reply command carries, not on characters
    @Test fun thePromptIsCutToTheQuotedByteBudget() {
        for (unit in listOf("x", "ž", "„“…", "'", "a'b„“", "😀")) {
            val p = TalkWords.prompt(unit.repeat(5_000))
            assertTrue(TalkWords.quotedBytes(p) <= TalkWords.MAX_QUOTED_BYTES, "$unit: ${TalkWords.quotedBytes(p)}")
            assertTrue(TalkWords.quotedBytes(p) > TalkWords.MAX_QUOTED_BYTES - 16, "$unit: the budget is used")
            assertFalse(p.last().isHighSurrogate(), "$unit: never half a pair")
        }
        assertEquals(8, TalkWords.quotedBytes("a'b"), "'a'\\''b' = 2 quotes + a + 4 + b")
        assertEquals(2 + 6 + 1 + 1, TalkWords.quotedBytes("„“;"), "quotes + two 3-byte marks + ; + its tmux escape")
        assertEquals("[hlas] krátké", TalkWords.prompt("krátké"))
    }

    // ---- kolo 3 ----

    @Test fun aReadingThatNeverEndsEndsTheModeAfterFiveMinutes() {
        thinking()
        on(TalkEvent.Answer("Odpověď.", now))
        assertEquals(TalkPhase.SPEAKING, s.phase)
        tick(TalkMachine.SPEAK_MAX_MS - 1)
        assertEquals(TalkPhase.SPEAKING, s.phase)
        tick(1)
        assertEquals(TalkEnd.STALLED, s.ending)
        assertTrue(fx.contains(TalkEffect.StopSpeaking))
        tick(TalkMachine.ENDING_MAX_MS)
        assertEquals(TalkPhase.OFF, s.phase, "even when the closing sentence hangs too")
    }

    @Test fun theOldAnswerArrivingDuringTheNextSendIsReadFirst() {
        thinkingAt(serverAtMs = 1_000_000_000)
        tick(TalkMachine.GIVE_UP_MS)
        on(TalkEvent.SpeechDone)
        assertTrue(s.awaiting)
        on(TalkEvent.Heard("a ještě tohle"))
        // the old turn ends (sec 1_000_070) just before the new keys go in (Enter at sec 1_000_071)
        on(TalkEvent.Answer("Stará odpověď.", now, serverSec = 1_000_070))
        on(TalkEvent.SendDone(TalkSendResult.Sent(serverAtMs = 1_000_071_200)))
        assertEquals(TalkPhase.SPEAKING, s.phase)
        assertEquals(listOf(TalkEffect.Speak("Stará odpověď.")), fx)
        assertTrue(s.awaiting, "the new prompt's answer is still due")
        on(TalkEvent.SpeechDone)
        on(TalkEvent.Answer("Nová odpověď.", now, serverSec = 1_000_075))
        assertEquals(listOf(TalkEffect.CancelListening, TalkEffect.Speak("Nová odpověď.")), fx)
    }

    @Test fun bothAnswersDuringOneSendAreReadOldThenNew() {
        thinkingAt(serverAtMs = 1_000_000_000)
        tick(TalkMachine.GIVE_UP_MS)
        on(TalkEvent.SpeechDone)
        on(TalkEvent.Heard("a ještě tohle"))
        on(TalkEvent.Answer("Stará.", now, serverSec = 1_000_070))
        on(TalkEvent.Answer("Nová.", now, serverSec = 1_000_074))
        on(TalkEvent.SendDone(TalkSendResult.Sent(serverAtMs = 1_000_071_200)))
        assertEquals(listOf(TalkEffect.Speak("Stará.")), fx)
        assertFalse(s.awaiting)
        on(TalkEvent.SpeechDone)
        assertEquals(listOf(TalkEffect.Speak("Nová.")), fx)
    }

    @Test fun withNothingAwaitedAnOlderTurnDuringTheSendIsStillDropped() {
        thinkingAt(serverAtMs = 1_000_000_000)
        on(TalkEvent.Answer("Odpověď.", now, serverSec = 1_000_003))
        on(TalkEvent.SpeechDone)
        assertFalse(s.awaiting)
        on(TalkEvent.Heard("další"))
        on(TalkEvent.Answer("Wake.", now, serverSec = 1_000_010))
        on(TalkEvent.SendDone(TalkSendResult.Sent(serverAtMs = 1_000_011_000)))
        assertEquals(TalkPhase.THINKING, s.phase)
    }
}
