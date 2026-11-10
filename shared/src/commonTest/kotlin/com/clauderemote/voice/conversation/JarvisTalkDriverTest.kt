package com.clauderemote.voice.conversation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [JarvisTalkDriver] against fake ears, mouth and link: the effects reach the platform and stale callbacks do not. */
class JarvisTalkDriverTest {
    private class Ears : TalkEars {
        var heard: ((String) -> Unit)? = null
        var error: ((String) -> Unit)? = null
        var listens = 0
        var finishes = 0
        var cancels = 0
        override fun listen(onHeard: (String) -> Unit, onError: (String) -> Unit) { listens++; heard = onHeard; error = onError }
        override fun finish() { finishes++ }
        override fun cancel() { cancels++ }
        var words = false
        override fun hearing() = words
    }

    private class Mouth : TalkMouth {
        val said = mutableListOf<String>()
        val tones = mutableListOf<TalkTone>()
        var done: (() -> Unit)? = null
        var stops = 0
        override fun speak(text: String, onDone: () -> Unit) { said += text; done = onDone }
        override fun stop() { stops++ }
        override fun tone(tone: TalkTone) { tones += tone }
    }

    private class Link : JarvisVoiceLink {
        val sent = mutableListOf<String>()
        var result: TalkSendResult = TalkSendResult.Sent()
        var up = true
        val turns = MutableSharedFlow<JarvisTurn>(extraBufferCapacity = 8)
        override suspend fun send(prompt: String, stillWanted: () -> Boolean): TalkSendResult { sent += prompt; return result }
        override fun reachable() = up
        override val answers = turns
    }

    private val ears = Ears()
    private val mouth = Mouth()
    private val link = Link()

    private fun TestScope.driver() = JarvisTalkDriver(backgroundScope, ears, mouth, link, clock = { testScheduler.currentTime })

    @Test fun oneTurnEndToEnd() = runTest {
        val d = driver()
        d.start(); runCurrent()
        assertEquals(1, ears.listens)
        assertEquals(listOf(TalkTone.LISTEN), mouth.tones)
        ears.heard!!("co je nového"); runCurrent()
        assertEquals(listOf("[hlas] co je nového"), link.sent)
        assertEquals(TalkPhase.THINKING, d.state.phase)
        advanceTimeBy(3_600); runCurrent()
        assertEquals(listOf(TalkTone.LISTEN, TalkTone.WAIT), mouth.tones)
        link.turns.emit(JarvisTurn(testScheduler.currentTime, "Dvě sessions čekají na schválení.")); runCurrent()
        assertEquals(listOf("Dvě sessions čekají na schválení."), mouth.said)
        mouth.done!!(); runCurrent()
        assertEquals(TalkPhase.LISTENING, d.state.phase)
        assertEquals(2, ears.listens)
        ears.heard!!("konec"); runCurrent()
        assertEquals(TalkEnd.WORD.sentence, mouth.said.last())
        assertEquals(1, link.sent.size, "the end word is not sent")
        mouth.done!!(); runCurrent()
        d.join()
        assertEquals(TalkPhase.OFF, d.state.phase)
    }

    @Test fun aCancelledUtteranceCannotMoveTheLoop() = runTest {
        val d = driver()
        d.start(); runCurrent()
        val stale = ears.heard!!
        ears.heard!!("ahoj"); runCurrent()
        link.turns.emit(JarvisTurn(testScheduler.currentTime, "Ahoj.")); runCurrent()
        stale("pozdní přepis"); runCurrent()
        assertEquals(listOf("[hlas] ahoj"), link.sent)
        assertEquals(TalkPhase.SPEAKING, d.state.phase)
        d.stop(); runCurrent()
        d.join()
    }

    @Test fun aStoppedReadingsDoneDoesNotListenTwice() = runTest {
        val d = driver()
        d.start(); runCurrent()
        ears.heard!!("ahoj"); runCurrent()
        link.turns.emit(JarvisTurn(testScheduler.currentTime, "Dlouhá odpověď.")); runCurrent()
        val oldDone = mouth.done!!
        d.button(); runCurrent()
        assertEquals(1, mouth.stops)
        assertEquals(2, ears.listens)
        oldDone(); runCurrent()
        assertEquals(2, ears.listens, "the interrupted reading's done is dropped")
        assertEquals(TalkPhase.LISTENING, d.state.phase)
        d.stop(); runCurrent()
        d.join()
    }

    @Test fun aRefusalIsSaidAndTheLoopListensAgain() = runTest {
        link.result = TalkSendResult.Refused("Jarvis teď nemůže přijmout, zkus to za chvíli.")
        val d = driver()
        d.start(); runCurrent()
        ears.heard!!("ahoj"); runCurrent()
        assertEquals(listOf("Jarvis teď nemůže přijmout, zkus to za chvíli."), mouth.said)
        mouth.done!!(); runCurrent()
        assertEquals(TalkPhase.LISTENING, d.state.phase)
        assertEquals(1, link.sent.size)
        d.stop(); runCurrent()
        d.join()
    }

    @Test fun aLostRouteIsSaidOnceAndEnds() = runTest {
        val d = driver()
        d.start(); runCurrent()
        link.up = false
        advanceTimeBy(TalkMachine.ROUTE_GRACE_MS + 1_000); runCurrent()
        assertEquals(listOf(TalkEnd.ROUTE_LOST.sentence), mouth.said)
        assertTrue(ears.cancels >= 1, "the microphone closed")
        mouth.done!!(); runCurrent()
        d.join()
        assertEquals(TalkEnd.ROUTE_LOST, d.state.ended)
    }

