import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // JediTerm — pure Swing terminal emulator (no WebView needed)
    implementation("org.jetbrains.jediterm:jediterm-core:3.64")
    implementation("org.jetbrains.jediterm:jediterm-ui:3.64")
}

compose.desktop {
    application {
        mainClass = "com.clauderemote.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)
            packageName = "Claude Remote"
            packageVersion = "1.0.0"
            description = "Claude Code Remote Controller"
            vendor = "Claude Remote"
            macOS {
                bundleID = "com.clauderemote.desktop"
                iconFile.set(project.file("src/main/resources/icon.png"))
            }

            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menuGroup = "Claude Remote"
                upgradeUuid = "e5a8b2c1-3d4f-4a6e-9b7c-8d2e1f0a3b5c"
                shortcut = true
                dirChooser = true
                perUserInstall = true
            }

            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
                debMaintainer = "clauderemote@example.com"
                appCategory = "Development"
            }
        }
    }
}
