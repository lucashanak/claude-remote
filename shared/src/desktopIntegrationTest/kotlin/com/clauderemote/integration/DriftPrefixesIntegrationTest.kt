package com.clauderemote.integration

import com.clauderemote.connection.SshManager
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.TmuxNameParser
import com.clauderemote.session.service.SessionPersistenceService
import com.clauderemote.storage.PersistedSession
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.jcraft.jsch.Session
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D6-S end to end (dodatek 3 hradla B, C, D; kritika D2 N14–N17, N23): the drift.sh of marker v19 against the drift.sh
 * of v18 (`golden/restore-installer-v18.sh`, the installer cedfd70 generated, installed whole by its own stdin) over
 * the same world, each world in its own sshd fixture: a private tmux server (`TMUX_TMPDIR` of the fixture), the
 * fixture's `systemctl`/`systemd-run`/`claude` shims, a private HOME. No world has a missing manifest entry, so no
 * self-heal runs. Times and the fixture's HOME are normalized out of what is compared.
 */
class DriftPrefixesIntegrationTest {

    private class World(val fixture: SshdFixture, val session: Session)

    private fun <T> world(block: World.() -> T): T {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        val fixture = SshdFixture.start()
        val manager = SshManager(ServerStorage(InMemoryKeyValueStore()))
        try {
            runBlocking { manager.connect(fixture.server, onOutput = {}, onConnectionLost = {}) }
            return World(fixture, assertNotNull(manager.getSession())).block()
        } finally {
            manager.shutdownForTest()
            fixture.close()
        }
    }

    private val dir get() = "\$HOME/.claude-remote"

    private fun World.crDir() = File(fixture.home, ".claude-remote")

    private fun World.installV18(): String {
        val stdin = (golden() + "\n" + SessionPersistenceService.INSTALL_RESTORE_END + "\n").toByteArray()
        val r = session.execCapture(SessionPersistenceService.INSTALL_RESTORE_EXEC, timeoutMs = 60_000, stdin = stdin)
        assertEquals(0, r.exit, "v18 install:\n${r.out}")
        return r.out
    }

    private fun World.installV19(prefix: String): String {
        val r = session.execCapture(SessionPersistenceService.INSTALL_RESTORE_EXEC, timeoutMs = 60_000,
            stdin = SessionPersistenceService.installRestoreStdin(prefix))
        assertEquals(0, r.exit, "v19 install:\n${r.out}")
        return r.out
    }

    /** Live sessions (each `sleep` in a folder of its own), the manifest drift reads, and the two kinds of tombstone. */
    private fun World.seed(live: List<String>, manifest: List<String>, durable: String? = null, transient: String? = null) {
        for (name in live) {
            val folder = File(fixture.home, "w/$name").apply { mkdirs() }
            fixture.tmux("new-session", "-d", "-s", name, "-c", folder.absolutePath, "sleep", "86400")
        }
        waitUntil(15_000, "the world's sessions") { fixture.tmuxSessions().containsAll(live) }
        if (manifest.isNotEmpty()) {
            val storage = SessionStorage(InMemoryKeyValueStore())
            manifest.forEachIndexed { i, name ->
                storage.upsert(PersistedSession(
                    id = "s$i", serverId = SERVER_ID, folder = File(fixture.home, "w/$name").absolutePath, mode = ClaudeMode.YOLO,
                    model = ClaudeModel.DEFAULT, tmuxSessionName = name, alias = "a$i", claudeSessionId = null, createdAt = 1_000L + i,
                ))
            }
            File(crDir(), "sessions.restore.json").writeText(storage.serializeForServer(SERVER_ID))
        }
        if (durable != null) File(crDir(), "forgotten.d").apply { mkdirs() }.resolve(durable).writeText("")
        if (transient != null) File(crDir(), "forgotten").writeText("$transient\n")
    }

    private fun World.tick() {
        val r = session.execCapture("bash \"$dir/drift.sh\"", timeoutMs = 60_000)
        assertEquals(0, r.exit, "drift:\n${r.out}\n${File(crDir(), "drift.log").readTextOrEmpty()}")
    }

