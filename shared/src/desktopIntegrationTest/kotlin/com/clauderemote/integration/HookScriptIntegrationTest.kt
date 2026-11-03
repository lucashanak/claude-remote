package com.clauderemote.integration

import com.clauderemote.connection.SshManager
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SessionActivity
import com.clauderemote.session.TabManager
import com.clauderemote.session.service.BootstrapScript
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecChannels
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecUnavailable
import com.clauderemote.session.service.ExecUnavailableReason
import com.clauderemote.session.service.ConnectionRegistry
import com.clauderemote.session.service.HookCommands
import com.clauderemote.session.service.HookEvent
import com.clauderemote.session.service.HookEventLine
import com.clauderemote.session.service.HookEventSink
import com.clauderemote.session.service.NotificationService
import com.clauderemote.storage.ServerStorage
import com.clauderemote.testsupport.LiveFileCanary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `hook-ev.sh`, its settings.json command and the notify watcher, against a real tmux
 * server and a real sshd, inside the fixture's sandbox HOME (every test also runs inside
 * [LiveFileCanary.guard], so the live settings, `~/.claude-remote` and `~/.cache/claude-remote`
 * are proven untouched).
 *
 * A fake `claude` (a bash script, so its `comm` is `claude`) runs the very command that
 * settings.json carries for each event, as a child with `CLAUDE_PID` / `CLAUDE_CODE_SESSION_ID`
 * in its env and the hook JSON on stdin, inside a pane of the private tmux server (the hook
 * inherits `TMUX` / `TMUX_PANE` from it). The chains are the real process shapes of the box:
 * `exec claude`, `bash -l -> bash -lc -> claude`, `bash -c "...; exec bash -l"`,
 * `start-claude-*.sh`, and a nested claude started from a tool shell.
 */
class HookScriptIntegrationTest {

    private val token = LiveFileCanary.newToken()
    private val canary = LiveFileCanary()
    private val sessionsStarted = mutableListOf<String>()

    private val eventsFile get() = File(fixture.home, ".cache/claude-remote/events")
    private val cmdDir get() = File(fixture.root, "cmds")
    private val binDir get() = File(fixture.root, "bin")
    private val shimPath get() = File(binDir, "claude")

    @Before fun reset() {
        eventsFile.parentFile.mkdirs()
        eventsFile.delete()
        File(fixture.home, ".claude-remote/hookev.off").delete()
        File(fixture.home, ".cache/claude-remote").setWritable(true)
    }

    @After fun cleanup() {
        for (s in sessionsStarted) fixture.tmux("kill-session", "-t", "=$s")
        sessionsStarted.clear()
        CommFlags.resetForTest()
    }

    private fun guarded(block: () -> Unit) = canary.guard(token, fixture.home, fixture.root) { block() }

    // ------------------------------------------------------------ chain helpers

    private fun payload(ev: String, sid: String, extra: String = ""): String = when (ev) {
        "work" -> """{"session_id":"$sid","prompt_id":"p1","hook_event_name":"UserPromptSubmit","prompt":"hello $token"$extra}"""
        "ask" -> """{"session_id":"$sid","hook_event_name":"PreToolUse","tool_name":"AskUserQuestion","tool_input":{"questions":[{"question":"Která barva? $token","header":"Barva","options":[{"label":"Červená","description":"d"},{"label":"Modrá","description":"d"}],"multiSelect":false}]}$extra}"""
        "askdone" -> """{"session_id":"$sid","hook_event_name":"PostToolUse","tool_name":"AskUserQuestion","tool_response":{"answers":{"q":"a"}}$extra}"""
        "perm" -> """{"session_id":"$sid","hook_event_name":"PermissionRequest","tool_name":"Bash","tool_input":{"command":"touch x","description":"Create file x"}$extra}"""
        "stop" -> """{"session_id":"$sid","hook_event_name":"Stop","stop_hook_active":false,"last_assistant_message":"done"$extra}"""
        "start" -> """{"session_id":"$sid","hook_event_name":"SessionStart","source":"clear","model":"m"$extra}"""
        "end" -> """{"session_id":"$sid","hook_event_name":"SessionEnd","reason":"clear"$extra}"""
        else -> error(ev)
    }

    private class Chain(val script: File) {
        val pid: Long? get() = File("${script.path}.pid").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        val done: Boolean get() = File("${script.path}.done").isFile
        val tmuxEnv: String get() = File("${script.path}.tmuxenv").readTextOrEmpty().trim()
        val hookResults: List<String> get() = File("${script.path}.hookres").readTextOrEmpty().lines().filter { it.isNotBlank() }
    }

    /** A script for the fake claude: steps `EV<TAB>json`, `SPAWN<TAB>script`, `SLEEP<TAB>n`. */
    private fun chainScript(sid: String, vararg steps: String, noClaudePid: Boolean = false): Chain {
        val f = File(fixture.root, "chain-${seq.incrementAndGet()}.txt")
        f.writeText(steps.joinToString("\n") + "\n")
        File("${f.path}.sid").writeText(sid)
        if (noClaudePid) File("${f.path}.nopid").writeText("")
        return Chain(f)
    }

    private fun step(ev: String, sid: String, extra: String = "") = "$ev\t${payload(ev, sid, extra)}"

    private fun launch(name: String, chain: Chain, shape: String, env: String = ""): Chain {
        val claude = "env $env CMDDIR=${cmdDir.absolutePath} ${shimPath.absolutePath} ${chain.script.absolutePath}"
        val cmd = when (shape) {
            "exec" -> "exec $claude"
            "bash-lc" -> {
                val w = File(fixture.root, "chain-$name.sh")
                w.writeText("#!/bin/bash\nbash -lc 'cd /tmp; $claude; true'\nsleep 20\n")
                "bash -l ${w.absolutePath}"
            }
            "exec-bash" -> "$claude; exec bash -l"
            "start-script" -> {
                val w = File(fixture.root, "start-claude-$name.sh")
                w.writeText("#!/bin/bash\ncd /tmp\n$claude\nsleep 20\n")
                "bash ${w.absolutePath}"
            }
            "pane-bash" -> {
                val w = File(fixture.root, "chain-$name.sh")
                w.writeText("#!/bin/bash\n$claude\nsleep 20\n")
                "bash ${w.absolutePath}"
            }
            else -> error(shape)
        }
        val r = fixture.tmux("new-session", "-d", "-s", name, "-x", "200", "-y", "50", cmd)
        sessionsStarted += name
        waitUntil(15_000, "chain $name to finish (tmux said: ${r.out.trim()})") { chain.done }
        return chain
    }

