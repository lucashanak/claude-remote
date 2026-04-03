# Building & Release

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| JDK | 17+ | Kotlin compilation |
| Gradle | 8.11+ | Build system (wrapper included) |
| Android SDK | API 35 | Android compilation target |
| Android Build Tools | 35.0.0 | APK building |
| NDK (optional) | r26+ | Cross-compiling mosh-client |

## Local Development

### Initial Setup

```bash
# Clone and download terminal assets
git clone <repo-url>
cd claude-remote
./setup-assets.sh   # Downloads xterm.js, addons, CSS from CDN
```

`setup-assets.sh` downloads into `shared-assets/terminal/` and copies to both `androidApp/src/main/assets/terminal/` and `desktopApp/src/main/resources/terminal/`.

### Build Commands

```bash
# Shared module (checks compilation for all targets)
./gradlew :shared:build

# Android debug APK
./gradlew :androidApp:assembleDebug

# Android release APK (requires signing config)
./gradlew :androidApp:assembleRelease

# Desktop — platform-specific package
./gradlew :desktopApp:packageDmg    # macOS
./gradlew :desktopApp:packageMsi    # Windows
./gradlew :desktopApp:packageDeb    # Linux

# Desktop — run directly
./gradlew :desktopApp:run
```

### Cross-Compile Mosh (Android)

Builds `mosh-client` binary for `arm64-v8a`:

```bash
./build-mosh.sh /path/to/android-ndk

# Builds: protobuf, OpenSSL 3.2, ncurses 6.4, mosh-client
# Output: androidApp/src/main/jniLibs/arm64-v8a/libmosh.so
```

## Project Modules

Defined in `settings.gradle.kts`:

```kotlin
include(":shared")        // KMM shared code
include(":androidApp")    // Android application
include(":desktopApp")    // Desktop application (JVM)
```

### Gradle Configuration

**Root `build.gradle.kts`** — Plugin declarations only:
- `kotlin("multiplatform")` 2.1.0
- `kotlin("plugin.serialization")` 2.1.0
- `org.jetbrains.compose` 1.7.3
- `com.android.application` 8.7.3

**`gradle.properties`**:
```properties
org.gradle.jvmargs=-Xmx2048M
android.useAndroidX=true
org.gradle.configuration-cache=true
kotlin.code.style=official
```

### shared/build.gradle.kts

Three targets: `androidTarget`, `jvm("desktop")`, with shared dependencies:

| Dependency | Purpose |
|------------|---------|
| `compose.material3` | UI components |
| `compose.runtime` | Compose runtime |
| `kotlinx-coroutines-core` 1.9.0 | Async operations |
| `kotlinx-serialization-json` 1.7.3 | JSON serialization |
| `jsch` 0.2.21 | SSH client |

Desktop-specific: `javafx-web` 21.0.5 (per-platform classifier).

### androidApp/build.gradle.kts

- `applicationId`: `com.clauderemote`
- `minSdk`: 26, `targetSdk`: 35
- ABI filters: `arm64-v8a`, `x86_64`
- Additional deps: `androidx.biometric` 1.1.0

### desktopApp/build.gradle.kts

- `mainClass`: `com.clauderemote.MainKt`
- Native distribution targets: DMG, DEB, MSI
- macOS `bundleID`: `com.clauderemote.desktop`
- Linux `appCategory`: `Development`
- Includes `jbsdiff` 1.0 for delta patches

## CI/CD Pipelines

### Android Build (`.github/workflows/build-android.yml`)

Triggered on push to `main`.

```
1. Checkout code
2. Setup JDK 17, Gradle, Android SDK
3. Run setup-assets.sh (download xterm.js)
4. Determine version:
   - Read latest git tag (vX.Y.Z)
   - Auto-increment patch number
   - Compute versionCode (major*10000 + minor*100 + patch)
5. Build release APK (assembleRelease)
6. Sign APK:
   - Decode keystore from ANDROID_KEYSTORE secret (base64)
   - zipalign -v 4
   - apksigner sign --ks release.keystore --ks-key-alias clauderemote
   - apksigner verify
7. Compute SHA-256 hash
8. Generate bsdiff delta patch:
   - Download previous version APK from GitHub Releases
   - Create .bspatch file
   - Only include if patch < 80% of full APK size
9. Create/update GitHub Release:
   - Tag: vX.Y.Z
   - Assets: APK, .bspatch (if applicable), SHA-256
```

### Desktop Build (`.github/workflows/build-desktop.yml`)

Triggered on push to `main`, runs after Android build.

Three parallel jobs + release:

```
build-macos:
  - macOS runner
  - ./gradlew :desktopApp:packageDmg
  - Upload ClaudeRemote-macOS.dmg

build-windows:
  - Windows runner
  - ./gradlew :desktopApp:packageMsi
  - Upload ClaudeRemote-windows.msi

build-linux:
  - Ubuntu runner
  - ./gradlew :desktopApp:packageDeb
  - Upload ClaudeRemote-linux.deb

release:
  - Wait for all build jobs + Android workflow
  - Download all artifacts
  - Upload to GitHub Release (same tag as Android)
```

## Versioning

Semantic versioning derived from git tags:

| Tag | versionName | versionCode |
|-----|-------------|-------------|
| `v1.0.0` | `1.0.0` | `10000` |
| `v1.2.3` | `1.2.3` | `10203` |
| `v2.0.0` | `2.0.0` | `20000` |

Auto-increment: CI reads latest tag, bumps patch by 1, creates new tag.

## Delta Updates

The Android build generates binary diff patches using `bsdiff`:

1. Previous APK downloaded from the last GitHub Release
2. `bsdiff old.apk new.apk patch.bspatch`
3. Patch included in release only if `patch_size < 0.8 * apk_size`
4. Client (`UpdateChecker`) can chain up to 3 patches for multi-version jumps
5. Full APK fallback if patch chain breaks or exceeds size threshold

## APK Signing

GitHub Secrets required:

| Secret | Purpose |
|--------|---------|
| `ANDROID_KEYSTORE` | Base64-encoded `.keystore` file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore and key password |

Key alias: `clauderemote`