    private data class Outcome(val restore: String, val app: String, val log: String, val forgotten: String, val live: List<String>)

    private fun World.normalized(s: String) =
        s.replace(fixture.home.absolutePath, "~").replace(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ"""), "<T>")

    /** One tick, what it left, and a second tick that must not add one byte to drift.log (N15). */
    private fun World.tickTwice(): Outcome {
        tick()
        val log = File(crDir(), "drift.log")
        val out = Outcome(
            restore = normalized(File(crDir(), "sessions.restore.json").readTextOrEmpty()),
            app = normalized(File(crDir(), "sessions.json").readTextOrEmpty()),
            log = normalized(log.readTextOrEmpty()),
            forgotten = File(crDir(), "forgotten").readTextOrEmpty(),
            live = fixture.tmuxSessions().filter { it != SshdFixture.KEEPALIVE_SESSION }.sorted(),
        )
        val size = if (log.isFile) log.length() else -1
        tick()
        assertEquals(size, if (log.isFile) log.length() else -1, "a tick without a change wrote to drift.log:\n${log.readTextOrEmpty()}")
        assertTrue(crDir().list()!!.none { it.startsWith(".drift.") }, "a tick left files: ${crDir().list()!!.toList()}")
        return out
    }

    private val worldBC = listOf("claude-server-a", "claude-box-b", "claude-test", "__anchor__", "other")

    private fun World.seedBC() = seed(worldBC, manifest = listOf("claude-server-a", "claude-server-gone"),
        durable = "claude-server-gone", transient = "claude-server-old")

    /**
     * Hradla B and C. B: on the box `server` the v19 drift does exactly what v18 did — no file (the installer writes
     * none for `server`) and a file of junk plus the default — the same manifest, mirror, log and tombstones, and
     * `__anchor__` killed as before. C: on the box `box` (the installer wrote `claude-box-`) the one difference is the
     * adoption of `claude-box-b`.
     */
    @Test fun onServerV19DoesWhatV18DidAndOnBoxItAdoptsOnlyTheBoxsOwnSession() {
        val v18 = world { installV18(); seedBC(); tickTwice() }
        assertContains(v18.restore, "claude-server-a")
        assertFalse(v18.restore.contains("claude-server-gone") || v18.restore.contains("claude-box-b"), v18.restore)
        assertEquals(listOf("claude-box-b", "claude-server-a", "claude-test", "other"), v18.live, "v18 kills __anchor__")
        assertContains(v18.log, "drift: reconciled 1 live (was 2)")

        val v19 = world {
            installV19(TmuxNameParser.DEFAULT_PREFIX)
            assertFalse(File(crDir(), "prefixes").exists(), "the box `server` gets no file")
            assertContains(File(crDir(), "drift.sh").readText(), "claude-remote-restore-v19")
            seedBC(); tickTwice()
        }
        assertEquals(v18, v19, "B: v19 on `server` without the file")

        val v19junk = world {
            installV19(TmuxNameParser.DEFAULT_PREFIX)
            File(crDir(), "prefixes").writeText(JUNK.joinToString("\n") + "\nclaude-server-\n")
            seedBC(); tickTwice()
        }
        assertEquals(v18, v19junk, "B: v19 on `server` with a file of junk and the default")

        val box = world {
            installV19(TmuxNameParser.prefixFor("box"))
            assertEquals("claude-box-\n", File(crDir(), "prefixes").readText(), "the installer wrote the box's prefix")
            seedBC(); tickTwice()
        }
        val v18Rows = rows(v18.restore)
        val boxRows = rows(box.restore)
        assertEquals(v18Rows, boxRows.filter { it.name() != "claude-box-b" }, "C: v18's manifest plus claude-box-b, nothing else")
        assertEquals(1, boxRows.count { it.name() == "claude-box-b" })
        val adopted = boxRows.single { it.name() == "claude-box-b" }
        assertEquals("~/w/claude-box-b", adopted["folder"]!!.jsonPrimitive.content)
        assertEquals(box.restore, box.app, "the mirror follows")
        assertEquals(v18.live, box.live)
        assertEquals(v18.forgotten, box.forgotten)
        val onlyV18 = v18.log.lines() - box.log.lines().toSet()
        val onlyBox = box.log.lines() - v18.log.lines().toSet()
        assertEquals(listOf("LIVE=[\"claude-server-a\"]", "[<T>] drift: reconciled 1 live (was 2)"), onlyV18, "C: log lines only v18 wrote")
        assertEquals(listOf("LIVE=[\"claude-box-b\",\"claude-server-a\"]", "[<T>] drift: reconciled 2 live (was 2)"), onlyBox, "C: log lines only v19 wrote")
    }

    /**
     * Hradlo D (N14): a `prefixes` file of nothing but broken lines adopts nothing and does not kill `__anchor__`
     * (no session of ours lives) — the same as v18 on the same world. With valid lines among them (N16: overlapping
     * prefixes and a duplicate of the default), each session of ours is one row and nothing else is adopted.
     */
    @Test fun aBrokenPrefixesFileAdoptsOnlyItsValidLines() {
        val live = listOf("claude-box-b", "claude-test", "__anchor__", "other")
        val v18 = world { installV18(); seed(live, emptyList()); tickTwice() }
        val junk = world {
            installV19(TmuxNameParser.DEFAULT_PREFIX)
            File(crDir(), "prefixes").writeText(JUNK.joinToString("\n") + "\n")
            seed(live, emptyList()); tickTwice()
        }
        assertEquals(v18, junk, "D: junk only = v18")
        assertEquals("", junk.restore, "nothing adopted, no manifest written")
        assertEquals("", junk.log, "nothing happened, nothing logged")
        assertEquals(live.sorted(), junk.live, "__anchor__ lives on (none of ours is live)")

        val mixed = world {
            installV19(TmuxNameParser.DEFAULT_PREFIX)
            File(crDir(), "prefixes").writeText((JUNK + listOf("claude-server-", "claude-server-lan-", "claude-box-", "claude-box-lan-")).joinToString("\n") + "\n")
            seed(listOf("claude-server-lan-1", "claude-box-lan-c", "claude-test", "__anchor__", "other", "claude-boxer"), emptyList())
            tickTwice()
        }
        assertEquals(listOf("claude-box-lan-c", "claude-server-lan-1"), rows(mixed.restore).map { it.name() }, "one row each, nothing foreign: ${mixed.restore}")
        assertEquals(listOf("claude-box-lan-c", "claude-boxer", "claude-server-lan-1", "claude-test", "other"), mixed.live, "__anchor__ dropped: a session of ours lives")
    }

    /**
     * The step on the live box, v18 → v19 (N17): the three scripts are rewritten by rename, install.log says so three
     * times naming the v18 line, the units stay (no `daemon-reload` path), a second install changes nothing, and an
     * older client (v18) after it finds its marker in the compat line and rewrites nothing back.
     */
    @Test fun anUpgradeFromV18RewritesTheThreeScriptsAndAV18ClientLeavesThemAlone() = world {
        installV18()
        val files = listOf("restore.sh", "drift.sh", "tmux-sockheal.sh").map { File(crDir(), it) }
        val before = files.map { inode(it) }
        val logBefore = File(crDir(), "install.log").readText().length

        val out = installV19(TmuxNameParser.DEFAULT_PREFIX)
        for (tag in listOf("RESTORE_SCRIPT_INSTALLED", "DRIFT_SCRIPT_INSTALLED", "SOCKHEAL_SCRIPT_INSTALLED", "RESTORE_UNIT_PRESENT")) {
            assertTrue(tag in out.lines(), "$tag:\n$out")
        }
        assertFalse("DRIFT_TIMER_INSTALLED" in out || "RESTORE_UNIT_INSTALLED" in out, "the units stay: no daemon-reload\n$out")
        files.forEachIndexed { i, f ->
            assertTrue(before[i] != inode(f), "${f.name} rewritten in place")
            assertContains(f.readText(), "claude-remote-restore-v19", message = f.name)
        }
        val log = File(crDir(), "install.log").readText().substring(logBefore)
        for (f in files) {
            assertContains(log, "${f.name} missing claude-remote-restore-v19 — rewriting; prior marker line: # marker-compat (do NOT remove): " +
                (6..18).joinToString(" ") { "claude-remote-restore-v$it" } + "\n", message = log)
        }
        // D6S-zaloha: the replaced v18 restore.sh and drift.sh are kept next to them, byte for byte; the guard script
        // gets no copy (its v19 differs from v18 only in the compat line)
        val copies = listOf("restore.sh" to "RESTORE_EOF", "drift.sh" to "DRIFT_EOF").map { (name, eof) ->
            val copy = File(crDir(), "$name.v18")
            assertEquals(heredoc(golden(), eof), copy.readTextOrEmpty(), "$name.v18 is the v18 script")
            assertContains(log, "install: the v18 $name kept as $name.v18 (rollback)")
            copy
        }
        assertFalse(File(crDir(), "tmux-sockheal.sh.v18").exists())
        val now = files.map { it.readText() }
        assertTrue("PRESENT" in installV19(TmuxNameParser.DEFAULT_PREFIX))
        val old = installV18()
        assertTrue("RESTORE_SCRIPT_PRESENT" in old.lines() && "INSTALLED" !in old, "a v18 client rewrote v19 scripts:\n$old")
        assertEquals(now, files.map { it.readText() }, "nothing rewritten after v19")
        assertEquals(listOf("RESTORE_EOF", "DRIFT_EOF").map { heredoc(golden(), it) }, copies.map { it.readText() }, "the copies stay")
        assertTrue(crDir().list()!!.none { ".tmp." in it }, "temp files left")
    }

    /** D6S-zaloha: a script of another marker than v18 gets no copy, and a copy already there is never overwritten. */
    @Test fun theRollbackCopyIsMadeOnlyFromV18AndOnlyOnce() {
        world {
            crDir().mkdirs()
            val v17 = "#!/usr/bin/env bash\n# marker-compat (do NOT remove): " + (6..17).joinToString(" ") { "claude-remote-restore-v$it" } + "\nexit 0\n"
            File(crDir(), "restore.sh").writeText(v17)
            File(crDir(), "drift.sh").writeText(v17)
            val out = installV19(TmuxNameParser.DEFAULT_PREFIX)
            assertTrue("DRIFT_SCRIPT_INSTALLED" in out.lines(), out)
            assertTrue(crDir().list()!!.none { it.endsWith(".v18") }, "a copy of a v17 script: ${crDir().list()!!.toList()}")
        }
        world {
            installV18()
            File(crDir(), "drift.sh.v18").writeText("an earlier copy\n")
            installV19(TmuxNameParser.DEFAULT_PREFIX)
            assertEquals("an earlier copy\n", File(crDir(), "drift.sh.v18").readText(), "an existing copy was overwritten")
            assertEquals(heredoc(golden(), "RESTORE_EOF"), File(crDir(), "restore.sh.v18").readText())
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun rows(json: String): List<JsonObject> =
        if (json.isBlank()) emptyList() else Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }

    private fun JsonObject.name() = this["tmuxSessionName"]!!.jsonPrimitive.content

    private fun inode(f: File): Any? = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java).fileKey()

    /** The body of the heredoc `<<'[eof]'` in [script], its last newline included. */
    private fun heredoc(script: String, eof: String): String {
        val start = script.indexOf('\n', script.indexOf("<<'$eof'")) + 1
        return script.substring(start, script.indexOf("\n$eof\n", start) + 1)
    }

    private fun golden(): String {
        val rel = "src/desktopTest/resources/golden/restore-installer-v18.sh"
        return (listOf(File(rel), File("shared/$rel")).firstOrNull { it.isFile } ?: error("golden $rel not found")).readText()
    }

    private companion object {
        const val SERVER_ID = "integration-server"

        /** The N14 fixture: lines that must all be skipped. */
        val JUNK = listOf("", "   ", "claude-box-\r", "claude-", "claude--", "*", "__anchor__", "[x]", "a b", " claude-box-", "claude-a.b-", "claude-*-", "claude-?-")
    }
}