    private fun events(): List<HookEvent> =
        eventsFile.readTextOrEmpty().lines().filter { it.isNotBlank() }.map {
            assertNotNull(HookEventLine.parse(it), "events line must parse: $it")
        }

    private fun assertQuietHooks(c: Chain) {
        assertTrue(c.hookResults.isNotEmpty(), "the fake claude recorded its hook runs")
        for (r in c.hookResults) assertEquals("0|", r, "every hook run exits 0 with EMPTY stdout: ${c.hookResults}")
    }

    // ------------------------------------------------------------------ chains

    @Test fun execClaudeIsAttributedToItself() = guarded {
        val c = launch("t-exec", chainScript("s-exec", step("work", "s-exec"), step("perm", "s-exec")), "exec")
        val ev = events()
        assertEquals(listOf("work", "perm"), ev.map { it.ev })
        assertEquals(setOf("t-exec"), ev.map { it.tmux }.toSet())
        assertEquals(c.pid, ev.first().pid, "C == pane_pid")
        assertContains(c.tmuxEnv, fixture.root.absolutePath, message = "the hook ran against the FIXTURE's tmux, never the live one")
        assertQuietHooks(c)
    }

    @Test fun theRestartChainBashLoginBashLcClaudeIsAttributed() = guarded {
        val c = launch("t-bashlc", chainScript("s-bl", step("work", "s-bl")), "bash-lc")
        assertEquals(listOf("work"), events().map { it.ev })
        assertEquals(c.pid, events().single().pid)
        assertQuietHooks(c)
    }

    @Test fun theRestoreChainShCWithExecBashIsAttributed() = guarded {
        val c = launch("t-execbash", chainScript("s-eb", step("start", "s-eb")), "exec-bash")
        assertEquals(listOf("start"), events().map { it.ev })
        assertQuietHooks(c)
    }

    @Test fun aStartClaudeScriptIsAttributed() = guarded {
        val c = launch("t-startscript", chainScript("s-ss", step("work", "s-ss")), "start-script")
        assertEquals(listOf("work"), events().map { it.ev })
        assertQuietHooks(c)
    }

    @Test fun aNestedClaudeUnderAnExecClaudePaneEmitsNothing() = guarded {
        val inner = chainScript("s-in", step("work", "s-in"), step("ask", "s-in"))
        val outer = chainScript("s-out", step("work", "s-out"), "SPAWN\t${inner.script.absolutePath}", step("stop", "s-out"))
        val c = launch("t-nest1", outer, "exec", env = "SHIM=${shimPath.absolutePath}")
        waitUntil(10_000, "inner claude to finish") { inner.done }
        val ev = events()
        assertEquals(listOf("work", "stop"), ev.map { it.ev }, "only the outer claude's events: $ev")
        assertEquals(setOf(c.pid), ev.map { it.pid }.toSet())
        assertQuietHooks(inner)
    }

    @Test fun aNestedClaudeUnderABashPaneEmitsNothing() = guarded {
        val inner = chainScript("s-in2", step("perm", "s-in2"))
        val outer = chainScript("s-out2", "SPAWN\t${inner.script.absolutePath}", step("work", "s-out2"))
        val c = launch("t-nest2", outer, "pane-bash", env = "SHIM=${shimPath.absolutePath}")
        waitUntil(10_000, "inner claude to finish") { inner.done }
        assertEquals(listOf("work"), events().map { it.ev })
        assertEquals(c.pid, events().single().pid)
    }

    @Test fun withoutClaudePidTheNearestClaudeAncestorIsUsed() = guarded {
        val c = launch("t-nopid", chainScript("s-np", step("work", "s-np"), noClaudePid = true), "bash-lc")
        assertEquals(listOf("work"), events().map { it.ev })
        assertEquals(c.pid, events().single().pid, "fallback walk found the claude, not the hook shell")
    }

    @Test fun aSessionIdThatIsNotTheClaudesOwnIsDropped() = guarded {
        launch("t-sid", chainScript("s-mine", step("work", "s-someone-else")), "exec")
        assertEquals(emptyList(), events())
    }

    // ------------------------------------------------------------------ lines

    @Test fun theLineCarriesTheNameAsBase64TheMillisecondClockAndStaysUnderOneKilobyte() = guarded {
        launch("t-line", chainScript("s-l", step("ask", "s-l")), "exec")
        val raw = eventsFile.readText().trim()
        assertTrue(raw.toByteArray().size < 1024, "one O_APPEND write: ${raw.length} bytes")
        val e = events().single()
        assertEquals("t-line", e.tmux)
        assertTrue(Math.abs(System.currentTimeMillis() - e.atMs) < 60_000, "epoch milliseconds: ${e.atMs}")
        assertTrue(raw.startsWith("claude-remote-ev ask "))
        assertTrue(Regex("^claude-remote-ev ask [A-Za-z0-9+/=]+ \\d{13} \\d+ [A-Za-z0-9+/=]*$").matches(raw), raw)
    }

    @Test fun askCarriesTheQuestionsAndOptionsInUtf8CappedAt600Bytes() = guarded {
        launch("t-ask", chainScript("s-a", step("ask", "s-a")), "exec")
        val e = events().single()
        assertContains(e.extra, "Která barva?")
        assertContains(e.extra, "[Červená, Modrá]")
        // a long Czech question is cut on a character boundary
        val long = "ž".repeat(900)
        val p = """{"session_id":"s-a2","tool_name":"AskUserQuestion","tool_input":{"questions":[{"question":"$long","options":[]}]}}"""
        val f = chainScript("s-a2", "ask\t$p")
        launch("t-ask2", f, "exec")
        val cut = events().last().extra
        assertTrue(cut.toByteArray().size <= 600, "${cut.toByteArray().size} bytes")
        assertTrue(cut.isNotEmpty() && cut.all { it == 'ž' }, "valid UTF-8, no torn character")
    }

