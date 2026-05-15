package com.clauderemote.session.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses Claude Code's JSONL transcripts into a flat list of UI-ready entries.
 *
 * Tolerant by design: unknown record types are dropped, partial/invalid lines
 * (transcript is append-only and may be mid-write) are skipped.
 *
 * Pairs assistant tool_use blocks with their following user tool_result blocks
 * via `sourceToolAssistantUUID` so the UI can collapse results under calls.
 */
object TranscriptParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val prettyJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val slashCommandRegex = Regex(
        "<command-name>([^<]+)</command-name>\\s*<command-message>[^<]*</command-message>\\s*<command-args>([^<]*)</command-args>",
        RegexOption.DOT_MATCHES_ALL
    )

    fun parseLines(lines: Sequence<String>): List<TranscriptEntry> {
        val out = mutableListOf<TranscriptEntry>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val obj = try {
                json.parseToJsonElement(line) as? JsonObject ?: continue
            } catch (_: Throwable) {
                continue
            }
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: continue
            when (type) {
                "user" -> parseUser(obj, out)
                "assistant" -> parseAssistant(obj, out)
                "system" -> parseSystem(obj, out)
                "last-prompt" -> parseLastPrompt(obj, out)
                // Drop: attachment, file-history-snapshot, ai-title, queue-operation, permission-mode.
                else -> {}
            }
        }
        return out
    }

    private fun parseUser(obj: JsonObject, out: MutableList<TranscriptEntry>) {
        val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return
        val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull
        val message = obj["message"]?.jsonObject ?: return
        val content = message["content"] ?: return

        when (content) {
            is JsonPrimitive -> {
                val text = content.contentOrNull ?: return
                val slash = slashCommandRegex.find(text)
                when {
                    slash != null -> out += TranscriptEntry.SlashCommand(
                        id = uuid,
                        timestamp = ts,
                        name = slash.groupValues[1].trim(),
                        args = slash.groupValues[2].trim()
                    )
                    text.startsWith("<local-command-stdout>") ||
                        text.startsWith("<local-command-caveat>") ||
                        text.startsWith("<command-") -> {
                        // Skip internal local-command bookkeeping.
                    }
                    else -> out += TranscriptEntry.UserPrompt(
                        id = uuid,
                        timestamp = ts,
                        text = text
                    )
                }
            }
            is JsonArray -> {
                content.jsonArray.forEachIndexed { idx, block ->
                    val b = block as? JsonObject ?: return@forEachIndexed
                    val btype = b["type"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                    if (btype == "tool_result") {
                        val toolUseId = b["tool_use_id"]?.jsonPrimitive?.contentOrNull
                            ?: obj["sourceToolAssistantUUID"]?.jsonPrimitive?.contentOrNull
                        val isError = b["is_error"]?.jsonPrimitive?.boolean ?: false
                        val text = extractToolResultText(b["content"])
                        out += TranscriptEntry.ToolResult(
                            id = "$uuid#$idx",
                            timestamp = ts,
                            toolUseId = toolUseId,
                            text = text,
                            isError = isError
                        )
                    }
                }
            }
            else -> {}
        }
    }

    private fun extractToolResultText(content: JsonElement?): String {
        if (content == null) return ""
        return when (content) {
            is JsonPrimitive -> content.contentOrNull ?: ""
            is JsonArray -> content.jsonArray.joinToString("\n") { item ->
                val o = item as? JsonObject ?: return@joinToString ""
                when (o["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> o["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    else -> ""
                }
            }
            else -> ""
        }
    }

    private fun parseAssistant(
        obj: JsonObject,
        out: MutableList<TranscriptEntry>
    ) {
        val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return
        val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull
        val message = obj["message"]?.jsonObject ?: return
        val model = message["model"]?.jsonPrimitive?.contentOrNull
        val content = message["content"]?.jsonArray ?: return

        content.forEachIndexed { idx, block ->
            val b = block as? JsonObject ?: return@forEachIndexed
            when (b["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> {
                    val text = b["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotBlank()) {
                        out += TranscriptEntry.AssistantText(
                            id = "$uuid#$idx",
                            timestamp = ts,
                            text = text,
                            model = model
                        )
                    }
                }
                "thinking" -> {
                    val text = b["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotBlank()) {
                        out += TranscriptEntry.AssistantThinking(
                            id = "$uuid#$idx",
                            timestamp = ts,
                            text = text
                        )
                    }
                }
                "tool_use" -> {
                    val toolUseId = b["id"]?.jsonPrimitive?.contentOrNull ?: "$uuid#$idx"
                    val name = b["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                    val input = b["input"] as? JsonObject
                    val (summary, full) = summarizeToolInput(name, input)
                    out += TranscriptEntry.ToolCall(
                        id = "$uuid#$idx",
                        timestamp = ts,
                        toolUseId = toolUseId,
                        name = name,
                        inputSummary = summary,
                        fullInput = full
                    )
                }
                else -> {}
            }
        }
    }

    private fun summarizeToolInput(name: String, input: JsonObject?): Pair<String, String> {
        if (input == null) return "" to ""
        val pretty = prettyJson.encodeToString(JsonObject.serializer(), input)
        val summary = when (name) {
            "Bash" -> input["command"]?.jsonPrimitive?.contentOrNull?.lines()?.first().orEmpty()
            "Read" -> {
                val path = input["file_path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val offset = input["offset"]?.jsonPrimitive?.contentOrNull
                val limit = input["limit"]?.jsonPrimitive?.contentOrNull
                if (offset != null || limit != null) "$path:${offset ?: 0}+${limit ?: "*"}" else path
            }
            "Edit", "Write" -> input["file_path"]?.jsonPrimitive?.contentOrNull.orEmpty()
            "Grep" -> input["pattern"]?.jsonPrimitive?.contentOrNull.orEmpty()
            "Glob" -> input["pattern"]?.jsonPrimitive?.contentOrNull.orEmpty()
            "Agent", "Task" -> input["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            "WebFetch" -> input["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            "WebSearch" -> input["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
            else -> input.keys.joinToString(", ")
        }
        return summary.take(200) to pretty
    }

    private fun parseLastPrompt(obj: JsonObject, out: MutableList<TranscriptEntry>) {
        // Fallback for Claude Code variants that record the user prompt only
        // in a `last-prompt` record (or alongside a user record without the
        // visible text). Dedupe against the previous UserPrompt entry so we
        // don't render the same question twice on builds that emit both.
        val text = obj["lastPrompt"]?.jsonPrimitive?.contentOrNull ?: return
        if (text.isBlank()) return
        val leaf = obj["leafUuid"]?.jsonPrimitive?.contentOrNull
        val recent = out.asReversed().firstOrNull { it is TranscriptEntry.UserPrompt }
            as? TranscriptEntry.UserPrompt
        if (recent != null && recent.text == text) return
        out += TranscriptEntry.UserPrompt(
            id = "last-prompt:" + (leaf ?: text.hashCode().toString()),
            timestamp = null,
            text = text
        )
    }

    private fun parseSystem(obj: JsonObject, out: MutableList<TranscriptEntry>) {
        val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return
        val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull
        val subtype = obj["subtype"]?.jsonPrimitive?.contentOrNull ?: return
        // Only surface user-meaningful system events. Hooks and local-command
        // stdout are noisy bookkeeping — keep but collapsed by default in UI.
        val text = obj["content"]?.jsonPrimitive?.contentOrNull
            ?: obj["stopReason"]?.jsonPrimitive?.contentOrNull
            ?: ""
        out += TranscriptEntry.SystemNote(
            id = uuid,
            timestamp = ts,
            subtype = subtype,
            text = text
        )
    }
}
