package com.clauderemote.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Fetches a file from the session's server. Callers must apply the inline caps
 * ([INLINE_MAX_BYTES], a time limit) on the SERVER side, before the transfer —
 * see SessionOrchestrator.downloadFileCapped.
 */
internal typealias ServerImageLoader = suspend (path: String) -> ByteArray?

/** Opens the full-screen preview (which also offers Save). */
internal typealias OpenImagePreview = (name: String, bytes: ByteArray, bitmap: ImageBitmap) -> Unit

/** Largest file fetched for inline display. Bigger ones are offered as a download. */
internal const val INLINE_MAX_BYTES = 15L * 1024 * 1024

/** How long an inline fetch may take before it is given up. */
internal const val INLINE_TIMEOUT_MS = 30_000L

/**
 * Longest side of a decoded inline image. The card is at most a phone's width
 * across, so more pixels than this only cost memory: a full-resolution
 * screenshot is ~10 MB as a bitmap, a large render far more.
 */
private const val INLINE_MAX_DIMENSION = 2048

/** Must match MAX_SHEET_SIDE in turntable.py, which renders sheets within it. */
private const val TURNTABLE_MAX_DIMENSION = 3072

/**
 * At most this many inline fetches at once. Each one opens an SFTP channel on
 * the session's SSH connection, which OpenSSH caps (MaxSessions, 10 by default)
 * together with the terminal and the path checks — scrolling through a history
 * full of images used to fail loads and starve the other channels.
 */
private val fetchPermits = Semaphore(3)

/**
 * Decoded images, so a message scrolled out of the list and back does not go
 * over SSH again — and failures, so a file that is too big or missing is not
 * re-fetched on every scroll either.
 *
 * Keyed per (session, message, path): Claude often re-renders the SAME
 * /tmp/shot.png for a later answer, and a path-only key would show the stale
 * picture in the new message.
 *
 * The budget counts what is actually held: the DECODED bitmap (width × height ×
 * 4) plus the original bytes kept for Save. Counting only the encoded file
 * looked like a 48 MB budget while a couple of dozen screenshots sat in memory
 * at ten times that.
 */
internal object InlineImageCache {
    private const val MAX_ENTRIES = 24
    private const val MAX_COST = 96L * 1024 * 1024
    private const val MAX_FAILURES = 64

    class Entry(val bytes: ByteArray, val bitmap: ImageBitmap) {
        val cost: Long = bytes.size + bitmap.width.toLong() * bitmap.height * 4
    }

    private val map = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val failures = LinkedHashMap<String, Failure>(16, 0.75f, true)
    private var total = 0L

    fun key(scope: String, messageId: String, path: String) = "$scope|$messageId|$path"

    @Synchronized fun get(key: String): Entry? = map[key]

    @Synchronized fun failure(key: String): Failure? = failures[key]

    @Synchronized fun putFailure(key: String, failure: Failure) {
        failures[key] = failure
        val it = failures.entries.iterator()
        while (failures.size > MAX_FAILURES && it.hasNext()) { it.next(); it.remove() }
    }

    @Synchronized fun clearFailure(key: String) { failures.remove(key) }

    @Synchronized fun put(key: String, entry: Entry) {
        failures.remove(key)
        map.remove(key)?.let { total -= it.cost }
        map[key] = entry
        total += entry.cost
        val it = map.entries.iterator()
        while ((map.size > MAX_ENTRIES || total > MAX_COST) && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == key) continue
            total -= eldest.value.cost
            it.remove()
        }
    }
}

/** Why an inline image is not showing, and whether a download can still help. */
internal data class Failure(val reason: String, val offerDownload: Boolean)

private sealed interface LoadState {
    data object Loading : LoadState
    class Ready(val entry: InlineImageCache.Entry) : LoadState
    class Failed(val failure: Failure) : LoadState
}

/**
 * An image Claude chose to show, rendered inside its answer. Tapping it opens
 * the full-screen preview. When it cannot be shown inline, the row says why and
 * — for a file that is merely too big — taps through to the normal file
 * download, so the picture is still reachable from the chat.
 */
