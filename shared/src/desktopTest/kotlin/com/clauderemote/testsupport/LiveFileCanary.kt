package com.clauderemote.testsupport

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.EnumSet

/**
 * Canary for tests that run server-side scripts (hook merge, hook-ev.sh,
 * ManifestCommands) on the dev box that also hosts the LIVE sessions.
 *
 * What it checks, around a [guard]ed block, against [liveHome]:
 *  1. **Settings files** — existence + sha256 BEFORE and AFTER of `.claude/settings.json`,
 *     `.claude/settings.local.json` and, for every `.claude-remote/accounts/<a>/`,
 *     its `settings.json` and `settings.local.json`. Any change, creation or deletion
 *     fails. (Claude processes load the account ones via `CLAUDE_CONFIG_DIR`.)
 *  2. **Path set** — the set of paths under `.claude-remote` and
 *     `.cache/claude-remote`, recursively (without their top-level `logs/`, which grow live)
 *     BEFORE and AFTER. A new or vanished path fails: a test that leaks a tombstone
 *     (`forgotten.d/<name>`), a marker (`hookev.installed`) or a manifest into the
 *     live home is caught even when the file is empty or has no token in it.
 *  3. **Token** — the block must embed a UNIQUE [guard] token in its input; after the
 *     block the token must appear in none of the live files' CONTENT (UTF-8, UTF-16LE
 *     and UTF-16BE) and in none of the live PATH names, searched recursively
 *     (including `logs/`). Files named `*.credentials.json` are never opened; only
 *     their path is looked at.
 *
 * Content and path identity, not mtime: the live files are legitimately appended to
 * by other things (sessions append to the events file, logs grow), so an mtime check
 * would raise false alarms and teach people to ignore it. When a check 1/2 failure is
 * caused by something other than the test (a live Claude rewriting its own
 * settings, the app creating a lock or socket), the message says which path and that
 * it may be a false alarm; the token check never is.
 *
 * Isolation of the block itself: [assertIsolated] runs BEFORE the block, and the
 * block receives a [GuardEnv] with the process environment to use for every child
 * process (`HOME` and `TMUX_TMPDIR` inside the temp root, no `TMUX`/`TMUX_PANE`).
 */
