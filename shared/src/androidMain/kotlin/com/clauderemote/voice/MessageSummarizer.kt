package com.clauderemote.voice

import com.clauderemote.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Condenses a session's last assistant message into a short Czech summary for
 * the watch/phone notification, via an Open WebUI native chat endpoint
 * (`normalizeApiBase(url)` + `/api/chat/completions`). OWUI's OpenAI
 * passthrough (`/openai/v1`) is disabled server-side (403), so we target the
 * native `/api` routes; `normalizeApiBase` only strips a trailing `/v1`, so a
 * pasted domain root passes through unchanged.
 *
 * Strictly best-effort: any failure (timeout, non-200, malformed body, empty
 * content) returns null so the caller falls back to the truncated raw
 * message. Never throws out.
 */
object MessageSummarizer {
    // ~12 s total budget. The model is hot (~0.6 s), but a single stalled
    // call must never wedge WearSync.push() indefinitely.
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val thinkBlock = Regex("(?s)<think>.*?</think>")

    suspend fun summarize(
        baseUrl: String,
        apiKey: String,
        model: String,
        activity: String,
        message: String,
        // "SENTENCE" (default) / "SHORT" / "PARAGRAPH" — see AppSettings.
        length: String = "SENTENCE",
    ): String? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || message.isBlank()) return@withContext null
        // Prompt differs by activity: an approval prompt is an action to
        // confirm, otherwise it's a question the agent is asking.
        val subject = if (activity == "APPROVAL_NEEDED") {
            "jakou akci má uživatel schválit"
        } else {
            "na co se AI agent ptá"
        }
        // Length controls both the wording of the ask and the token budget so
        // a longer target isn't truncated mid-sentence.
        val target = when (length) {
            "SHORT" -> "do 2–3 krátkých vět"
            "PARAGRAPH" -> "do krátkého odstavce (max ~4 věty)"
            else -> "do JEDNÉ krátké věty (max ~12 slov)"
        }
        val maxTokens = when (length) {
            "SHORT" -> 160
            "PARAGRAPH" -> 320
            else -> 60
        }
        val system = "Shrň, $subject, $target pro notifikaci. Česky, jen text, bez uvozovek."
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.3)
            .put("max_tokens", maxTokens)
            // Reasoning model: without this it emits a <think> block and
            // leaves `content` empty/leaky.
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", message)),
            )
            .toString()
        val req = Request.Builder()
            .url(normalizeApiBase(baseUrl) + "/api/chat/completions")
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val snippet = resp.body?.string().orEmpty().take(120).replace(Regex("\\s+"), " ")
                    FileLogger.log(
                        "MessageSummarizer",
                        "HTTP ${resp.code}${if (snippet.isNotBlank()) " — $snippet" else ""}",
                    )
                    return@use null
                }
                val payload = resp.body?.string().orEmpty()
                val content = JSONObject(payload)
                    .optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
                // Strip a stray <think> block defensively (enable_thinking:false
                // should suppress it, but belt-and-braces).
                thinkBlock.replace(content, "").trim().ifBlank { null }
            }
        }.onFailure { e ->
            FileLogger.log("MessageSummarizer", "summarize failed: ${e.message}")
        }.getOrNull()
    }

    /**
     * Fetch the model ids the server offers via Open WebUI's native
     * `GET /api/models` (`{"data":[{"id":..}]}`) so the settings UI can offer
     * a picker AND verify the URL+key in one tap. Unlike [summarize] this
     * throws on failure so the UI can surface "nepodařilo se načíst" with the
     * server's reason. Mirrors ServerCatalog.fetchModels (which uses `/v1`).
     */
    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) throw IllegalArgumentException("URL serveru je prázdné")
            val req = Request.Builder()
                .url(normalizeApiBase(baseUrl) + "/api/models")
                .get()
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} z /api/models")
                val payload = resp.body?.string().orEmpty()
                if (payload.trimStart().startsWith("<")) {
                    throw RuntimeException(
                        "Server vrátil HTML místo JSON — zkontrolujte URL (chybí /api segment?)."
                    )
                }
                val arr = JSONObject(payload).optJSONArray("data")
                    ?: throw RuntimeException("Neočekávaný tvar JSONu (chybí pole 'data')")
                buildList {
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                        if (id.isNotBlank()) add(id)
                    }
                }.sorted()
            }
        }
}
