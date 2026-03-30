# Claude Remote Controller - Design Document

## Overview

Mobilní a desktopová aplikace pro vzdálené ovládání Claude Code přes SSH/Mosh + tmux. Žádný vlastní backend - vše běží přes terminálové připojení k serverům.

## Core Concept

```
[Android/macOS App] → SSH/Mosh → [Remote Server] → tmux session → claude CLI
```

User vybere server, folder, claude options → app otevře tmux session s `claude` příkazem → interaguje přes terminál.

---

## Technologie

### Android
- **Kotlin** + Coroutines (reuse z vscode-android)
- **WebView** + xterm.js pro terminál (reuse z vscode-android)
- **JSch** pro SSH (reuse)
- **Mosh** cross-compiled binary (reuse)
- **Material Design 3** pro launcher UI

### macOS
- **Swift** + SwiftUI
- **WKWebView** + xterm.js pro terminál (stejný HTML/JS jako Android)
- **libssh2** nebo Process("ssh") pro SSH
- **Mosh** via Homebrew/bundled binary
- Nativní PTY přes `forkpty()`

### Sdílené (assets)
- `terminal/terminal.html` - xterm.js terminál (reuse z vscode-android, upravit)
- `overlay-ui/overlay.html` - klávesnice overlay (reuse pro Android)
- Claude control panel (nový HTML/JS komponent)

---

## Architektura

### Vrstvy

```
┌─────────────────────────────────────────┐
│              UI Layer                    │
│  ┌──────────┐ ┌──────────┐ ┌─────────┐ │
│  │ Launcher  │ │ Terminal │ │ Claude  │ │
│  │ (servers, │ │ (xterm.js│ │ Control │ │
│  │  folders) │ │  WebView)│ │ Panel   │ │
│  └──────────┘ └──────────┘ └─────────┘ │
├─────────────────────────────────────────┤
│           Session Manager               │
│  ┌──────────┐ ┌──────────┐ ┌─────────┐ │
│  │   Tab    │ │  Tmux    │ │ Claude  │ │
│  │ Manager  │ │ Manager  │ │ Config  │ │
│  └──────────┘ └──────────┘ └─────────┘ │
├─────────────────────────────────────────┤
│          Connection Layer               │
│  ┌──────────┐ ┌──────────┐ ┌─────────┐ │
│  │   SSH    │ │   Mosh   │ │   PTY   │ │
│  │ Manager  │ │ Manager  │ │ Process │ │
│  └──────────┘ └──────────┘ └─────────┘ │
├─────────────────────────────────────────┤
│          Storage Layer                  │
│  ┌──────────┐ ┌──────────┐             │
│  │  Server  │ │   App    │             │
│  │ Storage  │ │ Settings │             │
│  └──────────┘ └──────────┘             │
└─────────────────────────────────────────┘
```

---

## Screens & Flows

### 1. Launcher Screen (hlavní obrazovka)

```
┌─────────────────────────────┐
│  Claude Remote        [⚙️]  │
│─────────────────────────────│
│                             │
│  ACTIVE SESSIONS            │
│  ┌─────────────────────┐   │
│  │ 🟢 prod-server       │   │
│  │    ~/myproject       │   │
│  │    claude (plan mode)│   │
│  └─────────────────────┘   │
│  ┌─────────────────────┐   │
│  │ 🟢 dev-server        │   │
│  │    ~/api             │   │
│  │    claude (yolo)     │   │
│  └─────────────────────┘   │
│                             │
│  SERVERS                    │
│  ┌─────────────────────┐   │
│  │ 🖥️ prod-server       │   │
│  │   user@192.168.1.10 │   │
│  │   [Connect]         │   │
│  └─────────────────────┘   │
│  ┌─────────────────────┐   │
│  │ 🖥️ dev-server        │   │
│  │   dev@10.0.0.5      │   │
│  │   [Connect]         │   │
│  └─────────────────────┘   │
│                             │
│         [+ Add Server]      │
│                             │
└─────────────────────────────┘
```

