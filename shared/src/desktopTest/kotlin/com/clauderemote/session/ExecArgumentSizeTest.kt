package com.clauderemote.session

import com.clauderemote.connection.TmuxManager
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.BootstrapScript
import com.clauderemote.session.service.CloseFactsCommands
import com.clauderemote.session.service.ClosedRecord
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecResult
import com.clauderemote.session.service.HookCommands
import com.clauderemote.session.service.LEGACY_STOP_HOOK_COMMAND
import com.clauderemote.session.service.ManifestCommands
import com.clauderemote.session.service.Pace
import com.clauderemote.session.service.RemoteExec
import com.clauderemote.session.service.ServerBootstrap
import com.clauderemote.session.service.SessionOpsScript
import com.clauderemote.session.service.SessionRestore
import com.clauderemote.session.service.StdinScript
import com.clauderemote.session.service.TranscriptService
import com.clauderemote.session.service.buildNotifyWatcherCommand
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.PersistedSession
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.clauderemote.util.LogShipper
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * C0 (dávka C, K-1): an exec request is ONE SSH packet. Through the Cloudflare tunnel a request of ~80 KB
 * (the restore installer, 23 KB compressed) was followed by an exec transport that opened no more channels; uploads
 * go on stdin, paced 4 KB / 20 ms. This renders the exec requests the app sends — the install, the manifest push
 * and the tombstone clear through the production functions, the rest through their builders with realistic
 * input — and fails on any above [LIMIT].
 *
 * [KNOWN_OVER] are the requests above the limit at 18faa58 that C0 does not own (ServerBootstrap, HookCommands,
 * TranscriptService, ManifestCommands.reconcileAll):
 * each has a ceiling of its size then, rounded up to the next KB, so none can grow unnoticed; moving their payload to
 * stdin is the follow-up (C0-rozhodnuti.md).
 */
class ExecArgumentSizeTest {
    private val tabs = TabManager()
    private val storage = ServerStorage(FakeKeyValueStore())
    private val server = SshServer(id = "c4408f08-0000-4000-8000-000000000001", name = "server", host = "127.0.0.1", port = 1, username = "u", password = "p")

    @BeforeTest fun setUp() { CommFlags.resetForTest(); storage.addServer(server) }
    @AfterTest fun tearDown() { CommFlags.resetForTest() }

    private class NoBootstrap : ServerBootstrap {
        override suspend fun ensure(serverId: String) {}
        override fun invalidate(serverId: String) {}
        override suspend fun refreshFlags(serverId: String) {}
    }

    /** One exec request and what went on its stdin (null = none). */
    private class Sent(val cmd: String, val stdin: ByteArray?)

