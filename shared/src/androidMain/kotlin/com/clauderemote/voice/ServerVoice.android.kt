package com.clauderemote.voice

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Server-side text-to-speech via an OpenAI-compatible `/v1/audio/speech`
 * endpoint (Speaches with Piper/Kokoro). POSTs text, receives audio,
 * plays it through a MediaPlayer. Mirrors [TtsHolder]'s contract: one
 * active utterance at a time, completion dispatched on the main thread so
 * voice mode's turn-taking can resume listening.
 */
internal object ServerTts {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile private var player: MediaPlayer? = null
    private val currentCompletion = AtomicReference<(() -> Unit)?>(null)
    // Monotonic token: each speak() bumps it. An async fetch that resolves
    // after a newer speak()/stop() has advanced the token must NOT spin up
    // a MediaPlayer — otherwise two players overlap and the first leaks.
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    fun speak(
        context: Context,
        baseUrl: String,
        model: String,
        voice: String,
        apiKey: String,
        text: String,
        onFinish: () -> Unit,
    ) {
        // Replace any in-flight playback + its completion.
        stop()
        val gen = generation.incrementAndGet()
        currentCompletion.set(onFinish)
        val appContext = context.applicationContext
        scope.launch {
            val bytes = runCatching { fetch(baseUrl, model, voice, apiKey, text) }.getOrNull()
            if (gen != generation.get()) return@launch  // superseded
            if (bytes == null || bytes.isEmpty()) {
                fireCompletion()
                return@launch
            }
            val file = runCatching {
                File.createTempFile("cr-tts", ".mp3", appContext.cacheDir).apply { writeBytes(bytes) }
            }.getOrNull()
            if (file == null) {
                fireCompletion()
                return@launch
            }
            postOnMain {
                if (gen != generation.get()) {
                    runCatching { file.delete() }
                    return@postOnMain
                }
                runCatching {
                    // Release any lingering player before swapping in the new one.
                    player?.let { runCatching { it.release() } }
                    val mp = MediaPlayer()
                    player = mp
                    mp.setDataSource(file.absolutePath)
                    mp.setOnCompletionListener {
                        runCatching { file.delete() }
                        fireCompletion()
                    }
                    mp.setOnErrorListener { _, _, _ ->
                        runCatching { file.delete() }
                        fireCompletion()
                        true
                    }
                    mp.prepare()
                    mp.start()
                }.onFailure {
                    runCatching { file.delete() }
                    fireCompletion()
                }
            }
        }
    }

    fun stop() {
        generation.incrementAndGet() // supersede any in-flight fetch
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
        fireCompletion()
    }

    private fun fireCompletion() {
        currentCompletion.getAndSet(null)?.let { postOnMain(it) }
    }

    private suspend fun fetch(
        baseUrl: String,
        model: String,
        voice: String,
        apiKey: String,
        text: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("model", model)
            .put("input", text)
            .put("voice", voice)
            .put("response_format", "mp3")
            .toString()
        val req = Request.Builder()
            .url(normalizeApiBase(baseUrl) + "/v1/audio/speech")
            .post(json.toRequestBody("application/json".toMediaType()))
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            resp.body?.bytes()
        }
    }
}

/**
 * Fetches the available model list from an OpenAI-compatible
 * `GET /v1/models` endpoint so the settings UI can offer a picker instead
 * of free-text. Speaches returns `{"data":[{"id":..,"task":..}]}`.
 */
internal object ServerCatalog {
    data class ModelInfo(val id: String, val task: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetchModels(baseUrl: String, apiKey: String): List<ModelInfo> =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) return@withContext emptyList()
            runCatching {
                val req = Request.Builder()
                    .url(normalizeApiBase(baseUrl) + "/v1/models")
                    .get()
                    .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val payload = resp.body?.string().orEmpty()
                    val arr = JSONObject(payload).optJSONArray("data") ?: return@withContext emptyList()
                    buildList {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val id = o.optString("id")
                            if (id.isNotBlank()) add(ModelInfo(id, o.optString("task")))
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }

    /** Heuristic split when the API doesn't tag tasks. */
    fun isStt(m: ModelInfo): Boolean {
        val t = m.task.lowercase(); val id = m.id.lowercase()
        return t.contains("speech-recognition") || t.contains("transcription") ||
            id.contains("whisper") || id.contains("faster-whisper")
    }

    fun isTts(m: ModelInfo): Boolean {
        val t = m.task.lowercase(); val id = m.id.lowercase()
        return t.contains("text-to-speech") ||
            (t.contains("speech") && !t.contains("recognition") && !isStt(m)) ||
            id.contains("piper") || id.contains("kokoro") || id.contains("tts")
    }
}
