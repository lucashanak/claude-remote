package com.clauderemote.session

import com.clauderemote.connection.TmuxManager
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.BootstrapScript
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecResult
import com.clauderemote.session.service.HookCommands
import com.clauderemote.session.service.LEGACY_STOP_HOOK_COMMAND
import com.clauderemote.session.service.ManifestCommands
import com.clauderemote.session.service.Pace
import com.clauderemote.session.service.RemoteExec
import com.clauderemote.session.service.ServerBootstrap
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

    private class CapturingExec : RemoteExec {
        val commands = CopyOnWriteArrayList<String>()
        override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace): ExecResult {
            commands += cmd
            return ExecResult("RESTORE_SCRIPT_PRESENT", 0)
        }
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
        return out
    }

    @Test fun noExecRequestIsAboveFourKilobytes() {
        val sizes = sent().mapValues { it.value.toByteArray(Charsets.UTF_8).size }
        sizes.forEach { (what, n) -> println("exec request %6d B  %s".format(n, what)) }
        val over = sizes.filter { (what, n) -> n > (KNOWN_OVER[what] ?: LIMIT) }
        if (over.isNotEmpty()) fail("exec requests above ${LIMIT} B (send the payload on stdin, paced): $over")
        val stale = KNOWN_OVER.keys - sizes.keys
        assertTrue(stale.isEmpty(), "known exceptions no longer rendered here: $stale")
    }

    private companion object {
        const val LIMIT = 4096
        /** Size at 18faa58 (bytes) -> ceiling. The reconcile grows ~65 B per session (5 019 B at 58). */
        val KNOWN_OVER = mapOf(
            "reconcile, 58 sessions (reconcileAll)" to 5 * 1024, // 5 019
            "hooks v2 status" to 13 * 1024, // 12 469
            "hooks v2 remove" to 13 * 1024, // 12 469
            "bootstrap, hooks v2 install" to 38 * 1024, // 38 886
            "bootstrap, hooks v2 status" to 21 * 1024, // 20 777
            "streamd install" to 5 * 1024, // 5 025
        )
    }
}
