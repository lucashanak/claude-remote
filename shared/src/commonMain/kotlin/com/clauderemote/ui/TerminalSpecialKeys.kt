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
import androidx.compose.material.icons.filled.RecordVoiceOver
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
import com.clauderemote.voice.MicButton
import com.clauderemote.voice.VoiceModeScreen
import com.clauderemote.voice.WakeWordListener
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
internal fun SpecialKeysRow(
    onKey: (SpecialKey) -> Unit,
    onMore: () -> Unit,
) {
    val c = CRTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SpecialKeyBtn("Esc",  Modifier.weight(1f)) { onKey(SpecialKey.Esc)   }
        SpecialKeyBtn("Tab",  Modifier.weight(1f)) { onKey(SpecialKey.Tab)   }
        SpecialKeyBtn("↑",    Modifier.weight(1f)) { onKey(SpecialKey.Up)    }
        SpecialKeyBtn("↓",    Modifier.weight(1f)) { onKey(SpecialKey.Down)  }
        SpecialKeyBtn("⌃C",   Modifier.weight(1f)) { onKey(SpecialKey.CtrlC) }
        SpecialKeyBtn("⌃D",   Modifier.weight(1f)) { onKey(SpecialKey.CtrlD) }
        // The old "/" key typed a slash into the PTY (Claude's own in-terminal
        // slash menu, navigated + confirmed with Enter). It duplicated this
        // command menu, which sends the picked command immediately — so the
        // single "/" button now opens that menu.
        SpecialKeyBtn("/",    Modifier.weight(1f)) { onMore()                }
        // Left/right arrows live at the end — occasionally handy (editing a
        // line, moving through a TUI menu) but not part of the common set.
        SpecialKeyBtn("←",    Modifier.weight(1f)) { onKey(SpecialKey.Left)  }
        SpecialKeyBtn("→",    Modifier.weight(1f)) { onKey(SpecialKey.Right) }
    }
}

@Composable
private fun SpecialKeyBtn(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = CRTheme.colors
    val haptic = androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current

    var pressed by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(
        targetValue = if (pressed) c.accent.copy(alpha = 0.25f) else c.surface2,
        animationSpec = tween(if (pressed) 0 else 100),
        label = "keyBg"
    )

    Surface(
        modifier = modifier
            .height(32.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                hapticFeedback.performHapticFeedback(haptic)
                pressed = true
                onClick()
            },
        color = bgColor,
        shape = RoundedCornerShape(6.dp),
        tonalElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = CRType.keyboardKey, color = c.text)
        }
    }

    // Reset pressed state after flash
    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(100)
            pressed = false
        }
    }
}

// ---------------------------------------------------------------------------
// CRControlBar — mode/model chips + escape + slash commands
// ---------------------------------------------------------------------------

