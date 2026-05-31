package com.clauderemote.voice

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Text-to-speech via the Google Cloud Text-to-Speech REST API
 * (`texttospeech.googleapis.com/v1/text:synthesize`). Returns base64 MP3
 * in `audioContent`, which we decode and hand to the shared [MediaTtsCore].
 *
 * Needs an API key (Google Cloud project + billing). The `languageCode` is
 * derived from the voice name's locale prefix (e.g. `cs-CZ-Wavenet-A` →
 * `cs-CZ`), so changing the voice to another locale just works.
 */
internal object GoogleCloudTts {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun speak(
        context: Context,
        apiKey: String,
        voiceName: String,
        text: String,
        rate: Float = 1.0f,
        onFinish: () -> Unit,
        onError: ((String) -> Unit)? = null,
    ) {
        MediaTtsCore.speak(context, ".mp3", onFinish, onError) {
            fetch(apiKey, voiceName, text, rate)
        }
    }

    fun stop() = MediaTtsCore.stop()

    private suspend fun fetch(
        apiKey: String,
        voiceName: String,
        text: String,
        rate: Float,
    ): ByteArray = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw RuntimeException("Chybí Google Cloud API klíč")
        val languageCode = voiceName.split("-").take(2).joinToString("-").ifBlank { "cs-CZ" }
        val audioConfig = JSONObject()
            .put("audioEncoding", "MP3")
            // Google Cloud speakingRate is 0.25–4.0 (1.0 = normal).
            .apply { if (rate != 1.0f) put("speakingRate", rate.coerceIn(0.25f, 4.0f).toDouble()) }
        val json = JSONObject()
            .put("input", JSONObject().put("text", text))
            .put(
                "voice",
                JSONObject().put("languageCode", languageCode).put("name", voiceName),
            )
            .put("audioConfig", audioConfig)
            .toString()
        val req = Request.Builder()
            .url("https://texttospeech.googleapis.com/v1/text:synthesize?key=$apiKey")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    JSONObject(payload).getJSONObject("error").getString("message")
                }.getOrNull()
                throw RuntimeException(
                    "HTTP ${resp.code}${if (!msg.isNullOrBlank()) " — $msg" else ""}",
                )
            }
            val b64 = runCatching { JSONObject(payload).optString("audioContent") }.getOrNull()
            if (b64.isNullOrBlank()) throw RuntimeException("Odpověď bez audioContent")
            Base64.decode(b64, Base64.DEFAULT)
        }
    }
}
