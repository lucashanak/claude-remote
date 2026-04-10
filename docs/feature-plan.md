# Implementation Plan: New Features for Claude Remote

## Context
Claude Remote is a KMP app (Android + Desktop) for remote Claude Code sessions over SSH/tmux. This plan covers 15+ new features across 6 categories, organized into implementation phases ordered by dependency and impact.

---

## Phase 1: Foundation & Quick Wins

### 1.1 Session Health Indicator
**Files:** `TerminalScreen.kt`, `TabManager.kt`, `ClaudeSession.kt`, `InputPromptDetector.kt`

- Add `sessionState: SessionState` enum: `IDLE`, `WORKING`, `WAITING_FOR_INPUT`, `DISCONNECTED`
- Derive state from existing `InputPromptDetector` output + `SessionStatus`
- Show colored dot in tab bar and side panel (green=idle, yellow=working, blue=waiting, red=disconnected)
- Already have `SessionStatus` and prompt detection — wire them together

### 1.2 Context Window Visualizer
**Files:** `TerminalScreen.kt`, `InputPromptDetector.kt`, `SessionOrchestrator.kt`

- Already parsing context percent via `parseContextPercent()` and have `onContextUpdate` callback
- Add a thin horizontal progress bar below the tab bar (or in PromptInputBar area)
- Color gradient: green (0-50%) → yellow (50-80%) → red (80-100%)
- Show "132k/200k" text on tap/hover
- Wire existing `onContextUpdate` callback to UI state in `App.kt`

### 1.3 Connection Quality Indicator
**Files:** `SshManager.kt`, `TerminalScreen.kt`, `SessionOrchestrator.kt`

- Measure SSH round-trip latency: periodic `exec("echo ping")` every 10s on a separate exec channel
- Track rolling average (last 5 measurements)
- Display in top bar: icon + latency value (e.g., "23ms")
- Color: green (<100ms), yellow (100-300ms), red (>300ms)
- Add `latencyMs: StateFlow<Long?>` to SessionOrchestrator per session

---

## Phase 2: Monitoring & Observability Dashboard

### 2.1 Token Usage Dashboard
**Files:** New `UsageDashboardScreen.kt`, `App.kt` (add Screen.USAGE_DASHBOARD), `SessionOrchestrator.kt`

- New screen accessible from LauncherScreen or TerminalScreen
- Data source: existing `ccusage blocks --json` polling (already runs every 30s)
- Extend polling to store historical data points in memory (last 24h, aggregate per session)
- Display:
  - Current session usage bar (already have `sessionUsagePercent`)
  - Weekly usage bar (already have `weekUsagePercent`)
  - Per-model breakdown (parse from ccusage JSON)
  - Session history list with token counts
- Persist daily aggregates via `PlatformPreferences` (simple JSON)

### 2.2 Cost Estimation
**Files:** `UsageDashboardScreen.kt`, new `CostCalculator.kt`

- Pricing table per model (Opus/Sonnet/Haiku input/output/cache rates)
- Calculate from token counts in ccusage data
- Show estimated cost per session and cumulative daily/weekly
- Display in dashboard and optionally as small badge in TerminalScreen

### 2.3 Push Notifications (Android)
**Files:** `SessionOrchestrator.kt`, `MainActivity.kt` (Android), `Main.kt` (Desktop)

- Already have `onClaudeNeedsInput` callback and `KeepAliveService`
- Android: use existing notification channel, fire notification when Claude finishes task (INPUT_PROMPT detected while app is backgrounded)
- Desktop: already have system tray — enhance with task completion messages
- Add setting `notifyOnTaskComplete: Boolean` to `AppSettings`

---

## Phase 3: Command Palette & Quick Actions

### 3.1 Command Palette
**Files:** `TerminalScreen.kt`, new `CommandPalette.kt` composable

- Trigger: keyboard shortcut (Ctrl+K on desktop) or FAB/gesture on mobile
- Fuzzy-searchable list of actions:
  - Session actions: switch tab, close tab, reconnect, new session
  - Claude commands: /plan, /clear, /compact, /rewind, /config, /help
  - Mode switching: Normal, Plan, Auto-accept, YOLO
  - Model switching: Opus, Sonnet, Haiku
  - Navigation: Settings, Dashboard, Logs
  - Server snippets (per-server custom commands)
- Reuse existing `CommandPicker` pattern (already has filter + list)
- Each action has: icon, label, shortcut hint, category
- Recent actions float to top

---

## Phase 4: Terminal Enhancements

### 4.1 Split View (Desktop + Tablet)
**Files:** `TerminalScreen.kt`, `App.kt`, `SessionOrchestrator.kt`

- Desktop/wide layout only (>1000dp)
- Allow dragging a tab to create a split (horizontal or vertical)
- State: `splitSessions: List<Pair<String, String>>` (two session IDs side by side)
- Each split pane gets its own terminal content area
- Android: need two WebViews (or single WebView with two xterm instances)
- Desktop: two JediTermWidgets in a split pane
- Start simple: horizontal split only, max 2 panes
- Platform-specific: requires duplicating terminal content composable

### 4.2 Inline Image Preview
**Files:** `terminal.html` (xterm.js), `TerminalScreen.kt`

- Detect image output patterns from Claude (file paths to generated images, base64 data)
- For remote images: SFTP download to temp, display in overlay
- For iTerm2 inline image protocol (OSC 1337): parse in terminal.html
- Simpler approach: detect file paths ending in .png/.jpg/.svg in terminal output, add clickable link that downloads and previews
- Preview composable: overlay dialog with zoomable image

### 4.3 Custom Keyboard Shortcuts
**Files:** new `KeyboardShortcuts.kt`, `AppSettings.kt`, `TerminalScreen.kt`

