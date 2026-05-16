package com.clauderemote.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class CRColorScheme(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    val accentInk: Color,
    val tintAccent: Color,
    val tintGreen: Color,
    val tintRed: Color,
    val tintYellow: Color,
    val tintPurple: Color,
    val tintOrange: Color,
    val working: Color,
    val ready: Color,
    val approval: Color,
    val idle: Color,
    val disconnected: Color,
    val modeYolo: Color,
    val modePlan: Color,
    val modeAuto: Color,
    val modeNormal: Color,
)

object CRColorTokens {
    fun forVariant(variant: CRVariant, accent: CRAccent): CRColorScheme = when (variant) {
        CRVariant.Classic -> classic(accent)
        CRVariant.Glass -> glass(accent)
    }

    private fun classic(a: CRAccent) = CRColorScheme(
        bg = Color(0xFF0F172A),
        surface = Color(0xFF1E293B),
        surface2 = Color(0xFF283548),
        border = Color(0xFF334155),
        text = Color(0xFFE2E8F0),
        textDim = Color(0xFF94A3B8),
        accent = a.color,
        accentInk = Color(0xFF0F172A),
        tintAccent = a.color.copy(alpha = 0.15f),
        tintGreen = Color(0xFF4ADE80).copy(alpha = 0.15f),
        tintRed = Color(0xFFF87171).copy(alpha = 0.15f),
        tintYellow = Color(0xFFFBBF24).copy(alpha = 0.15f),
        tintPurple = Color(0xFFA78BFA).copy(alpha = 0.15f),
        tintOrange = Color(0xFFFB923C).copy(alpha = 0.18f),
        working = Color(0xFFFBBF24),
        ready = Color(0xFF4ADE80),
        approval = Color(0xFFFB923C),
        idle = Color(0xFF94A3B8),
        disconnected = Color(0xFFF87171),
        modeYolo = Color(0xFFF87171),
        modePlan = Color(0xFFA78BFA),
        modeAuto = Color(0xFF4ADE80),
        modeNormal = Color(0xFF94A3B8),
    )

    private fun glass(a: CRAccent) = classic(a).copy(
        bg = Color(0xFF06070B),
        surface = Color(0xFF1E293B).copy(alpha = 0.55f),
        surface2 = Color(0xFF283548).copy(alpha = 0.65f),
        border = Color(0xFF334155).copy(alpha = 0.6f),
    )
}
