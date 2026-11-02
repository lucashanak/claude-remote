package com.clauderemote.canarytest

import com.clauderemote.testsupport.LiveFileCanary
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Self-test of [LiveFileCanary] against a FAKE live home — never touches the real one. */
class LiveFileCanaryTest {
    private lateinit var base: File
    private lateinit var live: File
    private lateinit var testRoot: File
    private lateinit var testHome: File
    private lateinit var canary: LiveFileCanary
    private val token = LiveFileCanary.newToken()

    @BeforeTest fun setUp() {
        base = Files.createTempDirectory("canary-selftest").toFile()
        live = File(base, "livehome").apply { mkdirs() }
        File(live, ".claude").mkdirs()
        File(live, ".claude-remote/accounts/x").mkdirs()
        File(live, ".claude-remote/logs").mkdirs()
        File(live, ".cache/claude-remote").mkdirs()
        File(live, ".claude/settings.json").writeText("{\"hooks\":{}}")
        File(live, ".claude-remote/accounts/x/settings.json").writeText("{\"acct\":1}")
        File(live, ".claude-remote/restore.sh").writeText("#!/bin/sh\n")
        testRoot = File(base, "test").apply { mkdirs() }
        testHome = File(testRoot, "home").apply { mkdirs() }
        canary = LiveFileCanary(live)
    }

    @AfterTest fun tearDown() { base.deleteRecursively() }

    private fun guard(block: (LiveFileCanary.GuardEnv) -> Unit) = canary.guard(token, testHome, testRoot, block)

    @Test fun cleanRunPasses() {
        assertEquals(42, canary.guard(token, testHome, testRoot) {
            File(testHome, "settings.json").writeText(token) // token lands in the TEST home only
            42
        })
    }

    @Test fun tokenInLiveSettingsFails() {
        val e = assertFailsWith<AssertionError> { guard { File(live, ".claude/settings.json").appendText("x $token y") } }
        assertTrue(e.message!!.contains("settings.json"))
    }

    @Test fun tokenInLiveClaudeRemoteOrCacheFails() {
        val e0 = assertFailsWith<AssertionError> { guard { File(live, ".claude-remote/restore.sh").appendText(token) } }
        assertTrue(e0.message!!.contains("test token found in LIVE file"), e0.message)
        File(live, ".cache/claude-remote/other.json").writeText("{}") // pre-existing: only the TOKEN scan can catch the append
        val e = assertFailsWith<AssertionError> { guard { File(live, ".cache/claude-remote/other.json").appendText(token) } }
        assertTrue(e.message!!.contains("test token found in LIVE file"), e.message)
    }

    @Test fun tokenInSettingsLocalAndInLogsFails() {
        assertFailsWith<AssertionError> { guard { File(live, ".claude/settings.local.json").writeText(token) } }
        // logs/ is excluded from the PATH set (they grow live) but its content is still scanned
        File(live, ".claude-remote/logs/x.log").writeText("old\n")
        val e = assertFailsWith<AssertionError> { guard { File(live, ".claude-remote/logs/x.log").appendText(token) } }
        assertTrue(e.message!!.contains("x.log"))
    }

    @Test fun tokenInAnotherEncodingIsFound() {
        File(live, ".claude-remote/enc.bin").writeText("")
        for (cs in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val e = assertFailsWith<AssertionError> { guard { File(live, ".claude-remote/enc.bin").writeBytes(token.toByteArray(cs)) } }
            assertTrue(e.message!!.contains("test token found in LIVE file"), e.message)
        }
    }

    @Test fun tokenInAPathNameIsFoundEvenWhenTheFileIsEmpty() {
        val e = assertFailsWith<AssertionError> {
            guard { File(live, ".claude-remote/forgotten.d").apply { mkdirs() }.resolve(token).writeText("") }
        }
        assertTrue(e.message!!.contains("forgotten.d"), e.message)
        // a directory NAME carrying the token inside logs/ (excluded from the path set) is found by the name scan
        val e2 = assertFailsWith<AssertionError> {
            guard { File(live, ".claude-remote/logs/$token").mkdirs() }
        }
        assertTrue(e2.message!!.contains("NAME"), e2.message)
    }

    @Test fun tokenSplitAcrossReadBufferBoundaryIsFoundAtEveryOffset() {
        val len = token.toByteArray().size
        // every split of the token over the 64 KiB read boundary, including 1 byte before and 1 byte after it
        for (before in 1 until len) {
            val f = File(live, ".claude-remote/logs/big-$before.log") // logs/ is not in the path set: only the token scan can fail here
            val e = assertFailsWith<AssertionError>("split $before/${len - before}") {
                guard { f.writeBytes(ByteArray(65536 - before) { 'a'.code.toByte() } + token.toByteArray() + ByteArray(100)) }
            }
            assertTrue(e.message!!.contains("test token found in LIVE file"), e.message)
            f.delete()
        }
    }

