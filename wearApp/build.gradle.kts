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
        applicationId = "com.clauderemote.wear"
        // Wear OS 3+ only — androidx.wear.compose:compose-material3 requires it.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
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
}
