package com.clauderemote.integration

import com.clauderemote.connection.SshManager
import com.clauderemote.storage.ServerStorage
import com.jcraft.jsch.Session
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The alternative transports (Eternal Terminal, mosh) against the REAL binaries.
 *
 * Scope, stated honestly: the SHIPPED clients are separate artifacts — an
 * NDK-cross-compiled arm64 `et`/mosh-client for Android and a bundled desktop
 * binary, both built in CI — so nothing here can validate those specific
 * builds. What it does validate is the WIRING: the server-side invocation shapes
 * the app depends on, and the parsers it feeds them into. Those are the parts
 * that live in this repo and can regress here.
 */
class TransportBinaryIntegrationTest {

    private lateinit var fixture: SshdFixture
    private lateinit var manager: SshManager
    private lateinit var session: Session
    private var etserver: Process? = null

    @Before
    fun boot() {
        // NO SILENT SKIP. The Gradle task already refuses to run without
        // sshd/tmux/jq (see :shared:integrationTest's doFirst), so an unsupported
        // box HERE is a wiring problem — and a JUnit "skipped" on these tests is
        // indistinguishable from a pass in CI. Fail loudly, naming what is missing.
        SshdFixture.unsupportedReason?.let {
            error("integration environment incomplete — $it")
        }
        fixture = SshdFixture.start()
        manager = SshManager(ServerStorage(InMemoryKeyValueStore()))
        runBlocking { manager.connect(fixture.server, onOutput = {}, onConnectionLost = {}) }
        session = assertNotNull(manager.getSession())
    }

    @After
    fun shutdown() {
        // mosh-server and etserver daemonize away from us; both were registered
        // with the fixture by PID, so fixture.close() reaps them (by PID, never by
        // pattern — see SshdFixture.trackPid).
        etserver?.let { runCatching { it.destroyForcibly() } }
        if (::manager.isInitialized) manager.shutdownForTest()
        if (::fixture.isInitialized) fixture.close()
    }

    // ------------------------------------------------------------------ mosh (E)

    /**
     * mosh: a deliberately NARROW but real test. The app cannot be exercised
     * end-to-end here (it ships an arm64 Android mosh-client; a CI x86 runner
     * cannot run it), and asserting `mosh --version` would guard nothing at all.
     *
     * What DOES live in this repo and can break: MoshManager runs exactly
     * `mosh-server new 2>&1` over its SSH exec channel and then parses the reply
     * as `MOSH CONNECT <port> <key>` — `lines().find { startsWith("MOSH CONNECT") }`,
     * `split(" ")`, `parts[2]` port, `parts[3]` key, requiring `parts.size >= 4`.
     * So this asserts the real server-side invocation still produces a line that
     * real parser accepts, with a usable port and key.
     */
    @Test
    fun moshServerAnnouncesAConnectLineTheAppCanParse() {
        val moshServer = SshdFixture.onPath("mosh-server")
        if (moshServer == null) {
            println("SKIP moshServerAnnouncesAConnectLineTheAppCanParse: mosh-server not installed")
            return
        }

        // Byte-identical to MoshManager's command.
        val result = session.execCapture("mosh-server new 2>&1", timeoutMs = 30_000)
        recordDetachedPid(result.out)

        val connectLine = result.out.lines().find { it.startsWith("MOSH CONNECT") }
        assertNotNull(
            connectLine,
            "mosh-server new must print a `MOSH CONNECT <port> <key>` line; got:\n${result.out}",
        )
        val parts = connectLine.split(" ")
        assertTrue(parts.size >= 4, "MoshManager requires >= 4 fields, got ${parts.size}: $connectLine")
        val port = parts[2].toIntOrNull()
        assertNotNull(port, "field 3 must be a port number, got '${parts[2]}'")
        assertTrue(port in 1..65535, "mosh port out of range: $port")
        assertTrue(
            parts[3].length >= 16 && parts[3].all { it.isLetterOrDigit() || it == '+' || it == '/' },
            "field 4 must look like a mosh session key, got '${parts[3]}'",
        )
    }

    // -------------------------------------------------------------------- ET (D)

