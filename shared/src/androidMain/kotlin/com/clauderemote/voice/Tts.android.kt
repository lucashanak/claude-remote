package com.clauderemote.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

internal const val CZECH_LOCALE_TAG = "cs-CZ"

private val mainHandler = Handler(Looper.getMainLooper())

internal fun postOnMain(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
}

/**
 * Process-wide TextToSpeech holder. Tapping a different SpeakerButton — or
 * voice mode interrupting in-flight speech — replaces the active completion
 * so the previous caller's "speaking" state is reset before the new
 * utterance starts. Completion callbacks are always dispatched on the main
 * thread, so callers can mutate Compose state directly.
 */
internal object TtsHolder {
    private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var languageOk = false
    private val currentUtterance = AtomicReference<String?>(null)
    private val currentCompletion = AtomicReference<(() -> Unit)?>(null)

    fun speak(context: Context, text: String, onFinish: () -> Unit) {
        currentCompletion.getAndSet(onFinish)?.let { postOnMain(it) }

        val existing = engine
        if (existing != null && ready) {
            if (!languageOk) {
                fireCurrentCompletion()
                return
            }
            enqueue(existing, text)
            return
        }
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                ready = false
                fireCurrentCompletion()
                return@TextToSpeech
            }
            val t = engine ?: return@TextToSpeech
            val langResult = t.setLanguage(Locale.forLanguageTag(CZECH_LOCALE_TAG))
            languageOk = langResult != TextToSpeech.LANG_MISSING_DATA &&
                langResult != TextToSpeech.LANG_NOT_SUPPORTED
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finish(utteranceId)
                @Deprecated("Required override on older API levels.")
                override fun onError(utteranceId: String?) = finish(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)

                private fun finish(utteranceId: String?) {
                    if (utteranceId != null && utteranceId == currentUtterance.get()) {
                        currentUtterance.set(null)
                        fireCurrentCompletion()
                    }
                }
            })
            ready = true
            if (languageOk) enqueue(t, text) else fireCurrentCompletion()
        }
    }

    private fun enqueue(t: TextToSpeech, text: String) {
        val id = "cr-utt-${System.nanoTime()}"
        currentUtterance.set(id)
        t.stop()
        t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    fun stop() {
        engine?.stop()
        currentUtterance.set(null)
        fireCurrentCompletion()
    }

    private fun fireCurrentCompletion() {
        currentCompletion.getAndSet(null)?.let { postOnMain(it) }
    }
}
