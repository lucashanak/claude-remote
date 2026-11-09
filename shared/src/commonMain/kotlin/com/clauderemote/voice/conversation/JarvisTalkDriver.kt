package com.clauderemote.voice.conversation

import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The platform's speech-to-text for one utterance at a time. Callbacks may come on any thread; the driver drops the
 * ones of an utterance it has already cancelled.
 */
interface TalkEars {
    /** Open the microphone; [onHeard] once with the utterance (blank = nothing said), or [onError]. */
    fun listen(onHeard: (String) -> Unit, onError: (String) -> Unit)
    /** End the utterance now; [onHeard] still comes with what was said so far. */
    fun finish()
    /** Close the microphone; no callback matters any more. */
    fun cancel()
}

/** The platform's read-aloud. [speak]'s [onDone] must come exactly once — after the text, a stop or an error. */
interface TalkMouth {
    fun speak(text: String, onDone: () -> Unit)
    fun stop()
    fun tone(tone: TalkTone)
}

/**
 * A finished turn of Jarvis: [body] as the Stop notification chose it, [atMs] = when it ended on the device clock,
 * [serverSec] = the Stop marker's epoch second on the server's clock.
 */
data class JarvisTurn(val atMs: Long?, val body: String?, val serverSec: Long? = null)

/**
 * The app's one path into Jarvis' pane (SessionOrchestrator.jarvisVoiceLink): the checked reply exec of the watch's
 * answers, never a new channel, never another session.
 */
interface JarvisVoiceLink {
    /**
     * Types [prompt] into Jarvis' pane and presses Enter, only while he is idle with an empty input. [stillWanted] is
     * asked right before the exec starts; once it has started it runs to its end even if the mode ends meanwhile —
     * cut between the typing and the Enter, it would leave `[hlas] …` as a draft in his input.
     */
    suspend fun send(prompt: String, stillWanted: () -> Boolean = { true }): TalkSendResult
    /** Jarvis' session can be reached now (his tab's transport is up). Called every tick: cheap, any thread. */
    fun reachable(): Boolean
    /** Jarvis' finished turns, as they end. */
    val answers: Flow<JarvisTurn>
}

/**
 * Runs [TalkMachine] against the platform: one coroutine takes the events in order (callbacks, Jarvis' turns, a tick
 * every [TICK_MS]) and carries out the effects. Every listen and every speak has a generation, so a late callback of
 * one that was cancelled (the microphone closed under a final transcript, a reading stopped by the button) can never
 * move the loop.
 */