@Composable
internal fun InlineImage(
    alt: String,
    path: String,
    cacheKey: String,
    load: ServerImageLoader,
    onOpen: OpenImagePreview?,
    onFileLink: ((String) -> Unit)?,
) {
    val c = CRTheme.colors
    val turntable = remember(path) { TurntableSheet.parse(path) }
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember(cacheKey) {
        mutableStateOf<LoadState>(
            InlineImageCache.get(cacheKey)?.let { LoadState.Ready(it) }
                ?: InlineImageCache.failure(cacheKey)?.let { LoadState.Failed(it) }
                ?: LoadState.Loading
        )
    }
    LaunchedEffect(cacheKey, attempt) {
        if (state is LoadState.Ready) return@LaunchedEffect
        // A cached failure is final until the user explicitly retries.
        if (state is LoadState.Failed && attempt == 0) return@LaunchedEffect
        state = LoadState.Loading
        val outcome: LoadState = try {
            val bytes = fetchPermits.withPermit { load(path) }
            when {
                bytes === SessionOrchestrator.DOWNLOAD_TOO_LARGE ->
                    LoadState.Failed(Failure("is too large to show here", offerDownload = true))
                bytes == null -> LoadState.Failed(Failure("could not be loaded", offerDownload = false))
                else -> {
                    // A turntable sheet is many frames in one picture: downscaling
                    // it to the ordinary inline size would blur every frame, so it
                    // gets the sheet budget turntable.py renders within instead.
                    val maxDim = if (turntable != null) TURNTABLE_MAX_DIMENSION else INLINE_MAX_DIMENSION
                    val bitmap = withContext(Dispatchers.Default) {
                        com.clauderemote.util.decodeImageBitmapScaled(bytes, maxDim)
                    }
                    if (bitmap == null) LoadState.Failed(Failure("is not a readable image", offerDownload = true))
                    else LoadState.Ready(InlineImageCache.Entry(bytes, bitmap))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            LoadState.Failed(Failure("could not be loaded", offerDownload = false))
        }
        when (outcome) {
            is LoadState.Ready -> InlineImageCache.put(cacheKey, outcome.entry)
            is LoadState.Failed -> InlineImageCache.putFailure(cacheKey, outcome.failure)
            LoadState.Loading -> Unit
        }
        state = outcome
    }

    val name = fileNameFromPath(path)
    val shape = RoundedCornerShape(8.dp)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        when (val s = state) {
            is LoadState.Ready -> if (turntable != null && turntable.fits(s.entry.bitmap.width, s.entry.bitmap.height)) {
                TurntableView(s.entry.bitmap, turntable, alt.ifBlank { name })
            } else Image(
                bitmap = s.entry.bitmap,
                contentDescription = alt.ifBlank { name },
                contentScale = ContentScale.Fit,
                // Fit within the card's width and a sane height: a tall
                // screenshot must not push the rest of the answer off screen.
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .clip(shape)
                    .border(1.dp, c.border, shape)
                    .then(
                        if (onOpen != null) Modifier.clickable(onClickLabel = "Open full screen", role = Role.Image) {
                            onOpen(name, s.entry.bytes, s.entry.bitmap)
                        } else Modifier
                    ),
            )
            LoadState.Loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(shape)
                    .background(c.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Text("Loading $name…", style = CRType.monoTiny, color = c.textDim)
            }
            is LoadState.Failed -> {
                val download = s.failure.offerDownload && onFileLink != null
                Text(
                    "Image $name ${s.failure.reason} — " + if (download) "tap to download" else "tap to retry",
                    style = CRType.monoTiny,
                    color = c.textDim,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(c.surface2)
                        .clickable(role = Role.Button) {
                            if (download) {
                                onFileLink?.invoke(path)
                            } else {
                                InlineImageCache.clearFailure(cacheKey)
                                attempt++
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        if (alt.isNotBlank() && state is LoadState.Ready) {
            // Visible caption only — the image already carries the alt as its
            // description, so a screen reader would otherwise read it twice.
            Text(
                alt,
                style = CRType.monoTiny,
                color = c.textDim,
                modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics { },
            )
        }
    }
}
