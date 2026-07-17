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

internal sealed class RenderItem {
    data class Single(
        val entry: TranscriptEntry,
        /** True for a turn's final assistant answer — rendered with a frame. */
        val isFinalAnswer: Boolean = false,
    ) : RenderItem()
    data class ToolGroup(val calls: List<TranscriptEntry.ToolCall>) : RenderItem()
    data class TurnSteps(
        val turnKey: String,
        val steps: Int,
        val durationMs: Long?,
        val expanded: Boolean,
    ) : RenderItem()
    data class TimeGap(val key: String, val label: String) : RenderItem()
}

internal data class TurnMeta(val steps: Int, val durationMs: Long?)

/**
 * Work-step count + summed turn duration per user-prompt id, computed from the
 * RAW entry list so entries hidden by display filters (thinking, system) still
 * count. A "step" is a tool call, a thinking block, or an intermediate
 * assistant text; the turn's final assistant answer is not a step. Durations
 * are summed because hook-driven loops can stop a turn more than once.
 */
internal fun computeTurnMeta(entries: List<TranscriptEntry>): Map<String, TurnMeta> {
    val out = HashMap<String, TurnMeta>()
    var promptId: String? = null
    var steps = 0
    var lastContentWasText = false
    var duration = 0L
    var hasDuration = false
    fun flush() {
        promptId?.let {
            out[it] = TurnMeta(
                steps = (steps - if (lastContentWasText) 1 else 0).coerceAtLeast(0),
                durationMs = if (hasDuration) duration else null,
            )
        }
    }
    for (e in entries) {
        when (e) {
            is TranscriptEntry.UserPrompt, is TranscriptEntry.SlashCommand -> {
                flush()
                promptId = e.id
                steps = 0
                lastContentWasText = false
                duration = 0L
                hasDuration = false
            }
            is TranscriptEntry.ToolCall -> { steps++; lastContentWasText = false }
            is TranscriptEntry.AssistantThinking -> { steps++; lastContentWasText = false }
            is TranscriptEntry.AssistantText -> { steps++; lastContentWasText = true }
            is TranscriptEntry.SystemNote -> if (e.durationMs != null) {
                duration += e.durationMs
                hasDuration = true
            }
            else -> {}
        }
    }
    flush()
    return out
}

/**
 * Build the LazyColumn item list from display-filtered entries.
 *
 * Entries are segmented into turns anchored on user prompts / slash commands.
 * Every turn but the last renders as: prompt · [▶ N steps · duration] · final
 * answer, with the middle expanded on demand. The last (live) turn renders in
 * full. The final answer is extracted only when it is literally the turn's
 * last filtered entry, so display order is always preserved.
 */
internal fun buildRenderList(
    filtered: List<TranscriptEntry>,
    meta: Map<String, TurnMeta>,
    liveTurnDone: Boolean,
    isExpanded: (String) -> Boolean,
): List<RenderItem> {
    val anchors = filtered.indices.filter {
        filtered[it] is TranscriptEntry.UserPrompt || filtered[it] is TranscriptEntry.SlashCommand
    }
    if (anchors.isEmpty()) return insertTimeGaps(groupConsecutiveTools(filtered))
    val out = ArrayList<RenderItem>(filtered.size)
    // Pre-anchor prefix (post-compaction summary etc.) renders as-is.
    out += groupConsecutiveTools(filtered.subList(0, anchors.first()))
    for ((ai, start) in anchors.withIndex()) {
        val end = if (ai + 1 < anchors.size) anchors[ai + 1] else filtered.size
        val prompt = filtered[start]
        val body = filtered.subList(start + 1, end)
        out += RenderItem.Single(prompt)
        if (ai == anchors.size - 1) {
            // Live turn — never collapsed. Frame the trailing answer only once
            // the turn is complete (stop marker seen), so the frame doesn't
            // flicker on and off while Claude streams tools after a text block.
            val fIdx = if (liveTurnDone) finalAnswerIndex(body) else -1
            if (fIdx >= 0) {
                out += groupConsecutiveTools(body.subList(0, fIdx))
                out += RenderItem.Single(body[fIdx], isFinalAnswer = true)
                for (t in body.subList(fIdx + 1, body.size)) out += RenderItem.Single(t)
            } else {
                out += groupConsecutiveTools(body)
            }
            continue
        }
        val finalIdx = finalAnswerIndex(body)
        val final = if (finalIdx >= 0) body[finalIdx] else null
        val middle = if (finalIdx >= 0) body.subList(0, finalIdx) else body
        val trailing = if (finalIdx >= 0) body.subList(finalIdx + 1, body.size) else emptyList()
        if (middle.isNotEmpty()) {
            val key = prompt.id
            val m = meta[key]
            val expanded = isExpanded(key)
            out += RenderItem.TurnSteps(
                turnKey = key,
                // Raw-entry step count when available (counts hidden thinking);
                // fall back to the visible middle size.
                steps = m?.steps?.takeIf { it > 0 } ?: middle.size,
                durationMs = m?.durationMs,
                expanded = expanded,
            )
            if (expanded) out += groupConsecutiveTools(middle)
        }
        if (final != null) out += RenderItem.Single(final, isFinalAnswer = true)
        for (t in trailing) out += RenderItem.Single(t)
    }
    return insertTimeGaps(out)
}

