package com.clauderemote.testsupport

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Canary for tests that run server-side scripts (hook merge, hook-ev.sh,
 * ManifestCommands) on the dev box that also hosts the LIVE sessions.
 *
 * [guard] runs a block and then — **also when the block throws** — verifies that nothing
 * under [liveHome] was touched:
 *
 *  1. **Fingerprint** — BEFORE and AFTER, the type and content of every entry under
 *     `~/.claude-remote`, `~/.cache/claude-remote` and `~/.claude`. Per path:
 *     - a directory, a symlink (its TARGET string — a symlink replaced by a regular file with the
 *       same bytes is a change) or any other node is recorded by type;
 *     - a regular file by type + SHA-256 of its content (files over [MAX_READ_BYTES]: size, mtime, inode);
 *     - `*.credentials.json` is NEVER opened: size + mtime + inode only;
 *     - files that are legitimately appended to by live processes (`logs/`, `events`) must still
 *       exist, must not shrink and, while they keep their inode, keep their first [HEAD_BYTES] bytes (a replacement
 *       by another inode that did not shrink is tolerated; over [MAX_READ_BYTES] only size and inode are compared);
 *     - files and trees that live processes rewrite all the time ([CONFIG_LIVE_TOP], [REMOTE_LIVE_NAMES],
 *       [REMOTE_LIVE_PREFIXES], [CACHE_LIVE_NAMES], measured on the live box) are only checked for existence and
 *       type; their subtrees are not walked (a small file in them is still searched for the token);
 *     - `plugins` is walked but only `hooks*.json` outside `marketplaces` is compared (by content): those files
 *       inject hooks into every live session; every other plugin file contributes just its path name to the token search;
 *     - temp/lock files (`*.tmp.*`, `*.lock`, `.drift.*`, `temp_subdir_*`) are not recorded (they come and go, and
 *       one that vanishes while the tree is walked is simply skipped) but are still searched for the token.
 *     `~/.claude.json` is a live-rewritten file: existence and type only.
 *     Anything created, deleted, retyped or modified fails — including a marker created in a config
 *     dir (`~/.claude/claude-remote-hookev.installed`) and writes through the account symlinks
 *     (`accounts/<a>/hooks` -> `~/.claude/hooks`).
 *  2. **Token** — the block must embed a UNIQUE [guard] token in its input; afterwards the token must
 *     appear in none of the walked files' CONTENT (UTF-8, UTF-16LE, UTF-16BE and base64 in each of its three
 *     alignments, which is how `hook-ev.sh` writes names to `events`; `*.credentials.json` excluded) nor in
 *     `~/.claude.json`, and in none of the walked PATH NAMES (including `logs/`).
 *  3. **tmux** — the socket file, and the sessions and clients of the live tmux server ([tmuxSocket]), read-only, before and after: a session
 *     that was there and is gone, or all clients gone, fails (a user closing a session at that moment is a FALSE ALARM).
 *  4. **Children** — every process started through [GuardEnv.start] and every process that carries the
 *     guard's `CRIT_CANARY_TAG` in its environment (a detached grandchild inherits it) is killed
 *     BEFORE the checks, so nothing can write after the check has passed.
 *
 * Content and type identity, not mtime: live files are legitimately touched by other things, so an
 * mtime check would raise false alarms and teach people to ignore it. When a failure may have been
 * caused by something other than the test (a live Claude rewriting its settings, the app creating a
 * lock), the message says so (FALSE ALARM); the token check never is.
 *
 * Isolation of the block itself: [assertIsolated] runs BEFORE the block, and the block receives a
 * [GuardEnv] with the process environment to use for every child process (`HOME` and `TMUX_TMPDIR`
 * inside the temp root, no `TMUX`/`TMUX_PANE`).
 *
 * If both the block and the verification fail, the verification failure is thrown and the block's
 * exception is attached as suppressed: a test that EXPECTS the block to throw cannot hide damage.
 *
 * [liveHome] defaults to the REAL home: Gradle redirects the JVM's `user.home` to `build/t/home`
 * and hands the real one over as `clauderemote.live.home`.
 */
