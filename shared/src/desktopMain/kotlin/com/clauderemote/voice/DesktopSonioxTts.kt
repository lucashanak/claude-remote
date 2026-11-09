package com.clauderemote.voice

import com.clauderemote.connection.LongReads
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.roundToInt

/**
 * Soniox read-aloud on desktop: the shared [SonioxTtsSession] (the phone's
 * segmented player — paragraphs, buffering ahead, reconnects, the
 * [TtsPlayerBar][com.clauderemote.ui.TtsPlayerBar] transport) played through a
 * `javax.sound` [SourceDataLine].
 *
 * Reading speed is Soniox's own `speed` field, clamped to the 0.7–1.3 it
 * accepts ([sonioxStreamSpeed]). javax.sound has no pitch-preserving
 * time-stretch (Android's AudioTrack.playbackParams), and playing the 8 kHz
 * stream at another sample rate would change the pitch with the speed. The
 * server-side speed keeps the voice natural at no CPU cost; the price is that
 * a setting above 130 % reads at 130 % (logged once).
 */

/** PCM16 mono little-endian, as [TtsAudioSink] delivers it. */
internal fun ttsPcmFormat(sampleRate: Int) = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)

/** The system's default output, opened with ~0.5 s of buffer like the phone's AudioTrack. */
internal fun defaultTtsLine(format: AudioFormat, bufferBytes: Int): SourceDataLine =
    AudioSystem.getSourceDataLine(format).apply { open(format, bufferBytes) }

/**
 * [TtsAudioSink] over a [SourceDataLine]. Pause is `stop()`: per its contract a
 * blocking `write` on a stopped (or flushed) line returns what it wrote so far,
 * which is the short count the session waits on. [paused] makes a write that
 * starts during a pause return 0 at once instead of filling the buffer and
 * blocking until resume.
 */
internal class JavaSoundSink(
    private val openLine: (AudioFormat, Int) -> SourceDataLine = ::defaultTtsLine,
) : TtsAudioSink {
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var paused = true

    override fun open(sampleRate: Int): Int {
        val l = openLine(ttsPcmFormat(sampleRate), sampleRate) // 0.5 s of PCM16 at 8 kHz
        line = l
        return l.bufferSize
    }

    override fun write(pcm: ByteArray, off: Int, len: Int): Int {
        val l = line ?: return -1
        if (paused) return 0
        return l.write(pcm, off, len)
    }

    override fun play() {
        paused = false
        line?.start()
    }

    override fun pause() {
        paused = true
        line?.stop()
    }

    override fun flush() {
        line?.flush()
    }

    override fun halt() {
        paused = true
        line?.let { runCatching { it.stop() }; runCatching { it.flush() } }
    }

    override fun release() {
        line?.let { runCatching { it.stop() }; runCatching { it.close() } }
        line = null
    }
}

/**
 * Read-aloud on desktop, whichever engine speaks: Soniox when Settings → Voice
 * has a key, else the OS synthesiser ([DesktopSpeech]). Soniox falls back to
 * the OS synthesiser for the part not yet heard when the key is refused, the
 * server fails, the network gives nothing for [SONIOX_FALLBACK_MS], or there is
 * no audio device — logged, never thrown.
 *
 * This is also the entry for other desktop callers (Jarvis conversation mode):
 * [speak] with a completion, [stop], and [isSpeaking].
 */
internal object DesktopReadAloud {
    private const val TAG = "DesktopTts"
    /** No audio at all this long after starting = speak with the OS synthesiser instead. */
    const val SONIOX_FALLBACK_MS = 8_000L
    /** A wired desktop does not need the phone's 5 min of patience before giving up mid-reading. */
    private val desktopTuning = SonioxTtsTuning(giveUpMs = 60_000L)

