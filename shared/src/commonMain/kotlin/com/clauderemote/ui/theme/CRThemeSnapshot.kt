package com.clauderemote.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme

/**
 * Plain-value copy of the CRTheme composition locals, captured at a call site
 * that's still inside the original composition. A brand new top-level window
 * (Compose Desktop's DialogWindow) starts a SEPARATE composition root that
 * does not inherit CompositionLocals from its caller — reading CRTheme.colors
 * etc. from inside that new root without re-providing them would crash
 * ("No CRColors provided"). Capture the values here, thread this snapshot
 * across the window boundary as ordinary data, then [reprovide] it inside
 * the new root before rendering content that expects CRTheme to be present.
 */
data class CRThemeSnapshot(
    val variant: CRVariant,
    val density: CRDensity,
    val accent: CRAccent,
    val colors: CRColorScheme,
    val metrics: CRMetrics,
    val statusViz: CRStatusViz,
    val terminalView: CRTerminalView,
    val terminalScheme: CRTerminalScheme,
) {
    companion object {
        /** Capture the CURRENT values — call from inside the original composition. */
        @Composable
        fun current(): CRThemeSnapshot = CRThemeSnapshot(
            variant = CRTheme.variant,
            density = CRTheme.density,
            accent = CRTheme.accent,
            colors = CRTheme.colors,
            metrics = CRTheme.metrics,
            statusViz = CRTheme.statusViz,
            terminalView = CRTheme.terminalView,
            terminalScheme = CRTheme.terminalScheme,
        )
    }
}

/** Re-establish this snapshot's values as CRTheme composition locals around [content]. */
@Composable
fun CRThemeSnapshot.reprovide(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalCRVariant provides variant,
        LocalCRDensity provides density,
        LocalCRAccent provides accent,
        LocalCRColors provides colors,
        LocalCRMetrics provides metrics,
        LocalCRStatusViz provides statusViz,
        LocalCRTerminalView provides terminalView,
        LocalCRTerminalScheme provides terminalScheme,
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
