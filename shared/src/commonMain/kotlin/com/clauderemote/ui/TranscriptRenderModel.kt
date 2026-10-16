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
    /** Backlog hit the tail cap — see [HISTORY_TRUNCATED_KEY]. */
    historyTruncated: Boolean = false,
    isExpanded: (String) -> Boolean,
): List<RenderItem> {
    val head = if (historyTruncated) listOf(historyTruncatedMarker()) else emptyList()
    val anchors = filtered.indices.filter { isTurnAnchor(filtered[it]) }
    if (anchors.isEmpty()) return head + insertTimeGaps(groupConsecutiveTools(filtered))
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
        // A turn that ended in an API error has no final answer — but the error
        // is the only explanation the user gets for a turn that produced
        // nothing, so it must stay visible while the steps are collapsed.
        // Without this it fell into `middle` and vanished behind "▶ N steps".
        val errorIdx = if (finalIdx >= 0) -1 else errorTailIndex(body)
        val cutIdx = if (finalIdx >= 0) finalIdx else errorIdx
        val final = if (finalIdx >= 0) body[finalIdx] else null
        val errorTail = if (errorIdx >= 0) body[errorIdx] else null
        val middle = if (cutIdx >= 0) body.subList(0, cutIdx) else body
        val trailing = if (cutIdx >= 0) body.subList(cutIdx + 1, body.size) else emptyList()
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
        // Not isFinalAnswer: the card gives it error chrome, not the answer frame.
        if (errorTail != null) out += RenderItem.Single(errorTail)
        for (t in trailing) out += RenderItem.Single(t)
    }
    return head + insertTimeGaps(out)
}

/**
 * Does this entry start a new turn? Prompts and slash commands obviously do —
 * and so does a compaction, which ends the conversation so far and restarts it
 * from a summary. It used to be an anchor by accident: the injected summary was
 * parsed as a user bubble. Now that it is correctly a system note, saying so
 * explicitly keeps the answer BEFORE a compaction framed as that turn's answer
 * (15 real ones in this user's transcripts lost their frame otherwise) and
 * keeps post-compaction work out of the previous turn's step count.
 */
private fun isTurnAnchor(entry: TranscriptEntry): Boolean = when (entry) {
    is TranscriptEntry.UserPrompt, is TranscriptEntry.SlashCommand -> true
    is TranscriptEntry.SystemNote -> entry.subtype == "compact_summary"
    else -> false
}

/**
 * Key of the "earlier messages were not loaded" marker. Stable and namespaced
 * so it can never collide with an entry-derived item key.
 */
internal const val HISTORY_TRUNCATED_KEY = "hist:truncated"

/**
 * The chat starts mid-conversation whenever the backlog hit the `tail -n`
 * cap (or the in-memory entry cap trimmed the head). Saying so is cheap and
 * stops the user reading a half conversation as the whole one. It reuses the
 * TimeGap row — a labelled hairline is exactly the right weight for this; it
 * is deliberately NOT a "load more" button, since nothing loads more.
 */
private fun historyTruncatedMarker() =
    RenderItem.TimeGap(key = HISTORY_TRUNCATED_KEY, label = "earlier messages not loaded")

/**
 * Index of the turn's final answer: the last AssistantText followed only by
 * system notes (turn_duration / stop_hook_summary trail the answer when
 * system notes are shown). -1 when the turn ends in anything else (e.g. a
 * tool call) — then there is no final answer.
 *
 * An `isError` text (rate limit, transport failure) ends the turn without
 * answering it, so it is never the final answer — framing it as one dresses a
 * failure up as Claude's conclusion.
 */
internal fun finalAnswerIndex(body: List<TranscriptEntry>): Int {
    for (i in body.indices.reversed()) {
        val e = body[i]
        if (e is TranscriptEntry.SystemNote) continue
        return if (e is TranscriptEntry.AssistantText && !e.isError) i else -1
    }
    return -1
}

/**
 * Index of an error [TranscriptEntry.AssistantText] that ends the turn (system
 * notes may trail it), or -1. Counterpart of [finalAnswerIndex]: that one
 * refuses to call an error the answer, this one keeps it on screen anyway.
 */
internal fun errorTailIndex(body: List<TranscriptEntry>): Int {
    for (i in body.indices.reversed()) {
        val e = body[i]
        if (e is TranscriptEntry.SystemNote) continue
        return if (e is TranscriptEntry.AssistantText && e.isError) i else -1
    }
    return -1
}

/** Minimum quiet period between two entries that earns a visual separator. */
private const val TIME_GAP_MINUTES = 30L

/**
 * Minimum BACKWARDS step that earns a "resumed from" separator. A resumed or
 * compacted session re-emits old turns after new ones, so the conversation can
 * jump a month back between two adjacent items with nothing to mark it. The
 * threshold exists because most inversions are not that: of 304 observed
 * backwards steps, 296 are under a second — clock jitter between two records
 * written in the same moment, which must not draw anything.
 */
private const val RESUMED_GAP_SECONDS = 60L

/**
 * Insert a thin `── 14:32 ──` separator wherever two adjacent items are more
 * than [TIME_GAP_MINUTES] apart — an anchor for "I came back hours later" —
 * and a `resumed from <date>` one where time runs BACKWARDS by more than
 * [RESUMED_GAP_SECONDS].
 */
internal fun insertTimeGaps(items: List<RenderItem>): List<RenderItem> {
    if (items.size < 2) return items
    val out = ArrayList<RenderItem>(items.size + 4)
    var prev: Long? = null
    for (item in items) {
        val ts = itemTimestamp(item)
        val cur = isoToEpochSeconds(ts)
        val delta = if (prev != null && cur != null) cur - prev else null
        // Key derived from the item the gap precedes: at most one gap per
        // item, and stable across streaming updates (a positional index
        // would re-key every gap whenever an earlier item is inserted).
        if (delta != null && delta >= TIME_GAP_MINUTES * 60) {
            out += RenderItem.TimeGap(
                key = "gap:" + itemKey(item),
                label = formatTimestamp(ts!!).take(5)
            )
        } else if (delta != null && delta <= -RESUMED_GAP_SECONDS) {
            out += RenderItem.TimeGap(
                key = "gap:" + itemKey(item),
                // UTC date straight off the record: commonMain has no date
                // formatter, and a day-boundary skew matters less here than
                // showing the user which conversation they are looking at.
                label = "resumed from " + ts!!.take(10)
            )
        }
        if (cur != null) prev = cur
        out += item
    }
    return out
}

/**
 * Seconds, not [isoToEpochMinutes]'s minutes: a sub-second backwards step that
 * happens to straddle a minute boundary reads as a full minute back, which
 * would draw a "resumed" divider in the middle of one live turn.
 */
private fun isoToEpochSeconds(iso: String?): Long? {
    val minutes = isoToEpochMinutes(iso) ?: return null
    val seconds = if (iso!!.length >= 19 && iso[16] == ':') {
        iso.substring(17, 19).toLongOrNull() ?: 0L
    } else 0L
    return minutes * 60 + seconds
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
        // Same anchor rule as buildRenderList, so a compaction closes the turn
        // here too instead of folding what follows into the previous one.
        if (isTurnAnchor(e)) turn = e.id
        else turn?.let { out[e.id] = it }
    }
    return out
}

