import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

repositories {
    mavenCentral()
    maven("https://jogamp.org/deployment/maven")
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    // KCEF - Kotlin Chromium Embedded Framework for Compose Desktop WebView
    implementation("dev.datlag:kcef:2024.01.07.1")
}

compose.desktop {
    application {
        mainClass = "com.clauderemote.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Claude Remote"
            packageVersion = "1.0.0"
            description = "Claude Code Remote Controller"

            macOS {
                bundleID = "com.clauderemote.desktop"
                iconFile.set(project.file("src/main/resources/icon.icns"))
            }

            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }
    }
}
