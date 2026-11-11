package com.clauderemote.integration

import com.clauderemote.connection.SshManager
import com.clauderemote.session.service.SessionPersistenceService
import com.clauderemote.storage.ServerStorage
import com.jcraft.jsch.Session
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The world the v20 drift tests run in (`DriftV20IntegrationTest`, `DriftV20IdentityIntegrationTest`): one sshd
 * fixture (private tmux server, HOME, `TMUX_TMPDIR`, shims), the scripts installed whole by their own stdin, and ticks
 * run the way the drift unit runs them on the box: `env -i` with the unit's PATH (`%h/.local/bin:…`), so no locale at
 * all (kritika E H8/KK23: the fixture's wrap.sh exports `LANG=C.UTF-8`, the unit has none).
 *
 * The private tmux server is started over ssh (wrap.sh: the fixture's shims first on PATH) before anything else, so
 * every pane — restore.sh's relaunches included — finds the stand-in `claude`, never a real one on the JVM's PATH.
 * The stand-in is a script named `claude` (drift recognises its process by that name) that records how it was started
 * in `~/launches.log` and sleeps; the sessions a test starts with claude use a second, silent copy (`~/cbin/claude`).
 */
internal class DriftWorld(val fixture: SshdFixture, val session: Session) {
    val home: File get() = fixture.home
    val cr: File get() = File(fixture.home, ".claude-remote")

    /** The PATH of `claude-remote-drift.service` (`Environment=PATH=…`, `%h` = HOME). */
    private val unitPath = "\$HOME/.local/bin:\$HOME/.npm-global/bin:/usr/local/bin:/usr/bin:/bin"

    fun prepare() {
        cr.mkdirs()
        val bin = File(home, ".local/bin").apply { mkdirs() }
        val recorder = "#!/bin/sh\n# drift's claude stand-in (DriftV20World): records how it was started, never runs claude\n" +
            "printf '%s|%s|%s\\n' \"\$PWD\" \"\${CLAUDE_CONFIG_DIR:-}\" \"\$*\" >> \"\$HOME/launches.log\"\nsleep 86400\nexit 0\n"
        for (f in listOf(File(fixture.shims, "claude"), File(bin, "claude"))) { f.writeText(recorder); f.setExecutable(true) }
        File(home, "cbin").mkdirs()
        File(home, "cbin/claude").apply { writeText("#!/bin/sh\n# a claude a test started (DriftV20World)\nsleep 86400\nexit 0\n"); setExecutable(true) }
        File(bin, "systemd-run").apply {
            writeText("#!/usr/bin/env bash\nwhile [ \$# -gt 0 ]; do case \"\$1\" in --*) shift;; *) break;; esac; done\nexec \"\$@\"\n"); setExecutable(true)
        }
        File(bin, "systemctl").apply { writeText("#!/bin/sh\ncase \"\$*\" in *is-system-running*) echo running;; esac\nexit 0\n"); setExecutable(true) }
        File(bin, "loginctl").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        // the live-socket refusal of the test tmux shim, also under the unit's PATH (a copy, never a link out of the root)
        System.getProperty("clauderemote.test.shimDir")?.let { File(it, "tmux") }?.takeIf { it.isFile }?.let { shim ->
            File(bin, "tmux").apply { writeText(shim.readText()); setExecutable(true) }
        }
        val r = session.execCapture("tmux new-session -d -s ${SshdFixture.KEEPALIVE_SESSION} sleep 86400", timeoutMs = 20_000)
        waitUntil(10_000, "the private tmux server (${r.out})") { SshdFixture.KEEPALIVE_SESSION in fixture.tmuxSessions() }
        val path = fixture.tmux("show-environment", "-g", "PATH").out
        check(path.contains(fixture.shims.absolutePath)) { "the private server does not run with the fixture's shims first: $path" }
    }

    /** The installer [stdin] (whole, through `INSTALL_RESTORE_EXEC`); its output. */
    fun install(stdin: ByteArray, what: String): String {
        val r = session.execCapture(SessionPersistenceService.INSTALL_RESTORE_EXEC, timeoutMs = 60_000, stdin = stdin)
        assertEquals(0, r.exit, "$what install:\n${r.out}")
        return r.out
    }

    fun installV20(prefix: String): String = install(SessionPersistenceService.installRestoreStdin(prefix), "v20")
    fun installV19(prefix: String): String = install(Goldens.v19Stdin(prefix), "v19")
    fun installV18(): String = install(Goldens.v18Stdin(), "v18")

    /** One drift tick as the timer unit runs it (`env -i`, the unit's PATH, no locale); fails on a non-zero exit. */
    fun tick(timeoutMs: Long = 90_000) {
        val r = session.execCapture(
            "env -i HOME=\"\$HOME\" TMUX_TMPDIR=\"\$TMUX_TMPDIR\" PATH=\"$unitPath\" bash \"\$HOME/.claude-remote/drift.sh\"",
            timeoutMs = timeoutMs,
        )
        assertEquals(0, r.exit, "drift:\n${r.out}\n${File(cr, "drift.log").readTextOrEmpty()}")
    }

    fun folder(name: String): File = File(home, "w/$name").apply { mkdirs() }

    /** A live session [name] in its folder (`~/w/<name>` unless [folder]): `sleep`, or the silent claude with [claude]. */
    fun live(name: String, claude: List<String>? = null, folder: File = folder(name), env: Map<String, String> = emptyMap()) {
        val cmd = if (claude == null) listOf("sleep", "86400") else
            (if (env.isEmpty()) emptyList() else listOf("env") + env.map { "${it.key}=${it.value}" }) + listOf(File(home, "cbin/claude").absolutePath) + claude
        val r = fixture.tmux("new-session", "-d", "-s", name, "-c", folder.absolutePath, *cmd.toTypedArray())
        waitUntil(10_000, "session $name (${r.out})") { name in fixture.tmuxSessions() }
        if (claude != null) waitUntil(10_000, "claude in $name") { claudePid(name) != null }
    }

    /** The pid of the process named `claude` in [name]'s pane (the pane itself or a child), or null. */
    fun claudePid(name: String): Long? {
        val pane = fixture.tmux("-u", "list-panes", "-t", "=$name:", "-F", "#{pane_pid}").out.trim().lines().firstOrNull()?.trim()?.toLongOrNull()
            ?: return null
        fun comm(p: Long) = runCatching { File("/proc/$p/comm").readText().trim() }.getOrNull()
        if (comm(pane) == "claude") return pane
        val kids = runCatching { File("/proc/$pane/task/$pane/children").readText().trim().split(" ").mapNotNull { it.toLongOrNull() } }.getOrDefault(emptyList())
        return kids.firstOrNull { comm(it) == "claude" }
    }

    fun write(rel: String, text: String) = File(cr, rel).apply { parentFile.mkdirs(); writeText(text) }
    fun read(rel: String): String = File(cr, rel).readTextOrEmpty()

    fun sessions(): List<String> = fixture.tmuxSessions().filter { it != SshdFixture.KEEPALIVE_SESSION }.sorted()

    /** `name<TAB>pane_pid<TAB>session_created` of every session but the fixture's keepalive, sorted. */
    fun panes(): List<String> = fixture.tmux("-u", "list-panes", "-a", "-F", "#{session_name}\t#{pane_pid}\t#{session_created}").out
        .lines().filter { it.isNotBlank() && !it.startsWith(SshdFixture.KEEPALIVE_SESSION + "\t") }.sorted()

    fun launches(): List<String> = File(home, "launches.log").readTextOrEmpty().lines().filter { it.isNotEmpty() }

    /** Times, pids and HOME made comparable between worlds; HOME becomes `<HOME>`, never `~` (a `~` drift wrote stays one). */
    fun normalized(s: String) = s.replace(home.absolutePath, "<HOME>")
        .replace(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ"""), "<T>")
        .replace(Regex("""pid=\d+"""), "pid=<P>")
        .replace(Regex("""\d{8}T\d{6}Z"""), "<TS>")

    fun md5(rel: String): String = MessageDigest.getInstance("MD5").digest(File(cr, rel).let { if (it.isFile) it.readBytes() else ByteArray(0) })
        .joinToString("") { "%02x".format(it) }

    /** What a tick left that a later version of the scripts reads: the files compared between worlds. */
    fun state(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (f in listOf("sessions.restore.json", "sessions.json", "manifests/highwater.json", "forgotten", "drift.log", "restore.log", "sockheal.log")) {
            out[f] = normalized(read(f))
        }
        out["forgotten.d/"] = File(cr, "forgotten.d").list()?.sorted()?.joinToString("\n").orEmpty()
        out["manifests/"] = File(cr, "manifests").list()?.map { normalized(it) }?.sorted()?.joinToString("\n").orEmpty()
        out["tmux"] = sessions().joinToString("\n")
        out["launches"] = launches().joinToString("\n") { normalized(it) }
        return out
    }

    companion object {
        fun <T> world(block: DriftWorld.() -> T): T {
            SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
            val fixture = SshdFixture.start()
            val manager = SshManager(ServerStorage(InMemoryKeyValueStore()))
            try {
                runBlocking { manager.connect(fixture.server, onOutput = {}, onConnectionLost = {}) }
                return DriftWorld(fixture, assertNotNull(manager.getSession())).apply { prepare() }.block()
            } finally {
                manager.shutdownForTest()
                fixture.close()
            }
        }

        fun rows(json: String): List<JsonObject> =
            if (json.isBlank()) emptyList() else Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }

        fun JsonObject.str(key: String): String? = this[key]?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content }
    }
}

/** The goldens of earlier installers (`src/desktopTest/resources/golden`), as the stdin their clients send. */
internal object Goldens {
    private fun file(name: String): String {
        val rel = "src/desktopTest/resources/golden/$name"
        return (listOf(File(rel), File("shared/$rel")).firstOrNull { it.isFile } ?: error("golden $rel not found")).readText()
    }

    /** `INSTALL_RESTORE_COMMAND` of cedfd70 (marker v18, no prefix write). */
    val v18: String by lazy { file("restore-installer-v18.sh") }

    /** `INSTALL_RESTORE_COMMAND` of 1bdcbd5 (marker v19). */
    val v19: String by lazy { file("restore-installer-v19.sh") }

    fun v18Stdin(): ByteArray = (v18 + "\n" + SessionPersistenceService.INSTALL_RESTORE_END + "\n").toByteArray()

    /** v19's stdin: its installer, its prefix write (unchanged in v20: hradlo A in `DriftPrefixesTest`), the end line. */
    fun v19Stdin(prefix: String): ByteArray =
        (v19 + "\n" + SessionPersistenceService.prefixWrite(prefix) + "\n" + SessionPersistenceService.INSTALL_RESTORE_END + "\n").toByteArray()

    /** The body of the heredoc `<<'[eof]'` in [script], its last newline included. */
    fun heredoc(script: String, eof: String): String {
        val start = script.indexOf('\n', script.indexOf("<<'$eof'")) + 1
        return script.substring(start, script.indexOf("\n$eof\n", start) + 1)
    }
}
