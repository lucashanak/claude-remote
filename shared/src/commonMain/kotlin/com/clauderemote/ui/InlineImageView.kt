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
import androidx.compose.ui.unit.dp
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fetches a file from the session's server; same contract as onDownloadFile. */
internal typealias ServerImageLoader = suspend (path: String) -> ByteArray?

/** Opens the full-screen preview (which also offers Save). */
internal typealias OpenImagePreview = (name: String, bytes: ByteArray, bitmap: ImageBitmap) -> Unit

/**
 * Decoded images, kept so a message scrolled out of the list and back does not
 * fetch and decode its image again over SSH.
 *
 * Keyed per (session, message, path) rather than by path alone: Claude often
 * re-renders the SAME file (`/tmp/shot.png`) for a later answer, and a
 * path-only key would show the stale picture in the new message. Bounded by
 * count and by bytes, because a phone holding a dozen full-resolution
 * screenshots as bitmaps is how an app gets killed in the background.
 */
internal object InlineImageCache {
    private const val MAX_ENTRIES = 24
    private const val MAX_BYTES = 48L * 1024 * 1024

    class Entry(val bytes: ByteArray, val bitmap: ImageBitmap)

    private val map = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var total = 0L

    fun key(scope: String, messageId: String, path: String) = "$scope|$messageId|$path"

    @Synchronized fun get(key: String): Entry? = map[key]

    @Synchronized fun put(key: String, entry: Entry) {
        map.remove(key)?.let { total -= it.bytes.size }
        map[key] = entry
        total += entry.bytes.size
        val it = map.entries.iterator()
        while ((map.size > MAX_ENTRIES || total > MAX_BYTES) && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == key) continue
            total -= eldest.value.bytes.size
            it.remove()
        }
    }
}

/**
 * Bigger than this is not decoded inline: the bitmap would cost several times
 * the file size in memory, inside a scrolling list. It can still be opened from
 * the fallback row, which goes through the normal preview.
 */
private const val MAX_INLINE_BYTES = 15 * 1024 * 1024

private sealed interface LoadState {
    data object Loading : LoadState
    class Ready(val entry: InlineImageCache.Entry) : LoadState
    data class Failed(val reason: String) : LoadState
}

/**
 * An image Claude chose to show, rendered inside its answer. Tapping it opens
 * the full-screen preview; a failed load can be retried by tapping the row.
 */
@Composable
internal fun InlineImage(
    alt: String,
    path: String,
    cacheKey: String,
    load: ServerImageLoader,
    onOpen: OpenImagePreview?,
) {
    val c = CRTheme.colors
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember(cacheKey) {
        mutableStateOf<LoadState>(InlineImageCache.get(cacheKey)?.let { LoadState.Ready(it) } ?: LoadState.Loading)
    }
    LaunchedEffect(cacheKey, attempt) {
        if (state is LoadState.Ready) return@LaunchedEffect
        state = LoadState.Loading
        state = try {
            val bytes = withContext(Dispatchers.Default) { load(path) }
            when {
                bytes === SessionOrchestrator.DOWNLOAD_TOO_LARGE -> LoadState.Failed("too large to show (>50 MB)")
                bytes == null -> LoadState.Failed("could not be loaded")
                bytes.size > MAX_INLINE_BYTES -> LoadState.Failed("too large to show inline")
                else -> {
                    val bitmap = withContext(Dispatchers.Default) { com.clauderemote.util.decodeImageBitmap(bytes) }
                    if (bitmap == null) LoadState.Failed("is not a readable image")
                    else InlineImageCache.Entry(bytes, bitmap).also { InlineImageCache.put(cacheKey, it) }
                        .let { LoadState.Ready(it) }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            LoadState.Failed("could not be loaded")
        }
    }

    val shape = RoundedCornerShape(8.dp)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        when (val s = state) {
            is LoadState.Ready -> Image(
                bitmap = s.entry.bitmap,
                contentDescription = alt.ifBlank { fileNameFromPath(path) },
                contentScale = ContentScale.Fit,
                // Fit within the card's width and a sane height: a tall
                // screenshot must not push the rest of the answer off screen.
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .clip(shape)
                    .border(1.dp, c.border, shape)
                    .then(
                        if (onOpen != null) Modifier.clickable {
                            onOpen(fileNameFromPath(path), s.entry.bytes, s.entry.bitmap)
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
                Text("Loading ${fileNameFromPath(path)}…", style = CRType.monoTiny, color = c.textDim)
            }
            is LoadState.Failed -> Text(
                "🖼 ${fileNameFromPath(path)} ${s.reason} — tap to retry",
                style = CRType.monoTiny,
                color = c.textDim,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(c.surface2)
                    .clickable { attempt++ }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        if (alt.isNotBlank() && state is LoadState.Ready) {
            Text(alt, style = CRType.monoTiny, color = c.textDim, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