    @Test fun permCarriesTheToolAndItsDescriptionButAPermissionRequestForAskUserQuestionIsDropped() = guarded {
        val askPerm = """perm	{"session_id":"s-p","hook_event_name":"PermissionRequest","tool_name":"AskUserQuestion","tool_input":{}}"""
        launch("t-perm", chainScript("s-p", step("perm", "s-p"), askPerm), "exec")
        val ev = events()
        assertEquals(1, ev.size, "the AskUserQuestion permission is the `ask` event's business")
        assertEquals("Bash: Create file x", ev.single().extra)
    }

    @Test fun teammatesRunInProcessSoTheirAskAndPermAreEmittedForTheLeadsTab() = guarded {
        val agent = ""","agent_id":"aps-reporter-1","agent_type":"reporter""""
        launch("t-team", chainScript("s-t", step("ask", "s-t", agent), step("perm", "s-t", agent)), "exec")
        assertEquals(listOf("ask", "perm"), events().map { it.ev })
    }

    @Test fun aStopCarriesBgWhenBackgroundTasksArePendingEvenBehindALongMessage() = guarded {
        val longMsg = "x".repeat(100_000)
        val busy = """stop	{"session_id":"s-b","hook_event_name":"Stop","last_assistant_message":"$longMsg","background_tasks":[{"id":"b1"}],"session_crons":[]}"""
        val idle = """stop	{"session_id":"s-b","hook_event_name":"Stop","last_assistant_message":"ok","background_tasks":[],"session_crons":[]}"""
        launch("t-bg", chainScript("s-b", busy, idle), "exec")
        val ev = events()
        assertEquals(listOf("bg", ""), ev.map { it.extra })
    }

    @Test fun startCarriesTheSourceAndTheClaudeVersion() = guarded {
        launch("t-start", chainScript("s-s", step("start", "s-s")), "exec")
        assertContains(events().single().extra, "source=clear")
        assertContains(events().single().extra, "exe=")
    }

    // ------------------------------------------------------- contract of the command

    @Test fun anAskOrPermStillEmitsWithoutPythonJustWithoutDetail() = guarded {
        val shim = File(fixture.root, "nopy").apply { mkdirs() }
        File(shim, "python3").apply { writeText("#!/bin/sh\nexit 1\n"); setExecutable(true) }
        launch("t-nopy", chainScript("s-n", step("ask", "s-n")), "exec", env = "PATH=${shim.absolutePath}:${'$'}PATH")
        val e = events().single()
        assertEquals("ask", e.ev)
        assertEquals("", e.extra)
    }

    @Test fun theOffSwitchSilencesEverythingImmediatelyAndRemovingItRestoresIt() = guarded {
        File(fixture.home, ".claude-remote/hookev.off").writeText("")
        val c = launch("t-off1", chainScript("s-o1", step("work", "s-o1")), "exec")
        assertEquals(emptyList(), events())
        assertQuietHooks(c)
        File(fixture.home, ".claude-remote/hookev.off").delete()
        launch("t-off2", chainScript("s-o2", step("work", "s-o2")), "exec")
        assertEquals(listOf("work"), events().map { it.ev })
    }

    @Test fun nothingIsAppendedToAnEventsFileOver20MegabytesAndNothingBreaks() = guarded {
        eventsFile.parentFile.mkdirs()
        java.io.RandomAccessFile(eventsFile, "rw").use { it.setLength(21L * 1024 * 1024) }
        val before = eventsFile.length()
        val c = launch("t-big", chainScript("s-big", step("work", "s-big")), "exec")
        assertEquals(before, eventsFile.length())
        assertQuietHooks(c)
    }

    @Test fun aReadOnlyCacheDirIsExitZeroWithEmptyStdout() = guarded {
        File(fixture.home, ".cache/claude-remote").setWritable(false)
        val c = launch("t-ro", chainScript("s-ro", step("work", "s-ro")), "exec")
        assertEquals(emptyList(), events())
        assertQuietHooks(c)
    }

    @Test fun aNonLinuxHostWritesNothing() = guarded {
        val shim = File(fixture.root, "darwin").apply { mkdirs() }
        File(shim, "uname").apply { writeText("#!/bin/sh\necho Darwin\n"); setExecutable(true) }
        val c = launch("t-mac", chainScript("s-m", step("work", "s-m")), "exec", env = "PATH=${shim.absolutePath}:${'$'}PATH")
        assertEquals(emptyList(), events())
        assertQuietHooks(c)
    }

    private fun runHook(ev: String, stdin: String = "", script: String? = null, pre: String = ""): ProcResult {
        val f = File(fixture.root, "hook-under-test.sh")
        f.writeText(script ?: HookCommands.HOOK_EV_SCRIPT)
        f.setExecutable(true)
        val out = File(fixture.root, "hook-stdin.txt").apply { writeText(stdin) }
        // the settings.json command, pointed at the file under test instead of ~/.claude-remote/hook-ev.sh
        val cmd = HookCommands.entryCommand(ev).replace("\$HOME/.claude-remote/hook-ev.sh", f.absolutePath)
        return fixture.run("bash", "-c", "$pre sh -c ${shq(cmd)} < ${shq(out.absolutePath)}; echo \"rc=\$?\"")
    }

    private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    @Test fun aBrokenOrNoisyScriptCanNeverLeakStdoutOrAnExitCode() = guarded {
        for ((label, body) in listOf(
            "syntax error" to "#!/bin/sh\nif then fi (\n",
            "echo and exit 2" to "#!/bin/sh\necho INJECTED INTO THE MODEL CONTEXT\necho oops >&2\nexit 2\n",
            "exit 1" to "#!/bin/sh\nexit 1\n",
            "missing interpreter" to "#!/nonexistent/interp\n",
        )) {
            val r = runHook("work", payload("work", "s-x"), script = body)
            assertEquals("rc=0", r.out.trim(), "$label: stdout must be empty and the status 0, got:\n${r.out}")
        }
    }

