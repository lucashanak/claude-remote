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
    private const val TAG = "WatchTts"

    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var pendingText: String? = null

    fun speak(context: Context, text: String) {
        WearLog.i(context, TAG, "speak() called, ${text.length} chars, engineReady=$ready")
        val existing = engine
        if (existing != null && ready) {
            enqueue(context, existing, text)
            return
        }
        pendingText = text
        engine = TextToSpeech(context.applicationContext) { status ->
            val t = engine ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                WearLog.w(context, TAG, "TextToSpeech init FAILED, status=$status")
                return@TextToSpeech
            }
            val langResult = t.setLanguage(Locale.forLanguageTag("cs-CZ"))
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                WearLog.w(context, TAG, "cs-CZ not available (result=$langResult), falling back to device default")
                val fallback = t.setLanguage(Locale.getDefault())
                WearLog.i(context, TAG, "fallback setLanguage result=$fallback")
            } else {
                WearLog.i(context, TAG, "cs-CZ language set, result=$langResult")
            }
            ready = true
            pendingText?.let { enqueue(context, t, it) }
            pendingText = null
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
