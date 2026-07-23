package com.clauderemote.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

// BUNDLED fonts, not FontFamily.Default/.Monospace. Compose Desktop 1.7.x's
// FontCache.loadPlatformTypes does `.first()` on the platform font list and
// throws "NoSuchElementException: Array is empty" at the FIRST composition when
// the system reports no usable font for the requested generic family. On some
// Linux setups (notably a Manjaro without a resolvable monospace family — the
// terminal renders monospace text immediately) that crashed the packaged app on
// launch: the window opened then closed instantly. Shipping our own DejaVu TTFs
// makes text rendering independent of the system font list, so it can't happen.
// Only Normal + Bold are bundled; Compose synthesizes the in-between weights
// (W600/W800) the type scale uses.
actual val CRFontSans: FontFamily = FontFamily(
    Font("font/DejaVuSans.ttf", FontWeight.Normal, FontStyle.Normal),
    Font("font/DejaVuSans-Bold.ttf", FontWeight.Bold, FontStyle.Normal),
)

actual val CRFontMono: FontFamily = FontFamily(
    Font("font/DejaVuSansMono.ttf", FontWeight.Normal, FontStyle.Normal),
    Font("font/DejaVuSansMono-Bold.ttf", FontWeight.Bold, FontStyle.Normal),
)
