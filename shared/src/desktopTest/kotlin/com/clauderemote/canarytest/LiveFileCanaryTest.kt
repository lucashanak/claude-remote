package com.clauderemote.canarytest

import com.clauderemote.testsupport.LiveFileCanary
import com.clauderemote.testsupport.safeDeleteTree
import java.io.File
import java.nio.file.Files
import java.util.Base64
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
import kotlin.test.fail

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
        // like the box: account dirs are symlinks into ~/.claude
        File(live, ".claude/hooks").mkdirs()
        File(live, ".claude/hooks/a.sh").writeText("#!/bin/sh\necho a\n")
        File(live, ".claude/projects").mkdirs()
        File(live, ".claude.json").writeText("{\"n\":1}")
        Files.createSymbolicLink(File(live, ".claude-remote/accounts/x/hooks").toPath(), File(live, ".claude/hooks").toPath())
        testRoot = File(base, "test").apply { mkdirs() }
        testHome = File(testRoot, "home").apply { mkdirs() }
        canary = LiveFileCanary(live, tmuxSocket = null) // the tests below never talk to a tmux server unless they start their own
    }

    @AfterTest fun tearDown() { safeDeleteTree(base) }

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


    // ---- ways of damaging the live home that the first version of the canary did not notice ----

    private fun failure(block: (LiveFileCanary.GuardEnv) -> Unit): AssertionError = assertFailsWith<AssertionError> { guard(block) }

    @Test fun truncatingAnExistingFileWithoutTokenFails() {
        val e = failure { File(live, ".claude-remote/restore.sh").writeText("") }
        assertTrue(e.message!!.contains("restore.sh") && e.message!!.contains("modified"), e.message)
    }

    @Test fun atomicRenameOverALiveFileFails() {
        val e = failure {
            val tmp = File(live, ".claude-remote/restore.sh.tmp.1").apply { writeText("#!/bin/sh\necho swapped\n") }
            Files.move(tmp.toPath(), File(live, ".claude-remote/restore.sh").toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        assertTrue(e.message!!.contains("restore.sh") && e.message!!.contains("modified"), e.message)
    }

    @Test fun deleteAndRecreateWithDifferentContentFails() {
        val e = failure { File(live, ".claude-remote/restore.sh").apply { delete(); writeText("#!/bin/sh\nrm -rf x\n") } }
        assertTrue(e.message!!.contains("restore.sh") && e.message!!.contains("modified"), e.message)
    }

    @Test fun writeThroughTheAccountSymlinkIntoClaudeHooksFails() {
        val viaAccount = File(live, ".claude-remote/accounts/x/hooks/a.sh")
        val e = failure { viaAccount.writeText("#!/bin/sh\necho changed, no token\n") }
        assertTrue(e.message!!.contains(".claude/hooks/a.sh") && e.message!!.contains("modified"), e.message)
        viaAccount.writeText("#!/bin/sh\necho a\n")
        val t = failure { viaAccount.appendText(token) }
        assertTrue(t.message!!.contains("test token found in LIVE file"), t.message)
    }

    @Test fun hookMarkerInAClaudeConfigDirFails() {
        val e = failure { File(live, ".claude/claude-remote-hookev.installed").writeText("1800000000\n") }
        assertTrue(e.message!!.contains("claude-remote-hookev.installed") && e.message!!.contains("created"), e.message)
        // the same marker written through an account's config dir symlink target
        File(live, ".claude/claude-remote-hookev.installed").delete()
    }

    @Test fun tokenInClaudeJsonAndInClaudeHooksFails() {
        val a = failure { File(live, ".claude.json").appendText(token) }
        assertTrue(a.message!!.contains("test token found in LIVE file") && a.message!!.contains(".claude.json"), a.message)
        File(live, ".claude.json").writeText("{\"n\":1}")
        val b = failure { File(live, ".claude/hooks/a.sh").appendText(token) }
        assertTrue(b.message!!.contains("test token found in LIVE file"), b.message)
    }

    @Test fun newDirectoryUnderClaudeHooksFails() {
        val e = failure { File(live, ".claude/hooks/sub").mkdirs() }
        assertTrue(e.message!!.contains("hooks/sub") && e.message!!.contains("created"), e.message)
    }

    @Test fun corruptionFollowedByAnExpectedExceptionStillFails() {
        val e = assertFailsWith<AssertionError> {
            canary.guard(token, testHome, testRoot) {
                File(live, ".claude/settings.json").writeText("{\"hooks\":\"$token\"}")
                throw IllegalStateException("the exception this test expects")
            }
        }
        assertTrue(e.message!!.contains("settings.json"), e.message)
        assertTrue(e.suppressed.any { it is IllegalStateException }, "the block's own exception must stay visible: ${e.suppressed.toList()}")
    }

    @Test fun aCleanBlockThatThrowsKeepsItsOwnException() {
        val e = assertFailsWith<IllegalStateException> { guard { throw IllegalStateException("boom") } }
        assertEquals("boom", e.message)
    }

    @Test fun detachedChildIsKilledBeforeTheCheckAndNeverWritesLater() {
        val late = File(live, ".claude-remote/late.txt")
        guard { env ->
            // the shell exits at once; its background child is re-parented to init and would write 2 s later
            env.start(ProcessBuilder("sh", "-c", "(sleep 2; echo late > '${late.path}') >/dev/null 2>&1 &")).waitFor(5, TimeUnit.SECONDS)
        }
        Thread.sleep(3500)
        assertTrue(!late.exists(), "a detached child outlived the guard and wrote into the live home")
    }

    @Test fun childStartedWithApplyToCarriesTheTagSoItIsReapedToo() {
        val late = File(live, ".claude-remote/late2.txt")
        guard { env ->
            env.applyTo(ProcessBuilder("sh", "-c", "(sleep 2; echo late > '${late.path}') >/dev/null 2>&1 &")).start().waitFor(5, TimeUnit.SECONDS)
        }
        Thread.sleep(3500)
        assertTrue(!late.exists(), "tagged detached child was not reaped")
    }

    @Test fun deletedLiveLogAndTruncatedOrRewrittenEventsFail() {
        File(live, ".claude-remote/logs/x.log").writeText("line one\nline two\n")
        File(live, ".cache/claude-remote/events").writeText("e1\ne2\n")
        val del = failure { File(live, ".claude-remote/logs/x.log").delete() }
        assertTrue(del.message!!.contains("x.log") && del.message!!.contains("deleted"), del.message)
        File(live, ".claude-remote/logs/x.log").writeText("line one\nline two\n")
        val trunc = failure { File(live, ".cache/claude-remote/events").writeText("e1\n") }
        assertTrue(trunc.message!!.contains("events") && trunc.message!!.contains("truncated or rewritten"), trunc.message)
        File(live, ".cache/claude-remote/events").writeText("e1\ne2\n")
        val rewrite = failure { File(live, ".cache/claude-remote/events").writeText("XX\nYY\nzzzz\n") }
        assertTrue(rewrite.message!!.contains("truncated or rewritten"), rewrite.message)
        // growing logs and appended events are the legitimate case
        guard { File(live, ".claude-remote/logs/x.log").appendText("line three\n"); File(live, ".cache/claude-remote/events").appendText("e3\n") }
    }

    @Test fun credentialsFileIsCheckedBySizeMtimeInodeAndNeverOpened() {
        val cred = File(live, ".claude-remote/accounts/x/.credentials.json").apply { writeText("secret") }
        // rewritten to a different size: caught (before: only the name was looked at)
        assertFailsWith<AssertionError> { guard { cred.writeText("") } }
        cred.writeText("secret")
        // same size, atomic replace = new inode: caught
        assertFailsWith<AssertionError> {
            guard {
                val tmp = File(cred.parentFile, "cred.tmp").apply { writeText("SECRET") }
                Files.move(tmp.toPath(), cred.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
        }
        // its name is still scanned for the token
        assertFailsWith<AssertionError> { guard { File(live, ".claude-remote/accounts/x/$token.credentials.json").writeText("") } }
    }

    /** Rewrite [f] the way Claude's refresh does: a temp file renamed over it (new inode). */
    private fun atomicReplace(f: File, text: String) {
        val tmp = File(f.parentFile, "cred.tmp").apply { writeText(text) }
        Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * The false alarm of HookMergeTest (davka A): a live Claude refreshed its OAuth token while the guard ran,
     * rewrote `~/.claude/.credentials.json` and removed `.oauth_refresh.lock.owner`. That is not damage.
     */
    @Test fun anOauthRefreshThatRemovesItsLockIsTolerated() {
        val cred = File(live, ".claude/.credentials.json").apply { writeText("secret") }
        val lockDir = File(live, ".claude/.oauth_refresh.lock").apply { mkdirs() }
        val owner = File(live, ".claude/.oauth_refresh.lock.owner").apply { writeText("{}") }
        guard {
            atomicReplace(cred, "SECRET-2")
            assertTrue(owner.delete() && lockDir.delete())
        }
    }

    /** A refresh is fast: its lock may appear and vanish between the two snapshots. Both kinds of config dir. */
    @Test fun anOauthRefreshWhoseLockComesAndGoesInsideTheGuardIsTolerated() {
        for (dir in listOf(".claude", ".claude-remote/accounts/x")) {
            val cred = File(live, "$dir/.credentials.json").apply { writeText("secret") }
            guard {
                val lockDir = File(live, "$dir/.oauth_refresh.lock").apply { mkdirs() }
                val owner = File(live, "$dir/.oauth_refresh.lock.owner").apply { writeText("{}") }
                atomicReplace(cred, "SECRET-2")
                assertTrue(owner.delete() && lockDir.delete())
            }
        }
    }

    /** The exception is NOT a blind spot: without a refresh lock in the SAME config dir a credentials change fails. */
    @Test fun aCredentialsChangeWithoutARefreshLockInTheSameDirStillFails() {
        val cred = File(live, ".claude-remote/accounts/x/.credentials.json").apply { writeText("secret") }
        val bare = assertFailsWith<AssertionError> { guard { atomicReplace(cred, "SECRET-2") } }
        assertTrue(bare.message!!.contains("accounts/x/.credentials.json"), bare.message)
        // a lock in ANOTHER config dir does not excuse it
        val other = File(live, ".claude/.oauth_refresh.lock")
        val elsewhere = assertFailsWith<AssertionError> { guard { other.mkdirs(); atomicReplace(cred, "SECRET-3"); other.delete() } }
        assertTrue(elsewhere.message!!.contains("accounts/x/.credentials.json"), elsewhere.message)
        // nor a lock that was there before and still is (it did not come or go during the guard)
        File(live, ".claude-remote/accounts/x/.oauth_refresh.lock").mkdirs()
        val stale = assertFailsWith<AssertionError> { guard { atomicReplace(cred, "SECRET-4") } }
        assertTrue(stale.message!!.contains("accounts/x/.credentials.json"), stale.message)
    }

    /** Lay out the config-dir trees a live Claude writes behind every test's back (measured false alarms, kolo 2). */
    private fun liveNoiseDirs(dir: String) {
        File(live, "$dir/telemetry").mkdirs()
        File(live, "$dir/telemetry/1p_failed_events.old.json").writeText("[]")
        File(live, "$dir/tasks").mkdirs()
        File(live, "$dir/.omc").mkdirs()
        File(live, "$dir/.omc/update-check.json").writeText("{\"t\":1}")
    }

    /**
     * False alarms seen in 3 review runs: a live Claude writes its telemetry, its per-session task dirs and the
     * oh-my-claudecode update check. Those are TRANSIENT in both kinds of config dir.
     */
    @Test fun liveClaudeTelemetryTasksAndUpdateCheckAreTolerated() {
        for (dir in listOf(".claude", ".claude-remote/accounts/x")) {
            liveNoiseDirs(dir)
            guard {
                File(live, "$dir/telemetry/1p_failed_events.new-session.json").writeText("[1,2,3]")
                assertTrue(File(live, "$dir/telemetry/1p_failed_events.old.json").delete())
                File(live, "$dir/tasks/session-701c36a0").mkdirs()
                File(live, "$dir/tasks/session-701c36a0/1.json").writeText("{}")
                File(live, "$dir/.omc/update-check.json").writeText("{\"t\":22222}")
            }
        }
    }

    /** ...and nothing more: the neighbours stay STRICT, and the noise never excuses a credentials change. */
    @Test fun theLiveNoiseExceptionIsNarrow() {
        liveNoiseDirs(".claude-remote/accounts/x")
        val a = ".claude-remote/accounts/x"
        val omc = assertFailsWith<AssertionError> { guard { File(live, "$a/.omc/other.json").writeText("{}") } }
        assertTrue(omc.message!!.contains("$a/.omc/other.json"), omc.message)
        val task = assertFailsWith<AssertionError> { guard { File(live, "$a/tasks/not-a-session").writeText("x") } }
        assertTrue(task.message!!.contains("$a/tasks/not-a-session"), task.message)
        val tele = assertFailsWith<AssertionError> { guard { File(live, ".claude/telemetry").mkdirs() } }
        assertTrue(tele.message!!.contains(".claude/telemetry"), tele.message)
        val cred = File(live, "$a/.credentials.json").apply { writeText("secret") }
        val withNoise = assertFailsWith<AssertionError> {
            guard {
                File(live, "$a/telemetry/1p_failed_events.x.json").writeText("[]")
                atomicReplace(cred, "SECRET-2")
            }
        }
        assertTrue(withNoise.message!!.contains("$a/.credentials.json"), withNoise.message)
    }

    /** A refresh rewrites credentials; it never creates or deletes them. */
    @Test fun aRefreshLockDoesNotExcuseCreatingOrDeletingCredentials() {
        val cred = File(live, ".claude/.credentials.json")
        val lock = File(live, ".claude/.oauth_refresh.lock")
        assertFailsWith<AssertionError> { guard { lock.mkdirs(); cred.writeText("new"); lock.delete() } }
        assertTrue(cred.isFile)
        assertFailsWith<AssertionError> { guard { lock.mkdirs(); cred.delete(); lock.delete() } }
    }

    @Test fun aCredentialsFileIsNeverOpenedNotEvenForTheTokenScan() {
        // By design the canary does not read secrets, so an in-place change that keeps size, mtime and
        // inode is invisible to it. That blind spot is exactly what proves the file is not hashed or scanned
        // (a content hash would catch it, a token scan would find the token in it).
        val cred = File(live, ".claude/.credentials.json").apply { writeText("secret") }
        val mtime = cred.lastModified()
        guard {
            java.io.RandomAccessFile(cred, "rw").use { it.write("SECRET".toByteArray()) }
            cred.setLastModified(mtime)
        }
        guard { java.io.RandomAccessFile(cred, "rw").use { it.write(token.toByteArray().copyOf(6)) }; cred.setLastModified(mtime) }
    }

    @Test fun symlinkReplacedByARegularFileWithTheSameBytesFails() {
        val link = File(live, ".claude-remote/accounts/x/settings.local.json")
        File(live, ".claude/settings.local.json").writeText("{\"l\":1}")
        Files.createSymbolicLink(link.toPath(), File(live, ".claude/settings.local.json").toPath())
        val e = failure {
            val bytes = link.readBytes()
            link.delete()
            link.writeBytes(bytes)
        }
        assertTrue(e.message!!.contains("accounts/x/settings.local.json") && e.message!!.contains("changed type"), e.message)
    }

    @Test fun symlinkRetargetedToAnotherFileWithTheSameContentFails() {
        File(live, ".claude/settings.local.json").writeText("{\"l\":1}")
        File(live, ".claude/other.json").writeText("{\"l\":1}")
        val link = File(live, ".claude-remote/accounts/x/settings.local.json")
        Files.createSymbolicLink(link.toPath(), File(live, ".claude/settings.local.json").toPath())
        val e = failure {
            link.delete()
            Files.createSymbolicLink(link.toPath(), File(live, ".claude/other.json").toPath())
        }
        assertTrue(e.message!!.contains("symlink target"), e.message)
    }

    @Test fun volatileLiveFilesMayChangeWithoutAFalseAlarm() {
        // measured: written by live processes all the time (see CONFIG_LIVE_TOP / REMOTE_LIVE_NAMES)
        File(live, ".claude/history.jsonl").writeText("a\n")
        File(live, ".claude/projects/p").mkdirs()
        File(live, ".claude-remote/clients").mkdirs()
        File(live, ".claude-remote/clients/c1.json").writeText("{}")
        File(live, ".cache/claude-remote/notify").writeText("n\n")
        File(live, ".claude-remote/drift.log").writeText("d1\n")
        File(live, ".claude-remote/accounts/x/backups").mkdirs()
        File(live, ".claude-remote/accounts/x/.session-stats.json").writeText("{}")
        guard {
            File(live, ".claude-remote/accounts/x/backups/b1").writeText("b")
            File(live, ".claude-remote/accounts/x/.session-stats.json").writeText("{\"s\":1}")
            File(live, ".claude-remote/drift.log").appendText("d2\n")
            File(live, ".claude-remote/.drift.live.AbC123").writeText("scratch") // the drift cron's scratch file: appears and vanishes
            File(live, ".claude/settings.json.tmp.4242").apply { writeText("tmp"); delete() }
            File(live, ".claude/.claude.json.lock").apply { mkdirs(); delete() }
            File(live, ".claude-remote/.drift.live.AbC123").delete()
            File(live, ".claude/history.jsonl").writeText("rewritten\n")
            File(live, ".claude/projects/p/new.jsonl").writeText("x")
            File(live, ".claude-remote/clients/c1.json").writeText("{\"t\":2}")
            File(live, ".claude-remote/clients/c2.json").writeText("{}")
            File(live, ".cache/claude-remote/notify").writeText("completely different\n")
            File(live, ".claude.json").writeText("{\"n\":2}")
        }
        // ...but their EXISTENCE is still checked, and a leaked temp file is still searched for the token
        assertFailsWith<AssertionError> { guard { File(live, ".claude/history.jsonl").delete() } }
        val t = failure { File(live, ".claude/settings.json.tmp.1").writeText(token) }
        assertTrue(t.message!!.contains("test token found in LIVE file"), t.message)
        File(live, ".claude/settings.json.tmp.1").delete()
        assertFailsWith<AssertionError> { guard { File(live, ".claude.json").delete() } }
    }

    @Test fun defaultLiveHomeIsTheRealOneNotTheRedirectedUserHome() {
        // Gradle points user.home at build/t/home; the canary must keep watching the REAL home
        val real = System.getProperty("clauderemote.live.home") ?: return // not run by Gradle
        assertNotEquals(real, System.getProperty("user.home"), "user.home was not redirected for this Test task")
        val field = LiveFileCanary::class.java.getDeclaredField("liveHome").apply { isAccessible = true }
        assertEquals(File(real).path, (field.get(LiveFileCanary()) as File).path)
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

    // ---- a file that disappears while the tree is walked (the drift cron's scratch files do this every minute) ----

    @Test fun transientFilesThatVanishDuringTheWalkAreNotReportedAsCreated() {
        val dir = File(live, ".claude-remote")
        val ghosts = (1..8).map { File(dir, ".drift.tick.G$it") }
        // the first entry the walk visits deletes the others, which were listed but are gone when the walk stats them
        val walking = LiveFileCanary(live, tmuxSocket = null) { p -> if (p.startsWith(dir.toPath())) ghosts.forEach { if (it.toPath() != p) it.delete() } }
        walking.guard(token, testHome, testRoot) { ghosts.forEach { it.writeText("") } }
    }

    @Test fun ordinaryFilesThatVanishDuringTheWalkAreNotReportedAsCreatedEither() {
        val dir = File(live, ".claude-remote")
        val ghosts = (1..8).map { File(dir, "plain.G$it") }
        // the first entry the walk visits deletes ALL of them: the others are gone before their stat, the visited one before its read
        val walking = LiveFileCanary(live, tmuxSocket = null) { p -> if (p.startsWith(dir.toPath())) ghosts.forEach { it.delete() } }
        walking.guard(token, testHome, testRoot) { ghosts.forEach { it.writeText("x") } }
    }

    @Test fun aFileThatVanishesBetweenItsStatAndItsReadIsNotReported() {
        val gone = File(live, ".claude-remote/selfdeleting.json")
        val walking = LiveFileCanary(live, tmuxSocket = null) { p -> if (p.fileName.toString() == gone.name) gone.delete() }
        walking.guard(token, testHome, testRoot) { gone.writeText("{}") }
    }

    @Test fun aFileThatReallyIsGoneAfterTheTestStillFails() {
        File(live, ".claude-remote/keep.json").writeText("{}")
        val e = failure { File(live, ".claude-remote/keep.json").delete() }
        assertTrue(e.message!!.contains("keep.json") && e.message!!.contains("deleted"), e.message)
    }

    // ---- which live files may change (measured on the live box) ----

    @Test fun filesTheAppAndTheDriftScriptRewriteMayChange() {
        File(live, ".claude-remote/sessions.json").writeText("[1]")
        File(live, ".claude-remote/sessions.restore.json").writeText("[1]")
        File(live, ".claude-remote/sessions.restore.json.bak").writeText("[1]")
        File(live, ".claude-remote/forgotten").writeText("a\n")
        File(live, ".claude-remote/forgotten.d").mkdirs()
        File(live, ".claude-remote/forgotten.rejected.20261102T094911Z").writeText("r")
        File(live, ".claude/state").mkdirs()
        File(live, ".claude/state/mcp-discover-verdicts.json").writeText("{}")
        File(live, ".claude/mcp-needs-auth-cache.json").writeText("{}")
        File(live, ".claude-remote/accounts/x/state").mkdirs()
        File(live, ".claude-remote/accounts/x/mcp-needs-auth-cache.json").writeText("{}")
        guard {
            File(live, ".claude-remote/sessions.json").writeText("[2]")
            File(live, ".claude-remote/sessions.restore.json").writeText("[2]")
            File(live, ".claude-remote/sessions.restore.json.bak").writeText("[2]")
            File(live, ".claude-remote/forgotten").appendText("b\n")
            File(live, ".claude-remote/forgotten.d/new").writeText("n")
            File(live, ".claude-remote/forgotten.rejected.20261103T000000Z").writeText("r2")
            File(live, ".claude/state/mcp-discover-verdicts.json").writeText("{\"v\":1}")
            File(live, ".claude/state/another.json").writeText("{}")
            File(live, ".claude/mcp-needs-auth-cache.json").writeText("{\"v\":1}")
            File(live, ".claude-remote/accounts/x/state/s.json").writeText("{}")
            File(live, ".claude-remote/accounts/x/mcp-needs-auth-cache.json").writeText("{\"v\":1}")
        }
        // existence is still checked
        assertFailsWith<AssertionError> { guard { File(live, ".claude-remote/sessions.json").delete() } }
        assertFailsWith<AssertionError> { guard { File(live, ".claude/mcp-needs-auth-cache.json").delete() } }
    }

    /** Kolo 4: the two files the live jarvis daemon rewrites may change; anything else in jarvis/ is still strict. */
    @Test fun theJarvisDaemonsTwoFilesMayChangeButNothingElseInJarvis() {
        File(live, ".claude-remote/jarvis").mkdirs()
        File(live, ".claude-remote/jarvis/status.json").writeText("{\"t\":1}")
        File(live, ".claude-remote/jarvis/d-state.json").writeText("{\"t\":1}")
        File(live, ".claude-remote/jarvis/other.json").writeText("{}")
        guard {
            File(live, ".claude-remote/jarvis/status.json").writeText("{\"t\":2}")
            File(live, ".claude-remote/jarvis/d-state.json").writeText("{\"t\":2}")
        }
        val e = failure { File(live, ".claude-remote/jarvis/other.json").writeText("{\"t\":2}") }
        assertTrue(e.message!!.contains("jarvis/other.json") && e.message!!.contains("modified"), e.message)
        assertTrue(failure { File(live, ".claude-remote/jarvis/new.json").writeText("{}") }.message!!.contains("created"))
        assertTrue(failure { File(live, ".claude-remote/jarvis/status.json").delete() }.message!!.contains("jarvis/status.json"))
        assertTrue(failure { File(live, ".claude-remote/jarvis/d-state.json").writeText("[\"$token\"]") }.message!!.contains("test token found in LIVE file"))
    }

    @Test fun aTokenInASmallLiveWrittenFileIsStillFound() {
        File(live, ".claude-remote/sessions.json").writeText("[]")
        val e = failure { File(live, ".claude-remote/sessions.json").writeText("[\"$token\"]") }
        assertTrue(e.message!!.contains("test token found in LIVE file") && e.message!!.contains("sessions.json"), e.message)
    }

    @Test fun treesThatWereOnlyGuessedAreComparedStrictly() {
        for (dirName in listOf("paste-cache", "debug", "tasks", ".omc")) {
            File(live, ".claude/$dirName").mkdirs()
            val e = failure { File(live, ".claude/$dirName/x").writeText("x") }
            assertTrue(e.message!!.contains("$dirName/x") && e.message!!.contains("created"), e.message)
        }
        File(live, ".claude/stats-cache.json").writeText("{}")
        assertTrue(failure { File(live, ".claude/stats-cache.json").writeText("{\"a\":1}") }.message!!.contains("modified"))
    }

    @Test fun userSkillsAreComparedByContentButTheSyncCacheMayChange() {
        File(live, ".claude/skills/mine").mkdirs()
        File(live, ".claude/skills/mine/SKILL.md").writeText("# mine")
        File(live, ".claude/skills/synced/s1").mkdirs()
        File(live, ".claude/skills/synced/s1/manifest.json").writeText("{}")
        guard {
            File(live, ".claude/skills/synced/s1/manifest.json").writeText("{\"t\":1}")
            File(live, ".claude/skills/synced/s2").mkdirs()
        }
        val e = failure { File(live, ".claude/skills/mine/SKILL.md").writeText("# injected") }
        assertTrue(e.message!!.contains("skills/mine/SKILL.md") && e.message!!.contains("modified"), e.message)
        assertTrue(failure { File(live, ".claude/skills/new/SKILL.md").apply { parentFile.mkdirs() }.writeText("x") }.message!!.contains("created"))
    }

    @Test fun pluginHookFilesAreComparedByContentButTheRestOfPluginsMayChange() {
        val hooks = File(live, ".claude/plugins/cache/vendor/p/1.0/hooks/hooks.json")
        hooks.parentFile.mkdirs(); hooks.writeText("{\"hooks\":{}}")
        File(live, ".claude/plugins/cache/vendor/p/1.0/README.md").writeText("r")
        File(live, ".claude/plugins/marketplaces/m/hooks").mkdirs()
        File(live, ".claude/plugins/marketplaces/m/hooks/hooks.json").writeText("{}")
        File(live, ".claude/plugins/oh-my-claudecode").mkdirs()
        File(live, ".claude/plugins/oh-my-claudecode/.usage-cache-anthropic.json").writeText("{}")
        guard {
            File(live, ".claude/plugins/cache/vendor/p/1.0/README.md").writeText("changed")
            File(live, ".claude/plugins/cache/vendor/p/1.0/new.js").writeText("x")
            File(live, ".claude/plugins/known_marketplaces.json").writeText("{}")
            File(live, ".claude/plugins/marketplaces/m/hooks/hooks.json").writeText("{\"synced\":1}") // a marketplace sync rewrites these
            File(live, ".claude/plugins/oh-my-claudecode/.usage-cache-anthropic.json").writeText("{\"n\":1}")
            File(live, ".claude/plugins/cache/temp_subdir_1793655325248.clone/hooks/hooks.json").apply { parentFile.mkdirs() }.writeText("{}")
        }
        val changed = failure { hooks.writeText("{\"hooks\":{\"PreToolUse\":[]}}") }
        assertTrue(changed.message!!.contains("hooks/hooks.json") && changed.message!!.contains("modified"), changed.message)
        hooks.writeText("{\"hooks\":{}}")
        val added = failure { File(live, ".claude/plugins/cache/vendor/p/1.0/hooks/hooks.local.json").writeText("{}") }
        assertTrue(added.message!!.contains("hooks.local.json") && added.message!!.contains("created"), added.message)
    }

    @Test fun pluginHookScriptsAndPluginSkillsAreComparedByContent() {
        val v = ".claude/plugins/cache/vendor/p/1.0"
        val script = File(live, "$v/scripts/hook.mjs").apply { parentFile.mkdirs(); writeText("console.log(1)") }
        val skill = File(live, "$v/skills/s/SKILL.md").apply { parentFile.mkdirs(); writeText("# s") }
        val market = File(live, ".claude/plugins/marketplaces/m/skills/s/SKILL.md").apply { parentFile.mkdirs(); writeText("# m") }
        val lib = File(live, "$v/lib/util.mjs").apply { parentFile.mkdirs(); writeText("u") }
        guard { lib.writeText("changed"); market.writeText("# synced") } // not a hook script, and a marketplace sync
        val s1 = failure { script.writeText("fetch('http://evil')") }
        assertTrue(s1.message!!.contains("scripts/hook.mjs") && s1.message!!.contains("modified"), s1.message)
        script.writeText("console.log(1)")
        val s2 = failure { skill.writeText("# injected") }
        assertTrue(s2.message!!.contains("skills/s/SKILL.md") && s2.message!!.contains("modified"), s2.message)
        skill.writeText("# s")
        val s3 = failure { File(live, "$v/scripts/new.mjs").writeText("x") }
        assertTrue(s3.message!!.contains("scripts/new.mjs") && s3.message!!.contains("created"), s3.message)
        File(live, "$v/scripts/new.mjs").delete()
        val s4 = failure { File(live, "$v/skills/s2/SKILL.md").apply { parentFile.mkdirs() }.writeText("x") }
        assertTrue(s4.message!!.contains("skills/s2/SKILL.md") && s4.message!!.contains("created"), s4.message)
    }

    @Test fun aTokenInAPluginFileNameIsFound() {
        val e = failure { File(live, ".claude/plugins/cache/vendor/$token").apply { parentFile.mkdirs() }.writeText("x") }
        assertTrue(e.message!!.contains("NAME"), e.message)
    }

    @Test fun anAccountSymlinkIntoPluginsIsStillComparedByItsTarget() {
        File(live, ".claude/plugins").mkdirs()
        File(live, ".claude/plugins/a").mkdirs()
        File(live, ".claude/plugins/b").mkdirs()
        val link = File(live, ".claude-remote/accounts/x/plugins")
        Files.createSymbolicLink(link.toPath(), File(live, ".claude/plugins/a").toPath())
        val e = failure { link.delete(); Files.createSymbolicLink(link.toPath(), File(live, ".claude/plugins/b").toPath()) }
        assertTrue(e.message!!.contains("symlink target"), e.message)
    }

    // ---- append-only files ----

    @Test fun anAppendOnlyFileReplacedByAnotherInodeThatDidNotShrinkIsTolerated() {
        val log = File(live, ".claude-remote/logs/x.log").apply { writeText("line one\nline two\n") }
        guard {
            // the log shipper trims through a temp file and a rename: same name, new inode, different head
            val tmp = File(log.parentFile, "x.log.new").apply { writeText("ROTATED-HEAD\nline one\nline two\nline three\n") }
            Files.move(tmp.toPath(), log.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    }

    @Test fun anAppendOnlyFileReplacedByAShorterOneStillFails() {
        val log = File(live, ".claude-remote/logs/x.log").apply { writeText("line one\nline two\n") }
        val e = failure {
            val tmp = File(log.parentFile, "x.log.new").apply { writeText("short\n") }
            Files.move(tmp.toPath(), log.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        assertTrue(e.message!!.contains("truncated or rewritten"), e.message)
    }

    @Test fun anAppendOnlyFileRewrittenInPlaceToTheSameSizeStillFails() {
        val log = File(live, ".claude-remote/logs/x.log").apply { writeText("line one\n") }
        val e = failure { java.io.RandomAccessFile(log, "rw").use { it.write("LINE ONE\n".toByteArray()) } }
        assertTrue(e.message!!.contains("truncated or rewritten"), e.message)
    }

    private fun sparse(f: File, bytes: Long) { java.io.RandomAccessFile(f, "rw").use { it.setLength(bytes) } }

    @Test fun aHugeAppendOnlyFileMayGrowWithoutAFalseAlarm() {
        val log = File(live, ".claude-remote/logs/huge.log")
        sparse(log, LiveFileCanary.MAX_READ_BYTES + 1024)
        guard { java.io.FileOutputStream(log, true).use { it.write("more\n".toByteArray()) } }
        guard { } // and an untouched one
    }

    @Test fun aHugeAppendOnlyFileThatShrankOrChangedInodeFails() {
        val log = File(live, ".claude-remote/logs/huge.log")
        sparse(log, LiveFileCanary.MAX_READ_BYTES + 1024)
        val shrunk = failure { sparse(log, LiveFileCanary.MAX_READ_BYTES + 100) }
        assertTrue(shrunk.message!!.contains("truncated or rewritten"), shrunk.message)
        sparse(log, LiveFileCanary.MAX_READ_BYTES + 1024)
        val swapped = failure {
            val tmp = File(log.parentFile, "huge.log.new"); sparse(tmp, LiveFileCanary.MAX_READ_BYTES + 4096)
            Files.move(tmp.toPath(), log.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        assertTrue(swapped.message!!.contains("truncated or rewritten"), swapped.message)
    }

    // ---- the token in other encodings ----

    // bytes that make the standard and the URL-safe base64 alphabets differ ("?>?" encodes to "Pz4/")
    private val b64Token = "?>?>?>$token"

    @Test fun tokenInBase64AtEveryAlignmentIsFound() {
        for (offset in 0..2) for ((name, enc) in listOf("standard" to Base64.getEncoder(), "url-safe" to Base64.getUrlEncoder())) {
            val encoded = enc.encodeToString(("a".repeat(offset) + b64Token).toByteArray())
            val e = assertFailsWith<AssertionError>("$name offset $offset") {
                canary.guard(b64Token, testHome, testRoot) { File(live, ".claude-remote/logs/x.log").writeText("v2 s $encoded x\n") }
            }
            assertTrue(e.message!!.contains("test token found in LIVE file"), "$name offset $offset: ${e.message}")
        }
    }

    @Test fun tokenInABase64LineAppendedToTheLiveEventsFileIsFound() {
        File(live, ".cache/claude-remote/events").writeText("old\n")
        val line = "1730000000 " + Base64.getEncoder().encodeToString("session-$token".toByteArray()) + " stop\n"
        val e = failure { File(live, ".cache/claude-remote/events").appendText(line) }
        assertTrue(e.message!!.contains("test token found in LIVE file") && e.message!!.contains("events"), e.message)
    }

    @Test fun base64OfSomethingElseIsNotATokenHit() {
        guard { File(live, ".claude-remote/logs/x.log").writeText(Base64.getEncoder().encodeToString("nothing to see".toByteArray())) }
    }

    // ---- the live tmux server ----

    private fun tmuxAvailable(): Boolean = try { ProcessBuilder("tmux", "-V").redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS) } catch (_: Exception) { false }

    private fun tmux(sock: File, vararg args: String): Int {
        val p = ProcessBuilder(listOf("tmux", "-S", sock.path) + args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 }
        return p.exitValue()
    }

    private fun withPrivateTmux(block: (File) -> Unit) {
        if (!tmuxAvailable()) return
        val sock = File(base, "tm.sock")
        try {
            assertEquals(0, tmux(sock, "new-session", "-d", "-s", "keep"))
            assertEquals(0, tmux(sock, "new-session", "-d", "-s", "other"))
            block(sock)
        } finally {
            tmux(sock, "kill-server")
        }
    }

    @Test fun aLiveTmuxSessionThatIsGoneAfterTheBlockFails() = withPrivateTmux { sock ->
        val watching = LiveFileCanary(live, tmuxSocket = sock)
        watching.guard(token, testHome, testRoot) { }
        val e = assertFailsWith<AssertionError> { watching.guard(token, testHome, testRoot) { tmux(sock, "kill-session", "-t", "keep") } }
        assertTrue(e.message!!.contains("live tmux session") && e.message!!.contains("keep"), e.message)
        assertTrue(!e.message!!.contains("other"), e.message)
    }

    @Test fun aNewSessionOnTheLiveServerFails() = withPrivateTmux { sock ->
        val e = assertFailsWith<AssertionError> {
            LiveFileCanary(live, tmuxSocket = sock).guard(token, testHome, testRoot) { tmux(sock, "new-session", "-d", "-s", "added") }
        }
        assertTrue(e.message!!.contains("live tmux session") && e.message!!.contains("added") && e.message!!.contains("exists now"), e.message)
    }

    @Test fun aSocketThatWasRecreatedWithTheSameSessionsFailsAllTheSame() = withPrivateTmux { sock ->
        // what the watchdog leaves behind after a test deleted the socket: the sessions are all there again, in a NEW socket file
        val e = assertFailsWith<AssertionError> {
            LiveFileCanary(live, tmuxSocket = sock, watchedDirs = emptyList()).guard(token, testHome, testRoot) {
                tmux(sock, "kill-server")
                tmux(sock, "new-session", "-d", "-s", "keep")
                tmux(sock, "new-session", "-d", "-s", "other")
            }
        }
        assertTrue(e.message!!.contains("replaced") && e.message!!.contains("inode@birth"), e.message)
    }

    @Test fun aKilledLiveTmuxServerFails() = withPrivateTmux { sock ->
        val e = assertFailsWith<AssertionError> { LiveFileCanary(live, tmuxSocket = sock).guard(token, testHome, testRoot) { tmux(sock, "kill-server") } }
        assertTrue(e.message!!.contains("live tmux server"), e.message)
    }

    @Test fun losingEveryTmuxClientFailsButLosingNoneDoesNot() = withPrivateTmux { sock ->
        val client = ProcessBuilder("tmux", "-S", sock.path, "-C", "attach", "-t", "keep")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start() // control-mode client, stdin stays open
        try {
            val deadline = System.currentTimeMillis() + 10_000
            val watching = LiveFileCanary(live, tmuxSocket = sock)
            while (true) {
                val p = ProcessBuilder("tmux", "-S", sock.path, "list-clients").redirectErrorStream(true).start()
                if (p.inputStream.bufferedReader().readText().isNotBlank()) break
                check(System.currentTimeMillis() < deadline) { "the control-mode client never showed up in list-clients" }
                Thread.sleep(100)
            }
            watching.guard(token, testHome, testRoot) { } // clients unchanged
            val e = assertFailsWith<AssertionError> { watching.guard(token, testHome, testRoot) { tmux(sock, "detach-client", "-s", "keep") } }
            assertTrue(e.message!!.contains("client"), e.message)
        } finally {
            client.destroyForcibly()
        }
    }

    @Test fun aDeletedLiveTmuxSocketFileFailsEvenWithoutAnyTmuxBinary() {
        // a plain file stands in for the socket: only its existence is compared here, so this needs no tmux at all
        val fake = File(base, "fake-live.sock").apply { writeText("") }
        val e = assertFailsWith<AssertionError> { LiveFileCanary(live, tmuxSocket = fake).guard(token, testHome, testRoot) { fake.delete() } }
        assertTrue(e.message!!.contains("socket file of the live tmux server"), e.message)
        LiveFileCanary(live, tmuxSocket = fake.also { it.writeText("") }).guard(token, testHome, testRoot) { } // untouched: fine
    }

    @Test fun aSocketFileThatIsReplacedByAnotherFileFailsEvenWithoutAnyTmuxBinary() {
        val fake = File(base, "fake-live.sock").apply { writeText("") }
        val other = File(base, "other.sock").apply { writeText("") } // exists next to the original, so its inode is certainly another one
        val watching = LiveFileCanary(live, tmuxSocket = fake, watchedDirs = emptyList())
        val e = assertFailsWith<AssertionError> {
            watching.guard(token, testHome, testRoot) {
                Files.move(other.toPath(), fake.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        assertTrue(e.message!!.contains("replaced") && e.message!!.contains("socket file of the live tmux server"), e.message)
        watching.guard(token, testHome, testRoot) { fake.writeText("rewritten in place") } // the same file: fine
    }

    @Test fun aWatchedDirectoryThatWasDeletedAndCreatedAgainFailsButOneThatOnlyChangedInsideDoesNot() {
        val dir = File(base, "fake-live-dir").apply { mkdirs() }
        File(dir, "keep").writeText("x")
        val watching = LiveFileCanary(live, tmuxSocket = null, watchedDirs = listOf(dir))
        watching.guard(token, testHome, testRoot) { File(dir, "added").writeText("y"); File(dir, "keep").delete() } // same directory
        val aside = File(base, "fake-live-dir.aside")
        val replaced = assertFailsWith<AssertionError> {
            watching.guard(token, testHome, testRoot) { assertTrue(dir.renameTo(aside)); assertTrue(dir.mkdirs()) } // empty again, other inode
        }
        assertTrue(replaced.message!!.contains("fake-live-dir") && replaced.message!!.contains("replaced"), replaced.message)
        val gone = assertFailsWith<AssertionError> { watching.guard(token, testHome, testRoot) { assertTrue(dir.delete()) } }
        assertTrue(gone.message!!.contains("fake-live-dir") && gone.message!!.contains("deleted"), gone.message)
        LiveFileCanary(live, tmuxSocket = null, watchedDirs = listOf(dir)).guard(token, testHome, testRoot) { } // absent before: nothing to compare
    }

    @Test fun aTmuxServerThatNeverAnswersDoesNotHangTheCanary() {
        val ran = File(base, "hang-tmux.ran")
        val hang = File(base, "hang-tmux").apply { writeText("#!/bin/sh\n: > '${ran.path}'\nexec sleep 20\n"); setExecutable(true) }
        val sock = File(base, "hang.sock").apply { writeText("") }
        val started = System.nanoTime()
        LiveFileCanary(live, tmuxSocket = sock, watchedDirs = emptyList(), tmuxBinary = hang, tmuxTimeoutMs = 300).guard(token, testHome, testRoot) { }
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        assertTrue(ran.exists(), "the fake tmux was never started, so this test proved nothing")
        assertTrue(seconds < 10, "the canary waited $seconds s for a tmux server that never answers")
    }

    @Test fun noTmuxServerBeforeAndAfterIsNotAProblem() {
        LiveFileCanary(live, tmuxSocket = File(base, "no-such.sock")).guard(token, testHome, testRoot) { }
    }


    @Test fun realHomeDefaultNoOpBlockPasses() {
        // Against the REAL home: only reads, writes nothing; proves the default wiring works on this box.
        val root = Files.createTempDirectory("canary-real").toFile()
        try { LiveFileCanary().guard(LiveFileCanary.newToken(), File(root, "h").apply { mkdirs() }, root) {} }
        finally { safeDeleteTree(root) }
    }
}

/**
 * Every Test task of every module must run with no TMUX/TMUX_PANE, a private TMUX_TMPDIR, the tmux shim
 * first on PATH and a sandbox HOME/user.home (root build.gradle.kts, `subprojects { tasks.withType<Test>() }`),
 * so a stray `tmux` call cannot reach the live server on the default socket and nothing falls back to
 * the developer's real home.
 */
class DesktopTestEnvTest {
    @Test fun tmuxEnvIsStrippedFromTheTestJvm() {
        assertNull(System.getenv("TMUX"), "TMUX leaked into the test JVM — a stray tmux call would hit the LIVE server")
        assertNull(System.getenv("TMUX_PANE"))
    }

    @Test fun tmuxSocketDirectoryIsPrivateAndShortEnoughForASocket() {
        val dir = System.getenv("TMUX_TMPDIR")
        assertNotNull(dir, "TMUX_TMPDIR must be set for tests, else tmux uses the live default socket")
        assertEquals(System.getProperty("clauderemote.test.tmuxTmpDir"), dir, "TMUX_TMPDIR is not the directory Gradle configured")
        assertTrue(File(dir).isDirectory, "the directory is created before the tests run")
        assertNotEquals("/tmp", dir)
        assertTrue(!File(dir).canonicalPath.startsWith("/tmp/tmux-"), "TMUX_TMPDIR=$dir is the live socket directory")
        // sun_path is 108 bytes including the NUL; tmux appends /tmux-<uid>/default
        val uid = ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim()
        assertTrue(("$dir/tmux-$uid/default").length <= 107, "socket path too long for sun_path: $dir")
    }

    @Test fun homeAndUserHomeAreTheSandboxNotTheRealHome() {
        val real = System.getProperty("clauderemote.live.home")
        assertNotNull(real)
        val sandbox = File(System.getProperty("user.home")).canonicalPath
        assertNotEquals(File(real).canonicalPath, sandbox, "user.home still is the REAL home")
        assertEquals(sandbox, File(System.getenv("HOME")).canonicalPath, "HOME env differs from user.home")
        assertTrue("/build/t/" in "$sandbox/", "sandbox home $sandbox must be under build/t")
    }

    @Test fun theTmuxShimIsFirstOnPath() {
        val first = System.getenv("PATH").split(File.pathSeparator).first()
        assertEquals(File(System.getProperty("clauderemote.test.shimDir")).canonicalPath, File(first).canonicalPath)
        assertTrue(File(first, "tmux").canExecute())
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

/**
 * `scripts/test-shims/tmux` against a FAKE "real tmux" (prints REACHED), so no case can ever touch the
 * live server even when the shim is broken: a case is "refused" when the fake is not reached.
 */
class TmuxShimTest {
    private val shim = File(System.getProperty("clauderemote.test.shimDir") ?: "scripts/test-shims", "tmux")
    private lateinit var base: File
    private lateinit var fakeDir: File
    private val uid = ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim()
    private val liveSocket = "/tmp/tmux-$uid/default"

    @BeforeTest fun setUp() {
        base = Files.createTempDirectory("tmux-shim-test").toFile()
        fakeDir = File(base, "fake").apply { mkdirs() }
        File(fakeDir, "tmux").apply { writeText("#!/bin/sh\necho REACHED \"$@\"\n"); setExecutable(true) }
    }

    @AfterTest fun tearDown() { safeDeleteTree(base) }

    /** Run the shim with EXACTLY [env] (nothing inherited), PATH = shim dir + fake tmux dir. */
    private fun run(env: Map<String, String>, vararg args: String): Pair<Int, String> {
        val pb = ProcessBuilder(listOf(shim.absolutePath) + args).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment()["PATH"] = "${shim.parentFile.absolutePath}:${fakeDir.absolutePath}:/usr/bin:/bin"
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS))
        return p.exitValue() to out
    }

    private fun assertRefused(env: Map<String, String>, vararg args: String) {
        val (rc, out) = run(env, *args)
        assertNotEquals(0, rc, "shim let it through: env=$env args=${args.toList()} out=$out")
        assertTrue("REACHED" !in out, "the real tmux was reached: $out")
    }

    private fun assertAllowed(env: Map<String, String>, vararg args: String) {
        val (rc, out) = run(env, *args)
        assertEquals(0, rc, "shim refused a private socket: env=$env args=${args.toList()} out=$out")
        assertTrue("REACHED" in out, out)
    }

    @Test fun refusesTheLiveDefaultSocketInEveryWayAChildCanReachIt() {
        assertRefused(emptyMap(), "list-sessions")                                        // a child with a cleared environment
        assertRefused(mapOf("TMUX_TMPDIR" to "/tmp"), "list-sessions")                    // TMUX_TMPDIR pointing at /tmp
        assertRefused(mapOf("TMUX_TMPDIR" to "/tmp/"), "list-sessions")
        assertRefused(mapOf("TMUX_TMPDIR" to "/tmp/../tmp//"), "list-sessions")
        assertRefused(emptyMap(), "-S", liveSocket, "list-sessions")                      // an explicit -S on the live socket
        // attached and clustered flag forms, with a PRIVATE TMUX_TMPDIR so only the parsed -S can make these refuse
        val priv = mapOf("TMUX_TMPDIR" to base.path)
        assertRefused(priv, "-S$liveSocket", "list-sessions")
        assertRefused(priv, "-uS", liveSocket, "list-sessions")
        assertRefused(priv, "-S", liveSocket, "list-sessions")
        assertRefused(emptyMap(), "-L", "default", "list-sessions")
        assertRefused(mapOf("TMUX_TMPDIR" to base.path), "-L", "default", "list-sessions") // -L default is refused even with a private TMUX_TMPDIR
        assertRefused(emptyMap(), "-L", "other", "list-sessions")                         // another socket in the live directory
        assertRefused(mapOf("TMUX" to "$liveSocket,123,4"), "list-sessions")              // inherited TMUX
    }

    @Test fun refusesTheLiveSocketBehindEveryGlobalFlagThatTakesAnArgument() {
        // -c, -f and -T take a value; the parser must skip it and still see the -S/-L that follows
        val priv = mapOf("TMUX_TMPDIR" to base.path)
        for (flag in listOf("-c", "-f", "-T")) {
            assertRefused(priv, flag, "x", "-S", liveSocket, "list-sessions")
            assertRefused(priv, "${flag}x", "-S", liveSocket, "list-sessions")
            assertRefused(priv, "-2$flag", "x", "-S", liveSocket, "list-sessions")
            assertRefused(priv, flag, "x", "-L", "default", "list-sessions")
        }
        assertAllowed(priv, "-c", "x", "-S", File(base, "sock/tmux-$uid/default").path, "list-sessions")
        assertAllowed(priv, "-f", "/dev/null", "-T", "RGB", "list-sessions")
    }

    /**
     * A stand-in for the live socket directory INSIDE this test's own temp root, handed to the shim through
     * TMUX_SHIM_LIVE_DIR. Links in these tests point only at it, never at a real path outside the temp root.
     */
    private fun fakeLiveDir(): File = File(base, "fake-live/tmux-$uid").apply { mkdirs() }

    @Test fun refusesASymlinkedOrDottedPathToTheLiveSocket() {
        val live = fakeLiveDir()
        val liveEnv = mapOf("TMUX_SHIM_LIVE_DIR" to live.path)
        val link = File(base, "sneaky").apply { Files.createSymbolicLink(toPath(), File(live, "default").toPath()) }
        assertRefused(liveEnv, "-S", link.path, "list-sessions")
        assertRefused(liveEnv, "-S", "${live.path}/../tmux-$uid/default", "list-sessions")
        // the live directory reached through a link to its parent, as TMUX_TMPDIR
        val dirLink = File(base, "dirlink").apply { Files.createSymbolicLink(toPath(), live.parentFile.toPath()) }
        assertRefused(liveEnv + mapOf("TMUX_TMPDIR" to dirLink.path), "list-sessions")
        // and the same dotted path against the real live directory name; only a string handed to a fake tmux
        assertRefused(emptyMap(), "-S", "/tmp/tmux-$uid/../tmux-$uid/default", "list-sessions")
    }

    @Test fun letsAPrivateSocketThrough() {
        assertAllowed(mapOf("TMUX_TMPDIR" to base.path), "list-sessions")
        assertAllowed(emptyMap(), "-S", File(base, "sock/tmux-$uid/default").path, "new-session", "-d")
        assertAllowed(mapOf("TMUX_TMPDIR" to base.path), "-L", "mine", "ls")
        assertAllowed(mapOf("TMUX" to "${File(base, "s")},1,0"), "ls")
        assertAllowed(emptyMap(), "-V")
    }

    @Test fun aChildThatClearsItsEnvironmentDoesNotSeeTheLiveServer() {
        // PATH comes from the parent, TMUX_TMPDIR/TMUX are gone: what the real tmux would have done is use the live socket
        assertRefused(emptyMap(), "-F", "#{socket_path}", "list-sessions")
        // without any real tmux on PATH the shim fails closed too
        val pb = ProcessBuilder(shim.absolutePath, "ls").redirectErrorStream(true)
        pb.environment().clear(); pb.environment()["TMUX_TMPDIR"] = base.path
        val p = pb.start()
        p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS))
        assertNotEquals(0, p.exitValue())
    }
}
