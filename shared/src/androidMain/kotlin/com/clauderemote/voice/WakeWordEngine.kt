package com.clauderemote.voice

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Lightweight Vosk wrapper that consumes raw 16-kHz mono PCM and reports
 * when the configured Czech wake phrase appears in the recognised text.
 *
 * The constructor blocks while Vosk loads its acoustic model (~half a
 * second on a mid-range Android), so this should be created on a worker
 * thread — typically from [com.clauderemote.voice.WakeWordService]'s audio
 * loop, never the main thread.
 *
 * Wake phrases use a JSON keyword grammar so the recognizer focuses on
 * those specific words plus `[unk]` for everything else. This is the
 * recommended Vosk pattern for low-power wake-word style usage.
 */
internal class WakeWordEngine private constructor(
    private val model: Model,
    private val recognizer: Recognizer,
) {
    /** Feed a chunk of 16-kHz mono 16-bit PCM. Returns true on detection. */
    fun process(pcm: ByteArray, length: Int): Boolean {
        val final = recognizer.acceptWaveForm(pcm, length)
        val resultJson = if (final) recognizer.result else recognizer.partialResult
        return containsWakePhrase(resultJson)
    }

    /** Drop accumulated state so the next utterance starts fresh. */
    fun reset() {
        recognizer.reset()
    }

    fun close() {
        runCatching { recognizer.close() }
        runCatching { model.close() }
    }

    companion object {
        // The grammar is duplicated in the recognizer keyword list and the
        // detection regex — keep them in sync. Variants cover Czech spelling
        // ("klaude") and the anglicised "claude" the recognizer may emit
        // depending on how the speaker pronounces it.
        private val WAKE_PHRASES = listOf(
            "hej klaude",
            "hej claude",
            "ahoj klaude",
            "ahoj claude",
        )

        private const val SAMPLE_RATE = 16000f

        /**
         * Open the Vosk model and prepare a recognizer. Returns null if the
         * model directory isn't present — caller should check
         * [VoskModelManager.isModelReady] first and offer to download.
         */
        fun create(context: Context): WakeWordEngine? {
            if (!VoskModelManager.isModelReady(context)) return null
            val dir = VoskModelManager.modelDir(context).absolutePath
            val model = runCatching { Model(dir) }.getOrNull() ?: return null
            val grammar = WAKE_PHRASES.joinToString(
                prefix = "[",
                postfix = ", \"[unk]\"]",
                separator = ", ",
            ) { "\"$it\"" }
            val rec = runCatching { Recognizer(model, SAMPLE_RATE, grammar) }.getOrElse {
                runCatching { model.close() }
                return null
            }
            return WakeWordEngine(model, rec)
        }

        private fun containsWakePhrase(json: String?): Boolean {
            if (json.isNullOrBlank()) return false
            val text = runCatching {
                val obj = JSONObject(json)
                // result has "text", partialResult has "partial".
                obj.optString("text").ifBlank { obj.optString("partial") }
            }.getOrNull().orEmpty().lowercase()
            if (text.isBlank()) return false
            return WAKE_PHRASES.any { it in text }
        }
    }
}
