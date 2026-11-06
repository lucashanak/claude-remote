package com.clauderemote.session

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V16: what the answer of the creation command means ([FreshCreate.parse]), the command itself
 * ([ClaudeConfig.buildFreshCreateCommand]) and the next name after a taken one ([SessionOrchestrator.nextFreshName]).
 * New API of round 6: on 811d7fb this class does not compile (the behaviour is shown red by [FreshCreateTest]).
 */
class FreshCreateParseTest {
    private val nonce = "AbC123xyz"
    private val rc = ClaudeConfig.FRESH_CREATE_RC
    private val owner = ClaudeConfig.FRESH_CREATE_OWNER
    private val absent = ClaudeConfig.FRESH_CREATE_ABSENT
    private var orchestrator: SessionOrchestrator? = null

    @AfterTest fun tearDown() { orchestrator?.shutdownForTest() }

    @Test fun theSessionOfTheNameIsOursOnlyWhenItCarriesThisLaunchsNonce() {
        assertEquals(FreshCreate.Result.Ours, FreshCreate.parse("${rc}0\n${owner}CR_LAUNCH=$nonce\n", nonce), "created now")
        assertEquals(FreshCreate.Result.Ours, FreshCreate.parse("duplicate session: x\n${rc}1\n${owner}CR_LAUNCH=$nonce\n", nonce),
            "a retry after a lost answer: the session that try created")
        assertEquals(FreshCreate.Result.Taken, FreshCreate.parse("duplicate session: x\n${rc}1\n${owner}CR_LAUNCH=other\n", nonce), "another launch's")
        assertEquals(FreshCreate.Result.Taken, FreshCreate.parse("duplicate session: x\n${rc}1\n${owner}\n", nonce), "a session nobody's launch created")
        assertEquals(FreshCreate.Result.Taken, FreshCreate.parse("${rc}1\n${owner}-CR_LAUNCH\n", nonce))
        assertEquals(FreshCreate.Result.Ours, FreshCreate.parse("${rc}0\n$absent\n", nonce), "created and gone again: the attach rebuilds it")
        val failed = FreshCreate.parse("claude-remote: not starting a tmux server (socket guard exit 4)\n${rc}1\n$absent\n", nonce)
        assertTrue(failed is FreshCreate.Result.Failed && "socket guard exit 4" in failed.why, "$failed")
    }

    @Test fun aMissingOrCutAnswerIsLostNeverOursOrTaken() {
        assertEquals(FreshCreate.Result.Lost, FreshCreate.parse(null, nonce))
        assertEquals(FreshCreate.Result.Lost, FreshCreate.parse("", nonce))
        assertEquals(FreshCreate.Result.Lost, FreshCreate.parse("${rc}0\n", nonce), "cut before the owner check")
        // the command's own text (a shell echoing it) never reads as an answer: the markers there are not at a line start
        val cmd = ClaudeConfig.buildFreshCreateCommand("claude-box-work", "/work", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, "u-u-i-d", null, nonce)
        assertEquals(FreshCreate.Result.Lost, FreshCreate.parse(cmd, nonce))
    }

    @Test fun theCreationIsOneDetachedNewSessionThatNeverKillsOrAttachesATakenName() {
        val cmd = ClaudeConfig.buildFreshCreateCommand("claude-box-work", "/work", ClaudeMode.NORMAL, ClaudeModel.DEFAULT,
            "0f6c2a4e-1b2c-4d5e-8f90-123456789abc", null, nonce)
        // the run is quoted once more inside the server's limit (`timeout … sh -c '…'`, K6-1)
        assertTrue("tmux new-session -d -x 200 -y 50 -s '\\''claude-box-work'\\'' -e CR_LAUNCH=$nonce " in cmd, cmd)
        assertTrue("kill-session" !in cmd && "attach-session" !in cmd && "elif tmux has-session" !in cmd, cmd)
        assertTrue("--session-id 0f6c2a4e-1b2c-4d5e-8f90-123456789abc" in cmd && "--resume" !in cmd, "a new conversation under the tab's uuid: $cmd")
        assertTrue("tmux show-environment -t '=claude-box-work' CR_LAUNCH" in cmd, cmd)
        assertFailsWith<IllegalArgumentException> {
            ClaudeConfig.buildFreshCreateCommand("x", "/w", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, null, null, "a b")
        }
    }

