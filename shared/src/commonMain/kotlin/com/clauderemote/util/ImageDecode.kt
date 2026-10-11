package com.clauderemote.util

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decode encoded image bytes (PNG/JPEG/etc) into a Compose [ImageBitmap].
 * Returns null if the bytes are not a decodable image on this platform.
 */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

/**
 * Decode, downscaling so neither side exceeds [maxDimension] pixels.
 *
 * File size says nothing about decoded size: a 2 MB PNG can be 12000x8000,
 * which is a 384 MB bitmap — on Android that lives in native memory, so it
 * does not even throw OOM; the low-memory killer just takes the app. Anything
 * shown inline goes through this instead of [decodeImageBitmap].
 */
expect fun decodeImageBitmapScaled(bytes: ByteArray, maxDimension: Int): ImageBitmap?