    @Test fun theScriptItselfNeverWritesToStdoutOrStderrEvenWhenAToolItCallsDoes() = guarded {
        // a tmux that complains on stderr and chatters on stdout like a broken install would; the script has to
        // have closed both streams already (the settings.json command redirects too: this is the second layer)
        val shim = File(fixture.root, "noisy").apply { mkdirs() }
        File(shim, "tmux").apply { writeText("#!/bin/sh\necho NOISE-ON-STDERR >&2\nexit 3\n"); setExecutable(true) }
        File(shim, "date").apply { writeText("#!/bin/sh\necho NOISE-ON-STDERR >&2\nexec /bin/date \"\$@\"\n"); setExecutable(true) }
        val f = File(fixture.root, "hook-direct.sh").apply { writeText(HookCommands.HOOK_EV_SCRIPT); setExecutable(true) }
        val stdin = File(fixture.root, "empty-stdin").also { it.writeText("{}") }
        val r = fixture.run(
            "bash", "-c",
            "env -u TMUX PATH=${shq(shim.absolutePath)}:\$PATH TMUX_PANE=%99 CLAUDE_PID=\$\$ sh ${shq(f.absolutePath)} work < ${shq(stdin.absolutePath)}; echo \"rc=\$?\"",
        )
        assertEquals("rc=0", r.out.trim(), "no byte on either stream, status 0: ${r.out}")
        assertEquals(emptyList(), events())
    }

    @Test fun theRealScriptWithoutATmuxPaneIsSilentAndExitsZero() = guarded {
        val r = runHook("work", payload("work", "s-x"), pre = "env -u TMUX -u TMUX_PANE")
        assertEquals("rc=0", r.out.trim())
        assertEquals(emptyList(), events())
    }

    @Test fun aTwoMegabyteStdinIsDrainedSoTheWriterNeverGetsEpipe() = guarded {
        val f = File(fixture.root, "hook-under-test.sh").apply { writeText(HookCommands.HOOK_EV_SCRIPT); setExecutable(true) }
        val cmd = HookCommands.entryCommand("work").replace("\$HOME/.claude-remote/hook-ev.sh", f.absolutePath)
        val r = fixture.run("bash", "-c", "head -c 2097152 /dev/zero | sh -c ${shq(cmd)}; echo \"\${PIPESTATUS[@]}\"")
        assertEquals("0 0", r.out.trim(), "writer and hook both succeeded (141 would be SIGPIPE): ${r.out}")
    }

    // ------------------------------------------------------------ installer end to end

    @Test fun theInstallCommandWritesAnExecutableScriptAndIsIdempotentInTheSandbox() = guarded {
        val script = File(fixture.home, ".claude-remote/hook-ev.sh")
        assertTrue(script.isFile && script.canExecute())
        assertEquals(HookCommands.HOOK_EV_SCRIPT + "\n", script.readText())
        val again = fixture.run("bash", "-c", HookCommands.ENSURE_HOOKS_V2_COMMAND).out
        assertContains(again, "HOOKEV_SCRIPT_PRESENT")
        assertContains(again, "HOOKS_V2_EXISTS")
        // a NEWER script on disk is never overwritten
        val newer = HookCommands.HOOK_EV_SCRIPT.replace("claude-remote-hookev-v1", "claude-remote-hookev-v9") + "\n"
        script.writeText(newer)
        assertContains(fixture.run("bash", "-c", HookCommands.ENSURE_HOOKS_V2_COMMAND).out, "HOOKEV_SCRIPT_PRESENT")
        assertEquals(newer, script.readText())
        script.writeText(HookCommands.HOOK_EV_SCRIPT + "\n")
    }

    @Test fun theBootstrapScriptRunsToItsEndOnARealServerAndOnlyReadsTheHookState() = guarded {
        val settings = File(fixture.home, ".claude/settings.json")
        val before = settings.readBytes()
        val out = fixture.run("bash", "-c", BootstrapScript.build(installV2 = false), timeoutMs = 60_000).out
        val r = BootstrapScript.parse(out)
        assertTrue(r.complete, out)
        assertEquals("HOOK_EXISTS", r.legacyHook)
        assertEquals(HookCommands.V2State.INSTALLED, r.v2.state)
        assertNotNull(r.streamd)
        assertTrue(before.contentEquals(settings.readBytes()), "a bootstrap without a request writes nothing to settings.json")
    }

    @Test fun serverSideForceOffStopsTheInstallInTheShellAndACorruptFileDoesNot() = guarded {
        val flags = File(fixture.home, ".claude-remote/client-flags.json")
        val settings = File(fixture.home, ".claude/settings.json")
        try {
            // remove the hooks first so the install would have something to do
            assertContains(fixture.run("bash", "-c", HookCommands.REMOVE_HOOKS_V2_COMMAND).out, "HOOKS_V2_REMOVE_FIXED")
            flags.writeText("""{"off":["lazy","hooks"]}""")
            val offOut = fixture.run("bash", "-c", BootstrapScript.build(installV2 = true), timeoutMs = 60_000).out
            assertContains(offOut, "HOOKS_V2_SKIPPED_OFF")
            assertFalse(settings.readText().contains("claude-remote-ev"), "force-off: not even a partial install")
            flags.writeText("{ broken json")
            val badOut = fixture.run("bash", "-c", BootstrapScript.build(installV2 = true), timeoutMs = 60_000).out
            assertContains(badOut, "HOOKS_V2_FIXED", message = "a corrupt flags file switches nothing off")
            assertTrue(settings.readText().contains("claude-remote-ev"))
        } finally {
            flags.delete()
            if (!settings.readText().contains("claude-remote-ev")) fixture.run("bash", "-c", HookCommands.ENSURE_HOOKS_V2_COMMAND)
        }
    }

