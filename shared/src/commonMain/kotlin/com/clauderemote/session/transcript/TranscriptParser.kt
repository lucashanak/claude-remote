package com.clauderemote.session.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
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
            // Per-line isolation: a record that parses as JSON but trips a
            // downstream coercion (e.g. an unexpected field shape) must not
            // abort the whole batch — that would blank every entry after the
            // first malformed line.
            try {
                when (type) {
                    "user" -> parseUser(obj, out)
                    "assistant" -> parseAssistant(obj, out)
                    "system" -> parseSystem(obj, out)
                    // Post-compaction conversation summary. Has no uuid/timestamp;
                    // keyed off leafUuid.
                    "summary" -> parseSummary(obj, out)
                    // Drop: attachment, file-history-snapshot, ai-title, last-prompt,
                    // queue-operation, permission-mode. `last-prompt` is a duplicate
                    // checkpoint that Claude Code re-emits with the same text every
                    // turn — using it as a fallback produced ghost user bubbles.
                    else -> {}
                }
            } catch (_: Throwable) {
                // Skip this line, keep the batch alive.
            }
        }
        return out
    }

    /**
     * Context size in tokens of the MOST RECENT assistant message in [lines]
     * (input + cache-creation + cache-read), or null if none carries usage.
     * This is the exact figure Claude Code's statusline turns into `ctx:NN%`,
     * so the app can derive the context-window percentage from the same
     * transcript it already streams instead of scraping the TUI. Updates
     * naturally per assistant message — i.e. only while Claude is working.
     */
    fun latestContextTokens(lines: Sequence<String>): Long? {
        var result: Long? = null
        for (raw in lines) {
            val line = raw.trim()
            // Cheap pre-filter so we don't JSON-parse every line twice.
            if (line.isEmpty() || !line.contains("\"usage\"")) continue
            val obj = try {
                json.parseToJsonElement(line) as? JsonObject ?: continue
            } catch (_: Throwable) {
                continue
            }
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "assistant") continue
            val usage = obj["message"]?.jsonObject?.get("usage")?.jsonObject ?: continue
            val input = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
            val cacheCreate = usage["cache_creation_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
            val cacheRead = usage["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
            val total = input + cacheCreate + cacheRead
            if (total > 0L) result = total
        }
        return result
    }

    /**
     * True when a "user"-role message is actually a system/tool injection rather
     * than something the human typed. Claude Code logs these as user entries:
     * slash-command echoes, local-command output, background-task notifications,
     * system reminders, bash-tool I/O, prompt-submit hook output. They start
     * with a recognizable wrapper tag. Without this they render as bogus USER
     * bubbles (e.g. a <task-notification> showing the assistant's own summary).
     */
    private fun isSyntheticUserText(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("<command-") ||
            t.startsWith("<local-command-stdout>") ||
            t.startsWith("<local-command-caveat>") ||
            t.startsWith("<task-notification>") ||
            t.startsWith("<system-reminder>") ||
            t.startsWith("<user-prompt-submit-hook>") ||
            t.startsWith("<bash-input>") ||
            t.startsWith("<bash-stdout>") ||
            t.startsWith("<bash-stderr>")
    }

    private fun parseUser(obj: JsonObject, out: MutableList<TranscriptEntry>) {
        val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return
        // Meta records are Claude Code's own synthetic injections (caveats,
        // reminders) — not something the human typed. Rendering them as user
        // bubbles is misleading.
        if (obj["isMeta"]?.jsonPrimitive?.booleanOrNull == true) return
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
                    isSyntheticUserText(text) -> {
                        // Skip system/tool injections (local-command output,
                        // task notifications, system reminders, …) — not human input.
                    }
                    else -> out += TranscriptEntry.UserPrompt(
                        id = uuid,
                        timestamp = ts,
                        text = text
                    )
                }
            }
            is JsonArray -> {
                val textParts = mutableListOf<String>()
                content.jsonArray.forEachIndexed { idx, block ->
                    val b = block as? JsonObject ?: return@forEachIndexed
                    when (b["type"]?.jsonPrimitive?.contentOrNull) {
                        "tool_result" -> {
                            // Pair strictly on the tool_use block id. The old
                            // fallback to sourceToolAssistantUUID mixed a
                            // message-uuid into the toolUseId namespace, so it
                            // could never match a ToolCall and the result
                            // rendered as a permanent orphan.
                            val toolUseId = b["tool_use_id"]?.jsonPrimitive?.contentOrNull
                            val isError = b["is_error"]?.jsonPrimitive?.booleanOrNull ?: false
                            val text = extractToolResultText(b["content"])
                            out += TranscriptEntry.ToolResult(
                                id = "$uuid#$idx",
                                timestamp = ts,
                                toolUseId = toolUseId,
                                text = text,
                                isError = isError
                            )
                        }
                        // User input can arrive as a content-block array (pasted
                        // images, multi-part input) rather than a plain string —
                        // collect the text blocks so the prompt isn't dropped.
                        // Skip system/tool injections that arrive in array form.
                        "text" -> b["text"]?.jsonPrimitive?.contentOrNull
                            ?.takeUnless { isSyntheticUserText(it) }
                            ?.let { textParts += it }
                        else -> {}
                    }
                }
                if (textParts.any { it.isNotBlank() }) {
                    out += TranscriptEntry.UserPrompt(
                        id = uuid,
                        timestamp = ts,
                        text = textParts.joinToString("\n")
                    )
                }
            }
            else -> {}
        }
    }

    private fun extractToolResultText(content: JsonElement?): String {
        if (content == null) return ""
        return when (content) {
            is JsonPrimitive -> content.contentOrNull ?: ""
            // mapNotNull (not joinToString-with-"") so non-text blocks don't
            // emit blank lines, and an image-only result isn't rendered as an
            // empty body the user reads as "nothing happened".
            is JsonArray -> content.jsonArray.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                when (o["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> o["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
                    "image" -> "[image]"
                    "tool_reference" ->
                        o["tool_name"]?.jsonPrimitive?.contentOrNull?.let { "[tool: $it]" } ?: "[tool_reference]"
                    else -> null
                }
            }.joinToString("\n")
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
                        // Key off the stable tool_use block id (toolu_…) rather
                        // than the array index — index keys collide / shift when
                        // Claude re-emits a line on re-tail, dropping or
                        // duplicating tool cards.
                        id = toolUseId,
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
            "Bash" -> input["command"]?.jsonPrimitive?.contentOrNull?.lines()?.firstOrNull().orEmpty()
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
            "AskUserQuestion" -> {
                // Summary = the first question's text so the collapsed/chat row
                // is readable ("questions" alone is meaningless). full stays the
                // pretty input JSON for the prominent card to re-parse.
                (input["questions"] as? JsonArray)
                    ?.firstOrNull()?.let { it as? JsonObject }
                    ?.get("question")?.jsonPrimitive?.contentOrNull
                    .orEmpty()
            }
            else -> input.keys.joinToString(", ")
        }
        return summary.take(200) to pretty
    }

    private fun parseSystem(obj: JsonObject, out: MutableList<TranscriptEntry>) {
        val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return
        val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull
        val subtype = obj["subtype"]?.jsonPrimitive?.contentOrNull ?: return
        // Different system subtypes carry their body in different fields.
        // turn_duration has neither content nor stopReason (only durationMs);
        // emitting a SystemNote with an empty body produced hundreds of blank
        // rows when the user toggled system notes on.
        val raw = obj["content"]?.jsonPrimitive?.contentOrNull
            ?: obj["stopReason"]?.jsonPrimitive?.contentOrNull
            ?: obj["durationMs"]?.jsonPrimitive?.contentOrNull?.let { "turn took $it ms" }
            ?: ""
        val text = raw.trim()
        // stop_hook_summary marks the end of a turn (Claude stopped). Its body is
        // normally empty, but the UI relies on it as an in-band "Claude finished"
        // boundary — far more reliable in chat view than scraping the statusline —
        // so always emit it (with a label when blank).
        if (subtype == "stop_hook_summary") {
            out += TranscriptEntry.SystemNote(
                id = uuid,
                timestamp = ts,
                subtype = subtype,
                text = text.ifEmpty { "Claude finished" },
            )
            return
        }
        // Drop empty bodies and internal local-command bookkeeping (same filter
        // as the user path) — neither is meaningful to show.
        if (text.isEmpty() ||
            text.startsWith("<command-") ||
            text.startsWith("<local-command-")
        ) return
        out += TranscriptEntry.SystemNote(
            id = uuid,
            timestamp = ts,
            subtype = subtype,
            text = text
        )
    }

    private fun parseSummary(obj: JsonObject, out: MutableList<TranscriptEntry>) {
        val text = obj["summary"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return
        // Summary lines reference the conversation leaf they summarise; that
        // leafUuid is the stable id. Fall back to a content+time hash only when
        // it's absent (rare) — folding the timestamp in lowers the chance two
        // distinct summaries collide and get dropped by the stream's id dedup.
        val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull
        val id = obj["leafUuid"]?.jsonPrimitive?.contentOrNull
            ?: ("summary#" + (text + (ts ?: "")).hashCode())
        out += TranscriptEntry.SystemNote(
            id = id,
            timestamp = ts,
            subtype = "summary",
            text = text
        )
    }
}
