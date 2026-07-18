plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.clauderemote.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clauderemote.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            // Extract native libs to disk (extractNativeLibs=true). The bundled
            // mosh/et clients are executed as real files from nativeLibraryDir;
            // with the modern default (libs mmap'd inside the APK) they aren't
            // on disk and ProcessBuilder can't exec them.
            useLegacyPackaging = true
        }
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Native PTY helper disabled until mosh integration is needed
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //     }
    // }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":terminal-view"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Wearable Data Layer — talks to the wearApp companion (session sync,
    // reply/approve messages). Version pinned against Google's Maven
    // metadata directly (ground truth), see wearApp/build.gradle.kts.
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
}