/**
 * Index of the turn's final answer: the last AssistantText followed only by
 * system notes (turn_duration / stop_hook_summary trail the answer when
 * system notes are shown). -1 when the turn ends in anything else (e.g. a
 * tool call) — then there is no final answer.
 */
private fun finalAnswerIndex(body: List<TranscriptEntry>): Int {
    for (i in body.indices.reversed()) {
        val e = body[i]
        if (e is TranscriptEntry.SystemNote) continue
        return if (e is TranscriptEntry.AssistantText) i else -1
    }
    return -1
}

/** Minimum quiet period between two entries that earns a visual separator. */
private const val TIME_GAP_MINUTES = 30L

/**
 * Insert a thin `── 14:32 ──` separator wherever two adjacent items are more
 * than [TIME_GAP_MINUTES] apart — an anchor for "I came back hours later".
 */
private fun insertTimeGaps(items: List<RenderItem>): List<RenderItem> {
    if (items.size < 2) return items
    val out = ArrayList<RenderItem>(items.size + 4)
    var prev: Long? = null
    for (item in items) {
        val ts = itemTimestamp(item)
        val cur = isoToEpochMinutes(ts)
        if (prev != null && cur != null && cur - prev >= TIME_GAP_MINUTES) {
            // Key derived from the item the gap precedes: at most one gap per
            // item, and stable across streaming updates (a positional index
            // would re-key every gap whenever an earlier item is inserted).
            out += RenderItem.TimeGap(
                key = "gap:" + itemKey(item),
                label = formatTimestamp(ts!!).take(5)
            )
        }
        if (cur != null) prev = cur
        out += item
    }
    return out
}

/**
 * Tool calls that render as standalone prominent cards — never folded into a
 * collapsed tool group: questions, todo checklists, subagent launches.
 */
private val standaloneTools = setOf("AskUserQuestion", "TodoWrite", "Task", "Agent")

/**
 * Walk the entries and fuse any run of two-or-more consecutive ToolCall
 * entries into a single ToolGroup item.
 */
private fun groupConsecutiveTools(entries: List<TranscriptEntry>): List<RenderItem> {
    val out = ArrayList<RenderItem>(entries.size)
    var i = 0
    while (i < entries.size) {
        val e = entries[i]
        if (e is TranscriptEntry.ToolCall && e.name !in standaloneTools) {
            var j = i + 1
            while (j < entries.size && entries[j] is TranscriptEntry.ToolCall &&
                (entries[j] as TranscriptEntry.ToolCall).name !in standaloneTools
            ) j++
            if (j - i >= 2) {
                @Suppress("UNCHECKED_CAST")
                val run = entries.subList(i, j).toList() as List<TranscriptEntry.ToolCall>
                out += RenderItem.ToolGroup(run)
                i = j
                continue
            }
        }
        out += RenderItem.Single(e)
        i++
    }
    return out
}

internal fun itemKey(item: RenderItem): String = when (item) {
    is RenderItem.Single -> "s:" + item.entry.id
    is RenderItem.ToolGroup -> "tg:" + item.calls.first().id
    is RenderItem.TurnSteps -> "ts:" + item.turnKey
    is RenderItem.TimeGap -> item.key
}

/** Map every non-anchor entry id to the id of the user prompt anchoring its turn. */
internal fun mapEntryToTurn(filtered: List<TranscriptEntry>): Map<String, String> {
    val out = HashMap<String, String>()
    var turn: String? = null
    for (e in filtered) {
        if (e is TranscriptEntry.UserPrompt || e is TranscriptEntry.SlashCommand) turn = e.id
        else turn?.let { out[e.id] = it }
    }
    return out
}