- Define action registry (reuse Command Palette actions)
- Default shortcuts: Ctrl+K (palette), Ctrl+Tab (next tab), Ctrl+W (close tab), etc.
- Settings UI: list of actions with editable key bindings
- Desktop: intercept key events at window level
- Android: not applicable (no physical keyboard typically)
- Store as JSON map in AppSettings: `{"ctrl+k": "command_palette", ...}`

### 4.4 Swipe Gestures (Android)
**Files:** `TerminalScreen.kt`, `terminal.html`, `MainActivity.kt`

- Swipe left/right between tabs (conflict with terminal selection — use edge swipe or 2-finger)
- Swipe down from top edge: quick connect / command palette
- Implementation: Compose gesture detectors wrapping terminal content
- Need care to not conflict with terminal touch handling (selection, scroll)
- Use `pointerInput` with velocity-based detection on edges only

---

## Phase 5: Advanced Features

### 5.1 Offline Input Queue
**Files:** `SessionOrchestrator.kt`, `SshManager.kt`, `TerminalScreen.kt`

- When disconnected, buffer user input in a queue
- Show visual indicator "Queued (3 messages)" in input bar
- On reconnect, replay queued inputs with configurable delay between them
- Simple `pendingInputs: MutableList<String>` per session in SessionOrchestrator
- Flush in `autoReconnect` success path
- UI: show queue count badge on send button, option to clear queue

### 5.2 CLAUDE.md Editor
**Files:** `TerminalScreen.kt` (extend existing claudeMd dialog), `SessionOrchestrator.kt`

- Already have CLAUDE.md viewer (`showClaudeMd` state, reads via `cat .claude/CLAUDE.md`)
- Convert read-only dialog to editor:
  - TextField with monospace font, syntax-aware (markdown)
  - Save button: write back via `cat > .claude/CLAUDE.md << 'CLAUDE_EOF'\n...\nCLAUDE_EOF`
  - Or use SFTP upload (already have `uploadFile`)
- Add "Edit CLAUDE.md" button next to existing "View" button
- Preview toggle (rendered markdown vs raw)

### 5.3 SSH Key Management
**Files:** new `SshKeyManager.kt`, `ServerEditDialog.kt`, `SettingsScreen.kt`

- Generate RSA/Ed25519 keys: use JSch KeyPair API (`com.jcraft.jsch.KeyPair`)
- Store keys in app storage (PlatformPreferences or files)
- Key list UI in Settings: name, type, fingerprint, created date
- Deploy to server: `ssh-copy-id` equivalent (append to ~/.ssh/authorized_keys via exec channel)
- Import from file: already have `onPickKeyFile` callback
- Export public key: copy to clipboard or share
- Per-server key selection in ServerEditDialog (dropdown of managed keys)

---

## Implementation Order (recommended)

```
Week 1: Phase 1 (health indicator, context visualizer, connection quality)
Week 2: Phase 2 (usage dashboard, cost estimation, notifications)  
Week 3: Phase 3 (command palette)
Week 4: Phase 4.3 + 4.4 (keyboard shortcuts, swipe gestures)
Week 5: Phase 5.1 + 5.2 (offline queue, CLAUDE.md editor)
Week 6: Phase 5.3 (SSH key management)
Week 7: Phase 4.1 + 4.2 (split view, image preview) — most complex
```

---

## Key Files to Modify

| File | Changes |
|------|---------|
| `shared/.../ui/App.kt` | New screens, state for new features |
| `shared/.../ui/TerminalScreen.kt` | Health dots, context bar, split view, palette trigger, swipe |
| `shared/.../session/SessionOrchestrator.kt` | Latency, offline queue, extended usage data |
| `shared/.../session/InputPromptDetector.kt` | Session state derivation |
| `shared/.../storage/AppSettings.kt` | New settings for all features |
| `shared/.../ui/SettingsScreen.kt` | New settings UI sections |
| `shared/.../ui/ServerEditDialog.kt` | SSH key selection |
| `shared-assets/terminal/terminal.html` | Image detection, gesture handling |
| `androidApp/.../MainActivity.kt` | Enhanced notifications, gestures |
| `desktopApp/.../Main.kt` | Key event interception, split JediTerm |

## New Files

| File | Purpose |
|------|---------|
| `shared/.../ui/UsageDashboardScreen.kt` | Token usage & cost dashboard |
| `shared/.../ui/CommandPalette.kt` | Quick actions overlay |
| `shared/.../session/CostCalculator.kt` | Token -> cost conversion |
| `shared/.../connection/SshKeyManager.kt` | Key generation & management |
| `shared/.../session/KeyboardShortcuts.kt` | Shortcut registry & handling |

---

## Verification

- **Health indicator:** Launch session, verify dot changes color when Claude is working vs idle vs waiting
- **Context visualizer:** Send large prompts, verify bar fills up and changes color
- **Connection quality:** Verify latency number updates, simulate high latency
- **Usage dashboard:** Check numbers match `ccusage` CLI output
- **Cost estimation:** Verify against Anthropic pricing page
- **Notifications:** Background app, wait for Claude to finish, verify notification
- **Command palette:** Test Ctrl+K, verify all actions work, fuzzy search
- **Split view:** Drag tab to split, verify both terminals render and receive input
- **Image preview:** Have Claude generate an image, verify preview appears
- **Keyboard shortcuts:** Test all default bindings, customize one, verify
- **Swipe gestures:** Test edge swipe between tabs on Android
- **Offline queue:** Disconnect WiFi, type messages, reconnect, verify delivery
- **CLAUDE.md editor:** Open, edit, save, verify file changed on remote
- **SSH keys:** Generate key, deploy to server, connect with key auth
