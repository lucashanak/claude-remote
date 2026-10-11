package com.clauderemote.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? = try {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
} catch (_: Throwable) {
    null
}

actual fun decodeImageBitmapScaled(bytes: ByteArray, maxDimension: Int): ImageBitmap? = try {
    val image = Image.makeFromEncoded(bytes)
    val longest = maxOf(image.width, image.height)
    if (longest <= maxDimension) {
        image.toComposeImageBitmap()
    } else {
        val scale = maxDimension.toFloat() / longest
        val w = (image.width * scale).toInt().coerceAtLeast(1)
        val h = (image.height * scale).toInt().coerceAtLeast(1)
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(image, org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()))
        surface.makeImageSnapshot().toComposeImageBitmap()
    }
} catch (_: Throwable) {
    null
}
