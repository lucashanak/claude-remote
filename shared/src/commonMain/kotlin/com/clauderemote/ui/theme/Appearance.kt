package com.clauderemote.ui.theme

import androidx.compose.ui.graphics.Color

enum class CRVariant { Classic, Glass }

enum class CRDensity { Compact, Regular, Dense }

enum class CRAccent(val color: Color, val label: String) {
    Sky(Color(0xFF38BDF8), "Sky"),
    Mint(Color(0xFF4ADE80), "Mint"),
    Amber(Color(0xFFFBBF24), "Amber"),
    Violet(Color(0xFFA78BFA), "Violet"),
    Rose(Color(0xFFF472B6), "Rose"),
}

enum class CRStatusViz { Dot, Pill, Bar }

enum class CRTerminalView { Raw, Transcript }

enum class CRTerminalScheme(val id: String, val label: String) {
    Default("default", "Default"),
    SolarizedDark("solarized-dark", "Solarized Dark"),
    Dracula("dracula", "Dracula"),
    Monokai("monokai", "Monokai"),
    Linux("linux", "Linux"),
}

data class AppearanceState(
    val variant: CRVariant = CRVariant.Classic,
    val density: CRDensity = CRDensity.Regular,
    val accent: CRAccent = CRAccent.Sky,
    val statusViz: CRStatusViz = CRStatusViz.Pill,
    val terminalView: CRTerminalView = CRTerminalView.Raw,
    val terminalScheme: CRTerminalScheme = CRTerminalScheme.Default,
)
