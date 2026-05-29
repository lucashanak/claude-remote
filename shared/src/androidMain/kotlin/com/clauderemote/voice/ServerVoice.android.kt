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
            if (baseUrl.isBlank()) throw IllegalArgumentException("URL serveru je prázdné")
            val req = Request.Builder()
                .url(normalizeApiBase(baseUrl) + "/v1/models")
                .get()
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} z /v1/models")
                val payload = resp.body?.string().orEmpty()
                if (payload.trimStart().startsWith("<")) {
                    throw RuntimeException(
                        "Server vrátil HTML místo JSON — zkontrolujte URL " +
                            "(typicky špatná casing nebo chybí /api segment)."
                    )
                }
                val arr = JSONObject(payload).optJSONArray("data")
                    ?: throw RuntimeException("Neočekávaný tvar JSONu (chybí pole 'data')")
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("id")
                        if (id.isNotBlank()) add(ModelInfo(id, o.optString("task")))
                    }
                }
            }
        }

    /**
     * Fetch the list of TTS voices from the server. Open WebUI exposes
     * `/v1/audio/voices`; some servers use `/v1/audio/speech/voices`. We
     * try both. The response shape varies — JSON array of strings,
     * array of {name|id|voice} objects, or {voices:[...]}/{data:[...]}.
     */
    suspend fun fetchVoices(baseUrl: String, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) throw IllegalArgumentException("URL serveru je prázdné")
            val base = normalizeApiBase(baseUrl)
            var lastError: Throwable? = null
            for (path in listOf("/v1/audio/voices", "/v1/audio/speech/voices")) {
                try {
                    val result = fetchVoicesAt(base + path, apiKey)
                    if (result.isNotEmpty()) return@withContext result
                } catch (e: Throwable) {
                    lastError = e
                }
            }
            if (lastError != null) throw lastError!!
            emptyList()
        }

    private fun fetchVoicesAt(url: String, apiKey: String): List<String> {
        val req = Request.Builder().url(url).get().apply {
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        }.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} z $url")
            val payload = resp.body?.string().orEmpty()
            if (payload.trimStart().startsWith("<")) {
                throw RuntimeException(
                    "Server vrátil HTML místo JSON — zkontrolujte URL " +
                        "(typicky špatná casing nebo chybí /api segment)."
                )
            }
            // Try array-of-string / array-of-object first.
            return runCatching {
                val arr = org.json.JSONArray(payload)
                buildList {
                    for (i in 0 until arr.length()) {
                        val v = arr.opt(i)
                        when (v) {
                            is String -> if (v.isNotBlank()) add(v)
                            is JSONObject -> {
                                val name = listOf("name", "id", "voice", "voice_id")
                                    .firstNotNullOfOrNull { k -> v.optString(k).takeIf { it.isNotBlank() } }
                                if (name != null) add(name)
                            }
                        }
                    }
                }
            }.getOrElse {
                runCatching {
                    val obj = JSONObject(payload)
                    val arr = obj.optJSONArray("voices") ?: obj.optJSONArray("data") ?: return@getOrElse emptyList()
                    buildList {
                        for (i in 0 until arr.length()) {
                            val v = arr.opt(i)
                            when (v) {
                                is String -> if (v.isNotBlank()) add(v)
                                is JSONObject -> {
                                    val name = listOf("name", "id", "voice", "voice_id")
                                        .firstNotNullOfOrNull { k -> v.optString(k).takeIf { it.isNotBlank() } }
                                    if (name != null) add(name)
                                }
                            }
                        }
                    }
                }.getOrDefault(emptyList())
            }
        }
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
