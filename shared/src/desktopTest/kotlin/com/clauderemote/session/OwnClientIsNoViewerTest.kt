package com.clauderemote.session

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CloseFactsCheck
import com.clauderemote.session.service.CloseFactsCommands
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.testsupport.safeDeleteTree
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail

/**
 * "Dívá se jiné zařízení" must never be the closing device itself (Jarvis F0, `f0-server.md` question 1).
 *
 * During the undo window the tab is only hidden: its shell and its `tmux attach` client stay up (PendingCloses), so
 * the closing device's own client is attached and active while the facts run. What keeps it out of "someone else" is
 * the marker file: [SessionOrchestrator.buildAttachCommand]'s preamble records the attach's tty under this device's
 * key, and the facts the same orchestrator sends name that same file ([SessionOrchestrator.ownClientMarker]).
 *
 * Runs the real attach preamble and the real facts body in bash under a temp `$HOME`, with `tmux` and `tty` replaced
 * by fakes that answer from files (no tmux server is involved at all). Two orchestrators = two devices, each with its
 * own random device key.
 */
class OwnClientIsNoViewerTest {
    private val name = "claude-server-own-view"
    private val server = SshServer(id = "s1", name = "one", host = "unreachable.invalid", username = "u")
    private val tab = ClaudeSession(
        id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = name, connectionType = ConnectionType.SSH,
    )

    private val fakeTmux = """
        #!/bin/bash
        # fake tmux: never runs a real one; clients live in ${'$'}HOME/fake-clients as "<tty> <activity>" lines
        F="${'$'}HOME/fake-clients"; touch "${'$'}F"
        case "${'$'}1" in
          has-session) exit 0 ;;
          list-clients)
            fmt=""; while [ ${'$'}# -gt 0 ]; do [ "${'$'}1" = -F ] && fmt=${'$'}2; shift; done
            while read -r t a; do
              [ -n "${'$'}t" ] || continue
              case "${'$'}fmt" in *client_activity*) echo "${'$'}t ${'$'}a" ;; *) echo "${'$'}t" ;; esac
            done < "${'$'}F" ;;
          detach-client)
            t=""; while [ ${'$'}# -gt 0 ]; do [ "${'$'}1" = -t ] && t=${'$'}2; shift; done
            awk -v t="${'$'}t" '${'$'}1 != t' "${'$'}F" > "${'$'}F.tmp" && mv "${'$'}F.tmp" "${'$'}F" ;;
          *) exit 0 ;;
        esac
    """.trimIndent() + "\n"

    private class Box(val home: File, val bin: File)

    private fun box(): Box {
        val home = java.nio.file.Files.createTempDirectory("own-client-home").toFile()
        val bin = File(home, "bin").apply { mkdirs() }
        File(bin, "tmux").apply { writeText(fakeTmux); setExecutable(true) }
        File(bin, "tty").apply { writeText("#!/bin/bash\ncat \"\$HOME/fake-tty\"\n"); setExecutable(true) }
        return Box(home, bin)
    }

    private fun bash(b: Box, cmd: String, stdin: ByteArray? = null): String {
        val p = ProcessBuilder("bash", "-c", cmd).directory(b.home).redirectErrorStream(true).also {
            val env = it.environment()
            env["HOME"] = b.home.absolutePath
            env["PATH"] = b.bin.absolutePath + File.pathSeparator + env["PATH"].orEmpty()
        }.start()
        p.outputStream.use { o -> if (stdin != null) o.write(stdin) }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); fail("timeout") }
        return out
    }

    private fun now() = System.currentTimeMillis() / 1000

    /** A device attaches the way the app does: its preamble runs on [tty], then tmux has one more client there. */
    private fun attach(b: Box, device: SessionOrchestrator, tty: String, activity: Long = now()) {
        File(b.home, "fake-tty").writeText(tty)
        bash(b, device.buildAttachCommand(name))
        File(b.home, "fake-clients").appendText("$tty $activity\n")
    }

    /** What closing the tab on [device] gets back from the server, through the same check the app wires in. */
    private fun closeCheck(b: Box, device: SessionOrchestrator): Pair<CloseCheck?, String> {
        var raw = ""
        val check = CloseFactsCheck(read = { _, cmd, stdin, _ -> bash(b, cmd, stdin).also { raw = it } }, selfMarker = device::ownClientMarker)
        return runBlocking { check.check(tab) } to raw
    }

    private fun clientsLine(raw: String) = raw.lines().single { it.startsWith("FACT clients ") }

    @Test fun theClosingDevicesOwnClientNeverBlocks() {
        val b = box()
        val phone = SessionOrchestrator(ServerStorage(FakeKeyValueStore()), TabManager())
        val desktop = SessionOrchestrator(ServerStorage(FakeKeyValueStore()), TabManager())
        try {
            // the phone is attached and was typing a second ago: its own client, so nobody else is watching
            attach(b, phone, "/dev/pts/7")
            val (own, raw) = closeCheck(b, phone)
            assertEquals("FACT clients 0 1", clientsLine(raw), "own client found by its marker, no one else")
            assertEquals(CloseCheck.Clear, own)

            // a reconnect: the half-open old client is still listed when the new attach runs; the preamble drops it
            // and the marker follows the new tty
            attach(b, phone, "/dev/pts/8")
            assertFalse(File(b.home, "fake-clients").readText().contains("/dev/pts/7"), "the preamble detached the stale client")
            val (afterReconnect, raw2) = closeCheck(b, phone)
            assertEquals("FACT clients 0 1", clientsLine(raw2))
            assertEquals(CloseCheck.Clear, afterReconnect)

            // negative control: another device attached and active IS someone watching, from either side
            attach(b, desktop, "/dev/pts/20")
            val (fromPhone, raw3) = closeCheck(b, phone)
            assertEquals("FACT clients 1 1", clientsLine(raw3))
            assertContains((fromPhone as? CloseCheck.Block)?.text ?: fail("got $fromPhone"), "dívá se jiné zařízení")
            val (fromDesktop, _) = closeCheck(b, desktop)
            assertContains((fromDesktop as? CloseCheck.Block)?.text ?: fail("got $fromDesktop"), "dívá se jiné zařízení")

            // the other device gone quiet for longer than the viewer window: not watching any more
            File(b.home, "fake-clients").writeText("/dev/pts/8 ${now()}\n/dev/pts/20 ${now() - CloseFactsCommands.VIEWER_ACTIVE_S - 60}\n")
            assertEquals(CloseCheck.Clear, closeCheck(b, phone).first)
        } finally {
            phone.shutdownForTest()
            desktop.shutdownForTest()
            safeDeleteTree(b.home)
        }
    }
}
