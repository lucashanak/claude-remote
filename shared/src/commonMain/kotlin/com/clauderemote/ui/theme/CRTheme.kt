package com.clauderemote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

val LocalCRVariant = compositionLocalOf { CRVariant.Classic }
val LocalCRDensity = compositionLocalOf { CRDensity.Regular }
val LocalCRAccent = compositionLocalOf { CRAccent.Sky }
val LocalCRColors = compositionLocalOf<CRColorScheme> { error("No CRColors provided") }
val LocalCRMetrics = compositionLocalOf<CRMetrics> { error("No CRMetrics provided") }
val LocalCRStatusViz = compositionLocalOf { CRStatusViz.Pill }
val LocalCRTerminalView = compositionLocalOf { CRTerminalView.Raw }
val LocalCRTerminalScheme = compositionLocalOf { CRTerminalScheme.Default }

object CRTheme {
    val colors: CRColorScheme @Composable @ReadOnlyComposable get() = LocalCRColors.current
    val metrics: CRMetrics @Composable @ReadOnlyComposable get() = LocalCRMetrics.current
    val variant: CRVariant @Composable @ReadOnlyComposable get() = LocalCRVariant.current
    val density: CRDensity @Composable @ReadOnlyComposable get() = LocalCRDensity.current
    val accent: CRAccent @Composable @ReadOnlyComposable get() = LocalCRAccent.current
    val statusViz: CRStatusViz @Composable @ReadOnlyComposable get() = LocalCRStatusViz.current
    val terminalView: CRTerminalView @Composable @ReadOnlyComposable get() = LocalCRTerminalView.current
    val terminalScheme: CRTerminalScheme @Composable @ReadOnlyComposable get() = LocalCRTerminalScheme.current
}

@Composable
fun CRTheme(
    appearance: AppearanceState,
    content: @Composable () -> Unit,
) {
    val colors = remember(appearance.variant, appearance.accent) {
        CRColorTokens.forVariant(appearance.variant, appearance.accent)
    }
    val metrics = remember(appearance.density) {
        CRMetrics.forDensity(appearance.density)
    }
    CompositionLocalProvider(
        LocalCRVariant provides appearance.variant,
        LocalCRDensity provides appearance.density,
        LocalCRAccent provides appearance.accent,
        LocalCRColors provides colors,
        LocalCRMetrics provides metrics,
        LocalCRStatusViz provides appearance.statusViz,
        LocalCRTerminalView provides appearance.terminalView,
        LocalCRTerminalScheme provides appearance.terminalScheme,
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = colors.accent,
                onPrimary = colors.accentInk,
                background = colors.bg,
                surface = colors.surface,
                onSurface = colors.text,
                onSurfaceVariant = colors.textDim,
                outline = colors.border,
            ),
        ) {
            content()
        }
    }
}
