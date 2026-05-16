package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.components.Segmented
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType

/**
 * A template entry shown in the Templates tab of the expanded input sheet.
 */
data class InputTemplate(
    val id: String,
    val name: String,
    val body: String,
)

/**
 * Expanded prompt-composition bottom sheet.
 *
 * Trigger: tap or focus on the input field in the terminal control bar.
 * Not yet wired to a call site — compiles standalone.
 *
 * @param initialText  Pre-populate the text area (e.g. in-progress draft).
 * @param templates    Named prompt templates shown in the Templates tab.
 * @param history      Recent sent messages shown in the History tab.
 * @param onSend       Called with final text when user taps Send.
 * @param onDismiss    Called when the sheet should close.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandedInput(
    initialText: String = "",
    templates: List<InputTemplate> = emptyList(),
    history: List<String> = emptyList(),
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surface,
        contentColor = c.text,
        dragHandle = {
            // Spec: drag handle top
            Box(
                Modifier
                    .padding(vertical = 10.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.border)
            )
        },
    ) {
        ExpandedInputContent(
            initialText = initialText,
            templates = templates,
            history = history,
            onSend = { text ->
                onSend(text)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

/**
 * Inner content of the sheet — extracted so it can be previewed or embedded
 * without a ModalBottomSheet wrapper.
 */
@Composable
internal fun ExpandedInputContent(
    initialText: String,
    templates: List<InputTemplate>,
    history: List<String>,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics

    var text by remember { mutableStateOf(initialText) }
    var tabIndex by remember { mutableStateOf(0) }  // 0 = Templates, 1 = History
    val tabs = listOf("Templates", "History")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // ── Header ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = m.sectionPad, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Compose", style = CRType.cardTitle, color = c.text)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Close", tint = c.textDim)
            }
        }

        HorizontalDivider(color = c.border)

        // ── Text area ───────────────────────────────────────────────────
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 260.dp)
                .padding(horizontal = m.sectionPad, vertical = m.inputPad),
            placeholder = {
                Text(
                    "Type your message… (/ for commands)",
                    style = CRType.cardTitle,
                    color = c.textDim
                )
            },
            textStyle = if (text.startsWith("/")) CRType.mono else CRType.cardTitle,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = c.border,
                focusedBorderColor = c.accent,
                cursorColor = c.accent,
                unfocusedTextColor = c.text,
                focusedTextColor = c.text,
                unfocusedContainerColor = c.surface,
                focusedContainerColor = c.surface,
            ),
            shape = RoundedCornerShape(m.cardRadius),
        )

        // ── Tab strip: Templates / History ──────────────────────────────
        Row(
            modifier = Modifier
                .padding(horizontal = m.sectionPad)
                .padding(bottom = 8.dp),
        ) {
            Segmented(
                options = tabs.indices.toList(),
                selected = tabIndex,
                onSelect = { tabIndex = it },
                label = { tabs[it] },
            )
        }

        // ── Tab content ─────────────────────────────────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 200.dp)
                .padding(horizontal = m.sectionPad)
        ) {
            when (tabIndex) {
                0 -> TemplatesTab(
                    templates = templates,
                    onPick = { text = it },
                )
                1 -> HistoryTab(
                    history = history,
                    onPick = { text = it },
                )
            }
        }

        HorizontalDivider(color = c.border)

        // ── Footer row ──────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = m.sectionPad, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Attach placeholder
            OutlinedButton(
                onClick = { /* attach file — not yet wired */ },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.textDim),
                border = androidx.compose.foundation.BorderStroke(1.dp, c.border),
                shape = RoundedCornerShape(m.cardRadius),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("📎  Attach", style = CRType.pill, color = c.textDim)
            }

            // Command shortcut placeholder
            OutlinedButton(
                onClick = { text = "/" },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.textDim),
                border = androidx.compose.foundation.BorderStroke(1.dp, c.border),
                shape = RoundedCornerShape(m.cardRadius),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("⚡  /command", style = CRType.pill, color = c.textDim)
            }

            Spacer(Modifier.weight(1f))

            // Send
            Button(
                onClick = { if (text.isNotBlank()) onSend(text) },
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.accent,
                    contentColor = c.accentInk,
                    disabledContainerColor = c.surface2,
                    disabledContentColor = c.textDim,
                ),
                shape = RoundedCornerShape(m.cardRadius),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text("⏵  Send", style = CRType.cardTitle)
            }
        }
    }
}

// ── Tab composables ────────────────────────────────────────────────────────

@Composable
private fun TemplatesTab(
    templates: List<InputTemplate>,
    onPick: (String) -> Unit,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    if (templates.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No templates yet.", style = CRType.bodyDim, color = c.textDim)
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(templates) { tpl ->
                TemplateCard(template = tpl, onClick = { onPick(tpl.body) })
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@Composable
private fun HistoryTab(
    history: List<String>,
    onPick: (String) -> Unit,
) {
    val c = CRTheme.colors
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No history yet.", style = CRType.bodyDim, color = c.textDim)
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(history) { msg ->
                HistoryRow(text = msg, onClick = { onPick(msg) })
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@Composable
private fun TemplateCard(template: InputTemplate, onClick: () -> Unit) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    val shape = RoundedCornerShape(m.cardRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface2)
            .border(1.dp, c.border, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(template.name, style = CRType.cardTitle, color = c.text)
            Text(
                template.body,
                style = CRType.bodyDim,
                color = c.textDim,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HistoryRow(text: String, onClick: () -> Unit) {
    val c = CRTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("↑", style = CRType.mono, color = c.textDim)
        Text(
            text,
            style = CRType.cardTitle,
            color = c.text,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
