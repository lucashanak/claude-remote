# Handoff: Claude Remote — UI Redesign

Kompletní implementační spec pro redesign aplikace **Claude Remote** v Kotlin Multiplatform +
Compose Multiplatform.

> **Repo:** `github.com/lucashanak/claude-remote`
> **Target stack:** Kotlin Multiplatform + Compose Multiplatform + Material 3
> **Platformy:** Android (API 26+), Desktop (macOS / Windows / Linux)
> **Existing UI source:** `shared/src/commonMain/kotlin/com/clauderemote/ui/`

---

## 0 · Table of contents

1. [Overview & scope](#1--overview--scope)
2. [How to read the prototype](#2--how-to-read-the-prototype)
3. [Theme infrastructure](#3--theme-infrastructure-crtheme)
   - Color tokens · Type tokens · Spacing · Density · Variant
   - `CRTheme` composable + `LocalCR…` providers
   - DataStore persistence
4. [Appearance Settings screen](#4--appearance-settings-screen-user-facing-toggle)
5. [Component primitives](#5--component-primitives)
6. [Screens (in implementation order)](#6--screens)
   - 6.1 Launcher
   - 6.2 Session drawer
   - 6.3 Terminal
   - 6.4 Connect
   - 6.5 Settings
   - 6.6 Server edit
   - 6.7 Usage dashboard
   - 6.8 Command palette
   - 6.9 Expanded input
7. [Data model mapping](#7--data-model-mapping)
8. [ViewModel contracts](#8--viewmodel-contracts)
9. [Animations](#9--animations)
10. [Navigation wiring](#10--navigation-wiring)
11. [Implementation order](#11--implementation-order)
12. [Out of scope](#12--out-of-scope)

---

## 1 · Overview & scope

Redesign mobilní (Android) + desktopové aplikace pro vzdálené ovládání Claude Code CLI přes
SSH/Mosh + tmux. Bez vlastního backendu, vše přes terminál.

Pokrývá kompletní UI surface:

| # | Screen | File |
|---|---|---|
| 1 | Launcher (active sessions + servers) | `LauncherScreen.kt` |
| 2 | Session drawer (vertikální, slide-in) | nový — `SessionDrawer.kt` |
| 3 | Terminal screen (xterm/transcript + crumb bar + special keys + control bar) | `TerminalScreen.kt` |
| 4 | Connect flow (folder + mode + model + tmux + launch) | `ConnectScreen.kt` |
| 5 | Settings | `SettingsScreen.kt` |
| 6 | Server edit dialog | `ServerEditDialog.kt` |
| 7 | Usage dashboard | `UsageDashboardScreen.kt` |
| 8 | Command palette (overlay) | nový — `CommandPalette.kt` |
| 9 | Expanded input (bottom sheet) | nový — `ExpandedInput.kt` |

**Plus theme infrastructure** umožňující uživateli přepínat:
- **Variant** — `classic` / `glass`
- **Density** — `compact` / `regular` / `dense`
- **Accent** — `sky` / `mint` / `amber` / `violet` / `rose`
- **Terminal view** — `raw` (xterm WebView) / `transcript` (Compose-native)
- **Status viz** — `dot` / `pill` / `bar`
- **Terminal color scheme** — `default` / `solarized-dark` / `dracula` / `monokai` / `linux`

**Hi-fi.** Pixel-perfect mockupy s finálními barvami, typografií, spacingem a interakcemi.
Datové modely (`SshServer`, `ClaudeSession`, `ClaudeMode`, `SessionActivity`, …) **už v repu existují**
a designy z nich přímo čerpají — viz [§7 Data Model Mapping](#7--data-model-mapping).

---

## 2 · How to read the prototype

Otevři `prototype/Claude Remote.html` v prohlížeči. V pravém dolním rohu je **Tweaks panel**
(může být skrytý — klikni na ikonu posuvníků pokud nesvítí). V něm přepínáš všechny
varianty/density/accent/views a v reálném čase vidíš jejich efekt napříč screens.

Top strip nahoře přepíná mezi obrazovkami; levý toggle Android ↔ Desktop frame.

Klíčové soubory v `prototype/`:

```
prototype/
├── Claude Remote.html          ← entry; orchestruje App + Tweaks + Android frame
├── styles/
│   ├── tokens.css              ← Color + type tokens (Actions DS)
│   ├── app.css                 ← Všechny redesign-specific styly (Classic i Glass)
│   └── glass.css               ← Glass variant primitive overrides (reference)
├── data.jsx                    ← Mock data (SERVERS, SESSIONS, TMUX_SESSIONS, USAGE, …)
├── components.jsx              ← Primitive: StatusDot, StatusPill, ActivityHeatmap,
│                                  Sparkline, Icon, ServerGlyph, Pill, Segmented
├── screens-main.jsx            ← LauncherScreen, ConnectScreen, ServerEditScreen
├── screens-terminal.jsx        ← TerminalScreen, SessionDrawer, RawTerminal,
│                                  TranscriptView
├── screens-aux.jsx             ← SettingsScreen, UsageScreen, CommandPalette,
│                                  ExpandedInput
└── frames/
    ├── android-frame.jsx       ← Android device chrome
    └── tweaks-panel.jsx        ← Floating Tweaks panel
```

---

## 3 · Theme infrastructure: `CRTheme`

### 3.1 Variant + Density + Accent enums

```kotlin
// shared/src/commonMain/kotlin/com/clauderemote/ui/theme/Appearance.kt
package com.clauderemote.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class CRVariant { Classic, Glass }

enum class CRDensity { Compact, Regular, Dense }

enum class CRAccent(val color: Color, val label: String) {
    Sky(    Color(0xFF38BDF8), "Sky"),
    Mint(   Color(0xFF4ADE80), "Mint"),
    Amber(  Color(0xFFFBBF24), "Amber"),
    Violet( Color(0xFFA78BFA), "Violet"),
    Rose(   Color(0xFFF472B6), "Rose"),
}

enum class CRStatusViz { Dot, Pill, Bar }

enum class CRTerminalView { Raw, Transcript }

enum class CRTerminalScheme(val id: String, val label: String) {
    Default(       "default",         "Default"),
    SolarizedDark( "solarized-dark",  "Solarized Dark"),
    Dracula(       "dracula",         "Dracula"),
    Monokai(       "monokai",         "Monokai"),
    Linux(         "linux",           "Linux"),
}
```

### 3.2 Color tokens

```kotlin
// shared/src/commonMain/kotlin/com/clauderemote/ui/theme/CRColors.kt
package com.clauderemote.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class CRColorScheme(
    val bg:        Color,
    val surface:   Color,
    val surface2:  Color,
    val border:    Color,
    val text:      Color,
    val textDim:   Color,

    // Accent (live-overridden per CRAccent)
    val accent:    Color,
    val accentInk: Color,

    // Tints (15–18% alpha of signal colors)
    val tintAccent: Color,
    val tintGreen:  Color,
    val tintRed:    Color,
    val tintYellow: Color,
    val tintPurple: Color,
    val tintOrange: Color,

    // Status semantics
    val working:      Color,
    val ready:        Color,
    val approval:     Color,
    val idle:         Color,
    val disconnected: Color,

    // Mode pill colors (per ClaudeMode)
    val modeYolo:    Color,
    val modePlan:    Color,
    val modeAuto:    Color,
    val modeNormal:  Color,
)

object CRColorTokens {
    fun forVariant(variant: CRVariant, accent: CRAccent): CRColorScheme = when (variant) {
        CRVariant.Classic -> classic(accent)
        CRVariant.Glass   -> glass(accent)
    }

    private fun classic(a: CRAccent) = CRColorScheme(
        bg        = Color(0xFF0F172A),  // slate-900
        surface   = Color(0xFF1E293B),  // slate-800
        surface2  = Color(0xFF283548),
        border    = Color(0xFF334155),  // slate-700
        text      = Color(0xFFE2E8F0),  // slate-200
        textDim   = Color(0xFF94A3B8),  // slate-400

        accent    = a.color,
        accentInk = Color(0xFF0F172A),

        tintAccent = a.color.copy(alpha = 0.15f),
        tintGreen  = Color(0xFF4ADE80).copy(alpha = 0.15f),
        tintRed    = Color(0xFFF87171).copy(alpha = 0.15f),
        tintYellow = Color(0xFFFBBF24).copy(alpha = 0.15f),
        tintPurple = Color(0xFFA78BFA).copy(alpha = 0.15f),
        tintOrange = Color(0xFFFB923C).copy(alpha = 0.18f),

        working      = Color(0xFFFBBF24),
        ready        = Color(0xFF4ADE80),
        approval     = Color(0xFFFB923C),
        idle         = Color(0xFF94A3B8),
        disconnected = Color(0xFFF87171),

        modeYolo   = Color(0xFFF87171),
        modePlan   = Color(0xFFA78BFA),
        modeAuto   = Color(0xFF4ADE80),
        modeNormal = Color(0xFF94A3B8),
    )

    private fun glass(a: CRAccent) = classic(a).copy(
        // Glass differs primarily in surface treatment, not raw color tokens.
        // The variant flag is read inside primitives to apply blur + alpha
        // adjustments at draw time. Token-wise the only delta is the page bg:
        bg = Color(0xFF06070B),
    )
}
```

### 3.3 Density tokens

```kotlin
// shared/src/commonMain/kotlin/com/clauderemote/ui/theme/CRMetrics.kt
package com.clauderemote.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class CRMetrics(
    val cardPad:        Dp,   // inner padding of CRCard
    val cardPadV:       Dp,   // vertical override for one-line dense cards
    val cardPadH:       Dp,
    val cardGap:        Dp,   // vertical gap between cards in a stack
    val cardRadius:     Dp,
    val sectionPad:     Dp,
    val sectionTopGap:  Dp,
    val rowHeight:      Dp,   // list row min-height
    val inputPad:       Dp,
    val showPreviewLine: Boolean,  // launcher session card
    val showHeatmap:     Boolean,  // launcher session card
    val sessionCardOneLine: Boolean,
) {
    companion object {
        fun forDensity(d: CRDensity): CRMetrics = when (d) {
            CRDensity.Compact -> CRMetrics(
                cardPad = 12.dp, cardPadV = 10.dp, cardPadH = 12.dp,
                cardGap = 8.dp,  cardRadius = 10.dp,
                sectionPad = 12.dp, sectionTopGap = 14.dp,
                rowHeight = 48.dp, inputPad = 8.dp,
                showPreviewLine = true, showHeatmap = true,
                sessionCardOneLine = false,
            )
            CRDensity.Regular -> CRMetrics(
                cardPad = 16.dp, cardPadV = 14.dp, cardPadH = 16.dp,
                cardGap = 12.dp, cardRadius = 12.dp,
                sectionPad = 16.dp, sectionTopGap = 16.dp,
                rowHeight = 56.dp, inputPad = 10.dp,
                showPreviewLine = true, showHeatmap = true,
                sessionCardOneLine = false,
            )
            CRDensity.Dense -> CRMetrics(
                cardPad = 8.dp,  cardPadV = 6.dp,  cardPadH = 10.dp,
                cardGap = 4.dp,  cardRadius = 8.dp,
                sectionPad = 10.dp, sectionTopGap = 8.dp,
                rowHeight = 38.dp, inputPad = 6.dp,
                showPreviewLine = false, showHeatmap = false,
                sessionCardOneLine = true,
            )
        }
    }
}
```

### 3.4 Type tokens

```kotlin
// shared/src/commonMain/kotlin/com/clauderemote/ui/theme/CRType.kt
package com.clauderemote.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Fonts: Inter Tight (sans), JetBrains Mono (mono). Bundle via expect/actual or
// use platform fallbacks: Roboto on Android, SF on Desktop.
expect val CRFontSans: FontFamily
expect val CRFontMono: FontFamily

object CRType {
    val micro     = 11.sp
    val xs        = 12.sp
    val sm        = 13.sp
    val base      = 14.sp
    val lg        = 15.sp
    val xl        = 17.sp
    val h2        = 20.sp
    val h1        = 24.sp

    val titleBold = TextStyle(fontFamily = CRFontSans, fontSize = h1,   fontWeight = FontWeight.W800, letterSpacing = (-0.5).sp)
    val cardTitle = TextStyle(fontFamily = CRFontSans, fontSize = base, fontWeight = FontWeight.W600)
    val sectionH  = TextStyle(fontFamily = CRFontSans, fontSize = xs,   fontWeight = FontWeight.W600, letterSpacing = 0.6.sp)
    val bodyDim   = TextStyle(fontFamily = CRFontSans, fontSize = xs,   fontWeight = FontWeight.Normal)
    val mono      = TextStyle(fontFamily = CRFontMono, fontSize = xs)
    val monoTiny  = TextStyle(fontFamily = CRFontMono, fontSize = micro)
    val pill      = TextStyle(fontFamily = CRFontSans, fontSize = micro, fontWeight = FontWeight.W600, letterSpacing = 0.5.sp)
    val keyboardKey = TextStyle(fontFamily = CRFontMono, fontSize = xs, fontWeight = FontWeight.W600)
}
```

### 3.5 `CRTheme` provider

```kotlin
// shared/src/commonMain/kotlin/com/clauderemote/ui/theme/CRTheme.kt
package com.clauderemote.ui.theme

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

val LocalCRVariant   = compositionLocalOf { CRVariant.Classic }
val LocalCRDensity   = compositionLocalOf { CRDensity.Regular }
val LocalCRAccent    = compositionLocalOf { CRAccent.Sky }
val LocalCRColors    = compositionLocalOf<CRColorScheme> { error("No CRColors") }
val LocalCRMetrics   = compositionLocalOf<CRMetrics> { error("No CRMetrics") }
val LocalCRStatusViz = compositionLocalOf { CRStatusViz.Pill }

object CRTheme {
    val colors:   CRColorScheme @Composable @ReadOnlyComposable get() = LocalCRColors.current
    val metrics:  CRMetrics     @Composable @ReadOnlyComposable get() = LocalCRMetrics.current
    val variant:  CRVariant     @Composable @ReadOnlyComposable get() = LocalCRVariant.current
    val density:  CRDensity     @Composable @ReadOnlyComposable get() = LocalCRDensity.current
    val accent:   CRAccent      @Composable @ReadOnlyComposable get() = LocalCRAccent.current
    val statusViz: CRStatusViz  @Composable @ReadOnlyComposable get() = LocalCRStatusViz.current
}

@Composable
fun CRTheme(
    appearance: AppearanceState,                  // from DataStore-backed VM
    content: @Composable () -> Unit,
) {
    val colors  = remember(appearance.variant, appearance.accent) {
        CRColorTokens.forVariant(appearance.variant, appearance.accent)
    }
    val metrics = remember(appearance.density) {
        CRMetrics.forDensity(appearance.density)
    }
    CompositionLocalProvider(
        LocalCRVariant   provides appearance.variant,
        LocalCRDensity   provides appearance.density,
        LocalCRAccent    provides appearance.accent,
        LocalCRColors    provides colors,
        LocalCRMetrics   provides metrics,
        LocalCRStatusViz provides appearance.statusViz,
    ) {
        // Wrap in M3 MaterialTheme so M3 components inherit accent + colors.
        androidx.compose.material3.MaterialTheme(
            colorScheme = androidx.compose.material3.darkColorScheme(
                primary       = colors.accent,
                onPrimary     = colors.accentInk,
                background    = colors.bg,
                surface       = colors.surface,
                onSurface     = colors.text,
                onSurfaceVariant = colors.textDim,
                outline       = colors.border,
            ),
            typography = androidx.compose.material3.Typography(),
        ) {
            content()
        }
    }
}
```

### 3.6 Persisted appearance state

```kotlin
// shared/src/commonMain/kotlin/com/clauderemote/ui/theme/AppearanceState.kt
package com.clauderemote.ui.theme

import kotlinx.serialization.Serializable

@Serializable
data class AppearanceState(
    val variant:        CRVariant        = CRVariant.Classic,
    val density:        CRDensity        = CRDensity.Regular,
    val accent:         CRAccent         = CRAccent.Sky,
    val statusViz:      CRStatusViz      = CRStatusViz.Pill,
    val terminalView:   CRTerminalView   = CRTerminalView.Transcript,
    val terminalScheme: CRTerminalScheme = CRTerminalScheme.Default,
)
```

Persist přes `AppSettings.kt` (existující v repu) — přidej pole + serializer.

```kotlin
// shared/src/commonMain/kotlin/com/clauderemote/storage/AppSettings.kt — diff
@Serializable
data class AppSettings(
    /* … existing fields … */
    val appearance: AppearanceState = AppearanceState(),
)
```

A read/write přes `PlatformPreferences` (už existuje).

```kotlin
class AppearanceViewModel(
    private val settings: AppSettings,            // injected
    private val prefs: PlatformPreferences,
) {
    val state: MutableStateFlow<AppearanceState> = MutableStateFlow(settings.appearance)

    fun setVariant(v: CRVariant)         { update { it.copy(variant = v) } }
    fun setDensity(d: CRDensity)         { update { it.copy(density = d) } }
    fun setAccent(a: CRAccent)           { update { it.copy(accent = a) } }
    fun setStatusViz(s: CRStatusViz)     { update { it.copy(statusViz = s) } }
    fun setTerminalView(v: CRTerminalView)   { update { it.copy(terminalView = v) } }
    fun setTerminalScheme(s: CRTerminalScheme) { update { it.copy(terminalScheme = s) } }

    private fun update(f: (AppearanceState) -> AppearanceState) {
        state.value = f(state.value).also { /* persist via prefs */ }
    }
}
```

### 3.7 Hooking it up at app root

```kotlin
// shared/src/commonMain/kotlin/com/clauderemote/ui/App.kt — top level
@Composable
fun App() {
    val appearanceVm: AppearanceViewModel = remember { /* DI */ }
    val appearance by appearanceVm.state.collectAsState()

    CRTheme(appearance) {
        AppRoot(appearanceVm = appearanceVm)
    }
}
```

---

## 4 · Appearance Settings screen (user-facing toggle)

Dej uživateli **plnou kontrolu** nad theme/density/accent přes Settings → Appearance section.
Tohle nahrazuje "Tweaks panel" z prototypu pro production.

### 4.1 UI

```
┌──────────────────────────────────────────┐
│  ← Settings                              │
├──────────────────────────────────────────┤
│  APPEARANCE                              │
│                                          │
│  Style                                   │
│  ┌─────────────────────────────────────┐ │
│  │ [Classic] [ Glass ]                 │ │  ← Segmented, 2 options
│  └─────────────────────────────────────┘ │
│  Glass: aurora background, frosted panels│  ← caption per selection
│                                          │
│  Density                                 │
│  ┌─────────────────────────────────────┐ │
│  │ [Compact] [Regular] [ Dense ]       │ │  ← Segmented, 3 options
│  └─────────────────────────────────────┘ │
│  Dense: jednořádkové karty, 8+ visible   │
│                                          │
│  Accent                                  │
│  ●  ●  ●  ●  ●                           │  ← 5 swatches, selected has ring
│  Sky  Mint Amber Violet Rose             │
│                                          │
│  Status visualization                    │
│  ┌─────────────────────────────────────┐ │
│  │ [ Dot ] [ Pill ] [ Bar ]            │ │
│  └─────────────────────────────────────┘ │
│                                          │
│  ─── Preview ────────────────────────── │
│  ┌─────────────────────────────────────┐ │
│  │ ● redesign            [YOLO]  12m   │ │  ← live mini-card
│  │ ~/claude-remote                     │ │
│  └─────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

### 4.2 Implementation

```kotlin
// shared/src/commonMain/kotlin/com/clauderemote/ui/settings/AppearanceSection.kt
@Composable
fun AppearanceSection(vm: AppearanceViewModel) {
    val state by vm.state.collectAsState()
    val c = CRTheme.colors

    CRSection(title = "Appearance") {
        // ── Style ────────────────────────────────
        CRField(label = "Style") {
            CRSegmented(
                value   = state.variant,
                options = listOf(CRVariant.Classic to "Classic", CRVariant.Glass to "Glass"),
                onChange = vm::setVariant,
            )
            CRCaption(text = when (state.variant) {
                CRVariant.Classic -> "Solid surfaces, Material 3 baseline."
                CRVariant.Glass   -> "Aurora background, frosted panels, pill chrome."
            })
        }

        // ── Density ──────────────────────────────
        CRField(label = "Density") {
            CRSegmented(
                value   = state.density,
                options = listOf(
                    CRDensity.Compact to "Compact",
                    CRDensity.Regular to "Regular",
                    CRDensity.Dense   to "Dense",
                ),
                onChange = vm::setDensity,
            )
            CRCaption(text = when (state.density) {
                CRDensity.Compact -> "Tighter padding, gap 8dp."
                CRDensity.Regular -> "Default — gap 12dp, full preview lines."
                CRDensity.Dense   -> "Single-line cards. Heatmap and preview hidden."
            })
        }

        // ── Accent ────────────────────────────────
        CRField(label = "Accent") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CRAccent.values().forEach { a ->
                    AccentSwatch(
                        color    = a.color,
                        selected = a == state.accent,
                        label    = a.label,
                        onClick  = { vm.setAccent(a) },
                    )
                }
            }
        }

        // ── Status visualization ───────────────────
        CRField(label = "Status visualization") {
            CRSegmented(
                value   = state.statusViz,
                options = listOf(
                    CRStatusViz.Dot  to "Dot",
                    CRStatusViz.Pill to "Pill",
                    CRStatusViz.Bar  to "Bar",
                ),
                onChange = vm::setStatusViz,
            )
        }

        // ── Live preview ──────────────────────────
        Spacer(Modifier.height(16.dp))
        CRCaption(text = "Preview")
        SessionCardPreview()   // re-uses Launcher's SessionCard with mock data
    }
}

@Composable
private fun AccentSwatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(color)
                .then(if (selected)
                    Modifier.border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                        .padding(3.dp)
                        .border(2.dp, color, CircleShape)
                  else Modifier)
                .clickable(onClick = onClick)
        )
        Text(label, style = CRType.bodyDim, color = CRTheme.colors.textDim)
    }
}
```

`CRSegmented`, `CRField`, `CRCaption`, `CRSection` — viz [§5 Primitives](#5--component-primitives).

### 4.3 Where it lives

Settings screen má více sekcí (Terminal, Claude defaults, Connection, Security, Updates, About).
**Appearance** je nahoře (před Terminal section), protože je nejviditelnější UI volba.

Pro power users zachovej **floating dev-only Tweaks panel** (z prototypu) za feature flag —
useful při QA pro rychlé přepínání bez chození do Settings. Není to powered feature.

---

## 5 · Component primitives

Všechny primitives v `shared/src/commonMain/kotlin/com/clauderemote/ui/components/`.

### 5.1 `CRCard`

Surface s variant-aware treatmentem.

```kotlin
@Composable
fun CRCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val variant = CRTheme.variant
    val c = CRTheme.colors
    val m = CRTheme.metrics

    val shape = RoundedCornerShape(m.cardRadius)
    val base = Modifier
        .fillMaxWidth()
        .clip(shape)
        .then(when (variant) {
            CRVariant.Classic -> Modifier
                .background(c.surface)
                .border(1.dp, if (selected) c.accent else c.border, shape)
            CRVariant.Glass -> Modifier
                .glassSurface(alpha = 0.06f, blur = 28.dp)
                .border(1.dp,
                    if (selected) Color.White.copy(alpha = 0.30f)
                    else Color.White.copy(alpha = 0.14f),
                    shape)
                .specularTopHighlight()
        })
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = m.cardPadH, vertical = m.cardPadV)

    Column(modifier = base.then(modifier), content = content)
}
```

`glassSurface` + `specularTopHighlight` — viz `prototype/styles/glass.css` jako reference.
V Compose:
- `glassSurface` = `Modifier.drawBehind { drawRect(Color.White.copy(alpha = 0.06f)) }`
  + `Modifier.graphicsLayer { renderEffect = BlurEffect(28f, 28f) }` (Android 12+ only,
  fallback: tmavší alpha)
- `specularTopHighlight` = `drawWithContent { drawContent(); drawRect(
  brush = Brush.verticalGradient(0f to Color.White.copy(alpha=0.22f),
    0.55f to Color.Transparent), size = Size(size.width, size.height * 0.55f)) }`

### 5.2 `CRButton` (filled / outline / ghost / danger)

```kotlin
enum class CRButtonKind { Filled, Outline, Ghost, Danger, Success, Warning }

@Composable
fun CRButton(
    text: String,
    onClick: () -> Unit,
    kind: CRButtonKind = CRButtonKind.Filled,
    icon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val c = CRTheme.colors
    val variant = CRTheme.variant
    val pillShape = when (variant) {
        CRVariant.Glass   -> RoundedCornerShape(50)
        CRVariant.Classic -> RoundedCornerShape(8.dp)
    }
    val colors = when (kind) {
        CRButtonKind.Filled  -> c.accent  to c.accentInk
        CRButtonKind.Outline -> Color.Transparent to c.accent
        CRButtonKind.Ghost   -> Color.Transparent to c.text
        CRButtonKind.Danger  -> Color.Transparent to c.disconnected
        CRButtonKind.Success -> c.ready    to Color(0xFF062014)
        CRButtonKind.Warning -> c.approval to Color(0xFF2B1A05)
    }
    val border = when (kind) {
        CRButtonKind.Outline -> BorderStroke(1.dp, c.accent)
        CRButtonKind.Danger  -> BorderStroke(1.dp, c.disconnected)
        else                 -> null
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = pillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.first,
            contentColor   = colors.second,
        ),
        border = border,
        modifier = modifier.heightIn(min = 36.dp),
    ) {
        if (icon != null) { icon(); Spacer(Modifier.width(6.dp)) }
        Text(text, style = CRType.cardTitle)
    }
}
```

### 5.3 `CRSegmented`

```kotlin
@Composable
fun <T> CRSegmented(
    value: T,
    options: List<Pair<T, String>>,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CRTheme.colors
    val variant = CRTheme.variant
    val containerShape = when (variant) {
        CRVariant.Glass   -> RoundedCornerShape(50)
        CRVariant.Classic -> RoundedCornerShape(8.dp)
    }
    Row(
        modifier = modifier
            .clip(containerShape)
            .background(when (variant) {
                CRVariant.Glass   -> Color.Black.copy(alpha = 0.22f)
                CRVariant.Classic -> c.surface2
            })
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (key, label) ->
            val selected = key == value
            Box(
                Modifier
                    .weight(1f)
                    .clip(containerShape)
                    .background(if (selected) c.accent else Color.Transparent)
                    .clickable { onChange(key) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label,
                    style = CRType.cardTitle.copy(fontSize = CRType.xs),
                    color = if (selected) c.accentInk else c.textDim)
            }
        }
    }
}
```

### 5.4 Status indicators

#### `StatusDot`

```kotlin
@Composable
fun StatusDot(activity: SessionActivity, modifier: Modifier = Modifier) {
    val c = CRTheme.colors
    val color = activity.toColor(c)
    val infiniteTransition = rememberInfiniteTransition()
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = EaseOut)),
    )
    Box(modifier.size(8.dp)) {
        when (activity) {
            SessionActivity.WORKING -> {
                Box(Modifier
                    .matchParentSize()
                    .scale(1f + pulse * 0.6f)
                    .alpha(1f - pulse)
                    .background(color, CircleShape))
            }
            SessionActivity.APPROVAL_NEEDED -> {
                val blink by infiniteTransition.animateFloat(
                    0.4f, 1f,
                    infiniteRepeatable(tween(800), repeatMode = RepeatMode.Reverse),
                )
                Box(Modifier.matchParentSize().alpha(blink).background(color, CircleShape))
            }
            SessionActivity.WAITING_FOR_INPUT -> {
                // green with static glow
                Box(Modifier
                    .matchParentSize()
                    .background(color, CircleShape)
                    .drawBehind {
                        drawCircle(color.copy(alpha = 0.5f), radius = size.minDimension * 0.9f)
                    })
            }
            else -> Box(Modifier.matchParentSize().background(color, CircleShape))
        }
        // Center dot always
        Box(Modifier.matchParentSize().background(color, CircleShape))
    }
}

private fun SessionActivity.toColor(c: CRColorScheme) = when (this) {
    SessionActivity.WORKING            -> c.working
    SessionActivity.WAITING_FOR_INPUT  -> c.ready
    SessionActivity.APPROVAL_NEEDED    -> c.approval
    SessionActivity.IDLE               -> c.idle
    SessionActivity.DISCONNECTED       -> c.disconnected
}
```

#### `StatusBar`

```kotlin
@Composable
fun StatusBar(activity: SessionActivity, modifier: Modifier = Modifier) {
    val color = activity.toColor(CRTheme.colors)
    Box(modifier
        .size(width = 24.dp, height = 3.dp)
        .clip(RoundedCornerShape(2.dp))
        .background(color))
}
```

#### `StatusPill`

```kotlin
@Composable
fun StatusPill(activity: SessionActivity) {
    val c = CRTheme.colors
    val (bg, fg, label) = when (activity) {
        SessionActivity.WORKING           -> Triple(c.tintYellow,  c.working,      "Working")
        SessionActivity.WAITING_FOR_INPUT -> Triple(c.tintGreen,   c.ready,        "Ready")
        SessionActivity.APPROVAL_NEEDED   -> Triple(c.tintOrange,  c.approval,     "Approval")
        SessionActivity.IDLE              -> Triple(c.surface2,    c.idle,         "Idle")
        SessionActivity.DISCONNECTED      -> Triple(c.tintRed,     c.disconnected, "Offline")
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StatusDot(activity, Modifier.size(6.dp))
        Text(label.uppercase(), style = CRType.pill, color = fg)
    }
}
```

#### `StatusIndicator` (variant-router)

```kotlin
@Composable
fun StatusIndicator(activity: SessionActivity, modifier: Modifier = Modifier) {
    when (CRTheme.statusViz) {
        CRStatusViz.Dot  -> StatusDot(activity, modifier)
        CRStatusViz.Pill -> StatusPill(activity)
        CRStatusViz.Bar  -> StatusBar(activity, modifier)
    }
}
```

### 5.5 `ServerGlyph`

```kotlin
@Composable
fun ServerGlyph(name: String, size: Dp = 36.dp) {
    val initials = name.split("-", "_").take(2).map { it.first() }
        .joinToString("").uppercase()
    val hue = (name[0].code * 7 + name.last().code) % 360
    val primary   = Color.hsl(hue.toFloat(), 0.6f, 0.55f)
    val secondary = Color.hsl(((hue + 40) % 360).toFloat(), 0.6f, 0.45f)
    val radius = size * 0.32f
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(Brush.linearGradient(listOf(primary, secondary)))
            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(radius)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials,
            fontFamily = CRFontMono,
            fontSize = (size.value * 0.36).sp,
            fontWeight = FontWeight.W700,
            color = Color.Black.copy(alpha = 0.7f))
    }
}
```

### 5.6 `ModePill`, `Pill` (generic)

```kotlin
@Composable
fun ModePill(mode: ClaudeMode) {
    val c = CRTheme.colors
    val (bg, fg, label) = when (mode) {
        ClaudeMode.YOLO        -> Triple(c.tintRed,    c.modeYolo,   "YOLO")
        ClaudeMode.PLAN        -> Triple(c.tintPurple, c.modePlan,   "Plan")
        ClaudeMode.AUTO_ACCEPT -> Triple(c.tintGreen,  c.modeAuto,   "Auto")
        ClaudeMode.NORMAL      -> Triple(c.surface2,   c.modeNormal, "Normal")
    }
    Box(Modifier
        .clip(RoundedCornerShape(50))
        .background(bg)
        .padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(label.uppercase(), style = CRType.pill, color = fg)
    }
}
```

### 5.7 `ActivityHeatmap`

```kotlin
@Composable
fun ActivityHeatmap(history: List<SessionActivity>, max: Int = 16) {
    val c = CRTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        history.takeLast(max).forEach { a ->
            val color = when (a) {
                SessionActivity.WORKING          -> c.working.copy(alpha = 0.8f)
                SessionActivity.APPROVAL_NEEDED  -> c.approval
                SessionActivity.WAITING_FOR_INPUT -> c.ready.copy(alpha = 0.9f)
                SessionActivity.IDLE             -> Color.White.copy(alpha = 0.08f)
                SessionActivity.DISCONNECTED     -> c.disconnected.copy(alpha = 0.9f)
            }
            Box(Modifier
                .width(4.dp).height(14.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(color))
        }
    }
}
```

### 5.8 `CRTopBar`

```kotlin
@Composable
fun CRTopBar(
    title: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    onLeadingClick: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    customTitle: (@Composable () -> Unit)? = null,
) {
    val variant = CRTheme.variant
    val c = CRTheme.colors
    val modifier = when (variant) {
        CRVariant.Classic -> Modifier.fillMaxWidth()
            .background(c.surface)
            .border(0.dp, Color.Transparent)   // single border bottom drawn below
        CRVariant.Glass -> Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(50))
            .glassSurface(alpha = 0.06f, blur = 24.dp)
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
            .specularTopHighlight()
    }
    Row(modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (leadingIcon != null) {
            IconButton(onClick = onLeadingClick ?: {}) { leadingIcon() }
            Spacer(Modifier.width(8.dp))
        }
        if (customTitle != null) customTitle()
        else if (title != null) Text(title, style = CRType.cardTitle.copy(fontSize = 17.sp))
        Spacer(Modifier.weight(1f))
        if (actions != null) actions()
    }
    if (variant == CRVariant.Classic) {
        Divider(color = c.border, thickness = 1.dp)
    }
}
```

---

## 6 · Screens

> Each screen below lists: **Purpose**, **Layout structure**, **Density behavior**, **States**,
> **Compose entry point**. Reference the prototype JSX for exact pixel choices.

### 6.1 Launcher — `LauncherScreen.kt`

**Purpose:** Hlavní obrazovka — list aktivních sessions + list serverů.

#### Layout

```
┌──────────────────────────────────────┐
│ [☰] Claude Remote     [usage] [⚙]    │  ← Topbar
├──────────────────────────────────────┤
│ ACTIVE SESSIONS · 13       Usage ›   │  ← Section header
│ ┌────────────────────────────────┐   │
│ │ Session card 1                 │   │
│ │ Session card 2                 │   │
│ │ …                              │   │
│ └────────────────────────────────┘   │
│                                      │
│ SERVERS · 4                          │
│ ┌────────────────────────────────┐   │
│ │ Server card 1                  │   │
│ │ …                              │   │
│ └────────────────────────────────┘   │
│                              [+ FAB] │
└──────────────────────────────────────┘
```

#### Session card — `regular` density

```
┌─────────────────────────────────────────┐
│ [glyph 36] alias      [MODE]   [status] │
│            server:~/path        12m     │
│                                         │
│ › Reading file.kt at line 142…         │  ← preview-line (mono dim 11sp)
│                                         │
│ ▮▮▮▮▮▯▮▮▮▮▮▮▮▮▮▮      $0.42 · 28k     │  ← heatmap + cost
└─────────────────────────────────────────┘
```

#### Session card — `dense` density (one-line)

```
┌─────────────────────────────────────────┐
│ ● redesign      server   YOLO    12m    │
└─────────────────────────────────────────┘
```

Žádný glyph, žádný preview, žádný heatmap. 8–10 sessions nad foldem.

#### Compose skeleton

```kotlin
@Composable
fun LauncherScreen(
    vm: LauncherViewModel,
    onOpenSession: (sessionId: String) -> Unit,
    onConnectServer: (serverId: String) -> Unit,
    onEditServer: (serverId: String?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsage: () -> Unit,
) {
    val sessions by vm.sessions.collectAsState()
    val servers  by vm.servers.collectAsState()

    CRScreen {
        CRTopBar(
            leadingIcon = { Icon(Icons.Default.Menu, null) },
            onLeadingClick = { /* open drawer or do nothing on launcher */ },
            customTitle = {
                Text("Claude Remote",
                    style = CRType.cardTitle.copy(
                        fontSize = 17.sp, color = CRTheme.colors.accent))
            },
            actions = {
                IconButton(onClick = onOpenUsage)    { Icon(Icons.Default.QueryStats, null) }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings,   null) }
            },
        )

        LazyColumn(
            contentPadding = PaddingValues(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(CRTheme.metrics.cardGap),
        ) {
            // ── Active sessions section ────────────
            item { CRSectionHeader("Active sessions · ${sessions.size}",
                trailing = { TextButton(onClick = onOpenUsage) { Text("Usage ›") } }) }
            items(sessions, key = { it.id }) { s ->
                SessionLauncherCard(
                    session = s,
                    onClick = { onOpenSession(s.id) },
                )
            }

            // ── Servers section ───────────────────
            item { CRSectionHeader("Servers · ${servers.size}") }
            items(servers, key = { it.id }) { srv ->
                ServerLauncherCard(
                    server = srv,
                    onClick = { onEditServer(srv.id) },
                    onConnect = { onConnectServer(srv.id) },
                )
            }
        }

        // ── FAB ──────────────────────────────────
        FloatingActionButton(
            onClick = { onEditServer(null) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = CRTheme.colors.accent,
            contentColor   = CRTheme.colors.accentInk,
        ) { Icon(Icons.Default.Add, null) }
    }
}
```

`SessionLauncherCard` musí respektovat `CRTheme.metrics.sessionCardOneLine`, `showPreviewLine`,
`showHeatmap` z `LocalCRMetrics`.

#### States

- **Empty active sessions** — místo listu show empty state: "No active sessions. Connect to a server to start."
- **Empty servers** — "No servers yet. Tap + to add your first server."
- **Loading** — neukazovat (sessions/servers se loadují instantly z `ServerStorage` / `SessionStorage`)

---

### 6.2 Session drawer — `SessionDrawer.kt` (NEW)

**Purpose:** Vertikální drawer s 10-20+ sessions, replacing horizontální tab bar.

#### Layout

```
┌────────────────────────────────────┐
│ [terminal icon] Sessions  13   [×] │  ← Header
├────────────────────────────────────┤
│ [🔍 Filter…                       ] │  ← Search
├────────────────────────────────────┤
│ [glyph] home-nas               5  │  ← Group label
│ ─────────────────────────────────  │
│ │● redesign  ~/claude-remote YOLO  12m │  ← Active item (left accent bar)
│  ● vscode-…  ~/vscode-andro PLAN  4m │
│  ● dotfiles  ~/dotfiles    NORM  2m │
│                                    │
│ [glyph] hetzner-eu             3  │
│ ─────────────────────────────────  │
│  ● scrape    ~/actions      AUTO 1h │
│  ● capstone  ~/genai…       AUTO 24m │
│  ● ...                              │
├────────────────────────────────────┤
│ [+ New session]              [↻]   │  ← Footer
└────────────────────────────────────┘
```

- Width 86% of screen, max 320dp
- Backdrop blur za drawerem (rgba(5,8,14,0.65) + blur 4dp)
- Slide-in z `-100%`, 250ms `EaseOutCubic`
- Active item: 3dp accent bar vlevo + `tintAccent` background
- Group label: 14dp glyph + server name + count vpravo

#### Compose skeleton

```kotlin
@Composable
fun SessionDrawer(
    open: Boolean,
    sessions: List<ClaudeSession>,
    activities: Map<String, SessionActivity>,
    activeId: String,
    onPick: (id: String) -> Unit,
    onNew: () -> Unit,
    onClose: () -> Unit,
) {
    if (!open) return
    val c = CRTheme.colors

    Box(Modifier.fillMaxSize()) {
        // Backdrop
        Box(Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .blur(4.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() },
                indication = null) { onClose() })

        // Drawer
        val width = (LocalConfiguration.current.screenWidthDp * 0.86f).coerceAtMost(320f).dp
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(tween(250)) { -it } + fadeIn(),
            exit  = slideOutHorizontally(tween(250)) { -it } + fadeOut(),
        ) {
            Column(Modifier
                .width(width)
                .fillMaxHeight()
                .background(c.surface)
                .border(0.dp, c.border)
            ) {
                DrawerHeader(count = sessions.size, onClose = onClose)
                DrawerSearch(onChange = { /* filter */ })
                Divider(color = c.border)
                LazyColumn(Modifier.weight(1f)) {
                    // Group sessions by serverId
                    sessions.groupBy { it.server.id }.forEach { (serverId, group) ->
                        item { DrawerGroupLabel(server = group.first().server, count = group.size) }
                        items(group, key = { it.id }) { session ->
                            DrawerItem(
                                session = session,
                                activity = activities[session.id] ?: SessionActivity.IDLE,
                                selected = session.id == activeId,
                                onClick = { onPick(session.id); onClose() },
                            )
                        }
                    }
                }
                DrawerFooter(onNew = { onNew(); onClose() }, onReattachAll = {})
            }
        }
    }
}
```

---

### 6.3 Terminal — `TerminalScreen.kt`

**Purpose:** Hlavní pracovní obrazovka. Live xterm + control bar + special keys + input.

#### Layout

```
┌──────────────────────────────────────┐
│ [☰] ● alias-or-folder    [view] [⋯] │  ← Topbar, 56dp
├──────────────────────────────────────┤
│ [Sessions] server:folder ← →  4/13   │  ← Crumb bar, 36dp
├──────────────────────────────────────┤
│                                      │
│        Terminal body                 │  ← flex:1
│        (xterm OR transcript)         │
│                                      │
├──────────────────────────────────────┤
│  ● WORKING  Reading file.kt…  $0.42  │  ← Status row, 24dp
│  [mode AUTO] [model OPUS]  /cmd      │  ← Mode/model chips, 36dp
│  [Esc][Tab][↑][↓][←][→][/][⌃C][⌃D][···] │  ← Special keys, 32dp
│  [_______________ message _____] [⏵] │  ← Input row, 48dp
└──────────────────────────────────────┘
```

#### Crumb bar

```kotlin
@Composable
fun CrumbBar(
    session: ClaudeSession,
    index: Int, total: Int,
    onOpenDrawer: () -> Unit,
    onPrev: () -> Unit, onNext: () -> Unit,
) {
    val c = CRTheme.colors
    Row(Modifier
        .fillMaxWidth()
        .background(c.bg)
        .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CRChip(text = "Sessions", icon = Icons.Default.Menu, onClick = onOpenDrawer)
        Text("${session.server.name}", style = CRType.mono, color = c.textDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onPrev, enabled = index > 0) {
            Icon(Icons.Default.ChevronLeft, null) }
        Text("${index + 1}/$total", style = CRType.monoTiny, color = c.textDim)
        IconButton(onClick = onNext, enabled = index < total - 1) {
            Icon(Icons.Default.ChevronRight, null) }
    }
}
```

#### Special keys row (**CRITICAL**)

10 kláves v `Row` + `weight(1f)` každá:

`[Esc] [Tab] [↑] [↓] [←] [→] [/] [⌃C] [⌃D] [···]`

```kotlin
@Composable
fun SpecialKeysRow(
    onKey: (SpecialKey) -> Unit,
    onMore: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SpecialKeyBtn("Esc", Modifier.weight(1f))  { onKey(SpecialKey.Esc) }
        SpecialKeyBtn("Tab", Modifier.weight(1f))  { onKey(SpecialKey.Tab) }
        SpecialKeyBtn("↑",   Modifier.weight(1f))  { onKey(SpecialKey.Up) }
        SpecialKeyBtn("↓",   Modifier.weight(1f))  { onKey(SpecialKey.Down) }
        SpecialKeyBtn("←",   Modifier.weight(1f))  { onKey(SpecialKey.Left) }
        SpecialKeyBtn("→",   Modifier.weight(1f))  { onKey(SpecialKey.Right) }
        SpecialKeyBtn("/",   Modifier.weight(1f))  { onKey(SpecialKey.Slash) }
        SpecialKeyBtn("⌃C",  Modifier.weight(1f))  { onKey(SpecialKey.CtrlC) }
        SpecialKeyBtn("⌃D",  Modifier.weight(1f))  { onKey(SpecialKey.CtrlD) }
        SpecialKeyBtn("···", Modifier.weight(1f))  { onMore() }
    }
}

