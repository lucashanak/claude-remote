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
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Claude Remote"
            packageVersion = "1.0.0"
            description = "Claude Code Remote Controller"

            macOS {
                bundleID = "com.clauderemote.desktop"
            }
        }
    }
}
