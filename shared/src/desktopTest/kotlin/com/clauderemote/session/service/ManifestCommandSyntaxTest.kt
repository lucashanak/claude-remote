package com.clauderemote.session.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression guard for [ManifestCommands]: the remote shell one-liners that
 * maintain `sessions.json` and the close tombstones. Same rationale as
 * RestoreScriptSyntaxTest — these strings are exec'd over SSH with their output
 * discarded, so a shell typo ships to production servers and fails SILENTLY:
 * the manifest stops being pushed, or a close stops propagating, with nothing
 * in the app to show for it.
 *
 * Beyond `bash -n`, the tombstone/probe/clear trio is also EXECUTED against a
 * temp `$HOME` here — they are the mechanism that decides whether a session
 * closed on one device stays closed on the others, and the round-trip
 * (write → probe → clear → probe) is exactly what the multi-device bug broke.
 *
 * Lives in `desktopTest` because it shells out to bash; an Android unit test
 * has no usable bash.
 */
class ManifestCommandSyntaxTest {

    private fun bashAvailable(): Boolean = try {
        ProcessBuilder("bash", "-c", "exit 0").start().waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private fun flockAvailable(): Boolean = try {
        ProcessBuilder("bash", "-c", "command -v flock >/dev/null").start().waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private fun assertBashSyntaxValid(command: String, label: String) {
        val tmp = File.createTempFile("manifest-cmd", ".sh")
        tmp.deleteOnExit()
        try {
            tmp.writeText(command)
            val proc = ProcessBuilder("bash", "-n", tmp.absolutePath).start()
            val stderr = proc.errorStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit != 0) fail("`bash -n` failed for $label (exit=$exit):\n$stderr\n--- command ---\n$command")
        } finally {
            tmp.delete()
        }
    }

    /** Run [command] with HOME pointed at [home]; returns stdout. */
    private fun run(command: String, home: File): String {
        val pb = ProcessBuilder("bash", "-c", command)
        pb.environment()["HOME"] = home.absolutePath
        pb.redirectErrorStream(false)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return out
    }

    private fun tombstoned(out: String): Boolean =
        SessionPersistenceService.parseCloseState(out, NAME, json)?.tombstoned
            ?: fail("closeState produced no verdict line:\n$out")

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** A name with a quote and a space — both legal in tmux, both shell hazards. */
    private companion object {
        const val NAME = "claude-server-my proj's tab"
    }

    // ---- syntax ------------------------------------------------------------

    @Test
    fun everyCommand_passesBashSyntaxCheck() {
        if (!bashAvailable()) {
            println("bash not on PATH — skipping everyCommand_passesBashSyntaxCheck")
            return
        }
        val commands = listOf(
            "tombstone(durable)" to ManifestCommands.tombstone(NAME, durable = true),
            "tombstone(transient)" to ManifestCommands.tombstone(NAME, durable = false),
            "closeState" to ManifestCommands.closeState(NAME),
            "clearTombstone" to ManifestCommands.clearTombstone(NAME),
            "pushMerge" to ManifestCommands.pushMerge("server-1", 1234),
        )
        for ((label, command) in commands) assertBashSyntaxValid(command, label)
    }

    /**
     * The tmux name is user-supplied (ConnectScreen lets the user type one), so
     * every command must carry it single-quoted — an unquoted name with a space
     * silently tombstones the WRONG thing, and one with a quote breaks the
     * command outright.
     */
    @Test
    fun tmuxNameIsAlwaysSingleQuoted() {
        val quoted = ManifestCommands.sq(NAME)
        assertEquals("'claude-server-my proj'\\''s tab'", quoted)
        for (command in listOf(
            ManifestCommands.tombstone(NAME, durable = true),
            ManifestCommands.closeState(NAME),
            ManifestCommands.clearTombstone(NAME),
        )) {
            assertTrue(command.contains("N=$quoted"), "name not passed single-quoted in: $command")
        }
    }

    /** A name that would escape `forgotten.d/` never becomes a marker file. */
    @Test
    fun markerIsSkippedForNamesThatCannotBeFilenames() {
        assertTrue(ManifestCommands.isMarkerSafe("claude-server-ok"))
        assertTrue(!ManifestCommands.isMarkerSafe("a/b"))
        assertTrue(!ManifestCommands.isMarkerSafe("a\nb"))
        val cmd = ManifestCommands.tombstone("a/b", durable = true)
        assertTrue(!cmd.contains("forgotten.d"), "a slashed name must not be written as a marker file: $cmd")
        assertTrue(cmd.contains("forgotten"), "the transient tombstone must still be written: $cmd")
    }

    // ---- behaviour ---------------------------------------------------------

    /**
     * The close→probe round-trip other devices depend on: after a durable
     * close, the probe says tombstoned; after the user relaunches that same
     * tmux name, it does not (otherwise a reused name would stay poisoned and
     * the new session would be force-forgotten on every reconnect).
     */
    @Test
    fun tombstoneIsVisibleToTheProbeAndClearedByARelaunch() {
        if (!bashAvailable() || !flockAvailable()) {
            println("bash/flock unavailable — skipping tombstoneIsVisibleToTheProbeAndClearedByARelaunch")
            return
        }
        val home = File.createTempFile("manifest-home", "").apply { delete(); mkdirs() }
        try {
            assertTrue(!tombstoned(run(ManifestCommands.closeState(NAME), home)), "clean server must report no tombstone")

            run(ManifestCommands.tombstone(NAME, durable = true), home)
            assertTrue(tombstoned(run(ManifestCommands.closeState(NAME), home)), "a recorded close must be visible to peers")

            run(ManifestCommands.clearTombstone(NAME), home)
            assertTrue(!tombstoned(run(ManifestCommands.closeState(NAME), home)), "relaunching the name must un-poison it")
        } finally {
            home.deleteRecursively()
        }
    }

    /**
     * A DURABLE tombstone must survive the drift daemon self-cleaning the
     * transient `forgotten` file (it does so within ~60 s of the close) —
     * that's the whole point: a device that was asleep during the close still
     * learns about it when it wakes up.
     */
    @Test
    fun durableTombstoneOutlivesTheTransientFile() {
        if (!bashAvailable() || !flockAvailable()) {
            println("bash/flock unavailable — skipping durableTombstoneOutlivesTheTransientFile")
            return
        }
        val home = File.createTempFile("manifest-home", "").apply { delete(); mkdirs() }
        try {
            run(ManifestCommands.tombstone(NAME, durable = true), home)
            // Simulate drift's tombstone self-clean.
            File(home, ".claude-remote/forgotten").writeText("")
            assertTrue(
                tombstoned(run(ManifestCommands.closeState(NAME), home)),
                "durable marker must still answer after drift cleaned the transient tombstone",
            )

            // A transient-only close (the remote-scan prune's guess, not a user
            // close) must NOT survive it — a wrong guess may not durably kill a
            // session on every device.
            val other = "claude-server-pruned"
            run(ManifestCommands.tombstone(other, durable = false), home)
            File(home, ".claude-remote/forgotten").writeText("")
            val out = run(ManifestCommands.closeState(other), home)
            assertTrue(
                SessionPersistenceService.parseCloseState(out, other, json)?.tombstoned == false,
                "a transient tombstone must not outlive drift's self-clean:\n$out",
            )
        } finally {
            home.deleteRecursively()
        }
    }

    /** The probe also carries the manifest, so one round-trip answers both questions. */
    @Test
    fun closeStateReportsManifestMembership() {
        if (!bashAvailable() || !flockAvailable()) {
            println("bash/flock unavailable — skipping closeStateReportsManifestMembership")
            return
        }
        val home = File.createTempFile("manifest-home", "").apply { delete(); mkdirs() }
        try {
            File(home, ".claude-remote").mkdirs()
            File(home, ".claude-remote/sessions.json").writeText(
                """[{"id":"s1","serverId":"srv","folder":"/w","tmuxSessionName":"$NAME"}]"""
            )
            val state = SessionPersistenceService.parseCloseState(
                run(ManifestCommands.closeState(NAME), home), NAME, json,
            ) ?: fail("no verdict line")
            assertTrue(state.tracked, "a session listed in the manifest must read as tracked")
            assertTrue(!state.tombstoned)

            val gone = SessionPersistenceService.parseCloseState(
                run(ManifestCommands.closeState("claude-server-absent"), home), "claude-server-absent", json,
            ) ?: fail("no verdict line")
            assertTrue(!gone.tracked, "a session absent from the manifest must read as untracked")
        } finally {
            home.deleteRecursively()
        }
    }
}
