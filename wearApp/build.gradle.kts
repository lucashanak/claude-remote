plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.clauderemote.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clauderemote.android"
        // Wear OS 3+ only — androidx.wear.compose:compose-material3 requires it.
        minSdk = 30
        // Wear-side stays on 34 (Wear OS 5): targetSdk 35 blocks reading the
        // system "clockwork_sysui_package" setting, which TileService.getUpdater()
        // .requestUpdate() needs — on 35 every tile refresh threw
        // "only readable to apps with targetSdkVersion <= 34" (caught, but the
        // Tile then never refreshed on a phone push). 34 is the right target for
        // a Wear companion and restores instant tile updates.
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    // Fixed debug signing, committed to the repo. Without this, AGP
    // auto-generates a NEW random debug keystore per build machine — and
    // since GitHub Actions runners are fresh/ephemeral every run, every
    // release would sign with a different key, so `adb install -r`
    // (and the watch's own self-update) failed with
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE on every single update (confirmed
    // on a real device). This debug key has zero security value — it's
    // deliberately checked in so every build (CI or local) signs
    // identically.
    signingConfigs {
        getByName("debug") {
            storeFile = file("wear-debug.keystore")
            storePassword = "android"
            keyAlias = "weardebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        // AGP 8.7.3's "lint vital" pass crashes on this Kotlin 2.1 project
        // (NonNullableMutableLiveDataDetector hits an Analysis-API version
        // mismatch inside the lint tool itself — not a finding about our
        // code). checkReleaseBuilds only disables the automatic gate tied to
        // assembleRelease; `./gradlew :wearApp:lint` still runs on demand.
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Plain AndroidX Compose (not JetBrains Compose Multiplatform, not used by
    // `shared`) — Wear Compose isn't offered as a KMP artifact. Deliberately
    // no `project(":shared")` dependency: the watch talks to the phone only
    // through a small JSON message protocol over the Data Layer, so it has no
    // need to pull `shared`'s SSH/terminal/Compose-Multiplatform dependency
    // graph into a resource-constrained watch APK.
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    // Wearable Data Layer — talks to the phone app (androidApp's
    // PhoneWearService). Version pinned against Google's Maven metadata
    // directly (dl.google.com/.../maven-metadata.xml — ground truth).
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // RemoteInputIntentHelper — launches the watch's native voice/keyboard
    // input screen for the reply flow (mirrors the phone's own
    // ActivityResultContracts.StartActivityForResult + RecognizerIntent
    // pattern used elsewhere for dictation).
    implementation("androidx.wear:wear-input:1.2.0")
    // Soniox streaming STT/TTS over WebSocket (same version as `shared`).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Wear OS Tile (WearTileService) — the glanceable dlaždice. Tiles render
    // in the system process from a serialized ProtoLayout, so this is the
    // Java-builder ProtoLayout stack, NOT Compose. tiles 1.4.x targets the
    // protolayout 1.2.x line, so all four are pinned to that matched set;
    // protolayout-material provides the Text/Typography helpers used by the
    // layout (the tiles 1.1-era `tiles-material` is superseded by it).
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-material:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-expression:1.2.1")
    // Futures.immediateFuture for the onTileRequest/onResourcesRequest returns
    // (tiles ships only the ListenableFuture stub, not the full Futures API).
    implementation("com.google.guava:guava:33.3.1-android")
}
