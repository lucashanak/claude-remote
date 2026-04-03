# Platform Specifics

Claude Remote targets Android and Desktop (macOS, Windows, Linux) via Kotlin Multiplatform. This document covers platform-specific implementations and features.

## Kotlin Multiplatform `expect/actual`

Three interfaces require platform-specific implementations:

| Interface | Android | Desktop |
|-----------|---------|---------|
| `MoshManager` | Cross-compiled binary via ProcessBuilder | System `mosh` command |
| `PlatformPreferences` | `SharedPreferences` | Java `Properties` file |
| `PlatformBackHandler` | `OnBackPressedCallback` | No-op |

## Android

### Entry Point

**File:** `androidApp/src/main/kotlin/.../MainActivity.kt`

`MainActivity` extends `ComponentActivity` and hosts:
- Compose UI (via `setContent {}`)
- WebView for xterm.js terminal
- JavaScript bridge (`TerminalJSBridge`)
- Biometric authentication check
- System keyboard suppression (optional)

### App Initialization

**File:** `androidApp/src/main/kotlin/.../App.kt`

`Application` subclass that:
- Initializes `FileLogger` with app version and files directory
- Sets up crash handler (`Thread.setDefaultUncaughtExceptionHandler`) to log crashes
- Hooks `FileLogger.platformLog` to Android's `Log.d()` / `Log.e()`

### KeepAliveService

**File:** `androidApp/src/main/kotlin/.../KeepAliveService.kt`

Foreground service that prevents Android from killing the app during active SSH sessions:

```kotlin
class KeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    fun start(context: Context, description: String) {
        // Acquire PARTIAL_WAKE_LOCK (4-hour timeout)
        // Show persistent notification: "Claude Remote - Active session"
        // Start as foreground service
    }

    fun stop(context: Context) {
        // Release wake lock
        // Stop foreground service
    }

    fun updateDescription(description: String) {
        // Update notification text (e.g., session name)
    }
}
```

Started when a session becomes `ACTIVE`, stopped when all sessions end.

### Biometric Lock

Optional fingerprint/Face ID protection using `androidx.biometric`:

```kotlin
// In MainActivity.onCreate()
if (prefs.getBoolean("biometric_lock_enabled", false)) {
    val biometricPrompt = BiometricPrompt(
        this, executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result) {
                biometricUnlocked = true
                // Show app content
            }
            override fun onAuthenticationFailed() {
                // Show retry or exit
            }
        }
    )
    biometricPrompt.authenticate(promptInfo)
}
```

The UI is hidden behind a lock screen until authentication succeeds.

### Overlay Manager

**File:** `androidApp/src/main/kotlin/.../OverlayManager.kt`

Manages the touch keyboard overlay (`overlay-ui/overlay.html`) that provides quick access to terminal keys (Ctrl, Tab, Esc, arrows, y/n) without the system keyboard.

### Android Manifest

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

| Permission | Purpose |
|------------|---------|
| `INTERNET` | SSH/Mosh connections |
| `FOREGROUND_SERVICE` | KeepAliveService |
| `WAKE_LOCK` | Prevent CPU sleep during sessions |
| `POST_NOTIFICATIONS` | Foreground service notification |
| `REQUEST_INSTALL_PACKAGES` | Self-update from GitHub Releases |

### Build Configuration

- `applicationId`: `com.clauderemote`
- `minSdk`: 26 (Android 8.0)
- `targetSdk`: 35
- ABI filters: `arm64-v8a`, `x86_64`
- Signing: release keystore from CI secrets

### Android-Specific Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `suppressSystemKeyboard` | false | Hide system keyboard, use overlay only |
| `hapticFeedback` | true | Vibrate on overlay key press |
| `biometricLockEnabled` | false | Require fingerprint to open app |

### Storage

`PlatformPreferences` wraps `SharedPreferences` with namespace `"claude_remote"`:

```kotlin
actual class PlatformPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("claude_remote", MODE_PRIVATE)

    actual fun getString(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    actual fun putString(key: String, value: String) =
        prefs.edit().putString(key, value).apply()

    // ... getInt, putInt, getBoolean, putBoolean
}
```

### Mosh (Android)

Uses a cross-compiled `mosh-client` binary stored in `jniLibs/arm64-v8a/`:

1. SSH exec `mosh-server new` on the remote server
2. Parse `MOSH CONNECT <port> <key>` from output
3. Launch `ProcessBuilder(moshBinaryPath, host, port)` with `MOSH_KEY` env var
4. Stream output from process stdout

