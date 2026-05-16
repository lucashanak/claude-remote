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