    @Test fun utf16TokenSplitAfterAllButItsLastByteIsFound() {
        // the longest needle (UTF-16) decides how much must be carried over the buffer boundary
        for (cs in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val bytes = token.toByteArray(cs)
            for (before in listOf(bytes.size - 1, 1)) {
                val f = File(live, ".claude-remote/logs/u16-${cs.name()}-$before.log")
                val e = assertFailsWith<AssertionError>("${cs.name()} $before") {
                    guard { f.writeBytes(ByteArray(65536 - before) { 'a'.code.toByte() } + bytes + ByteArray(100)) }
                }
                assertTrue(e.message!!.contains("test token found in LIVE file"), e.message)
                f.delete()
            }
        }
    }

    @Test fun nonExistentEventsFileMustStayNonExistent() {
        val e = assertFailsWith<AssertionError> {
            guard { File(live, ".cache/claude-remote/events").writeText("no token in here") }
        }
        assertTrue(e.message!!.contains("did not exist"))
        // ...but an events file that already existed may be appended to by live sessions without the token
        File(live, ".cache/claude-remote/events").writeText("old\n")
        guard { File(live, ".cache/claude-remote/events").appendText("live line\n") }
    }

    @Test fun newOrVanishedPathUnderLiveRootsFailsEvenWithoutToken() {
        val a = assertFailsWith<AssertionError> { guard { File(live, ".claude-remote/hookev.installed").writeText("1730000000\n") } }
        assertTrue(a.message!!.contains("hookev.installed") && a.message!!.contains("did not exist"))
        File(live, ".claude-remote/hookev.installed").delete()
        val b = assertFailsWith<AssertionError> { guard { File(live, ".claude-remote/restore.sh").delete() } }
        assertTrue(b.message!!.contains("restore.sh") && b.message!!.contains("gone"))
        val c = assertFailsWith<AssertionError> { guard { File(live, ".claude-remote/accounts/y").mkdirs() } }
        assertTrue(c.message!!.contains("accounts"))
        // growing logs are NOT a path change
        guard { File(live, ".claude-remote/logs/new-install.log").writeText("line\n") }
    }

    @Test fun settingsFilesAreCheckedByContentHashAndExistence() {
        val acct = File(live, ".claude-remote/accounts/x/settings.json")
        val e = assertFailsWith<AssertionError> { guard { acct.writeText("{}") } } // changed, NO token
        assertTrue(e.message!!.contains("accounts/x/settings.json") && e.message!!.contains("modified"))
        assertTrue(e.message!!.contains("FALSE ALARM"), "must say a live rewrite can be a false alarm: ${e.message}")
        acct.writeText("{\"acct\":1}")
        val del = assertFailsWith<AssertionError> { guard { File(live, ".claude/settings.json").delete() } }
        assertTrue(del.message!!.contains("deleted"))
        File(live, ".claude/settings.json").writeText("{\"hooks\":{}}")
        val local = assertFailsWith<AssertionError> { guard { File(live, ".claude/settings.local.json").writeText("{}") } }
        assertTrue(local.message!!.contains("created"))
        File(live, ".claude/settings.local.json").delete()
        val newAcct = assertFailsWith<AssertionError> {
            guard { File(live, ".claude-remote/accounts/x/settings.local.json").writeText("{}") }
        }
        assertTrue(newAcct.message!!.contains("settings.local.json"))
        // truncation to the same-length-looking empty object is still a change
        val trunc = assertFailsWith<AssertionError> { guard { File(live, ".claude/settings.json").writeText("") } }
        assertTrue(trunc.message!!.contains("modified"))
    }

    @Test fun credentialsFilesAreNeverOpenedButTheirNamesAreStillChecked() {
        val cred = File(live, ".claude-remote/accounts/x/.credentials.json").apply { writeText("secret") }
        // content with the token is not scanned (never opened), and an existing credentials file is not a path change
        guard { cred.writeText(token) }
        assertFailsWith<AssertionError> { guard { File(live, ".claude-remote/accounts/x/$token.credentials.json").writeText("") } }
    }

    @Test fun mtimeChangeAloneIsNotAFailure() {
        guard { File(live, ".claude/settings.json").setLastModified(System.currentTimeMillis() + 100_000) }
    }