    @Test fun poolOrExecOffOnTheServerStopsTheInstallToo_AndOnlyTheFirst4096BytesOfTheFileCount() = guarded {
        val flags = File(fixture.home, ".claude-remote/client-flags.json")
        val settings = File(fixture.home, ".claude/settings.json")
        try {
            for (off in listOf("pool", "exec")) {
                fixture.run("bash", "-c", HookCommands.REMOVE_HOOKS_V2_COMMAND) // nothing to remove on the second round
                flags.writeText("""{"off":["$off"]}""")
                val out = fixture.run("bash", "-c", BootstrapScript.build(installV2 = true), timeoutMs = 60_000).out
                assertContains(out, "HOOKS_V2_SKIPPED_OFF", message = "$off off implies hooks off (they need both)")
                assertFalse(settings.readText().contains("claude-remote-ev"), "$off: not even a partial install")
            }
            // the Kotlin side reads `head -c 4096`; a longer file is judged by the same prefix on both sides, so
            // an "off" list beyond byte 4096 is an unparseable file here too and switches nothing off
            fixture.run("bash", "-c", HookCommands.REMOVE_HOOKS_V2_COMMAND)
            assertFalse(settings.readText().contains("claude-remote-ev"))
            flags.writeText("""{"pad":"${"x".repeat(5000)}","off":["hooks"]}""")
            val long = fixture.run("bash", "-c", BootstrapScript.build(installV2 = true), timeoutMs = 60_000).out
            assertContains(long, "HOOKS_V2_FIXED", message = "the shell does not see what the client cannot see")
        } finally {
            flags.delete()
            if (!settings.readText().contains("claude-remote-ev")) fixture.run("bash", "-c", HookCommands.ENSURE_HOOKS_V2_COMMAND)
        }
    }

    @Test fun aDamagedHookScriptWithAValidHeaderIsRewrittenAndAnIntactOneIsKept() = guarded {
        val script = File(fixture.home, ".claude-remote/hook-ev.sh")
        val intact = HookCommands.HOOK_EV_SCRIPT + "\n"
        try {
            script.writeText(intact.take(intact.length / 2)) // truncated, header intact
            val fixed = fixture.run("bash", "-c", HookCommands.ENSURE_HOOKS_V2_COMMAND).out
            assertContains(fixed, "HOOKEV_SCRIPT_INSTALLED")
            assertEquals(intact, script.readText())
            script.writeText(intact.replaceFirst("exit 0", "exit 7")) // the same length, different bytes
            assertContains(fixture.run("bash", "-c", HookCommands.ENSURE_HOOKS_V2_COMMAND).out, "HOOKEV_SCRIPT_INSTALLED", message = "same length, other bytes: the hash catches it")
            assertEquals(intact, script.readText())
            assertContains(fixture.run("bash", "-c", HookCommands.ENSURE_HOOKS_V2_COMMAND).out, "HOOKEV_SCRIPT_PRESENT")
            // a host without a working sha256sum: the size alone still catches a truncated file
            val noHash = File(fixture.root, "nohash").apply { mkdirs() }
            File(noHash, "sha256sum").apply { writeText("#!/bin/sh\nexit 127\n"); setExecutable(true) }
            script.writeText(intact.take(intact.length / 2))
            val bySize = fixture.run("bash", "-c", "PATH=${shq(noHash.absolutePath)}:\$PATH; ${HookCommands.ENSURE_HOOKS_V2_COMMAND}").out
            assertContains(bySize, "HOOKEV_SCRIPT_INSTALLED", message = "no sha256sum: the size check works alone")
            assertEquals(intact, script.readText())
        } finally {
            script.writeText(intact)
        }
    }

    // ------------------------------------------------- teammates, a stuck tmux, whitespace in the JSON

    /** `<name>` with a plain `sleep` main pane and the fake claude in a SPLIT pane of the same session (agent teams, tmux mode). */
    private fun launchInSplitPane(name: String, chain: Chain) {
        val claude = "exec env CMDDIR=${cmdDir.absolutePath} ${shimPath.absolutePath} ${chain.script.absolutePath}"
        fixture.tmux("new-session", "-d", "-s", name, "-x", "200", "-y", "50", "sleep 60")
        sessionsStarted += name
        val r = fixture.tmux("split-window", "-d", "-t", "=$name:0", claude)
        waitUntil(15_000, "split-pane chain $name to finish (tmux said: ${r.out.trim()})") { chain.done }
    }

    @Test fun aClaudeInASplitPaneOfTheSessionIsATeammateAndEmitsNothing() = guarded {
        val c = chainScript("s-split", step("work", "s-split"), step("perm", "s-split"))
        launchInSplitPane("t-split", c)
        assertEquals(emptyList(), events(), "only the main pane's claude is the one the app attaches to")
        assertQuietHooks(c)
    }

    @Test fun theMainPaneStillEmitsWhileASplitPaneExists() = guarded {
        val c = chainScript("s-main", step("work", "s-main"))
        val claude = "exec env CMDDIR=${cmdDir.absolutePath} ${shimPath.absolutePath} ${c.script.absolutePath}"
        fixture.tmux("new-session", "-d", "-s", "t-main", "-x", "200", "-y", "50", "sh -c 'sleep 2; $claude'")
        sessionsStarted += "t-main"
        fixture.tmux("split-window", "-d", "-t", "=t-main:0", "sleep 60")
        waitUntil(15_000, "main-pane chain to finish") { c.done }
        assertEquals(listOf("work"), events().map { it.ev })
        assertEquals("t-main", events().single().tmux)
    }

    /** Runs the real script with a `tmux` stub of the given body, returns (exit code, wall ms) measured inside the shell. */
    private fun runHookWithTmuxStub(stubBody: String): Pair<Int, Long> {
        val shim = File(fixture.root, "stuck-${seq.incrementAndGet()}").apply { mkdirs() }
        File(shim, "tmux").apply { writeText("#!/bin/sh\n$stubBody\n"); setExecutable(true) }
        val f = File(fixture.root, "hook-stuck.sh").apply { writeText(HookCommands.HOOK_EV_SCRIPT); setExecutable(true) }
        val stdin = File(fixture.root, "stuck-stdin").also { it.writeText("{}") }
        val r = fixture.run(
            "bash", "-c",
            "s=\$(date +%s%N); env -u TMUX PATH=${shq(shim.absolutePath)}:\$PATH TMUX_PANE=%99 CLAUDE_PID=\$\$ sh ${shq(f.absolutePath)} work < ${shq(stdin.absolutePath)}; " +
                "rc=\$?; e=\$(date +%s%N); echo \"rc=\$rc ms=\$(( (e-s)/1000000 ))\"",
            timeoutMs = 20_000,
        )
        val m = Regex("""rc=(\d+) ms=(\d+)""").find(r.out) ?: error("no result: ${r.out}")
        return m.groupValues[1].toInt() to m.groupValues[2].toLong()
    }