enum class SpecialKey(val bytes: ByteArray) {
    Esc(   byteArrayOf(0x1B)),
    Tab(   byteArrayOf(0x09)),
    Up(    byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte())),
    Down(  byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte())),
    Right( byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte())),
    Left(  byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte())),
    Slash( byteArrayOf('/'.code.toByte())),
    CtrlC( byteArrayOf(0x03)),
    CtrlD( byteArrayOf(0x04)),
}
```

VM:
```kotlin
fun sendKey(key: SpecialKey) {
    sessionOrchestrator.sendRaw(activeSession.value.id, key.bytes)
}
```

**Tap feedback:** scale `0.94` + flash background `c.accent` po 100ms — viz `prototype/styles/app.css` `.kkey:active` styly.

#### Terminal body — two views

- **`raw`** — WebView s xterm.js (existing). Scheme reads `appearance.terminalScheme`.
  Don't reinvent; just style the WebView host container with `CRTheme.colors.bg`.
- **`transcript`** — Compose-native:

```kotlin
@Composable
fun TranscriptView(messages: List<TranscriptMessage>) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (m == CRMetrics.forDensity(CRDensity.Dense)) 3.dp else 6.dp),
    ) {
        items(messages) { msg ->
            when (msg.role) {
                Role.User    -> UserBubble(msg.text)
                Role.Claude  -> ClaudeMessage(msg.text)     // ● glyph + full-width
                Role.Tool    -> ToolMessage(msg.text)       // ⏺ glyph + mono
                Role.Meta    -> MetaMessage(msg.text)       // center, mono dim
            }
        }
    }
}
```

User bubble = right-aligned `tintAccent` bg, 10dp radius. Claude/Tool messages = transparent
background, just colored glyph indicator and text (no bubbles).

#### Mode/model chips popup

Tap na `[mode AUTO]` → `Popup` (anchored above) s 4 mode options + popis.
Tap na `[model OPUS]` → similar.

Popup zavírá modální (touch mimo + esc).

---

### 6.4 Connect — `ConnectScreen.kt`

**Purpose:** Po výběru serveru — nastavit folder, mode, model, connection type, tmux session, spustit.

#### Sections (vertical scroll)

1. **Folder** — `OutlinedTextField` + recent folder chips horizontal scroll + `[Browse]` btn
2. **Claude options** — 3× `CRSegmented`: Mode, Model, Connection. Plus alias `OutlinedTextField`.
3. **Tmux · N on server** — radio list:
   - "New session" radio + tmux name preview (mono): `claude-{server}-{folder}{--alias}`
   - Existing tmux sessions s "attached" pill
4. **Will-run preview** — surface2 card s monospace textem celého příkazu, **live updates**:
   ```
   $ tmux new -A -s 'claude-home-nas-claude-remote--redesign'
   $ cd ~/claude-remote && claude --model opus --auto-accept
   ```
5. **[▶ Launch Claude]** full-width primary button

```kotlin
@Composable
fun ConnectScreen(
    serverId: String,
    vm: ConnectViewModel,
    onBack: () -> Unit,
    onLaunched: (sessionId: String) -> Unit,
) {
    val state by vm.state.collectAsState()
    CRScreen {
        CRTopBar(
            leadingIcon = { Icon(Icons.Default.ArrowBack, null) },
            onLeadingClick = onBack,
            customTitle = {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ServerGlyph(state.server.name, size = 26.dp)
                    Text(state.server.name, style = CRType.cardTitle.copy(fontSize = 15.sp))
                }
            },
        )
        Column(Modifier.verticalScroll(rememberScrollState())) {
            FolderSection(...)
            ClaudeOptionsSection(...)
            TmuxSection(...)
            WillRunPreview(state)
            CRButton("▶ Launch Claude",
                onClick = { vm.launch(onLaunched) },
                modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
    }
}
```

---

### 6.5 Settings — `SettingsScreen.kt`

```kotlin
@Composable
fun SettingsScreen(
    settingsVm: SettingsViewModel,
    appearanceVm: AppearanceViewModel,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    CRScreen {
        CRTopBar(
            leadingIcon = { Icon(Icons.Default.ArrowBack, null) },
            onLeadingClick = onBack,
            customTitle = { Text("Settings", style = CRType.cardTitle.copy(fontSize = 17.sp)) },
            actions = {
                IconButton(onClick = onOpenLogs) { Icon(Icons.Default.Description, null) }
            },
        )
        Column(Modifier.verticalScroll(rememberScrollState())) {
            AppearanceSection(vm = appearanceVm)         // ← §4
            TerminalSection(vm = settingsVm)
            ClaudeDefaultsSection(vm = settingsVm)
            ConnectionSection(vm = settingsVm)
            SecuritySection(vm = settingsVm)
            UpdatesSection(vm = settingsVm)
            AboutSection()
        }
    }
}
```

Sections z prototypu (`screens-aux.jsx → SettingsScreen`):

| Section | Controls |
|---|---|
| Appearance (§4) | Variant, Density, Accent, StatusViz, Preview |
| Terminal | Font size slider (9–24sp), Scrollback slider (1k–50k), Color scheme picker s live `SchemePreview` |
| Claude defaults | Default mode (segmented 4), Default model (segmented 4) |
| Connection | Default connection (SSH/Mosh segmented), Auto-reconnect toggle, Keep-alive toggle, Connect timeout slider |
| Security | Biometric lock toggle |
| Updates | Version row + Check button + delta-update status |
| About | Repo link, Docs link, Logs link |

**`SchemePreview`** ukazuje 3 řádky mock terminal output v dané scheme — pomáhá výběr.

---

### 6.6 Server edit — `ServerEditDialog.kt`

Form pro nový/edit server.

#### Sections

1. **Identity** — display name + (host 2fr | port 1fr) inline + username
2. **Auth** — segmented Password / SSH key
   - Password: text field type=password
   - SSH key: textarea preview (mono, 100dp max-height, fade-bottom mask) + `[Paste]` `[Generate]` buttons
3. **Advanced**
   - "Prefer Mosh" toggle s subtitle
   - "Cloudflare proxy" toggle → reveal token text input
   - `<details>`-like collapsible "Port forwarding · N"
4. **Claude defaults for this server** — mirror Settings ale per-server (default mode, model, folder, startup command)
5. **[Delete server]** danger btn full-width (jen u edit)

`[Save]` button v topbaru (right action).

---

### 6.7 Usage dashboard — `UsageDashboardScreen.kt`

#### Sections

1. **2-column stats** — 7-day cost (s ↑ delta%) + total tokens (s session count caption)
2. **Daily spend bar chart** — 7d / 30d range toggle (segmented), accent bars (today highlighted), peak/avg captions
3. **By model** — list cards: color dot + name + tokens + cost + horizontal progress bar (cost / total7d)
4. **Top sessions · 7d** — list rows ranked `01 02 …` s folder, host, tokens, cost
5. **Disclaimer card** — "Cost estimates based on local token counting"

#### Bar chart — `Canvas`

```kotlin
@Composable
fun DailyCostBars(daily: List<Float>, max: Float, accent: Color) {
    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
        val barW = (size.width - 4.dp.toPx() * (daily.size - 1)) / daily.size
        daily.forEachIndexed { i, v ->
            val h = (v / max) * size.height
            val today = i == daily.lastIndex
            val brush = Brush.verticalGradient(
                if (today) listOf(accent, accent.copy(alpha = 0.3f))
                else        listOf(accent.copy(alpha = 0.4f), accent.copy(alpha = 0.1f)),
                startY = size.height - h, endY = size.height,
            )
            drawRoundRect(
                brush = brush,
                topLeft = Offset(i * (barW + 4.dp.toPx()), size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
}
```

---

### 6.8 Command palette — `CommandPalette.kt` (NEW)

Trigger: `Cmd/Ctrl+K` global or `[⋯]` v terminal topbaru.

Overlay s `Dialog`:
- Backdrop blur, palette card top-center, max-width 400dp, max-height 70vh
- Search input top + `[esc]` kbd hint
- Grouped item list:
  - **Slash commands**: `/init /clear /compact /model /mode /help`
  - **Shortcuts**: `Cmd+T new tab`, `Cmd+W close`, `Cmd+K palette`, `Esc interrupt`, `Ctrl+C`
  - **Templates**: pojmenované prompts (z `Templates` storage)
- Item row: glyph column + name + desc (dim, right) + kbd hint
- Realtime fuzzy filter

```kotlin
@Composable
fun CommandPalette(
    commands: List<Command>,
    onPick: (Command) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // ... search + grouped lazy column
    }
}
```

---

### 6.9 Expanded input — `ExpandedInput.kt` (NEW)

Trigger: tap (or focus) na input v terminal control bar.

`ModalBottomSheet` (M3) s:
- Drag handle top
- Title "Compose" + close button
- Multi-line `OutlinedTextField` full-width (min 120dp, scrollable, mono font pro `/`)
- Segmented `Templates` / `History`:
  - **Templates** tab — cards z `Templates` storage, tap fills textarea
  - **History** tab — prev messages, tap fills
- Footer row: `[📎 Attach]` `[⚡ /command]` … `[⏵ Send]`

---

## 7 · Data model mapping

Existing models v `shared/src/commonMain/kotlin/com/clauderemote/model/`:

| Design field | Existing model |
|---|---|
| Server card → name, host, port, user, auth | `SshServer.name / host / port / username / authMethod` |
| Server star | `SshServer.favorite` |
| Server "X live" pill | derived: `sessions.count { it.server.id == id && it.status != DISCONNECTED }` |
| Recent folder chips | `SshServer.recentFolders` |
| Session "alias" badge | `ClaudeSession.alias` |
| Session folder | `ClaudeSession.folder` |
| Mode pill (YOLO/PLAN/AUTO/NORM) | `ClaudeMode` (existuje v `Enums.kt`) |
| Status dot | `SessionActivity` (existuje v `Enums.kt`) |
| Tmux session selector | `TmuxSession.name / windows / attached / created` |
| `lastLine` na session card | `ScreenStateSnapshot.lastLine` |
| Activity heatmap | **NEW**: ring buffer last-N `SessionActivity` ticků z `SessionStatusPoller` |
| Session cost / tokens | **NEW**: derive from `CostCalculator`, persist v `ClaudeSession` |

### Status color mapping

```kotlin
fun SessionActivity.color(c: CRColorScheme): Color = when (this) {
    SessionActivity.WORKING            -> c.working
    SessionActivity.WAITING_FOR_INPUT  -> c.ready
    SessionActivity.APPROVAL_NEEDED    -> c.approval
    SessionActivity.IDLE               -> c.idle
    SessionActivity.DISCONNECTED       -> c.disconnected
}
```

### Tmux name convention (existuje v `TmuxNameParser.kt`)

```
claude-{server}-{folder}[-yolo][--{alias}]
```

V Connect screen "Will-run preview" tohle replikuj přesně.

---

## 8 · ViewModel contracts

```kotlin
// Launcher
class LauncherViewModel(
    val servers:    StateFlow<List<SshServer>>,                       // ServerStorage
    val sessions:   StateFlow<List<ClaudeSession>>,                    // SessionStorage + live
    val activities: StateFlow<Map<SessionId, SessionActivity>>,        // SessionStatusPoller
    val previews:   StateFlow<Map<SessionId, String>>,                 // ScreenStateSnapshot.lastLine
    val histories:  StateFlow<Map<SessionId, List<SessionActivity>>>,  // ring buffer per session
    val costs:      StateFlow<Map<SessionId, SessionCost>>,            // CostCalculator
)

// Terminal
class TerminalViewModel(
    val activeSession:     StateFlow<ClaudeSession>,
    val allSessions:       StateFlow<List<ClaudeSession>>,
    val transcript:        StateFlow<List<TranscriptMessage>>,    // TranscriptStream
    val activity:          StateFlow<SessionActivity>,
    val lastLine:          StateFlow<String>,
    val cost:              StateFlow<SessionCost>,
    val draftInput:        MutableStateFlow<String>,
    fun sendInput(text: String)
    fun sendKey(key: SpecialKey)                                  // raw bytes
    fun switchMode(mode: ClaudeMode)                              // /plan, /normal, restart pro YOLO
    fun switchModel(model: ClaudeModel)                           // /model X
    fun switchSession(id: String)
    fun closeSession(id: String)
)

// Connect
class ConnectViewModel(
    val server:     SshServer,
    val folder:     MutableStateFlow<String>,
    val mode:       MutableStateFlow<ClaudeMode>,
    val model:      MutableStateFlow<ClaudeModel>,
    val connType:   MutableStateFlow<ConnectionType>,
    val alias:      MutableStateFlow<String>,
    val tmuxChoice: MutableStateFlow<TmuxChoice>,                 // New | Attach(name)
    val availableTmux: StateFlow<List<TmuxSession>>,
    val previewCommand: StateFlow<String>,                        // derived
    fun launch(onLaunched: (sessionId: String) -> Unit)
)

// Server edit
class ServerEditViewModel(
    val initial: SshServer?,
    val draft: MutableStateFlow<SshServer>,
    fun save(onSaved: (SshServer) -> Unit)
    fun delete(onDeleted: () -> Unit)
)

// Usage
class UsageViewModel(
    val cost7d:     StateFlow<Double>,
    val cost30d:    StateFlow<Double>,
    val tokens7d:   StateFlow<Long>,
    val daily:      StateFlow<List<Double>>,                       // 14 dní
    val byModel:    StateFlow<List<ModelUsage>>,
    val topSessions: StateFlow<List<SessionUsage>>,
)

// Appearance (§3.6)
class AppearanceViewModel { ... }
```

---

## 9 · Animations

| Element | Animation | Duration / Easing | Compose spec |
|---|---|---|---|
| Drawer slide-in | translateX -100%→0 | 250ms `EaseOutCubic` | `slideInHorizontally(tween(250))` |
| Drawer backdrop | opacity 0→1 | 200ms linear | `fadeIn(tween(200))` |
| Status dot Working | radial pulse | 1.6s `EaseOut` infinite | `infiniteRepeatable(tween(1600))` |
| Status dot Approval | opacity 0.4→1 alternate | 800ms | `infiniteRepeatable(tween(800), Reverse)` |
| Status dot Ready | static green glow 8dp | — | `drawBehind` blurred circle |
| Tab/screen change | fadeIn + translateY(6dp) | 350ms `EaseOut` | `slideInVertically + fadeIn` |
| Button press | scale 0.98 | 150ms | InteractionSource + `scale()` |
| Special key press | scale 0.94 + accent flash | 100ms | InteractionSource + `Modifier.scale` |
| Card hover (desktop) | border-color → accent | 180ms | `animateColorAsState` |
| Cursor blink (terminal) | opacity step 2 | 1.1s infinite | `infiniteRepeatable(StepEasing)` |
| FAB | static | — | — |
| Aurora drift (Glass) | translate ±2%, scale 1.02–1.04 | 32s alternate | `rememberInfiniteTransition` |

---

## 10 · Navigation wiring

Existing `App.kt` (38k lines per `github_get_tree`) má route logic. Po redesignu:

```kotlin
@Composable
fun AppRoot(appearanceVm: AppearanceViewModel) {
    var screen by remember { mutableStateOf<Screen>(Screen.Launcher) }
    var sessionDrawerOpen by remember { mutableStateOf(false) }
    var commandPaletteOpen by remember { mutableStateOf(false) }
    var expandedInputOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        when (val s = screen) {
            Screen.Launcher -> LauncherScreen(
                vm = launcherVm,
                onOpenSession   = { screen = Screen.Terminal(it) },
                onConnectServer = { screen = Screen.Connect(it) },
                onEditServer    = { screen = Screen.ServerEdit(it) },
                onOpenSettings  = { screen = Screen.Settings },
                onOpenUsage     = { screen = Screen.Usage },
            )
            is Screen.Connect    -> ConnectScreen(...)
            is Screen.Terminal   -> TerminalScreen(
                sessionId = s.id,
                onOpenDrawer = { sessionDrawerOpen = true },
                onOpenPalette = { commandPaletteOpen = true },
                onOpenInput = { expandedInputOpen = true },
                ...
            )
            Screen.Settings      -> SettingsScreen(settingsVm, appearanceVm, ...)
            is Screen.ServerEdit -> ServerEditDialog(...)
            Screen.Usage         -> UsageDashboardScreen(usageVm, ...)
        }

        // Overlays
        SessionDrawer(open = sessionDrawerOpen, ...)
        if (commandPaletteOpen) CommandPalette(...)
        if (expandedInputOpen)  ExpandedInput(...)
    }
}

sealed class Screen {
    object Launcher : Screen()
    data class Connect(val serverId: String) : Screen()
    data class Terminal(val sessionId: String) : Screen()
    object Settings : Screen()
    data class ServerEdit(val id: String?) : Screen()
    object Usage : Screen()
}
```

Use whatever navigation library is already in place (`Decompose`, `Voyager`, `JetBrains Compose Navigation`, …). Současný `App.kt` ti řekne, který pattern používáš.

---

## 11 · Implementation order

Suggested sequence (each step shippable independently):

1. **Theme infrastructure** (§3) — `Appearance.kt`, `CRColors.kt`, `CRMetrics.kt`, `CRType.kt`, `CRTheme.kt`, `AppearanceState.kt`, `AppearanceViewModel.kt`
2. **AppSettings persistence** — diff existing `AppSettings.kt`, add `appearance: AppearanceState`
3. **Primitives** (§5) — `CRCard`, `CRButton`, `CRSegmented`, `CRTopBar`, `StatusDot/Pill/Bar/Indicator`, `ServerGlyph`, `ModePill`, `ActivityHeatmap`, `Pill`, helpers
4. **Appearance Settings section** (§4) — first user-facing payoff of the theme work
5. **LauncherScreen rewrite** — session list hero, server list, FAB. Density support throughout.
6. **SessionDrawer** (§6.2) — new component, used by TerminalScreen
7. **TerminalScreen rewrite** — topbar + crumb bar + body + control bar (status row, mode/model chips, **special keys row**, input)
8. **TranscriptView restyle** — chat-like, glyphs místo bublin pro claude/tool
9. **ConnectScreen, ServerEditDialog, SettingsScreen (rest), UsageDashboardScreen** — apply tokens, density, primitives
10. **CommandPalette, ExpandedInput** — overlays
11. **Glass variant polish** — `glassSurface`, `specularTopHighlight`, aurora background, finer tuning. Defer until Classic is stable.
12. **Dev Tweaks panel** (optional) — port from prototype behind dev feature flag

---

## 12 · Out of scope

- Onboarding / first-launch flow (žádné servery)
- Server import / export (deep links: `claude-remote://server/folder`)
- Update banner — keep existing `UpdateBanner.kt`
- Log viewer — keep existing `LogViewerScreen.kt` as-is (eventual reskin can use new primitives)
- Folder browser modal — existing, adapt to new tokens when touching it
- File upload UI
- Approval dialog details (structure exists, just reskin with new primitives)

---

## Assets

- **No new bitmaps.** SVG strokes / Compose-native everywhere.
- **Icons** — Lucide-style mono-stroke (vidět v `prototype/components.jsx → Icon`). Recommend `compose-icons` lib for Lucide; Material Icons Extended as fallback. List of used icons: `menu, settings, plus, play, star, trash, pencil, chevright, chevleft, chevdown, server, folder, terminal, sliders, send, search, x, back, arrowup, cpu, bolt, history, expand, close, dots, key, lock, refresh, file, book, eye`.
- **Server glyphs** — auto-generated from server name hash (§5.5). No storage.
- **Fonts** — Inter Tight (sans, weights 400/500/600/700/800), JetBrains Mono (mono, 400/500/600). Bundle in `composeResources/font/` or use system fallbacks (Roboto on Android, SF on macOS).

---

## Reference: prototype tweak ↔ Compose state mapping

| Prototype tweak (`Claude Remote.html`) | Compose state |
|---|---|
| `variant: "classic" \| "glass"` | `AppearanceState.variant: CRVariant` |
| `density: "compact" \| "regular" \| "dense"` | `AppearanceState.density: CRDensity` |
| `accent: "sky" \| "mint" \| "amber" \| "violet" \| "rose"` | `AppearanceState.accent: CRAccent` |
| `statusViz: "dot" \| "pill" \| "bar"` | `AppearanceState.statusViz: CRStatusViz` |
| `terminalView: "raw" \| "transcript"` | `AppearanceState.terminalView: CRTerminalView` |
| `colorScheme: "default" \| "solarized-dark" \| ...` | `AppearanceState.terminalScheme: CRTerminalScheme` |
| `controlBar: "bottom" \| "top"` | (not implemented v1 — defer) |

Všechny stejné stavy live v prototypu i v Compose AppearanceVM.

---

**Konec spec. Otázky / chybějící části — řekni a doplním.**
