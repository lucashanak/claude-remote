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

/** Package name of the Google on-device TTS engine. */
private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

/**
 * Process-wide TextToSpeech holder for the on-device (Google) TTS engine.
 * Tapping a different SpeakerButton — or voice mode interrupting in-flight
 * speech — replaces the active completion so the previous caller's
 * "speaking" state is reset before the new utterance starts. Completion
 * callbacks are always dispatched on the main thread, so callers can mutate
 * Compose state directly.
 *
 * The engine is forced to [GOOGLE_TTS_PACKAGE] because the device default
 * (e.g. Xiaomi's own engine on HyperOS) may lack Czech; if Google TTS isn't
 * installed we fall back once to the device default. Failures (no engine,
 * missing cs-CZ voice) are surfaced via the optional `onError` so the
 * settings test button / voice mode can report them instead of silently
 * doing nothing.
 */
internal object TtsHolder {
    private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var languageOk = false
    @Volatile private var triedFallback = false
    @Volatile private var pendingText: String? = null
    @Volatile private var appContext: Context? = null
    private val currentUtterance = AtomicReference<String?>(null)
    private val currentCompletion = AtomicReference<(() -> Unit)?>(null)
    private val currentError = AtomicReference<((String) -> Unit)?>(null)

    fun speak(
        context: Context,
        text: String,
        onFinish: () -> Unit,
        onError: ((String) -> Unit)? = null,
    ) {
        currentCompletion.getAndSet(onFinish)?.let { postOnMain(it) }
        currentError.set(onError)
        appContext = context.applicationContext

        val existing = engine
        if (existing != null && ready) {
            if (!languageOk) {
                reportError("Hlas cs-CZ není v zařízení nainstalovaný (Nastavení Androidu → Řeč).")
                fireCurrentCompletion()
                return
            }
            enqueue(existing, text)
            return
        }
        pendingText = text
        triedFallback = false
        startEngine(GOOGLE_TTS_PACKAGE)
    }

    private fun startEngine(pkg: String?) {
        val ctx = appContext ?: return
        val initListener = TextToSpeech.OnInitListener { status -> onInit(status, pkg) }
        engine = runCatching {
            if (pkg != null) TextToSpeech(ctx, initListener, pkg) else TextToSpeech(ctx, initListener)
        }.getOrNull()
        if (engine == null) onInit(TextToSpeech.ERROR, pkg)
    }

    private fun onInit(status: Int, pkg: String?) {
        if (status != TextToSpeech.SUCCESS) {
            // Google engine not present → fall back to the device default once.
            if (pkg != null && !triedFallback) {
                triedFallback = true
                runCatching { engine?.shutdown() }
                engine = null
                startEngine(null)
                return
            }
            ready = false
            reportError("TTS engine se nepodařilo spustit.")
            fireCurrentCompletion()
            return
        }
        val t = engine ?: return
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
        val txt = pendingText
        if (languageOk && txt != null) {
            enqueue(t, txt)
        } else {
            reportError("Hlas cs-CZ není v zařízení nainstalovaný (Nastavení Androidu → Řeč).")
            fireCurrentCompletion()
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

    private fun reportError(msg: String) {
        currentError.getAndSet(null)?.let { e -> postOnMain { e(msg) } }
    }
}

/**
 * Routes a TTS request to the engine the user picked in settings
 * ([com.clauderemote.model.TtsEngine]). Used by SpeakerButton, voice mode,
 * and the settings test button so engine selection lives in exactly one
 * place. Always stops any prior playback first (across all engines) so
 * switching engines never overlaps audio. `onFinish` is always invoked
 * eventually so caller state machines (voice mode) keep moving; `onError`
 * carries a human message for Toast / error state.
 */
internal fun speakRouted(
    context: Context,
    text: String,
    onFinish: () -> Unit,
    onError: ((String) -> Unit)? = null,
) {
    stopAllTts()
    when (selectedTtsEngine(context)) {
        com.clauderemote.model.TtsEngine.SERVER -> {
            val cfg = ttsServerConfig(context)
            if (cfg.url.isBlank()) {
                onError?.invoke("Není nastavená adresa serveru (Nastavení → Voice).")
                onFinish()
            } else {
                ServerTts.speak(context, cfg.url, cfg.model, cfg.voice, cfg.apiKey, text, onFinish, onError)
            }
        }
        com.clauderemote.model.TtsEngine.GOOGLE_CLOUD -> {
            val cfg = googleCloudConfig(context)
            if (cfg.apiKey.isBlank()) {
                onError?.invoke("Chybí Google Cloud API klíč (Nastavení → Voice).")
                onFinish()
            } else {
                GoogleCloudTts.speak(context, cfg.apiKey, cfg.voice, text, onFinish, onError)
            }
        }
        com.clauderemote.model.TtsEngine.SYSTEM -> {
            TtsHolder.speak(context, text, onFinish, onError)
        }
    }
}

/** Stops every TTS engine (file-based core + on-device). */
internal fun stopAllTts() {
    MediaTtsCore.stop()
    TtsHolder.stop()
}
