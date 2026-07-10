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

    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var initializing = false
    @Volatile private var pendingText: String? = null

    fun speak(context: Context, text: String) {
        WearLog.i(context, TAG, "speak() called, ${text.length} chars, ready=$ready initializing=$initializing")
        synchronized(lock) {
            val existing = engine
            if (existing != null && ready) {
                enqueue(context, existing, text)
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
                if (text2 != null) enqueue(context, t, text2)
            }
        }
        WearLog.i(context, TAG, "TextToSpeech engine constructed, awaiting init callback")
    }

    private fun enqueue(context: Context, t: TextToSpeech, text: String) {
        t.stop()
        val id = "wear-utt-${System.nanoTime()}"
        val result = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        WearLog.i(context, TAG, "speak($id) enqueue result=$result (0=SUCCESS, -1=ERROR)")
    }
}