internal class LiveFileCanary(
    private val liveHome: File = File(System.getProperty("clauderemote.live.home") ?: System.getProperty("user.home")),
    /** Socket of the live tmux server whose sessions and clients are compared (read-only queries); null = not watched. */
    private val tmuxSocket: File? = defaultTmuxSocket(),
    /** Test seam: called for every walked path after its attributes were read and before its content is read. */
    private val visitHook: (Path) -> Unit = {},
) {
    /** Environment for child processes started by a guarded block. */
    class GuardEnv(val home: File, val tmuxTmpDir: File, internal val tag: String = "") {
        val variables: Map<String, String> =
            mapOf("HOME" to home.absolutePath, "TMUX_TMPDIR" to tmuxTmpDir.absolutePath) +
                (if (tag.isEmpty()) emptyMap() else mapOf(TAG_VARIABLE to tag))
        val removed: Set<String> = setOf("TMUX", "TMUX_PANE")
        private val started = ArrayList<Process>()

        /** Make [pb] run with the isolated environment and return it. */
        fun applyTo(pb: ProcessBuilder): ProcessBuilder {
            removed.forEach { pb.environment().remove(it) }
            pb.environment().putAll(variables)
            return pb
        }

        /** Start [pb] isolated ([applyTo]) and remember it: the guard kills it (and its descendants) before verifying. */
        fun start(pb: ProcessBuilder): Process {
            val p = applyTo(pb).start()
            synchronized(started) { started.add(p) }
            return p
        }

        /** Kill everything this block started or left behind, then wait (bounded) for it to be gone. */
        internal fun reap() {
            val victims = LinkedHashSet<ProcessHandle>()
            val me = ProcessHandle.current().pid()
            synchronized(started) { started.toList() }.forEach { p ->
                p.toHandle().descendants().forEach { victims.add(it) }
                victims.add(p.toHandle())
            }
            if (tag.isNotEmpty()) victims.addAll(taggedProcesses(tag))
            victims.removeAll { it.pid() == me }
            // descendants were collected before their parents die and get re-parented
            victims.forEach { if (it.isAlive) it.destroyForcibly() }
            victims.forEach { h ->
                try { h.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { /* reported by the checks, if it matters */ }
            }
        }
    }

    /**
     * STRICT = type + content hash; APPEND = may only grow; PRESENCE = existence and type only;
     * NAMES = walked, nothing recorded, only the path name is searched for the token;
     * CREDENTIALS = size + mtime + inode, never opened; TRANSIENT = comes and goes, not recorded.
     */
    private enum class Tier { STRICT, APPEND, PRESENCE, NAMES, CREDENTIALS, TRANSIENT }

    private class Entry(
        val tier: Tier,
        val kind: String,
        val detail: String,
        val size: Long = -1,
        val headHash: String = "",
        /** Inode identity of an append-only file. */
        val key: String = "",
        /** An append-only file over [MAX_READ_BYTES]: never read, so no head hash. */
        val big: Boolean = false,
    )

    /** Sessions (`#{session_id} #{session_name}`) and clients of the live tmux server; [up] false = no answer. */
    private class TmuxState(val up: Boolean, val sessions: Set<String>, val clients: Set<String>)

    private class Snapshot(val entries: Map<String, Entry>, val tokenHits: List<String>, val tmux: TmuxState?, val socketPresent: Boolean?)

    /** Walk roots: path (relative to the live home) and whether it is a directory tree. */
    private val roots get() = listOf(".claude-remote", ".cache/claude-remote", ".claude").map { File(liveHome, it) }

    private fun relOf(p: Path): String = liveHome.toPath().relativize(p).toString().replace(File.separatorChar, '/')

    private fun tierOf(rel: String): Tier {
        val parts = rel.split('/')
        val name = parts.last()
        if (name.endsWith(".credentials.json")) return Tier.CREDENTIALS
        // appear and vanish by themselves (atomic-write temp files, lock files/dirs, the drift cron's scratch files,
        // the plugin installer's clone directories)
        if (name.contains(".tmp.") || name.endsWith(".tmp") || name.endsWith(".lock") || name.startsWith(".drift.") ||
            name.startsWith("temp_subdir_")
        ) return Tier.TRANSIENT
        return when {
            parts[0] == ".claude" -> if (parts.size >= 2) configTier(parts.drop(1)) else Tier.STRICT
            parts[0] == ".claude-remote" && parts.size >= 2 && parts[1] == "accounts" ->
                if (parts.size >= 4) configTier(parts.drop(3)) else Tier.STRICT
            parts[0] == ".claude-remote" && parts.size >= 2 -> when {
                parts[1] == "logs" -> Tier.APPEND
                parts.size == 2 && parts[1].endsWith(".log") -> Tier.APPEND
                // what the drift script keeps below its own output directories: names are still searched, nothing is recorded
                parts.size >= 3 && REMOTE_LIVE_PREFIXES.any { parts[1].startsWith(it) } -> Tier.NAMES
                parts[1] in REMOTE_LIVE_NAMES || REMOTE_LIVE_PREFIXES.any { parts[1].startsWith(it) } -> Tier.PRESENCE
                else -> Tier.STRICT
            }
            parts[0] == ".cache" && parts.size >= 3 -> when (parts[2]) {
                "events" -> Tier.APPEND
                in CACHE_LIVE_NAMES -> Tier.PRESENCE
                else -> Tier.STRICT
            }
            else -> Tier.STRICT
        }
    }

    /** Tier of an entry of a Claude config dir (`~/.claude` or `accounts/<a>`); [c] = path inside that dir. */
    private fun configTier(c: List<String>): Tier {
        val top = c[0]
        if (top in CONFIG_LIVE_TOP || CONFIG_LIVE_PREFIXES.any { top.startsWith(it) }) return Tier.PRESENCE
        return when (top) {
            // user skills are content that every live session loads; only the sync cache is live-written
            "skills" -> if (c.size >= 2 && c[1] == "synced") Tier.PRESENCE else Tier.STRICT
            // plugins: ~1 GB, only the files that inject hooks into live sessions are compared by content
            "plugins" -> when {
                c.size == 1 -> Tier.STRICT
                c[1] in PLUGINS_LIVE_NAMES -> Tier.PRESENCE
                c.last().startsWith("hooks") && c.last().endsWith(".json") && c[1] != "marketplaces" -> Tier.STRICT
                else -> Tier.NAMES
            }
            else -> Tier.STRICT
        }
    }

    /** [token] null = BEFORE snapshot (no token search); else AFTER (the token search piggybacks the read). */
    private fun snapshot(token: String?): Snapshot {
        val needles = if (token == null) emptyList() else needles(token)
        val entries = HashMap<String, Entry>()
        val hits = ArrayList<String>()
        val nofollow = LinkOption.NOFOLLOW_LINKS

        fun record(p: Path, attrs: BasicFileAttributes): FileVisitResult {
            visitHook(p)
            val rel = relOf(p)
            var tier = tierOf(rel)
            val key = p.toString()
            if (token != null && key.contains(token)) hits.add("canary: test token found in the NAME of live path $key")
            if (tier == Tier.TRANSIENT) {
                if (token != null && attrs.isRegularFile && attrs.size() <= MAX_READ_BYTES && readFile(p, needles, 0).found) {
                    hits.add("canary: test token found in LIVE file $p")
                }
                return if (attrs.isDirectory) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }
            // a symlink is always compared by its target, wherever it lies
            if (tier == Tier.NAMES && attrs.isSymbolicLink) tier = Tier.STRICT
            if (tier == Tier.NAMES) return FileVisitResult.CONTINUE
            when {
                attrs.isSymbolicLink -> entries[key] = Entry(tier, "symlink", try { Files.readSymbolicLink(p).toString() } catch (_: IOException) { "?" })
                attrs.isDirectory -> {
                    entries[key] = Entry(tier, "dir", "")
                    // a PRESENCE directory is not walked, except the drift script's output directories (names only)
                    return if (tier == Tier.PRESENCE && !REMOTE_LIVE_PREFIXES.any { rel.startsWith(".claude-remote/$it") }) FileVisitResult.SKIP_SUBTREE
                    else FileVisitResult.CONTINUE
                }
                attrs.isRegularFile -> {
                    val size = attrs.size()
                    when (tier) {
                        Tier.CREDENTIALS -> entries[key] = Entry(tier, "file", "$size|${attrs.lastModifiedTime().toMillis()}|${attrs.fileKey()}")
                        Tier.PRESENCE -> {
                            // content is live-written, so it is not compared, but a small one is still searched for the token
                            if (token != null && size <= PRESENCE_SCAN_BYTES) {
                                val r = readFile(p, needles, 0)
                                if (r.found) hits.add("canary: test token found in LIVE file $p")
                            }
                            entries[key] = Entry(tier, "file", "")
                        }
                        else -> {
                            val big = size > MAX_READ_BYTES
                            val r = if (big) null else readFile(p, needles, headBytes = if (tier == Tier.APPEND) minOf(size, HEAD_BYTES.toLong()).toInt() else 0)
                            if (r != null && r.vanished) return FileVisitResult.CONTINUE // gone between stat and open: as if it never was
                            if (r != null && r.found) hits.add("canary: test token found in LIVE file $p")
                            val fileKey = attrs.fileKey().toString()
                            entries[key] = when {
                                big -> Entry(tier, "file", "big|$size|${attrs.lastModifiedTime().toMillis()}|$fileKey", size, key = fileKey, big = true)
                                r == null || r.failed -> Entry(tier, "unreadable", "")
                                tier == Tier.APPEND -> Entry(tier, "file", "", size, r.headHash, key = fileKey)
                                else -> Entry(tier, "file", r.hash)
                            }
                        }
                    }
                }
                else -> entries[key] = Entry(tier, "other", "")
            }
            return FileVisitResult.CONTINUE
        }

        for (root in roots) {
            val rootPath = root.toPath()
            if (!Files.exists(rootPath, nofollow)) continue
            Files.walkFileTree(rootPath, EnumSet.noneOf(FileVisitOption::class.java), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir == rootPath) FileVisitResult.CONTINUE else record(dir, attrs)
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = record(file, attrs)
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    // a file that vanished between the directory listing and its stat (the drift cron's scratch files do
                    // this every minute) or an entry that is not recorded anyway must not appear as "created"
                    if (exc is NoSuchFileException || tierOf(relOf(file)).let { it == Tier.TRANSIENT || it == Tier.NAMES }) return FileVisitResult.CONTINUE
                    entries[file.toString()] = Entry(Tier.STRICT, "unreadable", "")
                    return FileVisitResult.CONTINUE
                }
            })
        }
        // ~/.claude.json is rewritten by every live Claude: existence and type only (+ the token scan).
        val cj = File(liveHome, ".claude.json").toPath()
        if (Files.exists(cj, nofollow)) {
            val attrs = Files.readAttributes(cj, BasicFileAttributes::class.java, nofollow)
            entries[cj.toString()] = Entry(Tier.PRESENCE, if (attrs.isSymbolicLink) "symlink" else if (attrs.isRegularFile) "file" else "other", "")
            if (token != null && attrs.isRegularFile && attrs.size() <= MAX_READ_BYTES && readFile(cj, needles, 0).found) {
                hits.add("canary: test token found in LIVE file $cj")
            }
        }
        return Snapshot(entries, hits, tmuxState(), tmuxSocket?.let { Files.exists(it.toPath(), nofollow) })
    }

    /** Read-only `list-sessions` / `list-clients` against the live server's socket, with the REAL tmux (not the PATH shim, which refuses it). */
    private fun tmuxState(): TmuxState? {
        val socket = tmuxSocket ?: return null
        val tmux = realTmux() ?: return null
        fun query(vararg args: String): List<String>? = try {
            val pb = ProcessBuilder(listOf(tmux.path, "-S", socket.path) + args).redirectError(ProcessBuilder.Redirect.DISCARD)
            pb.environment().remove("TMUX"); pb.environment().remove("TMUX_PANE")
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); null }
            else if (p.exitValue() != 0) null
            else out.lines().filter { it.isNotBlank() }
        } catch (_: Exception) { null }
        val sessions = query("list-sessions", "-F", "#{session_id} #{session_name}") ?: return TmuxState(false, emptySet(), emptySet())
        val clients = query("list-clients", "-F", "#{client_name}") ?: emptyList()
        return TmuxState(true, sessions.toSet(), clients.toSet())
    }

    /** The first `tmux` on PATH that is not the test shim directory. */
    private fun realTmux(): File? {
        val shimDir = System.getProperty("clauderemote.test.shimDir")?.let { File(it).canonicalFile }
        return (System.getenv("PATH") ?: "").split(File.pathSeparator).filter { it.isNotEmpty() }
            .filter { shimDir == null || File(it).canonicalFile != shimDir }
            .map { File(it, "tmux") }.firstOrNull { it.canExecute() && !it.isDirectory }
    }

    private class Read(val hash: String, val headHash: String, val found: Boolean, val failed: Boolean, val vanished: Boolean = false)

    /** One streaming pass: SHA-256 of the whole content, of its first [headBytes] bytes, and the token search. */
    private fun readFile(p: Path, needles: List<ByteArray>, headBytes: Int): Read {
        val md = MessageDigest.getInstance("SHA-256")
        val head = MessageDigest.getInstance("SHA-256")
        var headLeft = headBytes
        val carryLen = (needles.maxOfOrNull { it.size } ?: 1) - 1
        var carry = ByteArray(0)
        var found = false
        try {
            Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS).use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    if (headLeft > 0) { val h = minOf(headLeft, n); head.update(buf, 0, h); headLeft -= h }
                    if (needles.isNotEmpty() && !found) {
                        val window = carry + buf.copyOf(n)
                        if (needles.any { indexOf(window, it) >= 0 }) found = true
                        carry = window.copyOfRange(maxOf(0, window.size - carryLen), window.size)
                    }
                }
            }
        } catch (_: NoSuchFileException) {
            return Read("", "", found, failed = true, vanished = true)
        } catch (_: IOException) {
            return Read("", "", found, failed = true)
        }
        return Read(hex(md.digest()), hex(head.digest()), found, failed = false)
    }

    private fun isNewLiveRemoteEntry(path: String): Boolean {
        val parts = relOf(File(path).toPath()).split('/')
        return parts.size == 2 && parts[0] == ".claude-remote" && REMOTE_LIVE_PREFIXES.any { parts[1].startsWith(it) }
    }

    private fun isLogsPath(path: String) = relOf(File(path).toPath()).startsWith(".claude-remote/logs/")

    private fun prefixHash(f: File, n: Int): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val buf = ByteArray(n); var got = 0; while (got < n) { val r = ins.read(buf, got, n - got); if (r < 0) break; got += r }; md.update(buf, 0, got) }
        hex(md.digest())
    } catch (_: IOException) { "?" }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /**
     * Run [block] (which must use [GuardEnv.home] as its HOME for any child process, start children
     * through [GuardEnv.start] and embed [token] in its input), then verify nothing live was touched.
     * Isolation is asserted BEFORE the block so a misconfigured test never gets to run; the
     * verification runs even when the block throws.
     */
    fun <T> guard(token: String, testHome: File, tempRoot: File, block: (GuardEnv) -> T): T {
        assertIsolated(testHome, tempRoot)
        val tmux = File(tempRoot, "t").apply { mkdirs() }
        val env = GuardEnv(testHome, tmux, token)
        val before = snapshot(null)
        var result: T? = null
        var failure: Throwable? = null
        try {
            result = block(env)
        } catch (t: Throwable) {
            failure = t
        }
        val violation: Throwable? = try {
            env.reap()
            verify(before, snapshot(token))
            null
        } catch (v: AssertionError) {
            v
        }
        if (violation != null) {
            failure?.let { violation.addSuppressed(it) }
            throw violation
        }
        if (failure != null) throw failure
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun verify(before: Snapshot, after: Snapshot) {
        val problems = ArrayList<String>()
        val falseAlarm = "possible FALSE ALARM if a live process or the live app wrote it: rerun to confirm"
        for (path in (before.entries.keys + after.entries.keys).sorted()) {
            val b = before.entries[path]; val a = after.entries[path]
            when {
                b == null && isLogsPath(path) -> Unit // a live app starting a new log file is normal; the token scan still covers it
                // the drift script and the app create new entries next to their live files (forgotten.rejected.<time>, sessions.restore.json.bak)
                b == null && a?.tier == Tier.PRESENCE && isNewLiveRemoteEntry(path) -> Unit
                b == null -> problems.add("canary: $path was created during the test (it did not exist before; $falseAlarm).")
                a == null -> problems.add("canary: $path was deleted during the test (it existed before and is gone now; $falseAlarm).")
                b.kind != a.kind -> problems.add("canary: $path changed type during the test (${b.kind} -> ${a.kind}); $falseAlarm.")
                b.tier == Tier.APPEND && b.kind == "file" -> {
                    // May only grow. A file that was replaced by another inode (the log shipper trims through a temp file
                    // and a rename) and did not shrink is tolerated; the same inode must keep its first bytes. A file
                    // over MAX_READ_BYTES has no head hash, so it must keep its inode.
                    // The AFTER entry's own head hash would cover more bytes when the file grew: re-hash the same prefix.
                    val sameInode = b.key == a.key
                    val rewritten = when {
                        a.size < b.size -> true
                        b.big -> !sameInode
                        else -> sameInode && prefixHash(File(path), minOf(b.size, HEAD_BYTES.toLong()).toInt()) != b.headHash
                    }
                    if (rewritten) {
                        problems.add("canary: append-only live file $path was truncated or rewritten during the test (size ${b.size} -> ${a.size}); $falseAlarm.")
                    }
                }
                b.detail != a.detail -> problems.add(
                    "canary: live file $path was modified during the test" +
                        (if (b.kind == "symlink") " (symlink target ${b.detail} -> ${a.detail})" else "") + "; $falseAlarm.",
                )
            }
        }
        // The socket FILE itself: a test that deleted it (a recursive delete through a link into /tmp did) leaves the live
        // server running but unreachable, which is worse than a dead one because nothing reports it.
        if (before.socketPresent == true && after.socketPresent == false) {
            problems.add("canary: the socket file of the live tmux server ($tmuxSocket) existed before the test and is gone now; $falseAlarm.")
        }
        verifyTmux(before.tmux, after.tmux, problems, falseAlarm)
        problems.addAll(after.tokenHits) // never a false alarm
        if (problems.isNotEmpty()) throw AssertionError(problems.take(20).joinToString("\n") + if (problems.size > 20) "\n... and ${problems.size - 20} more" else "")
    }

    private fun verifyTmux(b: TmuxState?, a: TmuxState?, problems: MutableList<String>, falseAlarm: String) {
        if (b == null || a == null || !b.up) return
        if (!a.up) {
            problems.add("canary: the live tmux server answered before the test and does not now; $falseAlarm.")
            return
        }
        for (gone in (b.sessions - a.sessions).sorted()) {
            problems.add("canary: live tmux session '$gone' existed before the test and is gone now; $falseAlarm.")
        }
        // clients attach and detach all the time, so only losing every one of them counts
        if (b.clients.isNotEmpty() && a.clients.isEmpty()) {
            problems.add("canary: every client of the live tmux server (${b.clients.sorted().joinToString()}) is gone after the test; $falseAlarm.")
        }
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

    /**
     * What the token looks like in a file: UTF-8, UTF-16LE/BE and base64 (standard and URL-safe alphabet) at each of the three
     * alignments the token can have inside a longer base64 text. `hook-ev.sh` writes session names and extras to `events`
     * base64-encoded, so a plain search would never see a token that reached a live `events` line.
     */
    private fun needles(token: String): List<ByteArray> {
        val raw = token.toByteArray(Charsets.UTF_8)
        val out = ArrayList<ByteArray>()
        out.add(raw)
        out.add(token.toByteArray(Charsets.UTF_16LE))
        out.add(token.toByteArray(Charsets.UTF_16BE))
        for (offset in 0..2) {
            val text = Base64.getEncoder().withoutPadding().encodeToString(ByteArray(offset) + raw)
            // characters that hold only token bits: from the first whole token sextet to the last one
            val from = (offset * 8 + 5) / 6
            val to = (offset + raw.size) * 8 / 6
            if (to <= from) continue
            val std = text.substring(from, to)
            out.add(std.toByteArray(Charsets.US_ASCII))
            out.add(std.replace('+', '-').replace('/', '_').toByteArray(Charsets.US_ASCII))
        }
        return out.filter { it.isNotEmpty() }.distinctBy { it.toList() }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    companion object {
        const val TAG_VARIABLE = "CRIT_CANARY_TAG"

        /** Files above this are not read: size + mtime + inode instead of a content hash, and no token search. */
        const val MAX_READ_BYTES = 64L * 1024 * 1024

        /** Append-only files are compared on their first bytes (the part that must never change). */
        const val HEAD_BYTES = 64 * 1024

        /**
         * Top-level entries of a Claude config dir (`~/.claude` and every `~/.claude-remote/accounts/<a>`) that live
         * Claude processes write continuously: existence and type only, subtrees not walked. MEASURED on the live box:
         * 21 samples 30 s apart over 10 minutes of idle (projects, hud, backups, `.session-stats.json`, `.claude.json`,
         * policy-limits*, remote-settings*, sessions), plus a modification-time scan over the last 10 hours of use
         * (state, mcp-needs-auth-cache.json, cache/model-catalog, file-history, teams, session-env) and the
         * names the task brief gave (history.jsonl, statsig, todos, shell-snapshots). `skills` and `plugins` are NOT here:
         * only `skills/synced` and `plugins/{synced,oh-my-claudecode}` are live-written ([configTier]).
         * Trees that were guessed rather than measured (paste-cache, debug, tasks, .omc, stats-cache.json,
         * gh-pr-status-cache.json) are compared strictly again. docs/TESTING.md "Live-box canary".
         */
        val CONFIG_LIVE_TOP: Set<String> = setOf(
            "projects", "history.jsonl", "statsig", "todos", "shell-snapshots", "sessions", "hud", "backups", "cache",
            "file-history", "session-env", "teams", "state", "mcp-needs-auth-cache.json",
            ".claude.json", ".session-stats.json",
        )
        val CONFIG_LIVE_PREFIXES: List<String> = listOf("policy-limits.json", "remote-settings.json", ".last-", ".session-stats.json")

        /** Entries directly below `~/.claude-remote` rewritten by live clients and the relay (measured). */
        val REMOTE_LIVE_NAMES: Set<String> = setOf("clients", "usage", "sessions.lock")

        /**
         * Entries below `~/.claude-remote` whose names START with one of these are rewritten by the app and the drift script:
         * the session list, its restore copies (`.bak`) and the forgotten-session files and directory.
         */
        val REMOTE_LIVE_PREFIXES: List<String> = listOf("sessions.json", "sessions.restore.json", "forgotten")

        /** Directly below a config dir's `plugins`: written by the plugin sync and by oh-my-claudecode. */
        val PLUGINS_LIVE_NAMES: Set<String> = setOf("synced", "oh-my-claudecode")

        /** A live-written file up to this size is still searched for the token. */
        const val PRESENCE_SCAN_BYTES = 1L * 1024 * 1024

        /** Entries directly below `~/.cache/claude-remote` rewritten by live hooks (measured). */
        val CACHE_LIVE_NAMES: Set<String> = setOf("notify")

        fun newToken(): String = "crit-canary-" + java.util.UUID.randomUUID()

        /** `/tmp/tmux-<uid>/default`, the socket of the live server (null when the uid cannot be read). */
        fun defaultTmuxSocket(): File? = try {
            File("/tmp/tmux-${Files.getAttribute(java.nio.file.Paths.get("/proc/self"), "unix:uid")}/default")
        } catch (_: Exception) { null }

        /** PIDs whose environment carries `CRIT_CANARY_TAG=<tag>` (Linux `/proc`; elsewhere: none). */
        internal fun taggedProcesses(tag: String): List<ProcessHandle> {
            val proc = File("/proc")
            if (!proc.isDirectory) return emptyList()
            val needle = "$TAG_VARIABLE=$tag".toByteArray(Charsets.UTF_8)
            val out = ArrayList<ProcessHandle>()
            for (d in proc.listFiles().orEmpty()) {
                val pid = d.name.toLongOrNull() ?: continue
                val env = try { File(d, "environ").readBytes() } catch (_: Exception) { continue }
                if (containsBytes(env, needle)) ProcessHandle.of(pid).ifPresent { out.add(it) }
            }
            return out
        }

        private fun containsBytes(hay: ByteArray, needle: ByteArray): Boolean {
            outer@ for (i in 0..hay.size - needle.size) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
                return true
            }
            return false
        }
    }
}
