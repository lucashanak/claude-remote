package com.clauderemote.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

expect val CRFontSans: FontFamily
expect val CRFontMono: FontFamily

object CRType {
    val micro = 11.sp
    val xs = 12.sp
    val sm = 13.sp
    val base = 14.sp
    val lg = 15.sp
    val xl = 17.sp
    val h2 = 20.sp
    val h1 = 24.sp

    val titleBold = TextStyle(fontFamily = CRFontSans, fontSize = h1, fontWeight = FontWeight.W800, letterSpacing = (-0.5).sp)
    val cardTitle = TextStyle(fontFamily = CRFontSans, fontSize = base, fontWeight = FontWeight.W600)
    val sectionH = TextStyle(fontFamily = CRFontSans, fontSize = xs, fontWeight = FontWeight.W600, letterSpacing = 0.6.sp)
    val bodyDim = TextStyle(fontFamily = CRFontSans, fontSize = xs, fontWeight = FontWeight.Normal)
    val mono = TextStyle(fontFamily = CRFontMono, fontSize = xs)
    val monoTiny = TextStyle(fontFamily = CRFontMono, fontSize = micro)
    val pill = TextStyle(fontFamily = CRFontSans, fontSize = micro, fontWeight = FontWeight.W600, letterSpacing = 0.5.sp)
    val keyboardKey = TextStyle(fontFamily = CRFontMono, fontSize = xs, fontWeight = FontWeight.W600)
}

/**
 * Material3 [androidx.compose.material3.Typography] with every style pinned to
 * [CRFontSans]. Material components (dialogs, buttons, menus, text fields) and
 * any Text without an explicit style read MaterialTheme.typography — which
 * defaults to FontFamily.Default → the SansSerif GENERIC family. On desktop that
 * generic family is resolved against the system font list, and Compose 1.7.x
 * throws "Array is empty" when the system has no usable font — crashing the app
 * on launch. Pinning to CRFontSans (a BUNDLED family on desktop; FontFamily.Default
 * on Android, so unchanged there) removes that system dependency everywhere.
 */
fun crTypography(): androidx.compose.material3.Typography {
    val b = androidx.compose.material3.Typography()
    val f = CRFontSans
    return androidx.compose.material3.Typography(
        displayLarge = b.displayLarge.copy(fontFamily = f),
        displayMedium = b.displayMedium.copy(fontFamily = f),
        displaySmall = b.displaySmall.copy(fontFamily = f),
        headlineLarge = b.headlineLarge.copy(fontFamily = f),
        headlineMedium = b.headlineMedium.copy(fontFamily = f),
        headlineSmall = b.headlineSmall.copy(fontFamily = f),
        titleLarge = b.titleLarge.copy(fontFamily = f),
        titleMedium = b.titleMedium.copy(fontFamily = f),
        titleSmall = b.titleSmall.copy(fontFamily = f),
        bodyLarge = b.bodyLarge.copy(fontFamily = f),
        bodyMedium = b.bodyMedium.copy(fontFamily = f),
        bodySmall = b.bodySmall.copy(fontFamily = f),
        labelLarge = b.labelLarge.copy(fontFamily = f),
        labelMedium = b.labelMedium.copy(fontFamily = f),
        labelSmall = b.labelSmall.copy(fontFamily = f),
    )
}