Built by `build-mosh.sh` which cross-compiles protobuf, OpenSSL, ncurses, and mosh for Android NDK arm64-v8a.

## Desktop

### Entry Point

**File:** `desktopApp/src/main/kotlin/.../Main.kt`

Compose Desktop `application {}` block with a `Window`:

```kotlin
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Claude Remote",
        icon = painterResource("icon.png")
    ) {
        App()  // Shared Compose UI from commonMain
    }
}
```

### JavaFX WebView Integration

Desktop uses JavaFX `WebView` embedded in Compose via `SwingPanel` (Swing interop):

```
Compose Desktop ──SwingPanel──► JPanel ──► JFXPanel ──► JavaFX WebView ──► xterm.js
```

This requires JavaFX dependencies with per-platform classifiers:

```kotlin
// In desktopApp/build.gradle.kts
val fxClassifier = when {
    os.isMacOsX && arch == "aarch64" -> "mac-aarch64"
    os.isMacOsX -> "mac"
    os.isWindows -> "win"
    arch == "aarch64" -> "linux-aarch64"
    else -> "linux"
}
implementation("org.openjfx:javafx-web:21.0.5:$fxClassifier")
```

### Desktop Terminal Bridge

**File:** `desktopApp/src/main/kotlin/.../DesktopTerminalBridge.kt`

Implements the same `window.Android` interface that the terminal JS expects:

```kotlin
class DesktopTerminalBridge {
    var onInput: ((String) -> Unit)? = null
    var onReady: ((Int, Int) -> Unit)? = null

    // Called from JavaScript
    fun onTerminalInput(data: String) = onInput?.invoke(data)
    fun onTerminalReady(cols: Int, rows: Int) = onReady?.invoke(cols, rows)
    fun openUrl(url: String) = Desktop.getDesktop().browse(URI(url))
}
```

### Native Distribution

Configured in `desktopApp/build.gradle.kts`:

```kotlin
compose.desktop {
    application {
        mainClass = "com.clauderemote.MainKt"
        nativeDistributions {
            targetFormats(Dmg, Deb, Msi)
            packageName = "ClaudeRemote"
            packageVersion = "1.0.0"  // Updated by CI

            macOS {
                bundleID = "com.clauderemote.desktop"
                iconFile.set(project.file("src/main/resources/icon.icns"))
            }
            windows {
                menuGroup = "Claude Remote"
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
            linux {
                appCategory = "Development"
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }
    }
}
```

### Storage (Desktop)

`PlatformPreferences` stores settings in `~/.claude-remote/settings.properties`:

```kotlin
actual class PlatformPreferences {
    private val file = File(System.getProperty("user.home"), ".claude-remote/settings.properties")
    private val props = Properties()

    init {
        if (file.exists()) {
            file.inputStream().use { props.load(it) }
        }
    }

    actual fun putString(key: String, value: String) {
        props.setProperty(key, value)
        file.parentFile.mkdirs()
        file.outputStream().use { props.store(it, null) }
    }
    // ...
}
```

### Mosh (Desktop)

Uses system-installed `mosh` command directly:

```kotlin
val process = ProcessBuilder(
    "mosh",
    "--ssh=ssh -p ${server.port}",
    "${server.username}@${server.host}",
    "--", startupCommand
).start()
```

Requires `mosh` to be installed on the user's machine (e.g., `brew install mosh`, `apt install mosh`).

### Desktop-Specific Behavior

| Feature | Behavior |
|---------|----------|
| Back button | No-op (`PlatformBackHandler` is empty) |
| Keep-alive | Not needed (desktop apps don't get killed) |
| Biometric | Not implemented (desktop) |
| Keyboard overlay | Not used (physical keyboard assumed) |
| System keyboard | Always available |
| File browser | Uses system file dialogs |

## Shared Code Distribution

Approximate code distribution across modules:

| Module | Files | Scope |
|--------|-------|-------|
| `shared/commonMain` | ~32 | All business logic, UI, models |
| `shared/androidMain` | 3 | `expect/actual` implementations |
| `shared/desktopMain` | 3 | `expect/actual` implementations |
| `androidApp` | 4 | Activity, services, app init |
| `desktopApp` | 2 | Window, JavaFX bridge |

The vast majority of code (~90%) is shared. Platform-specific code is minimal and focused on:
- Storage mechanism (SharedPreferences vs Properties)
- WebView hosting (Android WebView vs JavaFX WebView)
- System services (foreground service, biometric, overlay)
- Mosh binary execution strategy
