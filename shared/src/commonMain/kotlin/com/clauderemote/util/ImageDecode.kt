package com.clauderemote.util

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decode encoded image bytes (PNG/JPEG/etc) into a Compose [ImageBitmap].
 * Returns null if the bytes are not a decodable image on this platform.
 */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
