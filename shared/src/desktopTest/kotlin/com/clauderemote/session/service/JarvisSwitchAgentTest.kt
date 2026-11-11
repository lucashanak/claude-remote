package com.clauderemote.session.service

import com.clauderemote.testsupport.safeDeleteTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F5d switch contract, client side, against the REAL commands in bash with a temp HOME (the claim is a real `mv`, so
 * two clients racing is the file system's answer, not a fake's). Nothing touches tmux or a live file.
 */
class JarvisSwitchAgentTest {
    private val home: File = Files.createTempDirectory("jarvis-switch").toFile()
    private val dir = File(home, ".claude-remote/jarvis").apply { mkdirs() }

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

    private val now get() = System.currentTimeMillis()

    private fun writeRequest(
        id: String = "s-1",
        to: String = "default",
        at: Long = now,
        expires: Long = at + 600_000,
        tmux: String = TMUX,
        sid: String = "c-1",
    ) = File(dir, "switch-request.json").writeText(
        """{"v":1,"id":"$id","at":$at,"expires":$expires,"tmux":"$tmux","claudeSessionId":"$sid","from":"hanakl@nekrachni.cz","to":"$to","why":"7d 95 %"}""",
    )

    private fun status(json: String) = File(dir, "status.json").writeText(json)

    private val result get() = File(dir, "switch-result.json").takeIf { it.exists() }?.readText()

    private inner class Client(
        val name: String,
        var tab: JarvisSwitchAgent.JarvisTabRef? = JarvisSwitchAgent.JarvisTabRef("tab-$name", TMUX, "hanakl@nekrachni.cz", "c-1"),
        var appBusy: String? = null,
        var serverRun: JarvisSwitch.SwitchRun = JarvisSwitch.SwitchRun.Ok,
        val onSwitch: (Client) -> Unit = {},
    ) {
        val switches = CopyOnWriteArrayList<Pair<String, String>>()
        val aligned = CopyOnWriteArrayList<Pair<String, String>>()
        val agent = JarvisSwitchAgent(
            installId = { "inst-$name" },
            run = { cmd, _ -> sh(cmd) },
            jarvisTab = { tab },
            appCheck = { appBusy },
            switchNow = { id, slot -> switches += id to slot; onSwitch(this); serverRun },
            align = { id, slot -> aligned += id to slot },
        )
    }

    @Test fun theClaimingClientSwitchesAndWritesTheResult() = runBlocking {
        writeRequest(to = "default")
        val c = Client("a")
        val o = c.agent.tick()
        assertEquals(JarvisSwitchOutcome.Switched("s-1", true, null), o)
        assertEquals(listOf("tab-a" to "default"), c.switches.toList())
        assertFalse(File(dir, "switch-request.json").exists(), "claimed")
        assertTrue(File(dir, "switch-request.s-1.taken").exists())
        val r = result!!
        assertTrue(""""id":"s-1"""" in r && """"by":"app:inst-a"""" in r && """"ok":true""" in r, r)
        assertFalse(File(dir, ".switch-result.tmp").exists())
    }

    @Test fun twoClientsAtOnceOnlyOneSwitches() = runBlocking {
        repeat(5) { round ->
            File(dir, "switch-result.json").delete()
            dir.listFiles()!!.filter { it.name.endsWith(".taken") }.forEach { it.delete() }
            writeRequest(id = "s-r$round")
            val a = Client("a"); val b = Client("b")
            val outs = listOf(a, b).map { c -> async(Dispatchers.IO) { c.agent.tick() } }.awaitAll()
            assertEquals(1, outs.count { it is JarvisSwitchOutcome.Switched }, outs.toString())
            assertEquals(1, outs.count { it is JarvisSwitchOutcome.LostClaim }, outs.toString())
            assertEquals(1, a.switches.size + b.switches.size, "exactly one restart")
        }
    }

