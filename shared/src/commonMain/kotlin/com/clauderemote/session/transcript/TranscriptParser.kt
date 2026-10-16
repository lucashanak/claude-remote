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
import com.clauderemote.util.isoToEpochMillis

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

    /**
     * How close two identical prompt texts must be to count as one prompt seen
     * twice (queued record + its `user` twin) rather than the user typing the
     * same thing again later. Measured gap between a true pair: 1.5 s - 160 s;
     * between two genuine repeats: 14 h and up.
     */
    private const val DEDUP_WINDOW_MS = 15L * 60L * 1000L

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

    /**
     * Bookkeeping for one [parseLines] call.
     *
     * Duplicates have to be caught while the list is being built, not after:
     * /resume re-emits whole records verbatim (measured 17 id collisions in
     * 137k entries, the copy thousands of lines later), and a queued prompt can
     * also surface as a `user` record with the same text, so the two kinds of
     * duplicate are only visible against what has already been emitted.
     */
    private class ParseState {
        val out = mutableListOf<TranscriptEntry>()
        private val ids = HashSet<String>()
        /**
         * Trimmed text of every queued-command prompt emitted so far, with WHEN
         * it was emitted. The time matters: the same short text really is typed
         * twice in a long session — "autopilot" 14.6 h apart, "status?" 16.6 h
         * apart in this user's own transcripts — and those are two prompts, not
         * one recorded twice. A queued record and its `user` twin, by contrast,
         * land seconds apart (measured 1.5 s - 160 s). So dedup only inside
         * [DEDUP_WINDOW_MS]; outside it, both are real and both are shown.
         */
        val queuedTexts = HashMap<String, Long?>()
        /** Same, for prompts that came from a `user` record. */
        val userTexts = HashMap<String, Long?>()

        /**
         * True when [text] already arrived close enough in time to be the same
         * prompt seen twice. An unknown timestamp on either side is treated as
         * "cannot tell" and does NOT dedup — a duplicate bubble is a cosmetic
         * annoyance, a swallowed prompt is lost words.
         */
        fun isRecentDuplicate(
            text: String,
            timestamp: String?,
            /**
             * Which side to compare against. A queued record is checked against
             * BOTH sides (it can precede or follow its `user` twin, and /resume
             * can re-emit the attachment itself), but a `user` record is only
             * ever checked against queued ones: two plain user records with the
             * same text are two real prompts — Claude Code does not write the
             * same user record twice, and id dedup covers a verbatim re-emit.
             */
            againstUserPrompts: Boolean,
        ): Boolean {
            val now = timestamp?.let { isoToEpochMillis(it) } ?: return false
            val maps = if (againstUserPrompts) listOf(queuedTexts, userTexts) else listOf(queuedTexts)
            for (map in maps) {
                val seen = map[text] ?: continue
                if (seen != null && kotlin.math.abs(now - seen) <= DEDUP_WINDOW_MS) return true
            }
            return false
        }

        fun emit(entry: TranscriptEntry) {
            // A duplicate id is not merely redundant: the Android list keys off
            // it, and two items with one key is a hard crash there.
            if (ids.add(entry.id)) out += entry
        }

        /**
         * Emits a human prompt unless the same text already arrived as a queued
         * command — that record is the earlier and more accurate moment (it is
         * stamped when the user hit enter, not when Claude picked the prompt up).
         */
        fun emitUserPrompt(id: String, timestamp: String?, text: String) {
            val key = text.trim()
            if (isRecentDuplicate(key, timestamp, againstUserPrompts = false)) return
            userTexts[key] = timestamp?.let { isoToEpochMillis(it) }
            emit(TranscriptEntry.UserPrompt(id = id, timestamp = timestamp, text = text))
        }
    }

    fun parseLines(lines: Sequence<String>): List<TranscriptEntry> {
        val state = ParseState()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val obj = try {
                json.parseToJsonElement(line) as? JsonObject ?: continue
            } catch (_: Throwable) {
                continue
            }
            // Per-line isolation: a record that parses as JSON but trips a
            // downstream coercion (e.g. an unexpected field shape) must not
            // abort the whole batch — that would blank every entry after the
            // first malformed line. `type` itself is read in here for the same
            // reason: a non-primitive `type` throws on .jsonPrimitive.
            try {
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "user" -> parseUser(obj, state)
                    "assistant" -> parseAssistant(obj, state)
                    "system" -> parseSystem(obj, state)
                    // Post-compaction conversation summary. Has no uuid/timestamp;
                    // keyed off leafUuid.
                    "summary" -> parseSummary(obj, state)
                    // Carries the prompt the user typed while Claude was busy;
                    // every other attachment subtype is metadata.
                    "attachment" -> parseAttachment(obj, state)
                    // Drop: file-history-snapshot, ai-title, last-prompt,
                    // queue-operation, permission-mode. `last-prompt` is a duplicate
                    // checkpoint that Claude Code re-emits with the same text every
                    // turn — using it as a fallback produced ghost user bubbles.
                    else -> {}
                }
            } catch (_: Throwable) {
                // Skip this line, keep the batch alive.
            }
        }
        return state.out
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
            // Same per-line isolation as parseLines: every accessor below can
            // throw on an unexpected field shape, and one such record must not
            // cost us the context figure for the whole file.
            try {
                val obj = json.parseToJsonElement(line) as? JsonObject ?: continue
                if (obj["type"]?.jsonPrimitive?.contentOrNull != "assistant") continue
                val usage = obj["message"]?.jsonObject?.get("usage")?.jsonObject ?: continue
                val input = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
                val cacheCreate = usage["cache_creation_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
                val cacheRead = usage["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
                val total = input + cacheCreate + cacheRead
                if (total > 0L) result = total
            } catch (_: Throwable) {
                // Skip this line, keep scanning.
            }
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
            t.startsWith("<bash-stderr>") ||
            // OMC inter-agent coordination: another Claude session's message is
            // injected as a user turn ("Another Claude session sent a message:
            // <teammate-message …>…" + a permission-laundering warning). Not
            // typed by the human — it's agent plumbing, so it shouldn't render
            // as a USER bubble in Chat. Match anywhere (the text is prefixed by
            // the "Another Claude session…" wrapper, not the tag itself).
            t.contains("<teammate-message")
    }

    /**
     * A queued command is a prompt the user typed while Claude was still
     * working. Measured: 219 of them across 59 sessions, and only 7 ever got a
     * `user` record too — so without this the other 97% are simply missing from
     * the conversation.
     */
    private fun parseAttachment(obj: JsonObject, state: ParseState) {
        val attachment = obj["attachment"]?.jsonObject ?: return
        if (attachment["type"]?.jsonPrimitive?.contentOrNull != "queued_command") return
        // Only a human origin is a prompt; `peer` and `task-notification`
        // queue entries are agent plumbing and stay dropped.
        val origin = attachment["origin"]?.jsonObject?.get("kind")?.jsonPrimitive?.contentOrNull
        if (origin != "human") return
        val text = attachment["prompt"]?.jsonPrimitive?.contentOrNull ?: return
        if (text.isBlank() || isSyntheticUserText(text)) return
        // The record's own uuid, not attachment.source_uuid — that never
        // resolves to a record in the same file, so it cannot key an entry.
        val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return
        val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull
            ?: attachment["timestamp"]?.jsonPrimitive?.contentOrNull
        val key = text.trim()
        if (state.isRecentDuplicate(key, ts, againstUserPrompts = true)) return
        state.queuedTexts[key] = ts?.let { isoToEpochMillis(it) }
        state.emit(
            TranscriptEntry.UserPrompt(id = uuid, timestamp = ts, text = text, fromQueue = true)
        )
    }

    private fun parseUser(obj: JsonObject, state: ParseState) {
        val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return
        // Meta records are Claude Code's own synthetic injections (caveats,
        // reminders) — not something the human typed. Rendering them as user
        // bubbles is misleading.
        if (obj["isMeta"]?.jsonPrimitive?.booleanOrNull == true) return
        val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull
        val message = obj["message"]?.jsonObject ?: return
        val content = message["content"] ?: return

        // The post-compaction summary ("This session is being continued…") is
        // injected by Claude Code with role=user. As a UserPrompt it renders as
        // a multi-thousand-word bubble the human appears to have typed; it is a
        // system note about the conversation instead.
        if (obj["isCompactSummary"]?.jsonPrimitive?.booleanOrNull == true) {
            val text = plainText(content).trim()
            if (text.isNotEmpty()) {
                state.emit(
                    TranscriptEntry.SystemNote(
                        id = uuid,
                        timestamp = ts,
                        subtype = "compact_summary",
                        text = text
                    )
                )
            }
            return
        }

        when (content) {
            is JsonPrimitive -> {
                val text = content.contentOrNull ?: return
                val slash = slashCommandRegex.find(text)
                when {
                    slash != null -> state.emit(
                        TranscriptEntry.SlashCommand(
                            id = uuid,
                            timestamp = ts,
                            name = slash.groupValues[1].trim(),
                            args = slash.groupValues[2].trim()
                        )
                    )
                    isSyntheticUserText(text) -> {
                        // Skip system/tool injections (local-command output,
                        // task notifications, system reminders, …) — not human input.
                    }
                    else -> state.emitUserPrompt(uuid, ts, text)
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
                            state.emit(
                                TranscriptEntry.ToolResult(
                                    id = "$uuid#$idx",
                                    timestamp = ts,
                                    toolUseId = toolUseId,
                                    text = text,
                                    isError = isError
                                )
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
                    state.emitUserPrompt(uuid, ts, textParts.joinToString("\n"))
                }
            }
            else -> {}
        }
    }

    /** Text of a message body that may be a bare string or a content-block array. */
    private fun plainText(content: JsonElement): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.jsonArray.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            if (o["type"]?.jsonPrimitive?.contentOrNull == "text") {
                o["text"]?.jsonPrimitive?.contentOrNull
            } else null
        }.joinToString("\n")
        else -> ""
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
        state: ParseState
    ) {
        val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return
        val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull
        val message = obj["message"]?.jsonObject ?: return
        val model = message["model"]?.jsonPrimitive?.contentOrNull
        val content = message["content"]?.jsonArray ?: return
        val isError = obj["isApiErrorMessage"]?.jsonPrimitive?.booleanOrNull == true

        content.forEachIndexed { idx, block ->
            val b = block as? JsonObject ?: return@forEachIndexed
            when (b["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> {
                    val text = b["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotBlank()) {
                        state.emit(
                            TranscriptEntry.AssistantText(
                                id = "$uuid#$idx",
                                timestamp = ts,
                                text = text,
                                model = model,
                                isError = isError
                            )
                        )
                    }
                }
                "thinking" -> {
                    val text = b["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotBlank()) {
                        state.emit(
                            TranscriptEntry.AssistantThinking(
                                id = "$uuid#$idx",
                                timestamp = ts,
                                text = text
                            )
                        )
                    }
                }
                "tool_use" -> {
                    val toolUseId = b["id"]?.jsonPrimitive?.contentOrNull ?: "$uuid#$idx"
                    val name = b["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                    val input = b["input"] as? JsonObject
                    val (summary, full) = summarizeToolInput(name, input)
                    state.emit(
                        TranscriptEntry.ToolCall(
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
                    )
                }
                else -> {}
            }
        }
    }

    /**
     * Keys worth showing as a tool's one-line summary, best first. Covers the
     * 8.64% of tool calls (3,885 of 44,959 measured) that no named branch below
     * handles — MCP tools, plugin tools, future built-ins — which used to show
     * their parameter NAMES instead of any of their arguments.
     */
    private val summaryKeys = listOf(
        "query", "prompt", "message", "description", "path",
        "file_path", "url", "command", "pattern", "name", "text"
    )

    private fun summarizeToolInput(name: String, input: JsonObject?): Pair<String, String> {
        if (input == null) return "" to ""
        // Claude Code stores arguments it could not parse as
        // {"__unparsedToolInput":{"raw":"<json>","len":N}}. Summarising THAT
        // wrapper yields a card with no content at all (measured on 5 of 150
        // AskUserQuestion calls), so unwrap it and treat the raw payload as the
        // real input — for every tool, not just the one we noticed it on.
        if (input.size == 1) {
            val wrapper = input["__unparsedToolInput"]
            if (wrapper != null) {
                val raw = when (wrapper) {
                    is JsonObject -> wrapper["raw"]?.jsonPrimitive?.contentOrNull
                    is JsonPrimitive -> wrapper.contentOrNull
                    else -> null
                }.orEmpty()
                val parsed = try {
                    json.parseToJsonElement(raw) as? JsonObject
                } catch (_: Throwable) {
                    null
                }
                // Truncated/invalid raw still beats nothing: show it verbatim.
                return if (parsed != null) summarizeToolInput(name, parsed)
                else raw.take(200) to raw
            }
        }
        // 169 measured tool_use blocks have no arguments at all; an empty
        // summary made the row look like a rendering failure.
        if (input.isEmpty()) return "(no arguments)" to "{}"
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
            else -> fallbackSummary(input)
        }
        return summary.take(200) to pretty
    }

    /** Best readable argument of a tool we have no named branch for. */
    private fun fallbackSummary(input: JsonObject): String {
        for (key in summaryKeys) {
            val value = (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (!value.isNullOrBlank()) return value
        }
        // Any short string beats the key list; a long one (a file body, a diff)
        // would just be 200 characters of noise, so those fall through.
        val short = input.values.firstNotNullOfOrNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?.takeIf { it.isNotBlank() && it.length <= 200 }
        }
        return short ?: input.keys.joinToString(", ")
    }

    private fun parseSystem(obj: JsonObject, state: ParseState) {
        val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return
        val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull
        val subtype = obj["subtype"]?.jsonPrimitive?.contentOrNull ?: return
        // Different system subtypes carry their body in different fields.
        // turn_duration has neither content nor stopReason (only durationMs);
        // emitting a SystemNote with an empty body produced hundreds of blank
        // rows when the user toggled system notes on.
        val durationMs = obj["durationMs"]?.jsonPrimitive?.longOrNull
        val raw = obj["content"]?.jsonPrimitive?.contentOrNull
            ?: obj["stopReason"]?.jsonPrimitive?.contentOrNull
            ?: durationMs?.let { "turn took $it ms" }
            ?: ""
        val text = raw.trim()
        // stop_hook_summary marks the end of a turn (Claude stopped). Its body is
        // normally empty, but the UI relies on it as an in-band "Claude finished"
        // boundary — far more reliable in chat view than scraping the statusline —
        // so always emit it (with a label when blank).
        if (subtype == "stop_hook_summary") {
            state.emit(
                TranscriptEntry.SystemNote(
                    id = uuid,
                    timestamp = ts,
                    subtype = subtype,
                    text = text.ifEmpty { "Claude finished" },
                )
            )
            return
        }
        // Drop empty bodies and internal local-command bookkeeping (same filter
        // as the user path) — neither is meaningful to show.
        if (text.isEmpty() ||
            text.startsWith("<command-") ||
            text.startsWith("<local-command-")
        ) return
        state.emit(
            TranscriptEntry.SystemNote(
                id = uuid,
                timestamp = ts,
                subtype = subtype,
                text = text,
                durationMs = durationMs
            )
        )
    }

    private fun parseSummary(obj: JsonObject, state: ParseState) {
        val text = obj["summary"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return
        // Summary lines reference the conversation leaf they summarise; that
        // leafUuid is the stable id. Fall back to a content+time hash only when
        // it's absent (rare) — folding the timestamp in lowers the chance two
        // distinct summaries collide and get dropped by the stream's id dedup.
        val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull
        val id = obj["leafUuid"]?.jsonPrimitive?.contentOrNull
            ?: ("summary#" + (text + (ts ?: "")).hashCode())
        state.emit(
            TranscriptEntry.SystemNote(
                id = id,
                timestamp = ts,
                subtype = "summary",
                text = text
            )
        )
    }
}
