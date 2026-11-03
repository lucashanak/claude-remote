plugins {
    kotlin("multiplatform") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
}

// HARD SAFETY INVARIANT for EVERY Test task of EVERY module (shared desktopTest and
// integrationTest, androidApp/desktopApp/wearApp/terminal-* unit tests): this dev box runs the
// user's live Claude sessions on the DEFAULT tmux socket, and the tests themselves execute
// inside one of those sessions, so the Gradle-started JVM inherits TMUX/TMUX_PANE/HOME.
//  - A set TMUX *overrides* TMUX_TMPDIR; without TMUX the default socket is
//    `$TMUX_TMPDIR/tmux-UID/default`, and with TMUX_TMPDIR unset that is the LIVE server. So both
//    TMUX variables are stripped and TMUX_TMPDIR points at a private directory. Its path must stay
//    short (sun_path <= 107 bytes: `<dir>/tmux-<uid>/default`), so it lives under
//    `<root>/build/t/tmux` and falls back to a hashed directory in java.io.tmpdir when the checkout
//    is too deep for that.
//  - `scripts/test-shims` is put first on PATH: its `tmux` refuses the live socket even for a child
//    that clears or rewrites its environment (see scripts/test-shims/tmux for what it cannot stop).
//  - HOME and the JVM's `user.home` (Java ignores $HOME) point at `build/t/home/<project>-<task>`, a
//    directory of its own per Test task that is emptied before every run, so code that falls back to
//    the home directory (PlatformPreferences, EtManager, ...) cannot write the developer's real
//    `~/.claude-remote`, and neither state of an earlier run nor a task running in parallel can leak
//    into it. The REAL home is handed over as `clauderemote.live.home` for LiveFileCanary, which must
//    keep watching it.
val realUserHome: String = System.getProperty("user.home")
val sandboxDir = layout.buildDirectory.dir("t").get().asFile
val tmuxTmpDirPath: String = run {
    val short = File(sandboxDir, "tmux").absolutePath
    // "<dir>/tmux-<uid up to 10 digits>/default" must fit in sun_path (107 bytes).
    if (short.length + "/tmux-4294967295/default".length <= 107) short
    else {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(rootDir.absolutePath.toByteArray())
        File(System.getProperty("java.io.tmpdir"), "crit-tmux-" + digest.take(5).joinToString("") { "%02x".format(it) }).absolutePath
    }
}
val shimDirPath: String = File(rootDir, "scripts/test-shims").absolutePath

subprojects {
    tasks.withType<Test>().configureEach {
        val homePath = File(sandboxDir, "home/" + (project.path.removePrefix(":") + "-" + name).replace(':', '-')).absolutePath
        val tmuxPath = tmuxTmpDirPath // locals only: a doFirst lambda must not capture the script object (configuration cache)
        environment.remove("TMUX")
        environment.remove("TMUX_PANE")
        environment("TMUX_TMPDIR", tmuxTmpDirPath)
        environment("HOME", homePath)
        environment("PATH", shimDirPath + File.pathSeparator + (System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin"))
        // RealTranscriptHarnessTest reads (never writes) the developer's real transcripts and skips
        // without them; with `user.home` redirected it would skip silently, so hand it the real root.
        if (System.getenv("TRANSCRIPT_HARNESS_ROOT") == null) {
            environment("TRANSCRIPT_HARNESS_ROOT", "$realUserHome/.claude/projects")
        }
        systemProperty("user.home", homePath)
        systemProperty("clauderemote.live.home", realUserHome)
        systemProperty("clauderemote.test.tmuxTmpDir", tmuxTmpDirPath)
        systemProperty("clauderemote.test.shimDir", shimDirPath)
        // Environment is not a Gradle task input: without this a change of the isolation (or of the
        // shim) would leave an UP-TO-DATE/FROM-CACHE desktopTest that never ran under it.
        // Root-relative paths: an absolute path would keep two checkouts from sharing build-cache entries.
        val relativeToRoot = { path: String -> File(path).relativeToOrSelf(rootDir).path }
        inputs.property("testIsolation", listOf(tmuxTmpDirPath, shimDirPath, homePath).joinToString("|", transform = relativeToRoot))
        inputs.files(File(shimDirPath, "tmux")).withPropertyName("tmuxShim").withPathSensitivity(PathSensitivity.NONE)
        doFirst {
            File(tmuxPath).mkdirs()
            // Empties the sandbox home. Never File.deleteRecursively(): it follows links to directories, and a link
            // left in the sandbox home must be removed as a link, not emptied at its target.
            val start = File(homePath).toPath()
            if (java.nio.file.Files.exists(start, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                java.nio.file.Files.walkFileTree(start, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                        java.nio.file.Files.delete(file)
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(d: java.nio.file.Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                        if (exc != null) throw exc
                        java.nio.file.Files.delete(d)
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                })
            }
            File(homePath).mkdirs()
        }
    }
}
