package com.clauderemote.wear

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Minimal on-watch TTS — mirrors the phone's TtsHolder shape (lazy init,
 * reused across calls). Process-scoped: [WearDataListenerService] instances
 * are short-lived (system-created per event batch), so the engine itself
 * must live outside any single instance. Tries cs-CZ first (assistant
 * messages are often Czech), falls back to the watch's default voice if
 * that locale isn't installed.
 */
object WatchTts {
    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var pendingText: String? = null

    fun speak(context: Context, text: String) {
        val existing = engine
        if (existing != null && ready) {
            enqueue(existing, text)
            return
        }
        pendingText = text
        engine = TextToSpeech(context.applicationContext) { status ->
            val t = engine ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val langResult = t.setLanguage(Locale.forLanguageTag("cs-CZ"))
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                t.setLanguage(Locale.getDefault())
            }
            ready = true
            pendingText?.let { enqueue(t, it) }
            pendingText = null
        }
    }

    private fun enqueue(t: TextToSpeech, text: String) {
        t.stop()
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wear-utt-${System.nanoTime()}")
    }
}
