plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.library")
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("com.github.mwiede:jsch:0.2.21")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation("io.sigpipe:jbsdiff:1.0")
                implementation("org.json:json:20240303")
                implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.30.0")
                // JetBrains markdown parser (same one the renderer uses,
                // transitively) — used directly to convert message markdown to
                // HTML for rich clipboard copy.
                implementation("org.jetbrains:markdown:0.7.3")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
                implementation("com.github.k2-fsa:sherpa-onnx:1.13.1")
                // On-device wake-word engine (offline; AccessKey is a license
                // check only — no audio leaves the device).
                implementation("ai.picovoice:porcupine-android:3.0.2")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                // Virtual-time test dispatchers: the session layer is built on
                // delays (quiescence windows, redraw coalescing, poll loops).
                // runTest fast-forwards them so timing tests stay instant.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
    }

    // ---- Integration test layer: REAL sshd + REAL tmux + the real embedded
    // restore.sh/drift.sh. Sources live in src/desktopIntegrationTest/kotlin.
    //
    // Deliberately its OWN compilation rather than a filtered slice of
    // desktopTest: each test forks an sshd and a tmux server and takes seconds,
    // so it must never be pulled into (or slow down) the fast pure-logic lane.
    // `:shared:desktopTest` compiles only src/{common,desktop}Test, so it can't
    // see these classes at all — the separation is structural, not a filter
    // someone can forget to apply.
    //
    // associateWith(main) is what makes this worth doing: it grants `internal`
    // visibility into :shared, so the tests drive the actual production
    // TmuxProbes / ConnectionRegistry / INSTALL_RESTORE_COMMAND instead of
    // reimplementing them.
    val desktopTarget = targets.getByName("desktop") as
        org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    desktopTarget.compilations.create("integrationTest") {
        associateWith(desktopTarget.compilations.getByName("main"))
        defaultSourceSet.dependencies {
            implementation(kotlin("test"))
            // Pinned to the JUnit4 runner explicitly (the Test task below calls
            // useJUnit()) so kotlin("test") can't resolve to a framework the
            // hand-registered task isn't configured for.
            implementation(kotlin("test-junit"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("com.github.mwiede:jsch:0.2.21")
        }
    }
}

// TmuxTargetSyntaxTest SCANS the Kotlin sources of every module at runtime, which
// Gradle cannot see — they are not on the test classpath as files. With the build
// cache on (org.gradle.caching), desktopTest could be restored FROM-CACHE while a
// scanned source has changed, so the guard would silently not run: exactly the
// failure mode it exists to prevent. Declaring the scanned trees as inputs makes a
// change to any of them invalidate the task.
tasks.named<Test>("desktopTest") {
    inputs.files(
        rootProject.layout.projectDirectory.let { root ->
            listOf("shared/src", "androidApp/src", "wearApp/src", "desktopApp/src")
                .map { root.dir(it) }
                .filter { it.asFile.isDirectory }
                .map { project.fileTree(it) { include("**/*.kt") } }
        }
    )
        .withPropertyName("tmuxTargetScanSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .ignoreEmptyDirectories()
}

// `./gradlew :shared:integrationTest` — the slow lane. Not wired into `check`
// or `allTests`: it needs sshd/tmux/jq on the box, so it stays opt-in.
val integrationTest by tasks.registering(Test::class) {
    val compilation = (kotlin.targets.getByName("desktop") as
        org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget)
        .compilations.getByName("integrationTest")

    group = "verification"
    description = "Integration tests against a real sshd + real tmux server (slow)."

    testClassesDirs = compilation.output.classesDirs
    classpath = compilation.output.allOutputs + compilation.runtimeDependencyFiles
    dependsOn(compilation.compileTaskProvider)

    useJUnit()
    // Real external processes: serial, and never skipped. These assert against
    // the ENVIRONMENT (a live sshd, a real tmux server, the installed restore.sh)
    // rather than against their declared inputs, so reusing a previous result is
    // always wrong.
    //
    // Both switches are needed: `upToDateWhen { false }` only defeats the
    // up-to-date check, and a task that is out-of-date can still be restored
    // FROM-CACHE once org.gradle.caching is on — which would "pass" this lane
    // without ever starting sshd.
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }

    // HARD SAFETY INVARIANT. This dev box runs the user's live Claude sessions
    // on the DEFAULT tmux socket, and the tests themselves execute inside one of
    // those sessions — so the JVM inherits TMUX/TMUX_PANE. A set TMUX
    // *overrides* TMUX_TMPDIR, which means a single stray `tmux kill-server`
    // would destroy real work. Strip them from the test JVM here, and the
    // fixture strips them again from every child process it spawns.
    environment.remove("TMUX")
    environment.remove("TMUX_PANE")

    // The CI workflow uploads these paths on failure; keep them where it looks.
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/integrationTest"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/integrationTest"))
    binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/integrationTest/binary"))

    // sshd/tmux/restore logs land here so a CI-only failure is diagnosable.
    val logDir = layout.buildDirectory.dir("integration-logs").get().asFile
    systemProperty("clauderemote.integration.logDir", logDir.absolutePath)

    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }

    val resultsDir = layout.buildDirectory.dir("test-results/integrationTest").get().asFile

    doFirst {
        // Start from empty so the CI artifact holds only THIS run's diagnostics,
        // and so the no-results check below cannot be satisfied by a stale XML
        // from an earlier run.
        logDir.deleteRecursively()
        logDir.mkdirs()
        resultsDir.deleteRecursively()
        // Fail LOUDLY on a box without the infrastructure instead of "passing"
        // by running nothing — a silent zero-test success is the failure mode
        // this whole lane exists to prevent.
        val missing = mutableListOf<String>()
        if (!File("/usr/sbin/sshd").canExecute()) missing += "/usr/sbin/sshd (openssh-server)"
        val path = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        for (bin in listOf("tmux", "jq", "ssh-keygen", "flock")) {
            if (path.none { File(it, bin).canExecute() }) missing += bin
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "integrationTest cannot run — missing: ${missing.joinToString(", ")}. " +
                    "Install them (see .github/workflows/tests.yml) or run :shared:desktopTest instead."
            )
        }
    }

    doLast {
        val xml = resultsDir.listFiles { f: File -> f.name.endsWith(".xml") }
        if (xml == null || xml.isEmpty()) {
            throw GradleException(
                "integrationTest produced NO test results — the source set compiled but nothing ran. " +
                    "That is a wiring failure, not a pass."
            )
        }
    }
}

android {
    namespace = "com.clauderemote.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