### 2. Connect Flow (po kliknutí na server)

```
┌─────────────────────────────┐
│  ← prod-server              │
│─────────────────────────────│
│                             │
│  FOLDER                     │
│  ┌─────────────────────┐   │
│  │ ~/myproject       ▼  │   │
│  └─────────────────────┘   │
│  Recent: ~/api, ~/web      │
│  [Browse...] [New folder]  │
│                             │
│  CLAUDE OPTIONS             │
│  ┌─────────────────────┐   │
│  │ Mode:  ○ Plan       │   │
│  │        ○ Normal     │   │
│  │        ● Auto-accept│   │
│  │        ○ YOLO       │   │
│  ├─────────────────────┤   │
│  │ Model: opus    ▼    │   │
│  ├─────────────────────┤   │
│  │ Connection: SSH ▼   │   │
│  │ (SSH / Mosh)        │   │
│  └─────────────────────┘   │
│                             │
│  TMUX SESSION               │
│  ○ New session              │
│  ○ Attach: claude-main (1w) │
│  ○ Attach: debug (2w)       │
│                             │
│      [▶ Launch Claude]      │
│                             │
└─────────────────────────────┘
```

### 3. Terminal Screen (hlavní pracovní plocha)

```
┌─────────────────────────────┐
│ [≡] prod:~/myproject  [⚙️▼] │
│─────────────────────────────│
│                             │
│  ┌─── xterm.js ──────────┐ │
│  │ $ claude              │ │
│  │                       │ │
│  │ ╭──────────────────╮  │ │
│  │ │ Claude Code       │  │ │
│  │ │ Model: opus       │  │ │
│  │ │ Mode: auto-accept │  │ │
│  │ ╰──────────────────╯  │ │
│  │                       │ │
│  │ > What would you...   │ │
│  │                       │ │
│  │                       │ │
│  └───────────────────────┘ │
│                             │
│  ┌─ Claude Control Bar ──┐ │
│  │ [Plan] [Auto] [YOLO]  │ │
│  │ Model: [opus▼]        │ │
│  │ [Esc] [/clear] [/help]│ │
│  └───────────────────────┘ │
│                             │
│ [Tab1] [Tab2] [+]          │
│─────────────────────────────│
│ [Overlay Keyboard]          │
└─────────────────────────────┘
```

**Claude Control Bar** - plovoucí panel nad terminálem:
- Přepínání módů: posílá odpovídající příkazy do claude CLI
  - Plan → `/plan`
  - Auto-accept → odpovídající toggle
  - YOLO → restart s `--dangerously-skip-permissions`
- Model switch → `/model opus|sonnet|haiku`
- Quick commands → `/clear`, `/help`, Escape key
- Collapsible - lze skrýt pro více místa

### 4. Tab System

```
┌──────────────────────────────────────────┐
│ [prod:~/proj] [dev:~/api] [prod:~/web] + │
└──────────────────────────────────────────┘
```

- Každý tab = samostatná SSH/Mosh + tmux session
- Tabs mohou být na různých serverech i folderech
- Swipe mezi taby (Android) / Cmd+1-9 (macOS)
- Tab ukazuje: server:folder + indikátor aktivity

---

## Klíčové komponenty (reuse z vscode-android)

### Přímý reuse (kopie + úpravy)
| Komponenta | Soubor | Úpravy |
|---|---|---|
| SSH Manager | `SshSessionManager.kt` | Odstranit VS Code specifika, přidat claude cmd builder |
| Mosh Manager | `MoshSessionManager.kt` | Minimální úpravy |
| Tmux Manager | `TmuxManager.kt` | Přidat listFolders(), rozšířit session management |
| PTY Process | `PtyProcess.kt` + `pty_helper.c` | Beze změn |
| Server Storage | `ServerStorage.kt` | Přidat claude-specific fields |
| App Settings | `AppSettings.kt` | Nahradit VS Code settings za claude settings |
| Terminal HTML | `terminal/terminal.html` | Přidat claude control bar |
| Overlay Keyboard | `overlay-ui/overlay.html` | Beze změn |
| Keep Alive | `KeepAliveService.kt` | Beze změn |
| File Logger | `FileLogger.kt` | Beze změn |
| Mosh build | `build-mosh.sh` | Beze změn |

