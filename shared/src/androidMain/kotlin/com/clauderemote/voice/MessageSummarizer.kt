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
 * Condenses a session's last assistant message into one short Czech sentence
 * for the watch notification, via a self-hosted OpenAI-compatible chat
 * endpoint (`normalizeApiBase(url)` + `/v1/chat/completions` — same base
 * handling as [ServerTts], so a pasted `…/v1` URL doesn't double up).
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
    ): String? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || message.isBlank()) return@withContext null
        // Prompt differs by activity: an approval prompt is an action to
        // confirm, otherwise it's a question the agent is asking.
        val system = if (activity == "APPROVAL_NEEDED") {
            "Shrň, jakou akci má uživatel schválit, do JEDNÉ krátké věty " +
                "(max ~12 slov) pro notifikaci na hodinkách. Česky, jen ta věta, bez uvozovek."
        } else {
            "Shrň, na co se AI agent ptá, do JEDNÉ krátké věty " +
                "(max ~12 slov) pro notifikaci na hodinkách. Česky, jen ta věta, bez uvozovek."
        }
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.3)
            .put("max_tokens", 200)
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
            .url(normalizeApiBase(baseUrl) + "/v1/chat/completions")
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
}
