package com.clauderemote.wear

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Minimal on-watch TTS — mirrors the phone's TtsHolder shape (lazy init,
 * reused across calls). Process-scoped: [WearDataListenerService] instances
 * are short-lived (system-created per event batch), so the engine itself
 * must live outside any single instance. Tries cs-CZ first (assistant
 * messages are often Czech), falls back to the watch's default voice if
 * that locale isn't installed.
 *
 * All mutable state is guarded by [lock]. Confirmed on a real device: a
 * burst of `speak()` calls arriving before the FIRST engine finished
 * initializing (e.g. many sessions transitioning to WAITING_FOR_INPUT at
 * once right after a fresh sync) each saw `ready == false` and constructed
 * their OWN new TextToSpeech engine, clobbering the shared `engine`
 * reference — so none of them reliably finished speaking a single word.
 * The `initializing` guard ensures only the FIRST call constructs an
 * engine; later calls during that window just replace `pendingText`
 * (last-writer-wins) instead of spawning a competing instance.
 */
object WatchTts {
    private const val TAG = "WatchTts"
    private val lock = Any()
    // A bound TextToSpeech engine was never released once constructed —
    // process-scoped, so it stayed bound to the system TTS service
    // indefinitely even during long stretches with nothing to say. Shutting
    // down after a period of no new speak() calls frees it; the next speak()
    // just reconstructs it (a few hundred ms, same cost as the very first
    // utterance already pays).
    private const val IDLE_SHUTDOWN_MS = 30_000L
    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleShutdownRunnable = Runnable {
        synchronized(lock) {
            engine?.shutdown()
            engine = null
            ready = false
        }
    }

    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var initializing = false
    @Volatile private var pendingText: String? = null

    /**
     * @param interrupt if true, stops whatever is currently speaking and
     * speaks [text] immediately (the manual "Přehrát" tap — the user
     * explicitly wants to hear this now). If false, queues after anything
     * already speaking (auto-speak-on-transition) — with `interrupt` always
     * true here, a burst of session transitions (common right after a
     * sync) kept cutting each other off mid-sentence before finishing even
     * one message; confirmed on a real device via the "speak() enqueue"
     * log lines.
     */
    fun speak(context: Context, text: String, interrupt: Boolean = false) {
        WearLog.i(context, TAG, "speak() called, ${text.length} chars, ready=$ready initializing=$initializing interrupt=$interrupt")
        synchronized(lock) {
            val existing = engine
            if (existing != null && ready) {
                enqueue(context, existing, text, interrupt)
                return
            }
            pendingText = text
            if (initializing) {
                WearLog.i(context, TAG, "init already in flight — replaced pending text, no new engine")
                return
            }
            initializing = true
        }
        // Constructing the engine happens OUTSIDE the lock (the constructor
        // itself can be slow) — safe because `initializing` is already true,
        // so no concurrent caller will start a second one.
        engine = TextToSpeech(context.applicationContext) { status ->
            synchronized(lock) {
                initializing = false
                val t = engine
                if (t == null || status != TextToSpeech.SUCCESS) {
                    WearLog.w(context, TAG, "TextToSpeech init FAILED, status=$status")
                    return@synchronized
                }
                val langResult = t.setLanguage(Locale.forLanguageTag("cs-CZ"))
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    WearLog.w(context, TAG, "cs-CZ not available (result=$langResult), falling back to device default")
                    val fallback = t.setLanguage(Locale.getDefault())
                    WearLog.i(context, TAG, "fallback setLanguage result=$fallback")
                } else {
                    WearLog.i(context, TAG, "cs-CZ language set, result=$langResult")
                }
                // speak() returning SUCCESS only means the engine accepted the
                // request, not that anything audible came out — on some Wear
                // OS builds STREAM_MUSIC output from a caller with no audio
                // focus (e.g. a background service, not the foreground
                // Activity) gets silently dropped. USAGE_ASSISTANCE_
                // ACCESSIBILITY is the usage meant for exactly this ("app
                // needs to speak something aloud regardless of foreground
                // state"), same category TalkBack/screen readers use.
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val musicVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)
                val musicMax = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                WearLog.i(context, TAG, "STREAM_MUSIC volume=$musicVol/$musicMax")
                ready = true
                val text2 = pendingText
                pendingText = null
                // Nothing has spoken yet during boot, so queue-vs-flush is
                // moot here — always safe to just queue.
                if (text2 != null) enqueue(context, t, text2, interrupt = false)
            }
        }
        WearLog.i(context, TAG, "TextToSpeech engine constructed, awaiting init callback")
    }

    /**
     * Called when the user flips "Číst nahlas" off — QUEUE_ADD means any
     * backlog already queued (e.g. from a burst of session transitions)
     * keeps playing out otherwise, since the toggle only gates whether NEW
     * text gets enqueued, not what's already sitting in the engine's queue.
     * TextToSpeech.stop() both halts the current utterance and discards the
     * rest of the queue.
     */
    fun stop(context: Context) {
        synchronized(lock) {
            engine?.stop()
        }
        WearLog.i(context, TAG, "stop() called — halted playback and cleared queue")
    }

    private fun enqueue(context: Context, t: TextToSpeech, text: String, interrupt: Boolean) {
        if (interrupt) t.stop()
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val id = "wear-utt-${System.nanoTime()}"
        val result = t.speak(text, mode, null, id)
        WearLog.i(context, TAG, "speak($id) enqueue mode=$mode result=$result (0=SUCCESS, -1=ERROR)")
        idleHandler.removeCallbacks(idleShutdownRunnable)
        idleHandler.postDelayed(idleShutdownRunnable, IDLE_SHUTDOWN_MS)
    }
}
