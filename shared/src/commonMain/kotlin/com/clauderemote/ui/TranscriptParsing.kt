package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListScope
import com.clauderemote.model.SessionActivity
import com.clauderemote.session.status.RemoteSessionStatus
import com.clauderemote.session.transcript.TranscriptEntry
import com.clauderemote.voice.SpeakerButton
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.components.Pill
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import com.mikepenz.markdown.m3.Markdown
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.launch

internal data class AskOption(val label: String, val description: String)
internal data class AskQuestion(
    val header: String,
    val question: String,
    val multiSelect: Boolean,
    val options: List<AskOption>,
)

/**
 * Parse the AskUserQuestion tool_use input (the pretty JSON in fullInput) into
 * a list of questions + options. Returns empty on any shape mismatch.
 */
internal fun parseAskUserQuestions(json: String): List<AskQuestion> {
    if (json.isBlank()) return emptyList()
    return try {
        val obj = kotlinx.serialization.json.Json
            .parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        val arr = obj["questions"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        arr.mapNotNull { q ->
            val qo = q as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun str(key: String): String =
                (qo[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            // FIX E: parse boolean properly — toBooleanStrictOrNull handles both
            // JSON true (unquoted) and string "true" defensively.
            val multi = (qo["multiSelect"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toBooleanStrictOrNull() ?: false
            val opts = (qo["options"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { o ->
                    val oo = o as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    AskOption(
                        label = (oo["label"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
                        description = (oo["description"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
                    )
                } ?: emptyList()
            AskQuestion(
                header = str("header"),
                question = str("question"),
                multiSelect = multi,
                options = opts,
            )
        }
    } catch (_: Throwable) { emptyList() }
}

/**
 * Extract just the answer value(s) from Claude's verbose tool_result text.
 * Format: `User has answered your questions: "question"="answer". You can now…`
 * We pull all `="<value>"` matches and join them. Returns blank if no match so
 * the caller can fall back to the full trimmed text. Defensive — no crash.
 */
internal fun extractAskAnswers(text: String): String {
    return try {
        val regex = Regex("=\"([^\"]*)\"")
        val matches = regex.findAll(text).map { it.groupValues[1] }.toList()
        matches.joinToString(", ")
    } catch (_: Throwable) { "" }
}

internal data class TodoLine(val content: String, val status: String)

/** Parse a TodoWrite input `{"todos":[{"content","status",…}]}`; empty on mismatch. */
internal fun parseTodoInput(json: String): List<TodoLine> {
    if (json.isBlank()) return emptyList()
    return try {
        val obj = kotlinx.serialization.json.Json
            .parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        val arr = obj["todos"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        arr.mapNotNull { t ->
            val to = t as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val content = (to["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: (to["subject"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: return@mapNotNull null
            val status = (to["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "pending"
            TodoLine(content, status)
        }
    } catch (_: Throwable) {
        emptyList()
    }
}

internal fun entryMatchesQuery(e: TranscriptEntry, q: String): Boolean = when (e) {
    is TranscriptEntry.UserPrompt -> e.text.contains(q, ignoreCase = true)
    is TranscriptEntry.SlashCommand -> "/${e.name} ${e.args}".contains(q, ignoreCase = true)
    is TranscriptEntry.AssistantText -> e.text.contains(q, ignoreCase = true)
    is TranscriptEntry.AssistantThinking -> e.text.contains(q, ignoreCase = true)
    is TranscriptEntry.ToolCall ->
        e.name.contains(q, ignoreCase = true) ||
            e.inputSummary.contains(q, ignoreCase = true) ||
            e.fullInput.contains(q, ignoreCase = true)
    is TranscriptEntry.ToolResult -> e.text.contains(q, ignoreCase = true)
    is TranscriptEntry.SystemNote -> e.text.contains(q, ignoreCase = true)
}

internal fun itemTimestamp(item: RenderItem): String? = when (item) {
    is RenderItem.Single -> item.entry.timestamp
    is RenderItem.ToolGroup -> item.calls.first().timestamp
    is RenderItem.TurnSteps -> null
    is RenderItem.TimeGap -> null
}

/**
 * ISO-8601 UTC timestamp → minutes since epoch, or null on malformed input.
 * Uses the exact civil-date day count (Hinnant's days_from_civil) — no
 * platform date APIs exist in commonMain and second precision isn't needed
 * for gap detection.
 */
internal fun isoToEpochMinutes(iso: String?): Long? {
    if (iso == null || iso.length < 16) return null
    return try {
        val y = iso.substring(0, 4).toInt()
        val mo = iso.substring(5, 7).toInt()
        val d = iso.substring(8, 10).toInt()
        val h = iso.substring(11, 13).toInt()
        val mi = iso.substring(14, 16).toInt()
        val yy = if (mo <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097L + doe - 719468L
        days * 1440 + h * 60 + mi
    } catch (_: Throwable) {
        null
    }
}

internal fun formatTimestamp(iso: String): String {
    // Local wall-clock time; raw UTC clock substring only if parsing fails
    // (2026-05-15T15:24:02.384Z → 15:24:02).
    return com.clauderemote.util.isoToLocalTime(iso)
        ?: iso.substringAfter('T').substringBefore('.').substringBefore('Z').take(8)
}

internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