    // ---- round 7: the server's own limits (K6-1) and the cleanup of an own session (R6-2/R6-3), run by a real `sh` ----

    @Test fun aCreationTheServersLimitCutShortIsLost() {   // K6-1
        assertEquals(FreshCreate.Result.Lost, FreshCreate.parse("${rc}124\n$absent\n", nonce), "cut short: whether it created is unknown, try again")
    }

    @Test fun theServerEndsAHungCreationItselfAndItReadsAsLost() {   // K6-1
        FakeServer().use { srv ->
            srv.flag("hang-new")
            val (out, took) = srv.run(createCmd())
            assertTrue(out != null, "the server ended the hung creation itself (gave up waiting after $took ms)")
            assertTrue(took < SessionOrchestrator.FRESH_CREATE_MS, "within the client's limit ($took ms)")
            assertTrue("${rc}124" in out!!, out)
            assertEquals(FreshCreate.Result.Lost, FreshCreate.parse(out, nonce), out)
            assertTrue(srv.hungGone(), "the hung tmux client is gone on the server, so sshd's session is too")
        }
    }

    @Test fun theServerEndsAHungOwnerCheckItselfAndItReadsAsLost() {   // K6-1
        FakeServer().use { srv ->
            srv.flag("hang-has")
            val (out, took) = srv.run(createCmd())
            assertTrue(out != null, "the server ended the hung check itself (gave up waiting after $took ms)")
            assertTrue(took < (ClaudeConfig.FRESH_CHECK_LIMIT_S + 2) * 1000L, "the check's own limit ($took ms)")
            assertEquals(FreshCreate.Result.Lost, FreshCreate.parse(out, nonce), "a check cut short is neither absent nor taken: $out")
            assertTrue(srv.hungGone())
        }
    }

    @Test fun theServersLimitsAddUpBelowTheClients() {   // K6-1
        assertTrue((ClaudeConfig.FRESH_CREATE_SERVER_LIMIT_S + 2 * ClaudeConfig.FRESH_CHECK_LIMIT_S) * 1000L < SessionOrchestrator.FRESH_CREATE_MS)
    }

    @Test fun theCleanupKillsOnlyASessionCarryingTheLaunchsNonce() {   // R6-2/R6-3
        FakeServer().use { srv ->
            srv.owner("CR_LAUNCH=$nonce")
            val (out, _) = srv.run(ClaudeConfig.buildOwnSessionCleanupCommand("claude-box-work", nonce))
            assertTrue(srv.killed() == listOf("kill-session -t =claude-box-work") && ClaudeConfig.FRESH_CLEANED in out.orEmpty(), "ours: killed, exactly it ($out)")
        }
        for (other in listOf("CR_LAUNCH=other", "CR_LAUNCH=${nonce}x", "-CR_LAUNCH", null)) FakeServer().use { srv ->
            if (other != null) srv.owner(other)
            val (out, _) = srv.run(ClaudeConfig.buildOwnSessionCleanupCommand("claude-box-work", nonce))
            assertTrue(srv.killed().isEmpty() && ClaudeConfig.FRESH_CLEANED !in out.orEmpty(), "owner $other: never killed ($out)")
        }
    }

    private fun createCmd() = ClaudeConfig.buildFreshCreateCommand("claude-box-work", "/work", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, "u-u-i-d", null, nonce)