    @Test fun aSendThatThrowsIsARefusalNotAHang() = runTest {
        val throwing = object : JarvisVoiceLink by link {
            override suspend fun send(prompt: String, stillWanted: () -> Boolean): TalkSendResult = error("exec broke")
        }
        val d = JarvisTalkDriver(backgroundScope, ears, mouth, throwing, clock = { testScheduler.currentTime })
        d.start(); runCurrent()
        ears.heard!!("ahoj"); runCurrent()
        assertEquals(TalkPhase.SPEAKING, d.state.phase)
        d.stop(); runCurrent()
        d.join()
    }

    // kolo 3: "Ukončit" while sending — a send whose exec already started runs to its end (cut between the typing
    // and the Enter it would leave "[hlas] …" as Jarvis' draft); one that has not started yet never starts
    @Test fun stopWhileSendingNeverCutsAStartedExecAndStopsOneNotYetStarted() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var wantedAtExec: Boolean? = null
        var finished = false
        var cancelled = false
        val slow = object : JarvisVoiceLink by link {
            override suspend fun send(prompt: String, stillWanted: () -> Boolean): TalkSendResult = try {
                gate.await()                 // the voice check before the exec
                wantedAtExec = stillWanted()
                finished = true
                TalkSendResult.Sent()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
        }
        val d = JarvisTalkDriver(backgroundScope, ears, mouth, slow, clock = { testScheduler.currentTime })
        d.start(); runCurrent()
        ears.heard!!("ahoj"); runCurrent()
        assertEquals(TalkPhase.SENDING, d.state.phase)
        d.stop(); runCurrent()
        d.join()
        gate.complete(Unit); runCurrent()
        assertEquals(false, cancelled, "never cancelled")
        assertTrue(finished)
        assertEquals(false, wantedAtExec, "the link is told before the exec that nothing should go in")
        assertEquals(TalkPhase.OFF, d.state.phase, "its late result changes nothing")
    }

    // review LOW: a delayed listen and a cancel are carried out in the loop's order
    @Test fun aDelayedListenNeverOpensTheMicAfterTheReadingStarted() = runTest {
        val d = driver()
        d.start(); runCurrent()
        ears.error!!("socket"); runCurrent()   // -> Listen(1 s) pending
        assertEquals(1, ears.listens)
        d.stop(); runCurrent()
        advanceTimeBy(TalkMachine.STT_RETRY_MS + 100); runCurrent()
        assertEquals(1, ears.listens, "the retry came due after the end: no microphone")
        d.join()
    }

    @Test fun aRetryOpensTheMicOnTheLoop() = runTest {
        val d = driver()
        d.start(); runCurrent()
        ears.error!!("socket"); runCurrent()
        advanceTimeBy(TalkMachine.STT_RETRY_MS + 100); runCurrent()
        assertEquals(2, ears.listens)
        d.stop(); runCurrent()
        d.join()
    }

    @Test fun theServerSecondOfATurnReachesTheMachine() = runTest {
        link.result = TalkSendResult.Sent(serverAtMs = 1_000_000_500)
        val d = driver()
        d.start(); runCurrent()
        ears.heard!!("ahoj"); runCurrent()
        link.turns.emit(JarvisTurn(testScheduler.currentTime, "stará", serverSec = 1_000_000)); runCurrent()
        assertEquals(TalkPhase.THINKING, d.state.phase)
        link.turns.emit(JarvisTurn(testScheduler.currentTime, "Ahoj.", serverSec = 1_000_004)); runCurrent()
        assertEquals(listOf("Ahoj."), mouth.said)
        d.stop(); runCurrent()
        d.join()
    }

    @Test fun aReportIsReadOnlyWhenTheMicrophoneHearsNoWords() = runTest {
        val d = driver()
        d.start(); runCurrent()
        // words already heard in this utterance: the report waits, the microphone stays open
        ears.words = true
        d.report("Relay stojí na limitu."); runCurrent()
        assertEquals(TalkPhase.LISTENING, d.state.phase)
        assertEquals(emptyList(), mouth.said)
        assertEquals(0, ears.cancels)
        // his utterance turned out blank (noise): the waiting report is read now
        ears.words = false
        ears.heard!!(""); runCurrent()
        assertEquals(listOf("Relay stojí na limitu."), mouth.said)
        mouth.done!!(); runCurrent()
        assertEquals(TalkPhase.LISTENING, d.state.phase)
        // silence: a new report closes the microphone and is read at once
        val listensBefore = ears.listens
        d.report("Citace pushnula."); runCurrent()
        assertEquals(TalkPhase.SPEAKING, d.state.phase)
        assertEquals("Citace pushnula.", mouth.said.last())
        assertTrue(ears.cancels >= 1)
        mouth.done!!(); runCurrent()
        assertEquals(listensBefore + 1, ears.listens, "and it listens again after it")
        d.stop(); runCurrent()
    }

    @Test fun aLostHeadsetStopsTheMouthAndEndsTheLoop() = runTest {
        val d = driver()
        d.start(); runCurrent()
        ears.heard!!("co je nového"); runCurrent()
        link.turns.emit(JarvisTurn(testScheduler.currentTime, "Dlouhá odpověď.")); runCurrent()
        assertEquals(TalkPhase.SPEAKING, d.state.phase)
        val stops = mouth.stops
        d.outputLost(); runCurrent()
        assertTrue(mouth.stops > stops, "the reading is stopped")
        d.join()
        assertEquals(TalkEnd.OUTPUT_LOST, d.state.ended)
        assertEquals(listOf("Dlouhá odpověď."), mouth.said, "nothing more is said")
    }
}
