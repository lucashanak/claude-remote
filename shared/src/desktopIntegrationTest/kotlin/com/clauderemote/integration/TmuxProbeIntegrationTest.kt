package com.clauderemote.integration

import com.clauderemote.connection.SshManager
import com.clauderemote.session.TabManager
import com.clauderemote.session.service.ConnectionRegistry
import com.clauderemote.session.service.TerminalIOService
import com.clauderemote.session.service.TmuxProbes
import com.jcraft.jsch.ChannelExec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The production [TmuxProbes] driven over a real SSH connection against a real
 * tmux 3.5a server. `TmuxProbes` is `internal`, and the integration source set is
 * `associateWith(main)`, so it is constructed here for real (scope + registry +
 * tabManager + terminalIO) rather than approximated.
 */
class TmuxProbeIntegrationTest {

    /**
     * THE wrong-session regression (c7e157b), verified against real tmux for the
     * first time. `tmux has-session -t <name>` PREFIX-matches, so with only
     * `proj--cashy-2` alive a probe for `proj--cashy` used to answer YES — and the
     * attach that followed dropped the user into somebody else's conversation
     * instead of rebuilding their own. The fix is the exact target `-t '=name'`.
     */
    @Test
    fun probeTmuxSessionDoesNotPrefixMatchALongerSessionName() {
        val decoy = "proj--cashy-2"
        val requested = "proj--cashy"
        fixture.tmux("new-session", "-d", "-s", decoy, "sleep", "86400").also {
            assertEquals(0, it.exit, "could not create decoy session: ${it.out}")
        }
        waitUntil(10_000, "decoy session $decoy to exist") { fixture.tmuxSessions().contains(decoy) }
        assertFalse(
            fixture.tmuxSessions().contains(requested),
            "precondition: $requested must NOT exist, only the longer $decoy",
        )

        assertFalse(
            probes.probeTmuxSession(manager, requested),
            "probeTmuxSession('$requested') must be FALSE while only '$decoy' exists — " +
                "a true here is the wrong-session bug (prefix match) coming back",
        )
        // ...and the probe is not simply always-false:
        assertTrue(
            probes.probeTmuxSession(manager, decoy),
            "probeTmuxSession('$decoy') must be TRUE for the session that really exists",
        )
    }

    @Test
    fun probeTranscriptExistsTracksTheRealJsonlFile() {
        // probeTranscriptExists resolves ~/.claude/projects/<encoded>/<uuid>.jsonl
        // against $HOME, which wrap.sh has pointed at the sandbox home.
        val folder = File(fixture.home, "work/some.project_dir").apply { mkdirs() }
        val uuid = UUID.randomUUID().toString()
        val encoded = folder.absolutePath.replace(Regex("[^a-zA-Z0-9]"), "-")
        val transcript = File(fixture.home, ".claude/projects/$encoded/$uuid.jsonl")

        assertFalse(
            probes.probeTranscriptExists(manager, folder.absolutePath, uuid),
            "must be false before the transcript exists (fail-closed)",
        )

        transcript.parentFile.mkdirs()
        transcript.writeText("""{"type":"user","message":{"role":"user","content":"hi"}}""" + "\n")
        assertTrue(
            probes.probeTranscriptExists(manager, folder.absolutePath, uuid),
            "must be true once ${transcript.absolutePath} exists",
        )

        // A DIFFERENT uuid in the same project dir must still be false — proves the
        // probe matches the uuid, not merely the directory.
        assertFalse(
            probes.probeTranscriptExists(manager, folder.absolutePath, UUID.randomUUID().toString()),
            "an unrelated uuid must not be reported as present",
        )
    }