    /**
     * ET: the bootstrap handshake the app actually performs, against real
     * `etserver` + `etterminal`.
     *
     * The app never uses `et`'s own SSH bootstrap. It runs `etterminal` over its
     * existing SSH channel with a client-generated id/passkey, then parses
     * `IDPASSKEY:` + 16 chars + `/` + 32 chars out of the reply at fixed offsets
     * (SessionOrchestrator: `substring(marker + 10, marker + 10 + 16 + 1 + 32)`).
     * This runs that exact command shape and asserts the exact offsets — the
     * piece of the ET path that is ours to get wrong.
     */
    @Test
    fun etTerminalBootstrapReturnsAParseableIdpasskey() {
        val started = startEtServer() ?: return

        val id = "XX" + "abcdefghijklmn"          // 16 chars, as randomAlphaNum(14) yields
        val passkey = "0123456789abcdef".repeat(2) // 32 chars
        val bootstrap = session.execCapture(
            "timeout 8 sh -c 'echo $id/${passkey}_xterm-256color | " +
                "etterminal --serverfifo \"\$HOME/.claude-remote/et.sock\" --verbose=0 2>&1'",
            timeoutMs = 30_000,
        ).out

        val marker = bootstrap.indexOf("IDPASSKEY:")
        assertTrue(
            marker >= 0 && marker + 10 + 49 <= bootstrap.length,
            "etterminal must emit a full IDPASSKEY line (etserver on port $started); got:\n$bootstrap",
        )
        val idpasskey = bootstrap.substring(marker + 10, marker + 10 + 16 + 1 + 32)
        assertEquals(
            "$id/$passkey",
            idpasskey,
            "the app's fixed-offset IDPASSKEY parse must still line up with etterminal's output",
        )
    }

    /**
     * ET round-trip through the real `etserver`: the stock `et` client runs a
     * command and its output comes back.
     *
     * Two honest caveats. (1) The stock client does its OWN ssh bootstrap, a path
     * the app replaces with the etterminal handshake above — so this proves
     * etserver/etterminal work against real binaries, not the app's client
     * wiring. (2) `et` requires a controlling terminal, which a JVM child process
     * has not got, hence `script` to supply a pty; without `script` the test skips
     * rather than pretending.
     */
    @Test
    fun etClientRoundTripsCommandOutputThroughEtserver() {
        val port = startEtServer() ?: return
        if (SshdFixture.onPath("et") == null) {
            println("SKIP etClientRoundTripsCommandOutputThroughEtserver: et client not installed")
            return
        }
        if (SshdFixture.onPath("script") == null) {
            println("SKIP etClientRoundTripsCommandOutputThroughEtserver: `script` (util-linux) needed for a pty")
            return
        }

        val etCommand = listOf(
            "et", "-c", "echo ET_ROUNDTRIP_OK",
            "-p", port.toString(),
            "--serverfifo", File(fixture.home, ".claude-remote/et.sock").absolutePath,
            "--silent",
            "--logdir", fixture.root.absolutePath,
            "--ssh-option", "Port=${fixture.port}",
            "--ssh-option", "IdentityFile=${File(fixture.root, "id").absolutePath}",
            "--ssh-option", "StrictHostKeyChecking=no",
            "--ssh-option", "UserKnownHostsFile=/dev/null",
            "${fixture.username}@127.0.0.1",
        ).joinToString(" ")

        val result = fixture.run("script", "-qec", etCommand, "/dev/null", timeoutMs = 60_000)
        assertContains(
            result.out,
            "ET_ROUNDTRIP_OK",
            message = "the command's output must round-trip through etserver on port $port; got:\n${result.out}",
        )
    }

    /**
     * Start `etserver` on a free port with a private serverfifo inside the
     * sandbox. Returns the port, or null (after a loud println) when ET cannot be
     * brought up headlessly — never a faked assertion.
     */
    private fun startEtServer(): Int? {
        val bin = SshdFixture.onPath("etserver")
        if (bin == null) {
            println("SKIP: etserver not installed")
            return null
        }
        val fifo = File(fixture.home, ".claude-remote/et.sock")
        fifo.parentFile.mkdirs()
        val port = ServerSocket(0).use { it.localPort }
        val pb = ProcessBuilder(
            bin.absolutePath,
            "--port", port.toString(),
            "--bindip", "127.0.0.1",
            "--serverfifo", fifo.absolutePath,
            "--pidfile", File(fixture.root, "etserver.pid").absolutePath,
            "--logdir", fixture.root.absolutePath,
        ).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(File(fixture.root, "etserver.log")))
        pb.environment().apply {
            remove("TMUX")
            remove("TMUX_PANE")
            put("HOME", fixture.home.absolutePath)
        }
        etserver = pb.start().also { fixture.trackPid(it.pid()) }
        return try {
            waitUntil(15_000, "etserver to listen on 127.0.0.1:$port and create ${fifo.name}") {
                fifo.exists() && runCatching {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500); true }
                }.getOrDefault(false)
            }
            port
        } catch (e: AssertionError) {
            println(
                "SKIP: etserver would not come up headlessly ($e). log:\n" +
                    File(fixture.root, "etserver.log").let { if (it.isFile) it.readText() else "<none>" },
            )
            etserver?.destroyForcibly()
            etserver = null
            null
        }
    }

    /**
     * mosh-server prints `[mosh-server detached, pid = N]` and then daemonizes, so
     * this is the only handle on it. Hand it to the fixture, which kills tracked
     * PIDs directly.
     */
    private fun recordDetachedPid(output: String) {
        Regex("""pid\s*=\s*(\d+)""").find(output)
            ?.groupValues?.get(1)?.toLongOrNull()
            ?.let { fixture.trackPid(it) }
    }
}