    /** Records every request with its stdin (kritika D návrh 7, kritika D2 N10); answers like a server that has everything. */
    private class CapturingExec(val answer: (String, ByteArray?) -> String = { _, _ -> "RESTORE_SCRIPT_PRESENT" }) : RemoteExec {
        val sent = CopyOnWriteArrayList<Sent>()
        val commands: List<String> get() = sent.map { it.cmd }
        override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace): ExecResult {
            sent += Sent(cmd, stdin)
            val out = answer(cmd, stdin)
            return ExecResult(if (cmd == StdinScript.EXEC) out + "\n" + StdinScript.MARK + "0\n" else out, 0)
        }
    }

    /** An exec channel on a session of its own (restoreClosed): records the request and the stdin it is fed. */
    private class RecordingChannel(private val stdout: String) : com.jcraft.jsch.ChannelExec() {
        @Volatile var sentCommand: String? = null
        val stdin = java.io.ByteArrayOutputStream()
        override fun setCommand(command: String) { sentCommand = command }
        override fun connect(connectTimeout: Int) {}
        override fun getOutputStream(): java.io.OutputStream = object : java.io.OutputStream() {
            override fun write(b: Int) { synchronized(stdin) { stdin.write(b) } }
            override fun write(b: ByteArray, off: Int, len: Int) { synchronized(stdin) { stdin.write(b, off, len) } }
        }
        override fun getInputStream(): java.io.InputStream = java.io.ByteArrayInputStream(stdout.toByteArray())
        override fun setInputStream(`in`: java.io.InputStream?) {}
        override fun getExitStatus(): Int = 0
        override fun isClosed(): Boolean = false
        override fun isEOF(): Boolean = false
        override fun disconnect() {}
    }

    private val record = ClosedRecord(
        1, "2026-11-06T17:20:03Z", "claude-server-claude-remote--relay-design-davka-1", "11111111-2222-3333-4444-555555555555",
        "aaaaaaaa-2222-3333-4444-555555555555", null, "/home/lucas/projects/a-rather-long-folder-name/with/depth",
        "/home/lucas/projects/a-rather-long-folder-name/with/depth", "hanakl-nekrachni-cz", "SONNET", "AUTO", emptyList(), null, null,
        null, null, "app:android-706cbd7b", true, null,
    )

    /**
     * D3: the four scripts that now go on stdin, each rendered through its PRODUCTION caller: the close's facts check
     * and the close itself through the orchestrator's own pendingCloses wiring (`CloseFactsCheck`, `forgetSession`),
     * the restore through `restoreClosed` (a session of its own) and the `cr-session` install through the bootstrap's
     * `installSessionOps`. Name -> (request, stdin).
     */
    private fun sentOnStdin(): Map<String, Sent> {
        val out = linkedMapOf<String, Sent>()
        val sessions = SessionStorage(FakeKeyValueStore())
        val o = SessionOrchestrator(storage, tabs, sessions).also { it.connectionRegistry.bootstrap = NoBootstrap() }
        val exec = CapturingExec().also { o.connectionRegistry.remoteExec = it }
        val tab = com.clauderemote.model.ClaudeSession(
            id = "t1", server = server, folder = "/home/lucas/projects/a-rather-long-folder-name/with/depth", mode = ClaudeMode.YOLO,
            model = ClaudeModel.DEFAULT, tmuxSessionName = names[0], connectionType = com.clauderemote.model.ConnectionType.SSH,
        )
        tabs.addTab(tab)
        sessions.upsert(SessionStorage.fromClaudeSession(tab))
        try {
            assertTrue(o.pendingCloses.close(tab))
            val end = System.currentTimeMillis() + 10_000
            while (exec.sent.none { it.cmd == StdinScript.EXEC } && System.currentTimeMillis() < end) Thread.sleep(10)
            runBlocking { kotlinx.coroutines.withTimeout(10_000) { o.pendingCloses.flush().join() } }
            fun script(marker: String) = exec.sent.firstOrNull { it.stdin?.toString(Charsets.UTF_8)?.contains(marker) == true }
            out["close facts (closeFactsCommand)"] = script(CloseFactsCommands.END) ?: fail("no facts exec: ${exec.commands}")
            out["close (closeCommand)"] = script(ManifestCommands.CLOSE_KILL_PREFIX) ?: fail("no close exec: ${exec.commands}")

            val ch = RecordingChannel("CR_RESTORE EXISTS -\n\n${StdinScript.MARK}0\n")
            val session = com.jcraft.jsch.FakeSession(channels = { ch })
            com.clauderemote.connection.SshSessionHelper.execChannelsProvider = { _ ->
                object : com.clauderemote.session.service.ExecChannels {
                    override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Channel) -> T): T = error("unused")
                    override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Session) -> T): T = block(session)
                }
            }
            val (result, _) = runBlocking { o.restoreClosed(server, record) }
            assertEquals(com.clauderemote.session.service.RestoreResult.Exists(null), result, "fixture: the restore's answer was read")
            out["restore closed (restoreCommand)"] = Sent(ch.sentCommand ?: fail("no restore exec"), ch.stdin.toByteArray())
        } finally {
            com.clauderemote.connection.SshSessionHelper.execChannelsProvider = null
            o.shutdownForTest()
        }

        val boot = CapturingExec { cmd, _ ->
            if (cmd == StdinScript.EXEC) "SESSIONOPS_INSTALLED\n"
            else "===CRB flags===\n\n===CRB hooks===\nHOOK_EXISTS\n===CRB hooksv2===\nHOOKS_V2_STATE=ABSENT\nHOOKEV_OFF=0\n" +
                "===CRB streamd===\nSTREAMD_OK\n===CRB sessionops===\n${SessionOpsScript.CHECK_NEEDED}\n===CRB end===\n"
        }
        runBlocking {
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            try {
                com.clauderemote.session.service.DefaultServerBootstrap(scope = scope, exec = boot, applyServerOff = { _, _ -> }).ensure(server.id)
            } finally {
                scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            }
        }
        out["cr-session install (SessionOpsScript.INSTALL_COMMAND)"] = boot.sent.firstOrNull { it.cmd == StdinScript.EXEC }
            ?: fail("no install exec: ${boot.commands.map { it.take(60) }}")
        return out
    }

    /** 58 sessions (the live manifest, K-1) with names of a realistic length. */
    private val names = (1..58).map { "claude-server-claude-remote--relay-design-davka-$it" }

    private fun sent(): Map<String, String> {
        val sessions = SessionStorage(FakeKeyValueStore())
        names.forEachIndexed { i, n ->
            sessions.upsert(PersistedSession(id = "s$i", serverId = server.id, folder = "/home/lucas/projects/p$i", tmuxSessionName = n))
        }
        val o = SessionOrchestrator(storage, tabs, sessions).also { it.connectionRegistry.bootstrap = NoBootstrap() }
        val exec = CapturingExec().also { o.connectionRegistry.remoteExec = it }
        val out = linkedMapOf<String, String>()
        runBlocking {
            o.persistence.ensureRestoreService(server.id)
            out["restore install (ensureRestoreService)"] = exec.commands.last()
            o.persistence.pushSessionsToServer(server.id)
            out["manifest push (pushSessionsToServer)"] = exec.commands.last()
            o.persistence.clearTombstone(server.id, names[0])
            out["tombstone clear (clearTombstone)"] = exec.commands.last()
        }
        out["launch (buildFreshCreateCommand)"] = ClaudeConfig.buildFreshCreateCommand(
            names[0], "/home/lucas/projects/a-rather-long-folder-name/with/depth", ClaudeMode.YOLO, ClaudeModel.DEFAULT,
            "11111111-2222-3333-4444-555555555555", accountSlug = "hanakl-nekrachni-cz", nonce = "a1b2c3d4e5f6",
        )
        out["launch cleanup (buildOwnSessionCleanupCommand)"] = ClaudeConfig.buildOwnSessionCleanupCommand(names[0], "a1b2c3d4e5f6")
        out["reconcile, 58 sessions (reconcileAll)"] = ManifestCommands.reconcileAll(names)
        out["server snapshot"] = ManifestCommands.serverSnapshot()
        out["close state"] = ManifestCommands.closeState(names[0])
        out["tombstone"] = ManifestCommands.tombstone(names[0], durable = true)
        out["rename"] = ManifestCommands.renameSession(names[0], names[1])
        out["fresh-name listing"] = TmuxManager.freshNameListingCommand()
        out["log shipper append"] = LogShipper.remoteAppendCommand("android-706cbd7b")
        out["notify watcher"] = buildNotifyWatcherCommand(true)
        out["legacy Stop hook ensure"] = LEGACY_STOP_HOOK_COMMAND
        out["bootstrap flags only"] = BootstrapScript.FLAGS_ONLY_COMMAND
        out["hooks v2 status"] = HookCommands.STATUS_HOOKS_V2_COMMAND
        out["hooks v2 remove"] = HookCommands.REMOVE_HOOKS_V2_COMMAND
        out["bootstrap, hooks v2 install"] = BootstrapScript.build(installV2 = true)
        out["bootstrap, hooks v2 status"] = BootstrapScript.build(installV2 = false)
        out["streamd install"] = TranscriptService.ENSURE_STREAMD_COMMAND
        sentOnStdin().forEach { (what, s) -> out[what] = s.cmd }
        // Jarvis' conversation mode (F5a): the longest spoken prompt in its worst forms — 3-byte typography, and
        // apostrophes, which the shell quoting turns into 4 bytes each
        for ((what, unit) in listOf("3-byte" to "„“…", "apostrophes" to "a'", "2-byte, ending in ;" to "ěščř;")) {
            out["Jarvis voice prompt, $what (ReplyGuard.serverCommand, emptyInput)"] = com.clauderemote.session.service.ReplyGuard.serverCommand(
                com.clauderemote.session.QuickAccess.JARVIS_TMUX_NAME, com.clauderemote.session.service.ServerExpectation.Idle,
                com.clauderemote.voice.conversation.TalkWords.prompt(unit.repeat(3_000)), emptyInput = true,
            )
        }
        return out
    }

    @Test fun noExecRequestIsAboveFourKilobytes() {
        val sent = sent()
        val sizes = sent.mapValues { it.value.toByteArray(Charsets.UTF_8).size }
        sizes.forEach { (what, n) -> println("exec request %6d B  deflated %6d B  %s".format(n, deflated(sent.getValue(what)), what)) }
        val over = sizes.filter { (what, n) -> n > (KNOWN_OVER[what] ?: LIMIT) }
        if (over.isNotEmpty()) fail("exec requests above ${LIMIT} B (send the payload on stdin, paced): $over")
        val stale = KNOWN_OVER.keys - sizes.keys
        assertTrue(stale.isEmpty(), "known exceptions no longer rendered here: $stale")
    }

    /**
     * D3 (kritika D2 N1): the real guard against K-1. The connection compresses (zlib), and what got lost through the
     * Cloudflare tunnel was an exec request packet of more than 16 KB AFTER compression (23 KB, dávka C). No request may
     * come near that, whatever its raw size: [DEFLATED_LIMIT] after deflate, for every request rendered here.
     */
    @Test fun noExecRequestIsNearTheTunnelsPacketLimitAfterCompression() {
        val deflated = sent().mapValues { deflated(it.value) }
        val over = deflated.filter { it.value > DEFLATED_LIMIT }
        if (over.isNotEmpty()) fail("exec requests above $DEFLATED_LIMIT B after deflate (K-1 lost packets of > 16 KB compressed): $over")
    }

    /**
     * D3: the four scripts go on stdin behind [StdinScript.EXEC], byte for byte the builder's text (what used to be the
     * request), then the end line — the same script, only delivered differently. The request is a few hundred bytes.
     */
    @Test fun theFourBigScriptsGoOnStdinUnchangedBehindTheShortLauncher() {
        val expected = mapOf(
            "close facts (closeFactsCommand)" to CloseFactsCommands.closeFactsCommand(names[0], null),
            "close (closeCommand)" to null,
            "restore closed (restoreCommand)" to SessionRestore.restoreCommand(record),
            "cr-session install (SessionOpsScript.INSTALL_COMMAND)" to SessionOpsScript.INSTALL_COMMAND,
        )
        val sent = sentOnStdin()
        assertEquals(expected.keys, sent.keys)
        sent.forEach { (what, s) ->
            assertEquals(StdinScript.EXEC, s.cmd, "$what: the request is the launcher")
            val body = String(s.stdin ?: fail("$what: nothing on stdin"), Charsets.UTF_8)
            assertTrue(body.endsWith("\n" + StdinScript.END + "\n"), "$what: ends with the end line")
            val script = body.removeSuffix("\n" + StdinScript.END + "\n")
            assertTrue(script.toByteArray().size > LIMIT, "$what: the payload is the big one (${script.length} B)")
            expected[what]?.let { want ->
                // the facts carry this device's own client marker; the rest of the script is the builder's
                if (what.startsWith("close facts")) assertEquals(want.substringAfter("\n"), script.substringAfter("\n"), what)
                else assertEquals(want, script, what)
            }
        }
        val close = String(sent.getValue("close (closeCommand)").stdin!!, Charsets.UTF_8).removeSuffix("\n" + StdinScript.END + "\n")
        val by = Regex("BY='([^']*)'").find(close)?.groupValues?.get(1) ?: fail("no BY in the close")
        assertEquals(ManifestCommands.closeCommand(names[0], durable = true, by = by), close, "the close is the builder's, durable")
    }

    private fun deflated(s: String): Int {
        val d = java.util.zip.Deflater(6)
        d.setInput(s.toByteArray(Charsets.UTF_8)); d.finish()
        val buf = ByteArray(64 * 1024)
        var n = 0
        while (!d.finished()) n += d.deflate(buf)
        d.end()
        return n
    }

    private companion object {
        const val LIMIT = 4096
        /** Size at 18faa58 (bytes) -> ceiling. The reconcile grows ~65 B per session (5 019 B at 58). */
        val KNOWN_OVER = mapOf(
            "reconcile, 58 sessions (reconcileAll)" to 6 * 1024, // 5 019; F0 (Jarvis) sekce opened: 5 341
            "hooks v2 status" to 13 * 1024, // 12 469
            "hooks v2 remove" to 13 * 1024, // 12 469
            "bootstrap, hooks v2 install" to 39 * 1024, // 38 886; F0 (Jarvis) CHECK_COMMAND: 39 527
            "bootstrap, hooks v2 status" to 21 * 1024, // 20 777
            "streamd install" to 5 * 1024, // 5 025
            // D3: the F0 (Jarvis) requests close, close facts, restore and the cr-session install go on stdin
            // (StdinScript): no longer exceptions, LIMIT applies to them
        )
        /**
         * D3: the ceiling after deflate (zlib, level 6 like the connection's). K-1 lost a request packet of 23 KB
         * compressed (> 16 KB); the biggest request today, the bootstrap with the hooks v2 install, is 9 440 B.
         */
        const val DEFLATED_LIMIT = 12 * 1024
    }
}
