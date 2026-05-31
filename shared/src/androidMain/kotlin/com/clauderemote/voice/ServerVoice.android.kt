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
 * Shared MediaPlayer playback core for the file-based TTS engines
 * ([ServerTts], [GoogleCloudTts]). Given an engine-specific suspend
 * `fetch` that returns encoded audio bytes, it writes them to a temp file
 * and plays them, enforcing a single active utterance across ALL such
 * engines (one MediaPlayer, one generation token) so switching engines or
 * tapping a new SpeakerButton never overlaps audio. Completion is always
 * dispatched on the main thread so voice mode's turn-taking can resume.
 */
internal object MediaTtsCore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var player: MediaPlayer? = null
    private val currentCompletion = AtomicReference<(() -> Unit)?>(null)
    // Monotonic token: each speak() bumps it. An async fetch that resolves
    // after a newer speak()/stop() has advanced the token must NOT spin up
    // a MediaPlayer — otherwise two players overlap and the first leaks.
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    fun speak(
        context: Context,
        ext: String,
        onFinish: () -> Unit,
        onError: ((String) -> Unit)?,
        fetch: suspend () -> ByteArray,
    ) {
        // Replace any in-flight playback + its completion.
        stop()
        val gen = generation.incrementAndGet()
        currentCompletion.set(onFinish)
        val appContext = context.applicationContext
        scope.launch {
            val bytes = runCatching { fetch() }
                .onFailure { e ->
                    if (gen == generation.get() && onError != null) {
                        postOnMain { onError(e.message ?: "TTS selhal") }
                    }
                }
                .getOrNull()
            if (gen != generation.get()) return@launch  // superseded
            if (bytes == null || bytes.isEmpty()) {
                if (bytes != null && onError != null) {
                    postOnMain { onError("Server vrátil prázdné audio") }
                }
                fireCompletion()
                return@launch
            }
            val file = runCatching {
                File.createTempFile("cr-tts", ext, appContext.cacheDir).apply { writeBytes(bytes) }
            }.getOrNull()
            if (file == null) {
                if (onError != null) postOnMain { onError("Nelze zapsat audio do cache") }
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
}

/**
 * Server-side text-to-speech via an OpenAI-compatible `/v1/audio/speech`
 * endpoint (Speaches with Piper/XTTS). POSTs text, receives mp3, plays it
 * through the shared [MediaTtsCore].
 */
internal object ServerTts {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun speak(
        context: Context,
        baseUrl: String,
        model: String,
        voice: String,
        apiKey: String,
        text: String,
        speed: Float = 1.0f,
        onFinish: () -> Unit,
        onError: ((String) -> Unit)? = null,
    ) {
        MediaTtsCore.speak(context, ".mp3", onFinish, onError) {
            fetch(baseUrl, model, voice, apiKey, text, speed)
        }
    }

    fun stop() = MediaTtsCore.stop()

    private suspend fun fetch(
        baseUrl: String,
        model: String,
        voice: String,
        apiKey: String,
        text: String,
        speed: Float,
    ): ByteArray = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("model", model)
            .put("input", text)
            .put("voice", voice)
            .put("response_format", "mp3")
            // OpenAI-compatible 'speed' (0.25–4.0). Only send when non-default
            // so servers that don't support it keep working at normal speed.
            .apply { if (speed != 1.0f) put("speed", speed.coerceIn(0.25f, 4.0f).toDouble()) }
            .toString()
        val req = Request.Builder()
            .url(normalizeApiBase(baseUrl) + "/v1/audio/speech")
            .post(json.toRequestBody("application/json".toMediaType()))
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val snippet = resp.body?.string().orEmpty().take(120).replace(Regex("\\s+"), " ")
                throw RuntimeException("HTTP ${resp.code} z /v1/audio/speech${if (snippet.isNotBlank()) " — $snippet" else ""}")
            }
            resp.body?.bytes() ?: ByteArray(0)
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
            val list = parseVoicesPayload(payload)
            if (list.isEmpty()) {
                // Include a snippet so the user can paste it and we'll know
                // the exact JSON shape the server uses.
                val snippet = payload.take(220).replace(Regex("\\s+"), " ")
                throw RuntimeException("Nerozpoznaný tvar JSONu od $url: $snippet")
            }
            return list
        }
    }

    private fun parseVoicesPayload(payload: String): List<String> {
        // 1) Top-level JSON array of strings or objects.
        runCatching {
            val arr = org.json.JSONArray(payload)
            val r = jsonArrayToVoiceList(arr)
            if (r.isNotEmpty()) return r
        }
        // 2) Top-level JSON object with a nested array under common keys…
        runCatching {
            val obj = JSONObject(payload)
            for (key in listOf("voices", "data", "items", "results")) {
                val arr = obj.optJSONArray(key) ?: continue
                val r = jsonArrayToVoiceList(arr)
                if (r.isNotEmpty()) return r
            }
            // …or the voices are the object's own keys (map shape).
            val keys = obj.keys().asSequence().toList()
            val voiceLike = keys.filter { k ->
                k.isNotBlank() && (k.contains('_') || k.contains('-') || k.matches(Regex("^[A-Za-z][A-Za-z0-9_-]+$")))
            }
            if (voiceLike.isNotEmpty()) return voiceLike
        }
        return emptyList()
    }

    private fun jsonArrayToVoiceList(arr: org.json.JSONArray): List<String> = buildList {
        val keys = listOf(
            "name", "id", "voice", "voice_id", "voice_name",
            "key", "value", "model", "label", "slug",
        )
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is String -> if (v.isNotBlank()) add(v)
                is JSONObject -> {
                    val candidate = keys.firstNotNullOfOrNull { k ->
                        v.optString(k).takeIf { it.isNotBlank() }
                    }
                    if (candidate != null) add(candidate)
                }
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
