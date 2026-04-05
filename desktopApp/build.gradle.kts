import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

repositories {
    google()
    mavenCentral()
    maven("https://jogamp.org/deployment/maven")
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // JCEF (Chromium) for terminal WebView — works on macOS/Windows/Linux
    implementation("me.friwi:jcefmaven:122.1.10")
}

// Copy shared terminal assets before build
tasks.register<Copy>("copySharedAssets") {
    from("${rootProject.projectDir}/shared-assets/terminal")
    into("src/main/resources/terminal")
}
tasks.named("processResources") { dependsOn("copySharedAssets") }

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