    /**
     * The anti-SIGSEGV invariant. Two devices' worth of attaches on one session
     * leaves tmux with two clients of different sizes fighting over the layout,
     * which is the resize churn implicated in the tmux server SIGSEGVs that kill
     * every session at once. [TmuxProbes.singleClientPreamble] makes each attach
     * detach the PREVIOUS client from the same device key (by recorded tty, never
     * `detach-client -a`).
     *
     * Fully exercised, not approximated: each attach runs on its own exec channel
     * with `setPty(true)`, so the remote shell has a genuine controlling terminal
     * and `tty` yields a real /dev/pts/N — which is the value the preamble stores
     * and later targets.
     */
    @Test
    fun singleClientPreambleLeavesExactlyOneClientPerDevice() {
        val name = "claude-server-int--attach"
        val deviceKey = "device-key-aaaa"
        fixture.tmux("new-session", "-d", "-s", name, "sleep", "86400").also {
            assertEquals(0, it.exit, "could not create session to attach to: ${it.out}")
        }
        waitUntil(10_000, "$name to exist") { fixture.tmuxSessions().contains(name) }

        val preamble = probes.singleClientPreamble(name, deviceKey)
        val attaches = mutableListOf<ChannelExec>()
        try {
            val first = attach(preamble, name).also { attaches += it }
            val firstTty = awaitSingleClient(name) { true }
            assertEquals(
                firstTty,
                markerFile(deviceKey, name).takeIf { it.isFile }?.readText()?.trim(),
                "the preamble must record the attaching tty as this device's marker",
            )

            // Second attach from the SAME device key: its preamble must detach the
            // client recorded above and leave exactly one — the NEW one.
            //
            // The predicate must include "and it is not the old tty": the client
            // count passes through 1 -> 0 -> 1 (detach, then attach), so a bare
            // `size == 1` is satisfied instantly by the client that is about to be
            // dropped, and the next read finds an empty list.
            attach(preamble, name).also { attaches += it }
            val remaining = awaitSingleClient(name) { it != firstTty }
            waitUntil(10_000, "the first attach channel to be dropped by tmux") { first.isClosed }

            // A DIFFERENT device must not be touched — intentional multi-device
            // attach keeps working, one client each.
            attach(probes.singleClientPreamble(name, "device-key-bbbb"), name).also { attaches += it }
            waitUntil(20_000, "two clients (one per device) on $name") {
                clientTtys(name).let { it.size == 2 && it.contains(remaining) }
            }
            assertTrue(
                clientTtys(name).contains(remaining),
                "another device's attach must NOT detach this device's client",
            )
        } finally {
            attaches.forEach { runCatching { it.disconnect() } }
        }
    }

    /**
     * Poll until [tmuxName] has exactly one client whose tty satisfies [accept],
     * and return that tty — captured INSIDE the poll, so the value asserted on is
     * the one that satisfied the condition rather than whatever a second, racier
     * read happens to find.
     */
    private fun awaitSingleClient(tmuxName: String, accept: (String) -> Boolean): String {
        var found: String? = null
        var lastSeen: List<String> = emptyList()
        try {
            waitUntil(20_000, "exactly one accepted client on $tmuxName") {
                val ttys = clientTtys(tmuxName).also { lastSeen = it }
                val only = ttys.singleOrNull()
                if (only != null && accept(only)) { found = only; true } else false
            }
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}; last clients seen: $lastSeen", e)
        }
        return found!!
    }

    /** Open a pty-backed exec channel that runs the preamble and then attaches. */
    private fun attach(preamble: String, tmuxName: String): ChannelExec {
        val session = assertNotNull(manager.getSession())
        val ch = session.openChannel("exec") as ChannelExec
        ch.setPty(true)
        ch.setPtySize(200, 50, 200 * 8, 50 * 16)
        ch.setCommand(preamble + "tmux attach -t '=$tmuxName'")
        val out = ch.inputStream
        ch.connect(15_000)
        // Drain the attached pane's screen output, else tmux blocks on a full window.
        Thread { runCatching { out.readBytes() } }.apply { isDaemon = true }.start()
        return ch
    }

    private fun clientTtys(tmuxName: String): List<String> {
        val r = fixture.tmux("list-clients", "-t", "=$tmuxName", "-F", "#{client_tty}")
        if (r.exit != 0) return emptyList()
        return r.out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Mirrors the key construction in [TmuxProbes.singleClientPreamble]. */
    private fun markerFile(deviceKey: String, tmuxName: String): File {
        val key = (deviceKey.take(40) + "-" + tmuxName.takeLast(120))
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(fixture.home, ".claude-remote/clients/$key")
    }

    companion object {
        private lateinit var fixture: SshdFixture
        private lateinit var manager: SshManager
        private lateinit var probes: TmuxProbes
        private lateinit var scope: CoroutineScope

        @BeforeClass
        @JvmStatic
        fun boot() {
        // NO SILENT SKIP. The Gradle task already refuses to run without
        // sshd/tmux/jq (see :shared:integrationTest's doFirst), so an unsupported
        // box HERE is a wiring problem — and a JUnit "skipped" on these tests is
        // indistinguishable from a pass in CI. Fail loudly, naming what is missing.
        SshdFixture.unsupportedReason?.let {
            error("integration environment incomplete — $it")
        }
            fixture = SshdFixture.start()
            fixture.startTmuxServer()

            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val tabManager = TabManager()
            val registry = ConnectionRegistry(
                com.clauderemote.storage.ServerStorage(InMemoryKeyValueStore()),
                tabManager,
            )
            probes = TmuxProbes(scope, registry, tabManager, TerminalIOService(registry))

            manager = SshManager(com.clauderemote.storage.ServerStorage(InMemoryKeyValueStore()))
            runBlocking { manager.connect(fixture.server, onOutput = {}, onConnectionLost = {}) }
            assertNotNull(manager.getSession(), "SSH session must be live before probing")
        }

        @AfterClass
        @JvmStatic
        fun shutdown() {
            if (::manager.isInitialized) manager.shutdownForTest()
            if (::scope.isInitialized) scope.cancel()
            if (::fixture.isInitialized) fixture.close()
        }
    }
}