    @Test fun aTmuxThatNeverAnswersEndsTheHookWithinTwoSeconds() = guarded {
        val (rc, ms) = runHookWithTmuxStub("sleep 30")
        assertEquals(0, rc, "exit 0 even when tmux hangs")
        assertTrue(ms <= 2_000, "bounded by `timeout -k 1 1`: took $ms ms")
        assertEquals(emptyList(), events())
    }

    @Test fun aTmuxThatIgnoresTermIsKilledOneSecondLater() = guarded {
        // a plain `timeout 1` would wait for this stub for the full 30 s; `-k 1` adds one second and the process start
        val (rc, ms) = runHookWithTmuxStub("trap '' TERM\nsleep 30 &\nwait")
        assertEquals(0, rc)
        assertTrue(ms <= 2_600, "1 s to TERM + 1 s to KILL, not 30 s: took $ms ms")
        assertEquals(emptyList(), events())
    }

    @Test fun theOffSwitchIsCheckedBeforeTmuxIsEverCalledAndAFailingTmuxIsExitZero() = guarded {
        val shim = File(fixture.root, "recording").apply { mkdirs() }
        val called = File(fixture.root, "tmux-was-called")
        called.delete()
        File(shim, "tmux").apply { writeText("#!/bin/sh\ntouch ${called.absolutePath}\nexit 3\n"); setExecutable(true) }
        val f = File(fixture.root, "hook-rec.sh").apply { writeText(HookCommands.HOOK_EV_SCRIPT); setExecutable(true) }
        val stdin = File(fixture.root, "rec-stdin").also { it.writeText("{}") }
        val run = "env -u TMUX PATH=${shq(shim.absolutePath)}:\$PATH TMUX_PANE=%99 CLAUDE_PID=\$\$ sh ${shq(f.absolutePath)} work < ${shq(stdin.absolutePath)}; echo \"rc=\$?\""
        val off = File(fixture.home, ".claude-remote/hookev.off").apply { writeText("") }
        assertEquals("rc=0", fixture.run("bash", "-c", run).out.trim())
        assertFalse(called.exists(), "hookev.off: the script exits before it touches tmux")
        off.delete()
        assertEquals("rc=0", fixture.run("bash", "-c", run).out.trim(), "tmux fails with 3: still exit 0")
        assertTrue(called.exists(), "without the switch the script does call tmux")
    }

    @Test fun aSessionIdWithWhitespaceAroundTheColonIsStillOurs() = guarded {
        val pretty = "work\t{\"session_id\" : \"s-ws\", \"hook_event_name\" : \"UserPromptSubmit\"}"
        launch("t-ws", chainScript("s-ws", pretty), "exec")
        assertEquals(listOf("work"), events().map { it.ev }, "pretty-printed JSON must not silently drop events")
        val askPretty = "ask\t{\"session_id\": \"s-ws2\", \"tool_name\" : \"AskUserQuestion\", \"tool_input\": {\"questions\": [{\"question\" : \"Which one?\"}]}}"
        launch("t-ws2", chainScript("s-ws2", askPretty), "exec")
        assertEquals(listOf("work", "ask"), events().map { it.ev })
        assertContains(events().last().extra, "Which one?")
    }

    @Test fun prettyPrintedJsonDoesNotSlipPastTheOwnershipAndAskUserQuestionChecks() = guarded {
        // the same whitespace tolerance matters for the DROPS: a foreign session id and a PermissionRequest for
        // AskUserQuestion must still be recognised when the JSON is not compact
        val foreign = "work\t{\"session_id\" : \"someone-else\", \"hook_event_name\" : \"UserPromptSubmit\"}"
        launch("t-ws3", chainScript("s-ws3", foreign), "exec")
        val permAsk = "perm\t{\"session_id\" : \"s-ws4\", \"tool_name\" : \"AskUserQuestion\", \"tool_input\" : {}}"
        launch("t-ws4", chainScript("s-ws4", permAsk), "exec")
        assertEquals(emptyList(), events())
    }

    @Test fun aPermissionRequestForAnMcpToolWhoseInputNamesAskUserQuestionIsStillEmitted() = guarded {
        val mcp = "perm\t{\"session_id\":\"s-mcp\",\"hook_event_name\":\"PermissionRequest\",\"tool_name\":\"mcp__notes__write\",\"tool_input\":{\"tool_name\":\"AskUserQuestion\",\"text\":\"x\"}}"
        launch("t-mcp", chainScript("s-mcp", mcp), "exec")
        val ev = events()
        assertEquals(listOf("perm"), ev.map { it.ev }, "only the tool_name before tool_input decides: $ev")
    }

    // ------------------------------------------------- the watcher, end to end

    private class WatcherRig(val service: NotificationService, val scope: CoroutineScope, val manager: SshManager) {
        val activity = Collections.synchronizedList(mutableListOf<Pair<String, SessionActivity>>())
        val needsInput = Collections.synchronizedList(mutableListOf<Triple<String, String, String?>>())
        val sink = RecordingSink()
    }

    private class RecordingSink : HookEventSink {
        val checkClosed = Collections.synchronizedList(mutableListOf<Triple<String, String, Long>>())
        val working = Collections.synchronizedList(mutableListOf<String>())
        val uuid = Collections.synchronizedList(mutableListOf<String>())
        override fun onCheckClosed(serverId: String, tmuxName: String, delayMs: Long) { checkClosed += Triple(serverId, tmuxName, delayMs) }
        override fun onClaudeWorking(serverId: String, tmuxName: String, working: Boolean) { this.working += tmuxName }
        override fun onCheckUuid(serverId: String, tmuxName: String) { uuid += tmuxName }
    }

