package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clauderemote.model.PathCompletion
import com.clauderemote.model.RemoteDirEntry
import com.clauderemote.model.RemoteDirScan
import com.clauderemote.model.RemoteDirTree
import com.clauderemote.model.RemotePath
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType

/**
 * Remote folder picker.
 *
 * Replaces the anchored `DropdownMenu` the Connect screen used to browse with.
 * A menu was the wrong container for a navigable browser: capped at 380x360dp it
 * became a clipped sliver in a wide desktop window, drew over the card beneath
 * it, and cut its last row in half with nothing to say the list continued.
 *
 * Two rules keep the interaction unambiguous, which the old dropdown got wrong
 * by making one click both descend AND commit the path:
 *  - Clicking a row and pressing Enter do the SAME thing — descend into it.
 *  - Committing a path is always an explicit "Use" affordance (per row, or the
 *    footer button for the directory you have navigated to).
 *
 * Rendered as an in-window overlay rather than a [com.clauderemote.ui.components.FloatingDialog],
 * matching [CommandPaletteDialog] — the Connect screen has no heavyweight AWT
 * child to stack above, so the overlay is the simpler and safer of the two.
 */
@Composable
fun RemotePathPicker(
    initialPath: String,
    tree: RemoteDirTree,
    loading: Boolean,
    recents: List<String>,
    /** Bumped by the caller whenever a scan finishes; see [scanGeneration] use below. */
    scanGeneration: Int,
    /** True when the last attempt to list the directory we are showing FAILED. */
    loadFailed: (String) -> Boolean,
    onRequestListing: (String) -> Unit,
    onRefresh: (String) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics

    var cwd by remember { mutableStateOf(RemotePath.normalize(initialPath)) }
    var filter by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    // The highlight is held as a PATH and the index derived from it. Holding an
    // index meant a refresh or deepen scan that merged in a listing with one
    // fewer entry silently slid the highlight onto a DIFFERENT folder — and then
    // Enter descended into it, or Tab committed it. Toggling hidden folders did
    // the same, and the index could point past the end until the next arrow key
    // healed it.
    var selectedPath by remember { mutableStateOf<String?>(null) }

    // Navigating is free once the subtree is cached; only an unscanned directory
    // (deeper than the last scan reached) costs a round trip.
    //
    // Keyed on [scanGeneration] rather than on `tree`: keying on the tree works
    // only because `RemoteDirTree` compares by identity for want of an `equals`,
    // and resting recovery on that would turn adding a natural `equals` into a
    // silent deadlock here.
    LaunchedEffect(cwd, scanGeneration) {
        if (!tree.hasListing(cwd)) onRequestListing(cwd)
    }
    LaunchedEffect(filter, cwd) { selectedPath = null }

    // Android's system Back has to close the picker, not the screen behind it.
    // Escape already does this for a keyboard; without this, Back on a phone
    // navigated away and left the modal's owner gone from under it.
    PlatformBackHandler(enabled = true) {
        // Back walks OUT of the tree first, matching the breadcrumb, and only
        // dismisses once there is nowhere left to go up to — the same shape as
        // Backspace in the filter field.
        val parent = RemotePath.parent(cwd)
        if (filter.isNotEmpty()) filter = ""
        else if (parent != null) cwd = parent
        else onDismiss()
    }

    val rows: List<RemoteDirEntry> = remember(tree, cwd, filter, showHidden) {
        val all = tree.children(cwd).filter { showHidden || !it.isHidden }
        if (filter.isBlank()) all
        else all.mapNotNull { entry ->
            PathCompletion.fuzzyMatch(entry.name, filter)?.let { it.score to entry }
        }.sortedByDescending { it.first }.map { it.second }
    }
    // Falls back to the first row whenever the selected path is gone from the
    // current listing, which is the only sane answer once it no longer exists.
    val selectedIndex = rows.indexOfFirst { it.path == selectedPath }.let { if (it < 0) 0 else it }
    val hiddenCount = remember(tree, cwd) { tree.children(cwd).count { it.isHidden } }
    val truncated = tree.truncated

    val navigateInto: (RemoteDirEntry) -> Unit = { entry ->
        cwd = entry.path
        filter = ""
    }
    val goUp: () -> Unit = {
        RemotePath.parent(cwd)?.let { cwd = it; filter = "" }
    }
    fun openSelected() {
        rows.getOrNull(selectedIndex)?.let(navigateInto)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = Alignment.TopCenter,
    ) {
        val panelShape = RoundedCornerShape(m.cardRadius * 2)
        Box(
            modifier = Modifier
                // On a phone `widthIn` collapses to the available width, and the
                // height cap is lifted so the panel grows into the screen the way
                // a sheet would — capped, it left dead scrim below a list that was
                // itself cut off. The parent's own max-height still clamps it, so
                // "as tall as the content, up to the screen" needs no second
                // layout path.
                // Composed outside the Scaffold now, so nothing applies window
                // insets for us any more: without these the panel can sit under
                // the status bar or a camera cutout, and — worse, since this has
                // a text field — the soft keyboard covers the filter and the
                // "Use this folder" button with no way to scroll them back.
                .safeDrawingPadding()
                .imePadding()
                .padding(top = if (isMobile) 24.dp else 56.dp, start = 16.dp, end = 16.dp, bottom = 24.dp)
                .widthIn(min = 320.dp, max = 760.dp)
                .heightIn(max = if (isMobile) Dp.Infinity else 620.dp)
                .background(c.surface, panelShape)
                .border(1.dp, c.border, panelShape)
                .pointerInput(Unit) { detectTapGestures { /* swallow taps inside the panel */ } },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ── Breadcrumb ────────────────────────────────────────────
                // Every segment jumps straight to that level. The old picker
                // offered only ".. (up one level)", and each of those steps was
                // its own SSH round trip.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val crumbs = remember(cwd) { RemotePath.crumbs(cwd) }
                        crumbs.forEachIndexed { index, crumb ->
                            if (index > 0) {
                                Text(
                                    "/",
                                    style = CRType.mono,
                                    color = c.textDim,
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                )
                            }
                            val isLast = index == crumbs.lastIndex
                            Text(
                                crumb.label,
                                style = CRType.mono.copy(
                                    fontWeight = if (isLast) FontWeight.W700 else FontWeight.Normal
                                ),
                                color = if (isLast) c.text else c.accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable(enabled = !isLast) { cwd = crumb.path; filter = "" }
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = c.accent,
                        )
                        Spacer(Modifier.width(8.dp))
                    } else {
                        IconButton(onClick = { onRefresh(cwd) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Refresh, "Refresh", tint = c.accent, modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, "Close", tint = c.textDim, modifier = Modifier.size(18.dp))
                    }
                }

                // ── Recent jumps ──────────────────────────────────────────
                // Chips navigate (they do not commit), so chips, rows and Enter
                // all mean the same thing throughout the picker.
                if (recents.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("RECENT", style = CRType.sectionH, color = c.textDim)
                        recents.forEach { recent ->
                            val chipShape = RoundedCornerShape(999.dp)
                            Text(
                                RemotePath.name(recent),
                                style = CRType.mono,
                                color = c.text,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(chipShape)
                                    .background(c.surface2, chipShape)
                                    .border(1.dp, c.border, chipShape)
                                    .clickable { cwd = RemotePath.normalize(recent); filter = "" }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }

                // ── Filter ────────────────────────────────────────────────
                val filterFocus = remember { FocusRequester() }
                // Desktop opens with the cursor in the filter so the picker is
                // type-to-narrow; on a phone that would summon the soft keyboard
                // over the very list the user came to browse.
                LaunchedEffect(Unit) { if (!isMobile) filterFocus.requestFocus() }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        placeholder = { Text("Filter folders…", style = CRType.cardTitle, color = c.textDim) },
                        leadingIcon = {
                            Icon(Icons.Filled.Search, null, tint = c.textDim, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(filterFocus)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        selectedPath = rows.getOrNull(selectedIndex + 1)?.path
                                            ?: selectedPath
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        selectedPath = rows.getOrNull(selectedIndex - 1)?.path
                                            ?: selectedPath
                                        true
                                    }
                                    // Enter descends, matching a row click. It
                                    // never moves a caret, so it needs no guard.
                                    Key.Enter -> { openSelected(); true }
                                    // Committing is always explicit — Tab is the
                                    // keyboard twin of the "Use" buttons.
                                    Key.Tab -> {
                                        onPick(rows.getOrNull(selectedIndex)?.path ?: cwd)
                                        true
                                    }
                                    // Navigation keys only navigate once the
                                    // filter is empty; while there is text they
                                    // belong to the caret. Taking Left
                                    // unconditionally meant spotting a typo,
                                    // pressing Left to fix it, and instead
                                    // jumping up a directory with the filter
                                    // wiped.
                                    Key.DirectionRight ->
                                        if (filter.isEmpty()) { openSelected(); true } else false
                                    Key.DirectionLeft, Key.Backspace ->
                                        if (filter.isEmpty()) { goUp(); true } else false
                                    Key.Escape -> { onDismiss(); true }
                                    else -> false
                                }
                            },
                        singleLine = true,
                        // Soft keyboards deliver Enter as an IME action, not a
                        // Key.Enter event.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { openSelected() }),
                        textStyle = CRType.cardTitle,
                        colors = crTextFieldColors(),
                    )
                    if (hiddenCount > 0) {
                        val toggleShape = RoundedCornerShape(6.dp)
                        Text(
                            if (showHidden) "Hide hidden" else "Hidden ($hiddenCount)",
                            style = CRType.pill,
                            color = if (showHidden) c.accent else c.textDim,
                            modifier = Modifier
                                .clip(toggleShape)
                                .background(if (showHidden) c.tintAccent else c.surface2, toggleShape)
                                .border(1.dp, c.border, toggleShape)
                                .clickable { showHidden = !showHidden }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }

                HorizontalDivider(color = c.border)

                // ── Listing ───────────────────────────────────────────────
                val listState = rememberLazyListState()
                LaunchedEffect(selectedIndex, rows.size) {
                    if (selectedIndex in rows.indices) listState.animateScrollToItem(selectedIndex)
                }

                // Where the ordering flips from projects to plain folders, so the
                // list says WHY the top entries are on top.
                val firstPlainIndex = remember(rows) { rows.indexOfFirst { !it.isProject } }

                Box(modifier = Modifier.weight(1f, fill = false)) {
                    when {
                        rows.isEmpty() && loading && !tree.hasListing(cwd) -> PickerNotice("Loading…")
                        // A failure must never render as "No subfolders" — one
                        // dropped tunnel used to be indistinguishable from an
                        // empty home directory, with no way back but Refresh.
                        rows.isEmpty() && loadFailed(cwd) -> PickerNotice(
                            "Couldn't list this folder.",
                            actionLabel = "Retry",
                            onAction = { onRefresh(cwd) },
                        )
                        rows.isEmpty() && filter.isNotBlank() -> PickerNotice("No folder matches \"$filter\"")
                        rows.isEmpty() && hiddenCount > 0 -> PickerNotice("Only hidden folders here — Show hidden")
                        rows.isEmpty() -> PickerNotice("No subfolders")
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            state = listState,
                            contentPadding = PaddingValues(bottom = 6.dp),
                        ) {
                            itemsIndexed(rows, key = { _, entry -> entry.path }) { index, entry ->
                                if (filter.isBlank() && (index == 0 || index == firstPlainIndex)) {
                                    Text(
                                        if (entry.isProject) "PROJECTS" else "FOLDERS",
                                        style = CRType.sectionH,
                                        color = c.textDim,
                                        modifier = Modifier
                                            .padding(horizontal = 16.dp)
                                            .padding(top = 10.dp, bottom = 4.dp),
                                    )
                                }
                                FolderRow(
                                    entry = entry,
                                    selected = index == selectedIndex,
                                    onOpen = { navigateInto(entry) },
                                    onUse = { onPick(entry.path) },
                                )
                            }
                        }
                    }
                }

                if (truncated) {
                    Text(
                        "Showing the first ${RemoteDirScan.MAX_DIRS} folders — filter to narrow.",
                        style = CRType.bodyDim,
                        color = c.textDim,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                HorizontalDivider(color = c.border)

                // ── Commit ────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        cwd,
                        style = CRType.mono,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { onPick(cwd) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.accent,
                            contentColor = c.accentInk,
                        ),
                    ) {
                        Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Use this folder", style = CRType.cardTitle)
                    }
                }
            }
        }
    }
}