    private val stopSeq = AtomicLong(0)
    private val warnedNoKey = AtomicBoolean(false)
    private val warnedClamp = AtomicBoolean(false)
    private val lastFallbackReason = AtomicReference<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + LongReads)

    /** speakBlocking calls in progress, hand-over from Soniox to the OS synthesiser included. */
    private val reading = AtomicInteger(0)

    /**
     * True while a reading is in progress on either engine — also in the gap
     * while Soniox hands the rest over to the OS synthesiser, so a caller
     * taking turns (Jarvis conversation mode) never sees a false "done".
     */
    fun isSpeaking(): Boolean = reading.get() > 0 || SonioxTtsPlayer.isActive || DesktopSpeech.isSpeaking()

    /**
     * Reads [text] (already speakable — no markdown) and returns null when it
     * finished or was stopped, or a message when nothing could be spoken.
     * BLOCKS for the whole reading: call it on [LongReads], never Dispatchers.IO.
     * Never throws.
     *
     * [systemSpeak] gets the text, the rate and a "stopped meanwhile" check;
     * [beforeHandOver] is a test seam that runs between Soniox ending and the
     * OS synthesiser starting.
     */
    fun speakBlocking(
        text: String,
        ratePct: Int,
        apiKey: String = desktopSonioxApiKey(),
        voice: String = desktopSonioxTtsVoice(),
        sockets: TtsSocketFactory = sonioxOkHttpSockets(apiKey),
        sink: () -> TtsAudioSink = { JavaSoundSink() },
        systemSpeak: (String, Int, () -> Boolean) -> String? =
            { t, r, cancelled -> DesktopSpeech.speakBlocking(t, r, cancelled = cancelled) },
        fallbackAfterMs: Long = SONIOX_FALLBACK_MS,
        beforeHandOver: () -> Unit = {},
    ): String? {
        stop()
        val seq = stopSeq.get()
        val cancelled = { stopSeq.get() != seq }
        reading.incrementAndGet()
        try {
            if (apiKey.isBlank()) {
                if (warnedNoKey.compareAndSet(false, true)) {
                    FileLogger.log(TAG, "no Soniox key in Settings → Voice — reading with the system synthesiser")
                }
                return systemSpeak(text, ratePct, cancelled)
            }
            val segments = ttsSegments(text)
            if (segments.isEmpty()) return null
            val speed = sonioxStreamSpeed(ratePct / 100f)
            // The rate Soniox actually reads at — the hand-over keeps it, so the
            // voice changes but the pace does not jump (250 % → 130 % → 250 %).
            val effectivePct = ((speed ?: 1.0) * 100).roundToInt()
            if (effectivePct != ratePct && warnedClamp.compareAndSet(false, true)) {
                FileLogger.log(TAG, "reading speed $ratePct % is outside Soniox's 70–130 % — using $effectivePct %")
            }
            val done = CountDownLatch(1)
            val fallback = AtomicReference<Pair<String, Int>?>(null)
            val started = runCatching {
                val session = SonioxTtsSession(
                    segments = segments,
                    voice = voice,
                    sink = sink(),
                    sockets = sockets,
                    onFinish = { done.countDown() },
                    streamSpeed = speed,
                    onFallback = { reason, from -> fallback.set(reason to from) },
                    fallbackAfterMs = fallbackAfterMs,
                    tuning = desktopTuning,
                    tag = TAG,
                )
                FileLogger.log(TAG, "speak via Soniox (${text.length} chars → ${segments.size} segments, speed $effectivePct %)")
                SonioxTtsPlayer.start(session)
            }
            if (started.isFailure) {
                // Defensive: the session reports its own failures through
                // onFallback; anything thrown here must still end in speech
                // and a reset button, never in the caller's scope.
                SonioxTtsPlayer.stop()
                fallback.set("start selhal: ${started.exceptionOrNull()?.message}" to 0)
                done.countDown()
            }
            done.await()
            val (reason, from) = fallback.get() ?: run {
                lastFallbackReason.set(null)
                return null
            }
            beforeHandOver()
            // Stopped or superseded while the session was ending: say nothing more.
            if (cancelled()) return null
            // Once per run of the same failure — a refused key would otherwise log on every message.
            if (lastFallbackReason.getAndSet(reason) != reason) {
                FileLogger.log(TAG, "Soniox failed ($reason) — system synthesiser reads from segment ${from + 1}/${segments.size}")
            }
            val rest = segments.drop(from).joinToString("\n\n")
            return systemSpeak(rest, effectivePct, cancelled)
        } catch (e: Exception) {
            FileLogger.log(TAG, "read-aloud failed: ${e.message}")
            return e.message ?: "read-aloud failed"
        } finally {
            reading.decrementAndGet()
        }
    }

    /**
     * Non-blocking [speakBlocking]: [onDone] gets null or the error, on a
     * background thread, once the reading ended for any reason.
     */
    fun speak(text: String, ratePct: Int = desktopTtsSpeechRatePct(), onDone: (String?) -> Unit = {}) {
        scope.launch { onDone(runCatching { speakBlocking(text, ratePct) }.getOrElse { it.message ?: "TTS failed" }) }
    }

    /**
     * Stops whatever is reading, on either engine. Safe when nothing is. The
     * counter goes first, so a hand-over checking it right after Soniox ends
     * sees this stop.
     */
    fun stop() {
        stopSeq.incrementAndGet()
        SonioxTtsPlayer.stop()
        DesktopSpeech.stop()
    }

    /** [stop] off the caller's thread (spd-say's cancel forks a process). */
    fun stopAsync() {
        scope.launch { stop() }
    }
}