    private fun <T> withWatcher(
        tmux: String,
        bootstrap: com.clauderemote.session.service.ServerBootstrap? = null,
        execChannels: ExecChannels? = null,
        block: (WatcherRig, String) -> T,
    ): T {
        val tabs = TabManager()
        val registry = ConnectionRegistry(ServerStorage(InMemoryKeyValueStore()), tabs)
        registry.bootstrap = bootstrap
        registry.execChannels = execChannels
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val manager = SshManager(ServerStorage(InMemoryKeyValueStore()))
        runBlocking { manager.connect(fixture.server, onOutput = {}, onConnectionLost = {}) }
        val sid = "sess-w-$tmux"
        tabs.addTab(
            ClaudeSession(
                id = sid, server = fixture.server, folder = fixture.home.absolutePath, mode = ClaudeMode.YOLO,
                model = ClaudeModel.DEFAULT, tmuxSessionName = tmux, connectionType = ConnectionType.SSH,
            ),
        )
        registry.putSsh(sid, manager)
        lateinit var rig: WatcherRig
        val service = NotificationService(
            scope = scope, registry = registry, tabManager = tabs, isBackground = { true },
            updateActivity = { id, a -> rig.activity += id to a },
            activityOf = { id -> rig.activity.lastOrNull { it.first == id }?.second },
            lastAssistantEntry = { null }, entriesOf = { emptyList() }, streamOrNull = { null },
            onNeedsInput = { id, hint, _, body -> rig.needsInput += Triple(id, hint, body) },
        )
        rig = WatcherRig(service, scope, manager)
        service.hookSink = rig.sink
        try {
            service.startNotifyWatcher(sid, tmux, fixture.server.id)
            waitUntil(20_000, "the notify watcher to go live") { service.hookActiveSessions.value.contains(sid) }
            return block(rig, sid)
        } finally {
            service.unregisterNotifyWatcher(fixture.server.id, sid)
            scope.cancel()
            manager.shutdownForTest()
        }
    }

    private fun appendEvent(ev: String, tmux: String, extra: String = "") {
        val b64 = java.util.Base64.getEncoder()
        val line = "claude-remote-ev $ev ${b64.encodeToString(tmux.toByteArray())} ${System.currentTimeMillis()} 4242 " +
            (if (extra.isEmpty()) "" else b64.encodeToString(extra.toByteArray()))
        eventsFile.parentFile.mkdirs()
        eventsFile.appendText(line + "\n")
    }

    @Test fun aHookEventInTheEventsFileReachesTheSessionThroughTheRealWatcher() = guarded {
        withWatcher("claude-server-w1") { rig, sid ->
            appendEvent("perm", "claude-server-w1", "Bash: rm -rf build")
            waitUntil(15_000, "APPROVAL_NEEDED from the hook event") { rig.activity.contains(sid to SessionActivity.APPROVAL_NEEDED) }
            waitUntil(15_000, "the background notification") { rig.needsInput.isNotEmpty() }
            assertEquals("Bash: rm -rf build", rig.needsInput.first().third)

            appendEvent("work", "claude-server-w1")
            waitUntil(15_000, "WORKING after the next event") { rig.activity.last() == (sid to SessionActivity.WORKING) }
            waitUntil(5_000, "the latch re-arm") { rig.sink.working.contains("claude-server-w1") }
            assertTrue(rig.service.hookApprovalSince(fixture.server.id, "claude-server-w1") == null)

            appendEvent("start", "claude-server-w1", "source=clear exe=2.1.316")
            waitUntil(5_000, "the UUID re-check") { rig.sink.uuid.contains("claude-server-w1") }

            // an event for a session this device does not have is ignored
            val n = rig.activity.size
            appendEvent("perm", "claude-server-other", "x")
            Thread.sleep(1_500)
            assertEquals(n, rig.activity.size)
        }
    }

    @Test fun aLiveStopAfterTheLegacyMarkerLeavesTheSessionReadyAndAStopThatEndsADialogLeavesApproval() = guarded {
        val tmux = "claude-server-w6"
        val marker = File(fixture.home, ".cache/claude-remote/notify")
        withWatcher(tmux) { rig, sid ->
            appendEvent("perm", tmux, "Bash: ls")
            waitUntil(15_000, "APPROVAL_NEEDED") { rig.activity.lastOrNull() == (sid to SessionActivity.APPROVAL_NEEDED) }
            // the legacy Stop marker lands first (20 of 20 runs), the v2 stop of the same turn right behind it
            marker.appendText("claude-remote-notify $tmux ${System.currentTimeMillis() / 1000}\n")
            waitUntil(15_000, "ready from the legacy marker") { rig.activity.lastOrNull() == (sid to SessionActivity.WAITING_FOR_INPUT) }
            appendEvent("stop", tmux)
            Thread.sleep(2_500)
            assertEquals(sid to SessionActivity.WAITING_FOR_INPUT, rig.activity.last(), "the v2 stop must not overwrite ready: ${rig.activity.toList()}")

            // a stop that arrives while the dialog still stands does leave APPROVAL
            appendEvent("perm", tmux, "Bash: pwd")
            waitUntil(15_000, "APPROVAL_NEEDED again") { rig.activity.lastOrNull() == (sid to SessionActivity.APPROVAL_NEEDED) }
            appendEvent("stop", tmux)
            waitUntil(15_000, "WORKING after a stop that ends the dialog") { rig.activity.lastOrNull() == (sid to SessionActivity.WORKING) }
        }
    }