/**
 * One directory row: the body descends, the trailing "Use" commits.
 *
 * Icons are Material vectors, never font glyphs. The old rows drew `🗀`
 * (U+1F5C0), which the bundled desktop font has no coverage for, so every row
 * showed a tofu box instead of a folder.
 */
@Composable
private fun FolderRow(
    entry: RemoteDirEntry,
    selected: Boolean,
    onOpen: () -> Unit,
    onUse: () -> Unit,
) {
    val c = CRTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) c.tintAccent else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen,
            )
            .padding(start = 16.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.Folder,
            null,
            tint = if (entry.isProject) c.accent else c.textDim,
            modifier = Modifier.size(18.dp),
        )
        Text(
            entry.name,
            style = CRType.cardTitle,
            color = if (entry.isHidden) c.textDim else c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (entry.isProject) {
            val badgeShape = RoundedCornerShape(4.dp)
            Text(
                "git",
                style = CRType.pill,
                color = c.accent,
                modifier = Modifier
                    .clip(badgeShape)
                    .background(c.tintAccent, badgeShape)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Text(
            "Use",
            style = CRType.pill,
            // Always present, because touch has no hover to reveal it on demand,
            // but dim until this is the row the keyboard is on — at full accent
            // on every row it read as the primary action instead of the escape
            // hatch from "click descends".
            color = if (selected) c.accent else c.textDim,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onUse)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            "Open",
            tint = c.textDim,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun PickerNotice(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = CRTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = CRType.bodyDim, color = c.textDim)
        if (actionLabel != null && onAction != null) {
            val shape = RoundedCornerShape(6.dp)
            Text(
                actionLabel,
                style = CRType.cardTitle,
                color = c.accent,
                modifier = Modifier
                    .clip(shape)
                    .background(c.tintAccent, shape)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