internal class LiveFileCanary(
    private val liveHome: File = File(System.getProperty("user.home")),
) {
    /** Environment for child processes started by a guarded block. */
    class GuardEnv(val home: File, val tmuxTmpDir: File) {
        val variables: Map<String, String> =
            mapOf("HOME" to home.absolutePath, "TMUX_TMPDIR" to tmuxTmpDir.absolutePath)
        val removed: Set<String> = setOf("TMUX", "TMUX_PANE")

        /** Make [pb] run with the isolated environment and return it. */
        fun applyTo(pb: ProcessBuilder): ProcessBuilder {
            removed.forEach { pb.environment().remove(it) }
            pb.environment().putAll(variables)
            return pb
        }
    }

    private val roots get() = listOf(File(liveHome, ".claude-remote"), File(liveHome, ".cache/claude-remote"))

    private fun watchedSettings(): List<File> {
        val accounts = File(liveHome, ".claude-remote/accounts").listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        return (listOf(File(liveHome, ".claude")) + accounts).flatMap { dir ->
            listOf(File(dir, "settings.json"), File(dir, "settings.local.json"))
        }
    }

    private class Snapshot(val settings: Map<String, String?>, val paths: Set<String>)

    private fun snapshot() = Snapshot(
        settings = watchedSettings().associate { it.path to sha256OrNull(it) },
        paths = roots.flatMap { walk(it, includeLogs = false) }.map { it.toString() }.toSet(),
    )

    /**
     * Run [block] (which must use [GuardEnv.home] as its HOME for any child process and
     * embed [token] in its input), then verify nothing live was touched. Isolation is
     * asserted BEFORE the block so a misconfigured test never gets to run.
     */
    fun <T> guard(token: String, testHome: File, tempRoot: File, block: (GuardEnv) -> T): T {
        assertIsolated(testHome, tempRoot)
        val tmux = File(tempRoot, "t").apply { mkdirs() }
        val before = snapshot()
        val result = block(GuardEnv(testHome, tmux))
        val after = snapshot()

        for (path in (before.settings.keys + after.settings.keys).sorted()) {
            val b = before.settings[path]; val a = after.settings[path]
            if (b != a) {
                val what = when { b == null -> "created"; a == null -> "deleted"; else -> "modified" }
                throw AssertionError(
                    "canary: live settings file $path was $what during the test. If no test code touches it this can be a " +
                        "FALSE ALARM (a live Claude process rewrites its own settings): rerun to confirm.",
                )
            }
        }
        for (p in (after.paths - before.paths).sorted()) {
            throw AssertionError(
                "canary: $p did not exist before the test and exists now (possible false alarm if the live app or a " +
                    "live Claude created it: rerun to confirm).",
            )
        }
        for (p in (before.paths - after.paths).sorted()) {
            throw AssertionError(
                "canary: $p existed before the test and is gone now (possible false alarm if the live app removed it: " +
                    "rerun to confirm).",
            )
        }

        val needles = needles(token)
        val everything = roots.flatMap { walk(it, includeLogs = true) }
        for (p in everything) {
            if (p.toString().contains(token)) throw AssertionError("canary: test token found in the NAME of live path $p")
        }
        val contentTargets = (everything.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !isCredentials(it) }
            .map { it.toFile() } + watchedSettings()).distinct()
        for (f in contentTargets) {
            if (containsToken(f, needles)) throw AssertionError("canary: test token found in LIVE file ${f.path}")
        }
        return result
    }

    /**
     * Test HOME must lie inside the test's temp root, and the temp root must be neither
     * inside the live home nor an ancestor of it (`/`, `/home`): then a "test" HOME could
     * be the live one's parent or sibling and the isolation proves nothing.
     */
    fun assertIsolated(testHome: File, tempRoot: File) {
        val home = testHome.canonicalFile.toPath()
        val root = tempRoot.canonicalFile.toPath()
        val live = liveHome.canonicalFile.toPath()
        if (!home.startsWith(root)) throw AssertionError("canary: HOME ${testHome.path} is not under temp root ${tempRoot.path}")
        if (home == live || live.startsWith(home)) throw AssertionError("canary: HOME ${testHome.path} is or contains the live home")
        if (root.startsWith(live)) throw AssertionError("canary: temp root ${tempRoot.path} is inside the live home")
        if (live.startsWith(root)) throw AssertionError("canary: temp root ${tempRoot.path} is an ancestor of the live home")
    }

    private fun isCredentials(p: Path) = p.fileName.toString().endsWith(".credentials.json")

    /** All entries (dirs and files, symlinks not followed, root itself excluded) below [root]. */
    private fun walk(root: File, includeLogs: Boolean): List<Path> {
        if (!root.isDirectory) return emptyList()
        val rootPath = root.toPath()
        val out = ArrayList<Path>()
        Files.walkFileTree(rootPath, EnumSet.noneOf(FileVisitOption::class.java), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != rootPath) {
                    if (!includeLogs && dir.parent == rootPath && dir.fileName.toString() == "logs") return FileVisitResult.SKIP_SUBTREE
                    out.add(dir)
                }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                out.add(file)
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return out
    }

    private fun sha256OrNull(f: File): String? = try {
        if (!f.isFile) null else MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    } catch (_: IOException) { null }

    private fun needles(token: String): List<ByteArray> = listOf(
        token.toByteArray(Charsets.UTF_8),
        token.toByteArray(Charsets.UTF_16LE),
        token.toByteArray(Charsets.UTF_16BE),
    ).filter { it.isNotEmpty() }

    /** Streaming byte search (files can be tens of MB); unreadable/missing = cannot contain it. */
    private fun containsToken(f: File, needles: List<ByteArray>): Boolean {
        if (needles.isEmpty() || !f.isFile) return false
        val carryLen = needles.maxOf { it.size } - 1
        try {
            f.inputStream().buffered(1 shl 16).use { ins ->
                val buf = ByteArray(1 shl 16)
                var carry = ByteArray(0)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) return false
                    val window = carry + buf.copyOf(n)
                    if (needles.any { indexOf(window, it) >= 0 }) return true
                    carry = window.copyOfRange(maxOf(0, window.size - carryLen), window.size)
                }
            }
        } catch (_: IOException) {
            return false
        }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    companion object {
        fun newToken(): String = "crit-canary-" + java.util.UUID.randomUUID()
    }
}