    @Test fun busyPutsTheRequestBackAndSaysSo() = runBlocking {
        writeRequest()
        val c = Client("a", serverRun = JarvisSwitch.SwitchRun.Busy("DRAFT_IN_INPUT"))
        val o = c.agent.tick()
        assertIs<JarvisSwitchOutcome.Busy>(o)
        assertTrue(File(dir, "switch-request.json").exists(), "back for jarvis-d")
        assertFalse(File(dir, "switch-request.s-1.taken").exists())
        assertTrue(""""ok":false""" in result!! && """"error":"busy"""" in result!!)
        assertEquals(1, c.switches.size, "the server's idle check (in the switch's own shell run) said busy")
        // the app-side rule refuses just the same, and a busy request is not claimed again at once
        assertEquals(JarvisSwitchOutcome.Nothing, c.agent.tick())
        val d = Client("d", appBusy = "PROMPT_CHANGED: not idle")
        assertIs<JarvisSwitchOutcome.Busy>(d.agent.tick())
        assertTrue(File(dir, "switch-request.json").exists())
        assertEquals(emptyList(), d.switches.toList(), "the app-side rule refuses before any server run")
    }

    @Test fun putBackNeverOverwritesANewerRequest() {
        writeRequest(id = "s-old")
        assertTrue("__cr_claimed__" in sh(JarvisSwitch.claimCommand("s-old")))
        writeRequest(id = "s-new")
        sh(JarvisSwitch.returnCommand("s-old"))
        assertTrue(""""id":"s-new"""" in File(dir, "switch-request.json").readText(), "mv -n kept the newer one")
        assertFalse(File(dir, "switch-request.s-old.taken").exists())
    }

    @Test fun anExpiredRequestIsNeverClaimed() = runBlocking {
        writeRequest(at = now - 700_000, expires = now - 100_000)
        val c = Client("a")
        assertEquals(JarvisSwitchOutcome.Expired("s-1"), c.agent.tick())
        assertTrue(File(dir, "switch-request.json").exists(), "left for jarvis-d to delete")
        assertEquals(null, result)
    }

    @Test fun aClientWithoutJarvisTabLeavesItAlone() = runBlocking {
        writeRequest()
        assertEquals(JarvisSwitchOutcome.NotHere("s-1"), Client("a", tab = null).agent.tick())
        val other = JarvisSwitchAgent.JarvisTabRef("t", "claude-server-other", null)
        assertEquals(JarvisSwitchOutcome.NotHere("s-1"), Client("b", tab = other).agent.tick())
        assertTrue(File(dir, "switch-request.json").exists())
    }

    @Test fun aFailedRestartIsReportedAsSuch() = runBlocking {
        writeRequest(to = "lukas@kontexta.cz")
        val c = Client("a", serverRun = JarvisSwitch.SwitchRun.Failed("nologin"))
        assertEquals(JarvisSwitchOutcome.Switched("s-1", false, "nologin"), c.agent.tick())
        assertTrue(""""ok":false""" in result!!)
        assertEquals(listOf("tab-a" to "lukas@kontexta.cz"), c.switches.toList())
    }

    @Test fun otherClientsOnlyAlignTheModelOncePerSwitch() = runBlocking {
        status("""{"jarvisAccount":{"slot":"default","lastSwitch":{"id":"s-20261111T1620Z-aa01","at":${now},"from":"hanakl@nekrachni.cz","to":"default"}}}""")
        val c = Client("b")
        assertEquals(JarvisSwitchOutcome.Aligned("tab-b", "default"), c.agent.tick())
        assertEquals(listOf("tab-b" to "default"), c.aligned.toList())
        assertEquals(emptyList(), c.switches.toList(), "never a restart")
        assertEquals(JarvisSwitchOutcome.Nothing, c.agent.tick(), "once per lastSwitch.id")
        // already on that account: nothing to align
        status("""{"jarvisAccount":{"slot":"hanakl@nekrachni.cz","lastSwitch":{"id":"s-20261111T1720Z-aa02"}}}""")
        assertEquals(JarvisSwitchOutcome.Nothing, Client("c").agent.tick())
        // no lastSwitch: no alignment from a mere slot
        status("""{"jarvisAccount":{"slot":"default"}}""")
        assertEquals(JarvisSwitchOutcome.Nothing, Client("d").agent.tick())
    }

    @Test fun med1AnotherConversationIsRefusedAndNothingRuns() = runBlocking {
        writeRequest(sid = "c-OTHER")
        val c = Client("a")
        assertEquals(JarvisSwitchOutcome.Mismatch("s-1"), c.agent.tick())
        assertEquals(emptyList(), c.switches.toList())
        assertTrue(""""error":"mismatch"""" in result!!)
        writeRequest(id = "s-2", sid = "")
        assertNull(JarvisSwitch.parseRequest(File(dir, "switch-request.json").readText())?.claudeSessionId)
        assertEquals(JarvisSwitchOutcome.Mismatch("s-2"), Client("b").agent.tick(), "a request without one is refused too")
    }

    @Test fun theWinnerNeverAlignsBackToTheSlotItLeft() = runBlocking {
        // jarvis-d has not verified the switch yet: its status still names the slot left and an older switch
        status("""{"jarvisAccount":{"slot":"hanakl@nekrachni.cz","lastSwitch":{"id":"s-20261111T1000Z-0001"}}}""")
        writeRequest(id = "s-20261111T1620Z-ab12", to = "default")
        // the switch's own manifest push runs the pre-push check in the middle of the switch
        val c = Client("a", onSwitch = { me -> runBlocking { me.agent.confirmBeforePush() } })
        assertEquals(JarvisSwitchOutcome.Switched("s-20261111T1620Z-ab12", true, null), c.agent.tick())
        assertEquals(JarvisSwitchOutcome.Nothing, c.agent.tick(), "the old switch id is older than ours")
        assertEquals(emptyList(), c.aligned.toList(), "the model stays on the new slot")
    }

    @Test fun low2ALoserChecksTheSlotBeforeItPushes() = runBlocking {
        status("""{"jarvisAccount":{"slot":"default","lastSwitch":{"id":"s-20261111T1620Z-ab12","to":"default"}}}""")
        val loser = Client("b")
        loser.agent.confirmBeforePush()
        assertEquals(listOf("tab-b" to "default"), loser.aligned.toList(), "aligned before its push goes out")
        // within a minute no second read
        status("""{"jarvisAccount":{"slot":"lukas@kontexta.cz","lastSwitch":{"id":"s-20261111T1700Z-ab13"}}}""")
        loser.agent.confirmBeforePush()
        assertEquals(1, loser.aligned.size)
    }

    @Test fun aRunWhoseEndWasNotSeenIsReportedUnknown() = runBlocking {
        writeRequest()
        val c = Client("a", serverRun = JarvisSwitch.SwitchRun.Unknown)
        assertEquals(JarvisSwitchOutcome.Switched("s-1", null, "unknown"), c.agent.tick())
        val r = result!!
        assertTrue(""""ok":null""" in r && """"error":"unknown"""" in r, r)
        assertFalse(File(dir, "switch-request.json").exists(), "not put back: it may have happened")
    }

    private companion object {
        const val TMUX = "claude-server-jarvis"
    }
}
