# Claude Remote

Mobile and desktop app for remotely controlling [Claude Code](https://docs.anthropic.com/en/docs/claude-code) CLI over SSH/Mosh connections. No proprietary backend — all communication goes through standard terminal connections to your own servers.

```
[Android / Desktop App] --> SSH/Mosh --> [Your Server] --> tmux --> claude CLI
```

## Screenshots

| Launcher | Terminal | Expanded Input | Settings |
|----------|----------|----------------|----------|
| Server list with active sessions | xterm.js terminal with Claude Code | Multi-line editor with templates & history | Terminal, Claude defaults, connection config |

## Features

- **Full terminal emulation** — xterm.js-based terminal with 256-color support, mouse tracking, selection, search, and clickable links
- **Multi-session tabs** — Run multiple Claude Code sessions across different servers and folders simultaneously
- **Session persistence** — tmux-backed sessions survive disconnections and app restarts
- **Auto-reconnect** — Exponential backoff reconnection with automatic tmux reattach and output replay
- **Claude integration** — Quick mode switching (Normal / Plan / Auto-accept / YOLO), model picker (Opus / Sonnet / Haiku), slash command suggestions
- **Remote folder browser** — Browse and select project directories on remote servers via SSH
- **Port forwarding** — Local and remote port forwarding per server
- **File upload** — SFTP-based file upload to remote sessions
- **Delta updates** — Binary diff patching for efficient APK updates (Android)
- **Biometric lock** — Optional fingerprint/Face ID protection (Android)
- **Keep-alive service** — Foreground service with wake lock to maintain connections in background (Android)
- **Customizable terminal** — Font size, color schemes (default, solarized-dark, dracula, monokai, linux), scrollback buffer
- **SSH key authentication** — Password or private key auth with TOFU host key verification

## Platforms

| Platform | Format | Min Version |
|----------|--------|-------------|
| Android  | APK    | API 26 (Android 8.0) |
| macOS    | DMG    | macOS 12+ |
| Windows  | MSI    | Windows 10+ |
| Linux    | DEB    | Ubuntu 20.04+ |

## Quick Start

### Prerequisites

- A server with SSH access and [Claude Code](https://docs.anthropic.com/en/docs/claude-code) installed
- `tmux` installed on the server

### Usage

1. **Add a server** — Enter host, username, port, and authentication method (password or SSH key)
2. **Select a folder** — Browse remote directories or type a path manually
3. **Configure Claude** — Choose mode (Normal/Plan/Auto-accept/YOLO) and model (Opus/Sonnet/Haiku)
4. **Launch** — The app opens an SSH connection, creates a tmux session, and starts `claude` in your selected folder

## Building from Source

### Requirements

- JDK 17+
- Android SDK (for Android builds)
- Gradle 8.11+

### Build Commands

```bash
# Download terminal assets (xterm.js)
./setup-assets.sh

# Android APK
./gradlew :androidApp:assembleRelease

# Desktop (current platform)
./gradlew :desktopApp:packageDmg    # macOS
./gradlew :desktopApp:packageMsi    # Windows
./gradlew :desktopApp:packageDeb    # Linux

# Cross-compile mosh-client for Android (optional)
./build-mosh.sh /path/to/android-ndk
```

See [Building & Release](docs/building.md) for full CI/CD pipeline details.

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin (Multiplatform) |
| UI | Compose Multiplatform + Material 3 |
| Terminal | xterm.js 5.5.0 in WebView |
| SSH | JSch 0.2.21 |
| Session persistence | tmux |
| Serialization | kotlinx-serialization |
| Desktop WebView | JavaFX 21 |
| Build | Gradle 8.11, GitHub Actions |

## Project Structure

```
claude-remote/
├── shared/                     # Kotlin Multiplatform shared code
│   └── src/
│       ├── commonMain/         # Platform-agnostic (models, connection, session, UI, utils)
│       ├── androidMain/        # Android expect/actual implementations
│       └── desktopMain/        # Desktop expect/actual implementations
├── androidApp/                 # Android entry point, WebView bridge, services
├── desktopApp/                 # Desktop entry point, JavaFX WebView bridge
├── shared-assets/              # Shared HTML/JS/CSS (terminal, control panel, overlay)
├── .github/workflows/          # CI/CD (Android APK + Desktop packages)
├── build-mosh.sh               # Cross-compile mosh for Android
└── setup-assets.sh             # Download xterm.js from CDN
```

## Documentation

- [Architecture](docs/architecture.md) — Layered architecture, data models, session lifecycle, navigation
- [Building & Release](docs/building.md) — Build setup, CI/CD pipelines, signing, versioning, delta patches
- [Connection Layer](docs/connection.md) — SSH, Mosh, and tmux management in detail
- [Terminal Emulation](docs/terminal.md) — xterm.js integration, WebView bridge, JS interface
- [Platform Specifics](docs/platform-specifics.md) — Android and Desktop implementation details

## License

Private project.
