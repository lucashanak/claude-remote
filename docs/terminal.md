# Terminal Emulation

The terminal is implemented using [xterm.js](https://xtermjs.org/) running inside a platform WebView, with a JavaScript bridge connecting it to the native SSH layer.

## Architecture

```
┌─────────────────────────────────┐
│         Native App (Kotlin)      │
│  SessionOrchestrator             │
│    ├─ sendInput(data) ◄──────────┼── JS Bridge: onTerminalInput(data)
│    ├─ resize(cols, rows) ◄───────┼── JS Bridge: onTerminalReady(cols, rows)
│    └─ onOutput(data) ───────────►┼── JS: term.write(data)
├─────────────────────────────────┤
│      WebView (platform-specific) │
│  ┌───────────────────────────┐  │
│  │     xterm.js Terminal     │  │
│  │  - 256-color rendering    │  │
│  │  - Mouse tracking         │  │
│  │  - Selection + clipboard  │  │
│  │  - Search overlay         │  │
│  │  - Link detection         │  │
│  └───────────────────────────┘  │
└─────────────────────────────────┘
```

## Shared Assets

All terminal HTML/JS/CSS lives in `shared-assets/` and is copied to platform-specific asset directories by `setup-assets.sh`.

### Files

| File | Purpose |
|------|---------|
| `shared-assets/terminal/terminal.html` | Main xterm.js host page |
| `shared-assets/terminal/xterm.js` | xterm.js library (vendored from CDN) |
| `shared-assets/terminal/xterm.css` | xterm.js styles |
| `shared-assets/terminal/xterm-addon-fit.js` | Auto-fit terminal to container |
| `shared-assets/terminal/xterm-addon-web-links.js` | Clickable URL detection |
| `shared-assets/claude-control/control.html` | Claude mode/model control panel |
| `shared-assets/claude-control/control.css` | Control panel styles |
| `shared-assets/claude-control/control.js` | Control panel logic |
| `shared-assets/overlay-ui/overlay.html` | Android keyboard overlay |

### Asset Distribution

```
shared-assets/terminal/ ──copy──► androidApp/src/main/assets/terminal/
                         ──copy──► desktopApp/src/main/resources/terminal/
```

## xterm.js Configuration

From `terminal.html`:

```javascript
const term = new Terminal({
    cursorBlink: true,
    cursorStyle: 'block',
    fontSize: 14,           // Overridden by AppSettings
    fontFamily: 'monospace',
    theme: { /* color scheme */ },
    scrollback: 10000,      // Overridden by AppSettings
    allowTransparency: true,
    convertEol: false,
    disableStdin: false
});

// Addons
const fitAddon = new FitAddon.FitAddon();
term.loadAddon(fitAddon);
term.loadAddon(new WebLinksAddon.WebLinksAddon());

term.open(document.getElementById('terminal'));
fitAddon.fit();
```

## JavaScript Bridge Interface

The native app exposes a `window.Android` object that the terminal JS calls:

### Terminal → Native

```javascript
// User typed something
window.Android.onTerminalInput(data);

// Terminal initialized or resized
window.Android.onTerminalReady(cols, rows);

// User clicked a URL
window.Android.openUrl(url);
```

### Native → Terminal

Called via `webView.evaluateJavascript()`:

```javascript
// Write SSH output to terminal
term.write(data);

// Resize terminal
term.resize(cols, rows);
fitAddon.fit();

// Clear terminal
term.clear();

// Set font size
term.options.fontSize = size;
fitAddon.fit();

// Apply color scheme
term.options.theme = { background: '#1e1e1e', ... };

// Set scrollback
term.options.scrollback = lines;

// Search
term.findNext(query);
term.findPrevious(query);
```

## Terminal Features

### Mouse Support

xterm.js mouse tracking is enabled, supporting:
- Click to position cursor
- Scroll wheel (both in terminal and tmux)
- Click-drag selection
- Right-click context menu

Mouse events are encoded and sent through the JS bridge as raw bytes via `sendBytes()`.

### Selection and Clipboard

Android-style selection with handles:
- Long-press to start selection
- Drag handles to adjust range
- Copy/paste toolbar appears above selection
- Copy sends to system clipboard
- Paste reads from system clipboard and sends via `onTerminalInput()`

### Search Overlay

In-terminal search:
- Activated via UI button or keyboard shortcut
- `term.findNext(query)` / `term.findPrevious(query)`
- Highlights matches in the scrollback buffer

### Link Detection

The `WebLinksAddon` detects URLs in terminal output and makes them clickable:
- Click calls `window.Android.openUrl(url)`
- Android: opens in system browser via Intent
- Desktop: opens via `Desktop.browse()`

### Context Menu

Right-click (or long-press on Android) shows:
- Copy
- Paste
- Select All
- Clear Terminal

### Toast Notifications

```javascript
function showToast(message, duration) {
    // Creates floating notification at bottom of terminal
    // Used for: "Copied to clipboard", "Reconnecting...", etc.
}
```

### Color Schemes

Available schemes (configured in `AppSettings.terminalColorScheme`):

| Scheme | Background | Description |
|--------|------------|-------------|
| `default` | `#1e1e1e` | Dark neutral |
| `solarized-dark` | `#002b36` | Solarized dark palette |
| `dracula` | `#282a36` | Dracula theme |
| `monokai` | `#272822` | Monokai colors |
| `linux` | `#000000` | Classic Linux console |

Applied via `term.options.theme = { ... }` when the terminal loads or settings change.

## Platform Implementations

### Android WebView

**File:** `androidApp/src/main/kotlin/.../MainActivity.kt`

```kotlin
private fun initTerminal() {
    terminalWebView = WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        addJavascriptInterface(TerminalJSBridge(), "Android")
        loadUrl("file:///android_asset/terminal/terminal.html")
    }
}

inner class TerminalJSBridge {
    @JavascriptInterface
    fun onTerminalInput(data: String) {
        sessionOrchestrator.sendInput(activeSessionId, data)
    }

    @JavascriptInterface
    fun onTerminalReady(cols: Int, rows: Int) {
        sessionOrchestrator.resize(activeSessionId, cols, rows)
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
```

### Desktop JavaFX WebView

**File:** `desktopApp/src/main/kotlin/.../Main.kt`

Embeds JavaFX WebView in Compose Desktop via `SwingPanel`:

```kotlin
@Composable
fun DesktopTerminalWebView(onBridgeReady: (WebEngine) -> Unit) {
    SwingPanel(
        factory = {
            JPanel(BorderLayout()).also { panel ->
                val jfxPanel = JFXPanel()
                Platform.runLater {
                    val webView = WebView()
                    val engine = webView.engine

                    engine.load(
                        javaClass.getResource("/terminal/terminal.html")
                            ?.toExternalForm()
                    )

                    // Inject JS bridge
                    val window = engine.executeScript("window") as JSObject
                    window.setMember("Android", DesktopTerminalBridge())

                    jfxPanel.scene = Scene(webView)
                    onBridgeReady(engine)
                }
                panel.add(jfxPanel)
            }
        }
    )
}
```

**File:** `desktopApp/src/main/kotlin/.../DesktopTerminalBridge.kt`

```kotlin
class DesktopTerminalBridge {
    var onInput: ((String) -> Unit)? = null
    var onReady: ((Int, Int) -> Unit)? = null

    fun onTerminalInput(data: String) = onInput?.invoke(data)
    fun onTerminalReady(cols: Int, rows: Int) = onReady?.invoke(cols, rows)
    fun openUrl(url: String) = Desktop.getDesktop().browse(URI(url))
}
```

## Claude Control Panel

**Files:** `shared-assets/claude-control/control.{html,css,js}`

A separate WebView overlay providing quick access to Claude controls:
- Mode buttons (Normal / Plan / Auto-accept / YOLO)
- Model dropdown (Default / Opus / Sonnet / Haiku)
- Quick commands (/clear, /help, Escape)
- Collapsible to save screen space

Communicates with the terminal by sending commands through the same JS bridge (e.g., sending `/model opus\n` as terminal input).

## Keyboard Overlay (Android)

**File:** `shared-assets/overlay-ui/overlay.html`

Touch-friendly overlay keyboard with common terminal keys:
- Modifier keys: Ctrl, Tab, Esc
- Arrow keys
- Special keys: y, n (for Claude confirmations)
- Mode/slash command buttons

Managed by `OverlayManager` in `androidApp/`.
