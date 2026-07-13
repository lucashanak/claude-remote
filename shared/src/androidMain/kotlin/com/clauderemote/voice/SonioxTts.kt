package com.clauderemote.voice

import android.content.Context
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Text-to-speech via Soniox's REST API (`POST tts-rt.soniox.com/tts`).
 * Returns raw WAV bytes for the whole utterance, handed to the shared
 * [MediaTtsCore] player — same shape as [GoogleCloudTts].
 *
 * Voices are language-agnostic (one speaker name works across all 60+
 * languages), so [voice] is a name like "Adrian"/"Maya", not a locale;
 * `language` is sent as cs but mixed Czech/English text reads fine.
 */
internal object SonioxTts {
    private const val TAG = "SonioxTts"
    private const val TTS_URL = "https://tts-rt.soniox.com/tts"
    private const val MODEL = "tts-rt-v1"
    private const val SAMPLE_RATE = 24000

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun speak(
        context: Context,
        apiKey: String,
        voice: String,
        text: String,
        onFinish: () -> Unit,
        onError: ((String) -> Unit)? = null,
    ) {
        MediaTtsCore.speak(context, ".wav", onFinish, onError) {
            fetch(apiKey, voice, text)
        }
    }

    fun stop() = MediaTtsCore.stop()

    private suspend fun fetch(
        apiKey: String,
        voice: String,
        text: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw RuntimeException("Chybí Soniox API klíč")
        val json = JSONObject()
            .put("model", MODEL)
            .put("language", "cs")
            .put("voice", voice.ifBlank { "Adrian" })
            .put("audio_format", "wav")
            .put("sample_rate", SAMPLE_RATE)
            .put("text", text)
            .toString()
        val req = Request.Builder()
            .url(TTS_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                // Error responses come back as JSON, not audio.
                val payload = resp.body?.string().orEmpty()
                val msg = runCatching { JSONObject(payload).optString("error_message") }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                FileLogger.log(TAG, "HTTP ${resp.code} — ${msg ?: payload.take(200)}")
                throw RuntimeException("HTTP ${resp.code}${if (msg != null) " — $msg" else ""}")
            }
            val bytes = resp.body?.bytes() ?: throw RuntimeException("Prázdná odpověď TTS")
            val ct = resp.header("Content-Type") ?: "?"
            FileLogger.log(TAG, "ok: ${bytes.size} bytes, Content-Type=$ct")
            bytes
        }
    }
}