### Nové komponenty
| Komponenta | Popis |
|---|---|
| `ClaudeConfig.kt` | Builder pro claude CLI příkaz s options |
| `ClaudeControlPanel` | HTML/JS control bar pro přepínání módů/modelů |
| `TabManager.kt` | Správa více paralelních sessions/tabů |
| `FolderBrowser.kt` | Procházení a výběr remote folders přes SSH |
| `SessionOrchestrator.kt` | Koordinace: server → folder → tmux → claude |

---

## Claude CLI Integration

### Spuštění claude
```bash
# Základní
cd ~/project && claude

# S auto-accept
cd ~/project && claude --auto-accept

# YOLO mode
cd ~/project && claude --dangerously-skip-permissions

# S modelem
cd ~/project && claude --model opus

# Kompletní
cd ~/project && claude --model sonnet --auto-accept
```

### Runtime ovládání (posílání do terminálu)
```
/model opus          # přepnout model
/plan                # plan mode
/clear               # vyčistit kontext
Escape key           # přerušit
/help                # nápověda
```

### Tmux wrapper
```bash
tmux new-session -A -s 'claude-myproject' \; set-option -g mouse on
# Pak uvnitř tmux:
cd ~/myproject && claude --model opus --auto-accept
```

---

## Data Model

### SshServer (rozšířený z vscode-android)
```kotlin
data class SshServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMethod: AuthMethod, // PASSWORD, KEY
    val password: String? = null,
    val privateKey: String? = null,
    val preferMosh: Boolean = false,
    // Claude-specific
    val defaultFolder: String = "~",
    val recentFolders: List<String> = emptyList(),
    val defaultClaudeMode: ClaudeMode = ClaudeMode.NORMAL,
    val defaultClaudeModel: ClaudeModel = ClaudeModel.OPUS,
    val portForwards: List<PortForward> = emptyList()
)
```

### ClaudeSession
```kotlin
data class ClaudeSession(
    val id: String,
    val server: SshServer,
    val folder: String,
    val mode: ClaudeMode,
    val model: ClaudeModel,
    val tmuxSession: String,
    val connectionType: ConnectionType, // SSH, MOSH
    val status: SessionStatus // CONNECTING, ACTIVE, DISCONNECTED
)
```

### Enums
```kotlin
enum class ClaudeMode { PLAN, NORMAL, AUTO_ACCEPT, YOLO }
enum class ClaudeModel { OPUS, SONNET, HAIKU }
enum class ConnectionType { SSH, MOSH }
```

---

## macOS Implementation

### Technologie
- **SwiftUI** pro UI
- **WKWebView** pro xterm.js terminál (sdílený HTML/JS s Androidem)
- **Process + forkpty()** pro lokální PTY
- **ssh/mosh** system binaries nebo libssh2
- **SwiftData** pro persistenci serverů

### macOS-specific
- Native tab bar (NSTabView style)
- Keyboard shortcuts: Cmd+T (new tab), Cmd+W (close), Cmd+1-9 (switch)
- Touch Bar support (Claude mode buttons)
- Menu bar: File > New Connection, rychlý přístup k serverům
- Notifikace: session disconnect, claude čeká na input

### Sdílený kód (assets)
- `terminal/terminal.html` - identický xterm.js terminál
- `claude-control/control.html` - Claude control bar
- Oba loadnuté do WKWebView, komunikace přes WKScriptMessageHandler

---

## Implementační plán