    @Test fun theWatcherAsksTheGateForALongPermitNeverAShortOne() = guarded {
        val kinds = Collections.synchronizedList(mutableListOf<ExecKind>())
        // a gate whose exec transport is not up: the watcher must have asked for LONG, then fall back to the tab's session
        val gate = object : ExecChannels {
            override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Channel) -> T): T {
                kinds += kind
                throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
            }
            override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Session) -> T): T =
                throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
        }
        withWatcher("claude-server-w7", execChannels = gate) { rig, sid ->
            assertTrue(kinds.isNotEmpty(), "the watcher went through the gate")
            assertEquals(setOf(ExecKind.LONG), kinds.toSet(), "a long-lived tail is a LONG permit: $kinds")
            assertTrue(rig.service.hookActiveSessions.value.contains(sid), "and fell back to the tab's session")
        }
    }

    @Test fun aForgetLogLineForOneOfOurTabsTriggersTheClosedCheckOnceEvenAfterAReplay() = guarded {
        val log = File(fixture.home, ".claude-remote/forget.log")
        log.parentFile.mkdirs()
        val ts = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now())
        try {
            withWatcher("claude-server-w2") { rig, _ ->
                log.appendText("[$ts] forget: claude-server-w2 (durable=1)\n")
                log.appendText("[$ts] forget: claude-server-nobody (durable=1)\n")
                waitUntil(15_000, "the first closed-elsewhere check") { rig.sink.checkClosed.size >= 1 }
                waitUntil(15_000, "the retry") { rig.sink.checkClosed.size >= 2 }
                assertEquals(setOf("claude-server-w2"), rig.sink.checkClosed.map { it.second }.toSet())
                assertEquals(3_000L, rig.sink.checkClosed.first().third)
            }
            // a second watcher (a reconnect) replays the same 25 lines: no new check for the old line is expected from the
            // SAME tracker; a fresh process only acts on lines younger than 60 s, which this one is - so it asserts the age rule instead
            val old = "[2026-01-01T00:00:00Z] forget: claude-server-w3 (durable=1)\n"
            log.appendText(old)
            withWatcher("claude-server-w3") { rig, _ ->
                Thread.sleep(2_000)
                assertTrue(rig.sink.checkClosed.none { it.second == "claude-server-w3" }, "a line from January is a replay, not an event")
            }
        } finally {
            log.delete()
        }
    }

    @Test fun aWatcherReconnectInvalidatesTheBootstrapMemoAndEnsuresAgain() = guarded {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val fake = object : com.clauderemote.session.service.ServerBootstrap {
            override suspend fun ensure(serverId: String) { calls += "ensure" }
            override fun invalidate(serverId: String) { calls += "invalidate" }
            override suspend fun refreshFlags(serverId: String) {}
        }
        withWatcher("claude-server-w5", fake) { rig, sid ->
            assertEquals(emptyList(), calls.toList(), "the first connect is not a RE-connect")
            // kill only THIS fixture's tail: its command line carries the sandbox home, no live process does
            fixture.run("pkill", "-f", File(fixture.home, ".cache/claude-remote/notify").absolutePath)
            waitUntil(30_000, "the watcher to reconnect and call the bootstrap") { calls.contains("ensure") }
            assertEquals("invalidate", calls.first(), "memo dropped first, then ensured: $calls")
            waitUntil(20_000, "hook detection live again") { rig.service.hookActiveSessions.value.contains(sid) }
        }
    }

    @Test fun withHooksForcedOffForTheServerTheWatcherIgnoresEventLines() = guarded {
        CommFlags.applyServerOff(fixture.server.id, setOf("hooks"))
        withWatcher("claude-server-w4") { rig, _ ->
            appendEvent("perm", "claude-server-w4", "Bash: ls")
            Thread.sleep(2_000)
            assertEquals(emptyList(), rig.activity.toList(), "comm_hooks off for this server = today's behaviour")
        }
    }

    companion object {
        private lateinit var fixture: SshdFixture
        /** JUnit makes a new instance per test: the counter must outlive it or chain files collide across tests. */
        private val seq = java.util.concurrent.atomic.AtomicInteger()

        @BeforeClass
        @JvmStatic
        fun boot() {
            SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
            fixture = SshdFixture.start()
            fixture.startTmuxServer()
            // the private server only: panes run bash whatever the box's login shell is
            fixture.tmux("set-option", "-g", "default-shell", "/bin/bash")
            File(fixture.root, "bin").mkdirs()
            File(fixture.root, "cmds").mkdirs()
            val shim = File(fixture.root, "bin/claude")
            shim.writeText(
                """
                #!/bin/bash
                # Fake claude: its comm is "claude". Runs the settings.json hook command per step, like claude does.
                S="${'$'}1"
                echo ${'$'}${'$'} > "${'$'}S.pid"
                echo "${'$'}TMUX" > "${'$'}S.tmuxenv"
                export CLAUDE_PID=${'$'}${'$'}
                export CLAUDE_CODE_SESSION_ID="${'$'}(cat "${'$'}S.sid")"
                [ -f "${'$'}S.nopid" ] && unset CLAUDE_PID
                while IFS=${'$'}'\t' read -r op arg; do
                  case "${'$'}op" in
                    SPAWN) bash -c "'${'$'}SHIM' '${'$'}arg'; true" < /dev/null ;;
                    SLEEP) sleep "${'$'}arg" ;;
                    *) printf '%s' "${'$'}arg" > "${'$'}S.stdin"
                       cmd="${'$'}(cat "${'$'}CMDDIR/${'$'}op")"
                       # a plain fork+exec from THIS process, like claude's own spawn: no pipeline and no command
                       # substitution around it, whose forked bash subshell would carry comm "claude" and sit between
                       # this process and the hook (real claude never has such an intermediate)
                       sh -c "${'$'}cmd" < "${'$'}S.stdin" > "${'$'}S.out" 2>&1; rc=${'$'}?
                       echo "${'$'}rc|${'$'}(cat "${'$'}S.out")" >> "${'$'}S.hookres" ;;
                  esac
                done < "${'$'}S"
                touch "${'$'}S.done"
                sleep 2
                """.trimIndent() + "\n",
            )
            shim.setExecutable(true)
            for ((_, _, ev) in HookCommands.WANTED) File(fixture.root, "cmds/$ev").writeText(HookCommands.entryCommand(ev))
            // hooks v2 installed into the SANDBOX home by the very command the app runs
            val out = fixture.run("bash", "-c", HookCommands.ENSURE_HOOKS_V2_COMMAND).out
            check(out.contains("HOOKS_V2_FIXED") && out.contains("HOOKEV_SCRIPT_INSTALLED")) { "sandbox install: $out" }
            // a real box has the legacy Stop entry already; bring the sandbox to that state
            fixture.run("bash", "-c", com.clauderemote.session.service.LEGACY_STOP_HOOK_COMMAND)
        }

        @AfterClass
        @JvmStatic
        fun shutdown() {
            if (::fixture.isInitialized) fixture.close()
        }
    }
}
