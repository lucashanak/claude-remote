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

/**
 * Render an Edit tool_use input as a unified diff: each old_string line
 * prefixed with red '−', each new_string line with green '+'. Falls back
 * to raw JSON if the input doesn't have the expected shape.
 */
@Composable
internal fun EditDiffBlock(fullInput: String) {
    val c = CRTheme.colors
    val parsed = remember(fullInput) { parseEditInput(fullInput) }
    if (parsed == null) {
        IndentedMono(fullInput)
        return
    }
    val (path, oldStr, newStr) = parsed
    Column(modifier = Modifier.fillMaxWidth()) {
        if (path.isNotBlank()) {
            Text(
                path,
                style = CRType.monoTiny,
                color = c.textDim
            )
            Spacer(Modifier.height(2.dp))
        }
        DiffPane(removed = oldStr, added = newStr)
    }
}

@Composable
internal fun WriteDiffBlock(fullInput: String) {
    val c = CRTheme.colors
    val parsed = remember(fullInput) { parseWriteInput(fullInput) }
    if (parsed == null) {
        IndentedMono(fullInput)
        return
    }
    val (path, content) = parsed
    Column(modifier = Modifier.fillMaxWidth()) {
        if (path.isNotBlank()) {
            Text(
                path,
                style = CRType.monoTiny,
                color = c.textDim
            )
            Spacer(Modifier.height(2.dp))
        }
        DiffPane(removed = "", added = content)
    }
}

@Composable
private fun DiffPane(removed: String, added: String) {
    val c = CRTheme.colors
    // Error (red) bg for removed lines, green tint for added lines
    val errorBg = c.disconnected.copy(alpha = 0.15f)
    val addBg = c.ready.copy(alpha = 0.12f)
    val errorFg = c.disconnected
    val addFg = c.ready
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        if (removed.isNotBlank()) {
            removed.lines().forEach { line ->
                DiffLine(prefix = "−", text = line, prefixColor = errorFg, background = errorBg)
            }
        }
        if (added.isNotBlank()) {
            added.lines().forEach { line ->
                DiffLine(prefix = "+", text = line, prefixColor = addFg, background = addBg)
            }
        }
    }
}

@Composable
private fun DiffLine(
    prefix: String,
    text: String,
    prefixColor: Color,
    background: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            prefix,
            style = CRType.mono,
            color = prefixColor,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text,
            style = CRType.mono,
            color = CRTheme.colors.text
        )
    }
}

private fun parseEditInput(json: String): Triple<String, String, String>? {
    if (json.isBlank()) return null
    return try {
        val obj = kotlinx.serialization.json.Json
            .parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject ?: return null
        val path = obj["file_path"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: ""
        val oldS = obj["old_string"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: ""
        val newS = obj["new_string"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: ""
        if (oldS.isEmpty() && newS.isEmpty()) return null
        Triple(path, oldS, newS)
    } catch (_: Throwable) { null }
}

private fun parseWriteInput(json: String): Pair<String, String>? {
    if (json.isBlank()) return null
    return try {
        val obj = kotlinx.serialization.json.Json
            .parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject ?: return null
        val path = obj["file_path"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: ""
        val content = obj["content"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: return null
        path to content
    } catch (_: Throwable) { null }
}

