package com.clauderemote.session.service

import com.clauderemote.testsupport.safeDeleteTree
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** F5d: the real write and read commands in bash against a temp HOME (no tmux, nothing live). */
class JarvisAccountsShellTest {
    private val home: File = Files.createTempDirectory("jarvis-accounts").toFile()

    @AfterTest fun cleanUp() = safeDeleteTree(home)

    private fun sh(cmd: String): String {
        val p = ProcessBuilder("bash", "-c", cmd).apply {
            environment()["HOME"] = home.absolutePath
            environment().remove("TMUX"); environment().remove("TMUX_PANE")
            redirectErrorStream(true)
        }.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    private val file get() = File(home, ".claude-remote/jarvis/accounts")

    @Test fun writesAtomicallyAndReadsBack() {
        assertEquals(emptyList(), JarvisAccountsFile.parseRead(sh(JarvisAccountsFile.READ)), "missing file = off")
        val chosen = listOf("default", "hanakl@nekrachni.cz")
        assertTrue(JarvisAccountsFile.writeOk(sh(JarvisAccountsFile.writeCommand(chosen))))
        assertEquals("default\nhanakl@nekrachni.cz\n", file.readText())
        assertFalse(File(home, ".claude-remote/jarvis/.accounts.tmp").exists(), "the tmp file was renamed")
        assertEquals(chosen, JarvisAccountsFile.parseRead(sh(JarvisAccountsFile.READ)))
        // one left: written empty, autopick off
        assertTrue(JarvisAccountsFile.writeOk(sh(JarvisAccountsFile.writeCommand(listOf("default")))))
        assertEquals("", file.readText())
        assertEquals(emptyList(), JarvisAccountsFile.parseRead(sh(JarvisAccountsFile.READ)))
    }

    @Test fun theStatusReadTakesOnlyTheField() {
        val dir = File(home, ".claude-remote/jarvis").apply { mkdirs() }
        assertEquals(JarvisAccountView.Read.Unreadable, JarvisAccountView.parse(sh(JarvisAccountView.READ)), "no status.json")
        File(dir, "status.json").writeText("""{"sessions":[{"name":"x"}]}""")
        assertEquals(JarvisAccountView.Read.Absent, JarvisAccountView.parse(sh(JarvisAccountView.READ)))
        File(dir, "status.json").writeText("""{"sessions":[],"jarvisAccount":{"slot":"default","fiveHour":5,"sevenDay":10,"autopick":"on"}}""")
        val r = JarvisAccountView.parse(sh(JarvisAccountView.READ))
        assertTrue(r is JarvisAccountView.Read.Ok && r.info.slot == "default" && r.info.autopickOn, r.toString())
        File(dir, "status.json").writeText("{torn")
        assertEquals(JarvisAccountView.Read.Unreadable, JarvisAccountView.parse(sh(JarvisAccountView.READ)))
    }

    @Test fun theQuickListReadsOnlyIdentitiesAndIsFast() {
        File(home, ".claude.json").writeText("""{"projects":{},"oauthAccount":{"emailAddress":"lukashanak@nekrachni.cz","organizationName":"Nekrachni"}}""")
        File(home, ".claude-remote/accounts/hanakl@nekrachni.cz").apply { mkdirs() }
            .let { File(it, ".claude.json").writeText("""{"oauthAccount":{"emailAddress":"hanakl@nekrachni.cz","organizationName":"Ústav"}}""") }
        File(home, ".claude-remote/accounts/broken").mkdirs() // never logged in: still listed, without an e-mail
        // a credentials file that must not be read: the command never names it
        assertTrue(".credentials" !in AccountService.LIST_ACCOUNTS_QUICK_CMD)
        val started = System.nanoTime()
        val list = AccountService.parseAccounts(sh(AccountService.LIST_ACCOUNTS_QUICK_CMD))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "no claude process start")
        assertEquals(listOf("default", "broken", "hanakl@nekrachni.cz"), list.map { it.slug })
        assertEquals("lukashanak@nekrachni.cz", list[0].email)
        assertTrue(list[0].isDefault)
        assertEquals("", list[1].email)
        assertEquals("hanakl@nekrachni.cz", list[2].email)
        assertEquals("Ústav", list[2].orgName)
    }
}