    /** A server of one fake `tmux` (first on PATH, HOME of its own: no socket guard, never the real tmux) that a real `sh` runs commands on. */
    private class FakeServer : AutoCloseable {
        val dir: java.io.File = java.nio.file.Files.createTempDirectory("k7-srv").toFile()
        init {
            java.io.File(dir, "tmux").apply {
                writeText(
                    """
                    #!/bin/sh
                    D='${dir.absolutePath}'
                    case "${'$'}1" in
                      list-sessions) exit 0 ;;
                      new-session) [ -f "${'$'}D/hang-new" ] && { echo ${'$'}${'$'} > "${'$'}D/pid"; exec sleep 60; }; exit 0 ;;
                      has-session) [ -f "${'$'}D/hang-has" ] && { echo ${'$'}${'$'} > "${'$'}D/pid"; exec sleep 60; }; [ -f "${'$'}D/owner" ] ;;
                      show-environment) [ -f "${'$'}D/owner" ] && { cat "${'$'}D/owner"; exit 0; }; echo 'unknown variable' >&2; exit 1 ;;
                      kill-session) echo "${'$'}*" >> "${'$'}D/killed"; exit 0 ;;
                      *) exit 0 ;;
                    esac
                    """.trimIndent() + "\n",
                )
                setExecutable(true)
            }
        }
        fun flag(name: String) { java.io.File(dir, name).writeText("") }
        fun owner(line: String) { java.io.File(dir, "owner").writeText(line + "\n") }
        fun killed(): List<String> = java.io.File(dir, "killed").takeIf { it.exists() }?.readLines().orEmpty()

        /** Output of [cmd], or null when it was still running at the client's limit; and how long it ran. */
        fun run(cmd: String): Pair<String?, Long> {
            val pb = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true)
            pb.environment()["PATH"] = "${dir.absolutePath}:${System.getenv("PATH")}"
            pb.environment()["HOME"] = dir.absolutePath
            val t0 = System.currentTimeMillis()
            val p = pb.start()
            val out = StringBuilder()
            val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
            val done = p.waitFor(SessionOrchestrator.FRESH_CREATE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            val took = System.currentTimeMillis() - t0
            if (!done) {
                p.descendants().forEach { it.destroyForcibly() }
                p.destroyForcibly()
                return null to took
            }
            reader.join(2_000)
            return out.toString() to took
        }

        fun hungGone(): Boolean {
            val pid = java.io.File(dir, "pid").readText().trim()
            val end = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < end && java.io.File("/proc/$pid").exists()) Thread.sleep(50)
            return !java.io.File("/proc/$pid").exists()
        }

        override fun close() {
            java.io.File(dir, "pid").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()?.let { pid ->
                ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
            }
            com.clauderemote.testsupport.safeDeleteTree(dir)
        }
    }

    @Test fun theNextNameCountsOnFromTheRequestedOne() {
        val o = SessionOrchestrator(ServerStorage(FakeKeyValueStore()), TabManager(), SessionStorage(FakeKeyValueStore())).also { orchestrator = it }
        assertEquals("claude-box-work-2", o.nextFreshName("box", "/work", "claude-box-work", setOf("claude-box-work")))
        assertEquals("claude-box-work-4", o.nextFreshName("box", "/work", "claude-box-work-3", setOf("claude-box-work-3")))
        assertEquals("claude-box-work-2-yolo", o.nextFreshName("box", "/work", "claude-box-work-yolo", setOf("claude-box-work-yolo")))
        assertEquals("claude-box-work-2--my-alias", o.nextFreshName("box", "/work", "claude-box-work--my-alias", setOf("claude-box-work--my-alias")))
        assertEquals("claude-box-work-3", o.nextFreshName("box", "/work", "claude-box-work", setOf("claude-box-work", "claude-box-work-2")))
        assertEquals("some-name-2", o.nextFreshName("box", "/work", "some-name", setOf("some-name")), "a name of another shape: numbered after it")
    }
}