    @Test fun homeMustBeUnderTempRoot() {
        assertFailsWith<AssertionError> { canary.assertIsolated(File(base, "elsewhere").apply { mkdirs() }, testRoot) }
        assertFailsWith<AssertionError> { canary.assertIsolated(live, base) } // live home itself
        assertFailsWith<AssertionError> { canary.assertIsolated(File(testRoot, "../escape"), testRoot) } // .. escape
        canary.assertIsolated(testHome, testRoot)
    }

    @Test fun tempRootInsideTheLiveHomeIsRejected() {
        val inside = File(live, "tmp").apply { mkdirs() }
        val home = File(inside, "h").apply { mkdirs() }
        val e = assertFailsWith<AssertionError> { canary.assertIsolated(home, inside) }
        assertTrue(e.message!!.contains("inside the live home"), e.message)
    }

    @Test fun tempRootThatIsAnAncestorOfTheLiveHomeIsRejected() {
        // `base` contains `livehome`; a HOME below it that is neither the live home nor its ancestor
        val home = File(base, "somewhere").apply { mkdirs() }
        val e = assertFailsWith<AssertionError> { canary.assertIsolated(home, base) }
        assertTrue(e.message!!.contains("ancestor of the live home"), e.message)
    }

    @Test fun symlinkEscapeFromTempRootIsRejected() {
        val link = File(testRoot, "linkhome")
        Files.createSymbolicLink(link.toPath(), File(base, "elsewhere").apply { mkdirs() }.toPath())
        assertFailsWith<AssertionError> { canary.assertIsolated(link, testRoot) }
    }

    @Test fun blockDoesNotRunWhenHomeIsNotIsolated() {
        var ran = false
        assertFailsWith<AssertionError> { canary.guard(token, live, base) { ran = true } }
        assertEquals(false, ran)
    }

    @Test fun guardHandsTheBlockAnIsolatedProcessEnvironment() {
        guard { env ->
            assertEquals(testHome.absolutePath, env.variables["HOME"])
            assertTrue(env.variables.getValue("TMUX_TMPDIR").startsWith(testRoot.absolutePath))
            val pb = ProcessBuilder("true")
            pb.environment()["TMUX"] = "/tmp/tmux-1000/default,1,0"
            pb.environment()["TMUX_PANE"] = "%1"
            env.applyTo(pb)
            assertNull(pb.environment()["TMUX"])
            assertNull(pb.environment()["TMUX_PANE"])
            assertEquals(testHome.absolutePath, pb.environment()["HOME"])
            assertEquals(env.variables["TMUX_TMPDIR"], pb.environment()["TMUX_TMPDIR"])
        }
    }

    @Test fun realHomeDefaultNoOpBlockPasses() {
        // Against the REAL home: only reads, writes nothing; proves the default wiring works on this box.
        val root = Files.createTempDirectory("canary-real").toFile()
        try { LiveFileCanary().guard(LiveFileCanary.newToken(), File(root, "h").apply { mkdirs() }, root) {} }
        finally { root.deleteRecursively() }
    }
}

/**
 * Every Test task must run with no TMUX/TMUX_PANE and with TMUX_TMPDIR pointing below
 * `build/` (build.gradle.kts, tasks.withType<Test>), so a stray `tmux` call cannot reach the
 * live server on the default socket.
 */
class DesktopTestEnvTest {
    @Test fun tmuxEnvIsStrippedFromTheTestJvm() {
        assertNull(System.getenv("TMUX"), "TMUX leaked into the test JVM — a stray tmux call would hit the LIVE server")
        assertNull(System.getenv("TMUX_PANE"))
    }

    @Test fun tmuxSocketDirectoryIsPrivateUnderBuild() {
        val dir = System.getenv("TMUX_TMPDIR")
        assertNotNull(dir, "TMUX_TMPDIR must be set for tests, else tmux uses the live default socket")
        assertTrue("/build/" in dir && dir.endsWith("/tmp/test-tmux"), "TMUX_TMPDIR=$dir must live under the module's build/ dir")
        assertTrue(File(dir).isDirectory, "the directory is created before the tests run")
        assertNotEquals("/tmp", dir)
    }

    @Test fun tmuxCannotSeeTheLiveServer() {
        val tmuxOnPath = (System.getenv("PATH") ?: "").split(File.pathSeparator).any { File(it, "tmux").canExecute() }
        if (!tmuxOnPath) return
        // Read-only query. With the private TMUX_TMPDIR there is no server on this socket, so tmux
        // exits non-zero ("no server running" / "error connecting"); the live server would answer 0.
        val p = ProcessBuilder("tmux", "list-sessions", "-F", "#{socket_path}").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "tmux hung")
        assertNotEquals(0, p.exitValue(), "tmux answered from the test JVM — is it talking to the LIVE server? output=$out")
        assertTrue(!out.contains("/tmp/tmux-"), "tmux reported the default socket: $out")
    }
}