### Fáze 1: Android MVP
1. Nový Android projekt, zkopírovat reusable komponenty z vscode-android
2. Launcher UI (servers, add server)
3. SSH/Mosh connection s tmux (reuse)
4. Terminal s xterm.js (reuse)
5. Základní tab systém (2+ sessions)
6. Claude command builder (folder + options → příkaz)
7. Claude control bar (mode/model switching)

### Fáze 2: Android Polish
1. Folder browser (remote ls přes SSH)
2. Recent folders per server
3. Session restore po app restart
4. Keep-alive service (reuse)
5. Overlay keyboard (reuse)
6. Settings screen

### Fáze 3: macOS App
1. Nový SwiftUI projekt
2. SSH/Mosh connection (Process-based)
3. WKWebView + sdílený terminal HTML
4. Native tab UI
5. Claude control panel
6. Server management
7. Keyboard shortcuts

### Fáze 4: Shared Improvements
1. Server sync mezi zařízeními (iCloud/file export)
2. Quick-connect: deep links (`claude-remote://server/folder`)
3. Session notifications
4. Biometric lock (reuse pro Android, Face ID pro macOS)

---

## Struktura projektu

```
claude-remote/
├── android/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/clauderemote/app/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── SshSessionManager.kt      # reuse
│   │   │   │   ├── MoshSessionManager.kt      # reuse
│   │   │   │   ├── TmuxManager.kt             # reuse+extend
│   │   │   │   ├── PtyProcess.kt              # reuse
│   │   │   │   ├── TabManager.kt              # new
│   │   │   │   ├── ClaudeConfig.kt            # new
│   │   │   │   ├── SessionOrchestrator.kt     # new
│   │   │   │   ├── FolderBrowser.kt           # new
│   │   │   │   ├── ServerStorage.kt           # reuse+extend
│   │   │   │   ├── AppSettings.kt             # reuse+modify
│   │   │   │   ├── KeepAliveService.kt        # reuse
│   │   │   │   └── FileLogger.kt              # reuse
│   │   │   ├── cpp/
│   │   │   │   ├── pty_helper.c               # reuse
│   │   │   │   └── CMakeLists.txt             # reuse
│   │   │   ├── assets/
│   │   │   │   ├── terminal/                  # reuse+modify
│   │   │   │   ├── claude-control/            # new
│   │   │   │   └── overlay-ui/               # reuse
│   │   │   └── res/
│   │   └── build.gradle.kts
│   ├── build-mosh.sh                          # reuse
│   └── build.gradle.kts
├── macos/
│   ├── ClaudeRemote/
│   │   ├── ClaudeRemoteApp.swift
│   │   ├── Views/
│   │   │   ├── LauncherView.swift
│   │   │   ├── TerminalView.swift
│   │   │   ├── ServerEditView.swift
│   │   │   └── SettingsView.swift
│   │   ├── Managers/
│   │   │   ├── SshManager.swift
│   │   │   ├── MoshManager.swift
│   │   │   ├── TmuxManager.swift
│   │   │   ├── SessionManager.swift
│   │   │   └── ClaudeConfig.swift
│   │   ├── Models/
│   │   │   ├── Server.swift
│   │   │   ├── ClaudeSession.swift
│   │   │   └── AppSettings.swift
│   │   └── Resources/
│   │       └── terminal/                      # shared assets
│   └── ClaudeRemote.xcodeproj
├── shared-assets/
│   ├── terminal/
│   │   ├── terminal.html
│   │   ├── xterm.js
│   │   ├── xterm.css
│   │   └── addons/
│   └── claude-control/
│       ├── control.html
│       ├── control.css
│       └── control.js
├── DESIGN.md
└── README.md
```

---

## Bezpečnost

- Hesla a klíče: Android Keystore / macOS Keychain
- Biometric lock (volitelný)
- Žádná data na externích serverech - vše lokální + SSH
- Known hosts TOFU (reuse z vscode-android)
- Base64 pro citlivá data v storage (reuse, zvážit upgrade na encrypted storage)
