package com.clauderemote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.SessionActivity
import com.clauderemote.model.SessionStatus
import com.clauderemote.session.CommandFetcher
import com.clauderemote.session.SlashCommand
import com.clauderemote.session.status.RemoteSessionStatus
import com.clauderemote.session.transcript.TranscriptEntry
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.components.CRStatus
import com.clauderemote.ui.components.Pill
import com.clauderemote.ui.components.ServerGlyph
import com.clauderemote.ui.components.StatusIndicator
import com.clauderemote.ui.components.color
import com.clauderemote.ui.theme.CRTerminalView
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import com.clauderemote.ui.theme.LocalCRTerminalView
import kotlinx.coroutines.launch




@Composable
internal fun CRControlBar(
    session: ClaudeSession,
    activity: com.clauderemote.model.SessionActivity? = null,
    onSendCommand: (String) -> Unit,
    onSendEscape: () -> Unit,
    onPageUp: () -> Unit = {},
    onPageDown: () -> Unit = {},
    onSwitchModel: (ClaudeModel) -> Unit,
    onSwitchEffort: ((com.clauderemote.model.ClaudeEffort) -> Unit)? = null,
    onOpenCommands: () -> Unit,
) {
    val c = CRTheme.colors

    var showModePop by remember { mutableStateOf(false) }
    var showEffortPop by remember { mutableStateOf(false) }
    var showModelPop by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Mode popup
        if (showModePop) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                color = c.surface,
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 8.dp,
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("MODE", style = CRType.sectionH, color = c.textDim,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    ClaudeMode.entries.forEach { mode ->
                        val isActive = session.mode == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isActive) c.tintAccent else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    showModePop = false
                                    // mode switching is handled by sending the shift-tab toggle command
                                    onSendCommand("[Z")
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(mode.displayName, style = CRType.bodyDim,
                                color = if (isActive) c.accent else c.text,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Model popup
        if (showModelPop) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                color = c.surface,
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 8.dp,
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("MODEL", style = CRType.sectionH, color = c.textDim,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    ClaudeModel.entries.forEach { model ->
                        val isActive = session.model == model
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isActive) c.tintAccent else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    showModelPop = false
                                    onSwitchModel(model)
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(model.displayName, style = CRType.bodyDim,
                                color = if (isActive) c.accent else c.text,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Effort popup. No "active" highlight — unlike mode/model, effort
        // isn't tracked on ClaudeSession (Claude doesn't echo it back in any
        // form this app parses), so there's nothing to compare against.
        if (showEffortPop && onSwitchEffort != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                color = c.surface,
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 8.dp,
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("EFFORT", style = CRType.sectionH, color = c.textDim,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    com.clauderemote.model.ClaudeEffort.entries.forEach { effort ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    showEffortPop = false
                                    onSwitchEffort(effort)
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(effort.displayName, style = CRType.bodyDim, color = c.text,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Actual bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Mode chip
            val modeColor = when (session.mode) {
                ClaudeMode.YOLO -> c.modeYolo
                ClaudeMode.AUTO -> c.modeAuto
                ClaudeMode.PLAN -> c.modePlan
                ClaudeMode.AUTO_ACCEPT -> c.modeAuto
                ClaudeMode.NORMAL -> c.modeNormal
            }
            val modeShort = when (session.mode) {
                ClaudeMode.YOLO -> "YOLO"
                ClaudeMode.AUTO -> "AUTO"
                ClaudeMode.PLAN -> "PLAN"
                ClaudeMode.AUTO_ACCEPT -> "EDIT"
                ClaudeMode.NORMAL -> "NORM"
            }
            Surface(
                onClick = { showModePop = !showModePop; showModelPop = false; showEffortPop = false },
                color = modeColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("mode", style = CRType.monoTiny, color = c.textDim)
                    Text(modeShort, style = CRType.pill, color = modeColor)
                }
            }

            // Model chip
            Surface(
                onClick = { showModelPop = !showModelPop; showModePop = false; showEffortPop = false },
                color = c.tintAccent,
                shape = RoundedCornerShape(6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("model", style = CRType.monoTiny, color = c.textDim)
                    Text(session.model.displayName.uppercase(), style = CRType.pill, color = c.accent)
                }
            }

            // Effort chip — no per-session "current" state to show (see the
            // popup's kdoc), just a launcher for the picker.
            if (onSwitchEffort != null) {
                Surface(
                    onClick = { showEffortPop = !showEffortPop; showModePop = false; showModelPop = false },
                    color = c.surface2,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("effort", style = CRType.monoTiny, color = c.textDim)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Right-hand button cluster — scrollable so it never clips on narrow phones
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            // /cmd
            Surface(
                onClick = onOpenCommands,
                color = c.surface2,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text("/cmd", style = CRType.pill, color = c.textDim,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }

            // Scroll the tmux pane via copy-mode (NOT stdin)
            CtrlButton("PgUp") { onPageUp() }
            CtrlButton("PgDn") { onPageDown() }
            // Esc
            CtrlButton("Esc") { onSendEscape() }
            // C-c
            CtrlButton("C-c") { onSendCommand("") }
            // y / n — emphasized when the agent is waiting for approval
            val awaiting = activity == com.clauderemote.model.SessionActivity.APPROVAL_NEEDED
            CtrlButton("y", emphasized = awaiting) { onSendCommand("y\r") }
            CtrlButton("n", emphasized = awaiting) { onSendCommand("n\r") }
            } // end scrollable button Row
        }
    }
}

// ---------------------------------------------------------------------------
// SESSION SIDE PANEL — redesigned (CRCard + group-by-server)
// ---------------------------------------------------------------------------