class JarvisTalkDriver(
    private val scope: CoroutineScope,
    private val ears: TalkEars,
    private val mouth: TalkMouth,
    private val link: JarvisVoiceLink,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val tickMs: Long = TICK_MS,
    private val onState: (TalkState) -> Unit = {},
) {
    /** The loop's inbox: the machine's events, and a delayed listen coming due ([ListenDue]). */
    private val events = Channel<Any>(Channel.UNLIMITED)

    /** A [TalkEffect.Listen] with a delay is due: opened on the loop, so it is ordered against a CancelListening. */
    private class ListenDue(val gen: Int)

    /** Whether the send under way is still wanted ([TalkEffect.CancelSend] clears it; a started exec is never cut). */
    @kotlin.concurrent.Volatile private var sendWanted: java.util.concurrent.atomic.AtomicBoolean? = null
    @kotlin.concurrent.Volatile private var job: Job? = null
    @kotlin.concurrent.Volatile var state: TalkState = TalkState()
        private set

    // touched only on the loop's coroutine (effects) and compared in callbacks: a stale value only drops a callback
    @kotlin.concurrent.Volatile private var listenGen = 0
    @kotlin.concurrent.Volatile private var speakGen = 0

    /** False when it already runs. */
    fun start(): Boolean {
        if (job != null) return false
        events.trySend(TalkEvent.Start)
        job = scope.launch { loop() }
        return true
    }

    fun button() { events.trySend(TalkEvent.Button) }

    fun stop() { events.trySend(TalkEvent.Stop) }

    /** Completes when the conversation has ended. */
    suspend fun join() { job?.join() }

    private suspend fun loop() {
        val answers = scope.launch { link.answers.collect { events.trySend(TalkEvent.Answer(it.body, it.atMs, it.serverSec)) } }
        val ticker = scope.launch {
            while (isActive) {
                delay(tickMs)
                val ok = try { link.reachable() } catch (e: Exception) { false }
                events.trySend(TalkEvent.Tick(ok))
            }
        }
        try {
            for (m in events) {
                if (m is ListenDue) {
                    if (m.gen == listenGen) openEars(m.gen)
                    continue
                }
                val e = m as TalkEvent
                val step = TalkMachine.reduce(state, e, clock())
                if (step.state.phase != state.phase) FileLogger.log(TAG, "${state.phase} -> ${step.state.phase} on ${e::class.simpleName}")
                state = step.state
                onState(state)
                step.effects.forEach { apply(it) }
                if (step.effects.any { it is TalkEffect.Ended }) break
            }
        } finally {
            sendWanted?.set(false)
            answers.cancel()
            ticker.cancel()
            listenGen++
            speakGen++
            runCatching { ears.cancel() }
            runCatching { mouth.stop() }
        }
    }

    private fun apply(effect: TalkEffect) {
        when (effect) {
            // Opened on the loop itself (a delayed one comes back as ListenDue): a CancelListening handled after it is
            // always also carried out after it, so the microphone never opens under a reading.
            is TalkEffect.Listen -> {
                val gen = ++listenGen
                if (effect.delayMs <= 0) openEars(gen)
                else scope.launch { delay(effect.delayMs); events.trySend(ListenDue(gen)) }
            }
            TalkEffect.FinishListening -> ears.finish()
            TalkEffect.CancelListening -> { listenGen++; ears.cancel() }
            TalkEffect.CancelSend -> sendWanted?.set(false)
            is TalkEffect.Send -> {
                val wanted = java.util.concurrent.atomic.AtomicBoolean(true).also { sendWanted = it }
                scope.launch {
                    val r = try {
                        link.send(effect.prompt) { wanted.get() }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FileLogger.log(TAG, "send failed: ${e::class.simpleName}: ${e.message}")
                        TalkSendResult.Refused("Poslat to nešlo, zkus to znovu.")
                    }
                    events.trySend(TalkEvent.SendDone(r))
                }
            }
            is TalkEffect.Speak -> {
                val gen = ++speakGen
                mouth.speak(effect.text) { if (gen == speakGen) events.trySend(TalkEvent.SpeechDone) }
            }
            TalkEffect.StopSpeaking -> { speakGen++; mouth.stop() }
            is TalkEffect.Tone -> runCatching { mouth.tone(effect.tone) }
            is TalkEffect.Ended -> FileLogger.log(TAG, "ended: ${effect.why}")
        }
    }

    private fun openEars(gen: Int) {
        ears.listen(
            onHeard = { t -> if (gen == listenGen) events.trySend(TalkEvent.Heard(t)) },
            onError = { w -> if (gen == listenGen) events.trySend(TalkEvent.SttFailed(w)) },
        )
    }

    companion object {
        private const val TAG = "JarvisTalk"
        const val TICK_MS = 500L
    }
}

/** What a platform provides to run the mode: Android's microphone service now, the desktop once its player exists. */
interface JarvisTalkHost {
    /** Start the mode (from a foreground action: Android may only open the microphone then). */
    fun start()
    fun stop()
    fun button()
}

/**
 * The mode as the UI sees it, process wide: its state (the platform's driver publishes it) and the platform host.
 * [host] null = this platform cannot hold a conversation (the desktop until its Soniox player is wired): the
 * "Mluvit s Jarvisem" buttons are not drawn.
 */
object JarvisTalk {
    private val _state = MutableStateFlow(TalkState())
    val state: StateFlow<TalkState> = _state.asStateFlow()

    @kotlin.concurrent.Volatile var host: JarvisTalkHost? = null

    /** A sentence for the UI when the mode could not even start (no Soniox key, no microphone permission). */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    fun publish(s: TalkState) { _state.value = s }

    fun report(problem: String?) { _problem.value = problem }

    fun start() { _problem.value = null; host?.start() }

    fun stop() { host?.stop() }

    fun button() { host?.button() }

    /** The UI line for [s]. */
    fun label(s: TalkState): String = when (s.phase) {
        TalkPhase.OFF -> when (s.ended) {
            null -> "Konverzace s Jarvisem"
            TalkEnd.BUTTON -> "Konverzace ukončena"
            else -> s.ended.sentence ?: "Konverzace ukončena"
        }
        TalkPhase.LISTENING -> if (s.awaiting) "Poslouchám… (Jarvis ještě odpoví)" else "Poslouchám…"
        TalkPhase.SENDING -> "Posílám Jarvisovi…"
        TalkPhase.THINKING -> "Jarvis přemýšlí…"
        TalkPhase.SPEAKING -> if (s.ending != null) "Končím…" else "Mluví…"
    }
}
