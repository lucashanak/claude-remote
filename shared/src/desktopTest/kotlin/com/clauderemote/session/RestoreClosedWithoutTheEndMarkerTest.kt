package com.clauderemote.session

import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.ClosedRecord
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecChannels
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.RestoreResult
import com.clauderemote.session.service.StdinScript
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D3 (kritika D2 N9, review D2 test N-T1): a restore whose answer came back without the launcher's end marker
 * (`__CR_END__<status>`) may have run or not. `restoreClosed` says [RestoreResult.Unknown] ("it may have started, look
 * before you try again"), never [RestoreResult.Failed] ("it failed", an invitation to restore a second time) and never
 * a result read from the cut-off answer. Through the production `restoreClosed`, its exec channel on a session of its
 * own answering what the test gives it.
 */
class RestoreClosedWithoutTheEndMarkerTest {
    private val tabs = TabManager()
    private val storage = ServerStorage(FakeKeyValueStore())
    private val server = SshServer(id = "srv-1", name = "server", host = "127.0.0.1", port = 1, username = "u", password = "p")
    private val record = ClosedRecord(
        1, "2026-11-06T17:20:03Z", "claude-server-work", "11111111-2222-3333-4444-555555555555", null, null,
        "/home/u/work", "/home/u/work", null, "SONNET", "AUTO", emptyList(), null, null, null, null, "app:test", true, null,
    )

    @BeforeTest fun setUp() { CommFlags.resetForTest(); storage.addServer(server) }
    @AfterTest fun tearDown() {
        SshSessionHelper.execChannelsProvider = null
        CommFlags.resetForTest()
    }

    /** An exec channel that answers [stdout] and swallows its stdin. */
    private class AnsweringChannel(private val stdout: String) : com.jcraft.jsch.ChannelExec() {
        override fun setCommand(command: String) {}
        override fun connect(connectTimeout: Int) {}
        override fun getOutputStream(): java.io.OutputStream = java.io.OutputStream.nullOutputStream()
        override fun getInputStream(): java.io.InputStream = java.io.ByteArrayInputStream(stdout.toByteArray())
        override fun setInputStream(`in`: java.io.InputStream?) {}
        override fun getExitStatus(): Int = 0
        override fun isClosed(): Boolean = false
        override fun isEOF(): Boolean = false
        override fun disconnect() {}
    }

    private fun restore(stdout: String): RestoreResult {
        val o = SessionOrchestrator(storage, tabs, SessionStorage(FakeKeyValueStore()))
        try {
            val session = com.jcraft.jsch.FakeSession(channels = { AnsweringChannel(stdout) })
            SshSessionHelper.execChannelsProvider = { _ ->
                object : ExecChannels {
                    override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Channel) -> T): T = error("unused")
                    override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Session) -> T): T = block(session)
                }
            }
            return runBlocking { withTimeout(20_000) { o.restoreClosed(server, record).first } }
        } finally {
            o.shutdownForTest()
        }
    }

    @Test fun aRestoreWhoseAnswerLacksTheEndMarkerIsUnknownNeverFailed() {
        for (cut in listOf(
            "CR_RESTORE FAILED tmux\n",                         // a result line, but the marker never came
            "CR_RESTORE RESTORED 4242 0f6c2a4e-1b2c-4d5e-8f90-123456789abc\n",
            "CR_RESTORE EXISTS -\n\n__CR_END_",                  // cut inside the marker
            "",                                                  // nothing at all
        )) {
            assertEquals(RestoreResult.Unknown, restore(cut), "answer '$cut'")
        }
    }

    /** The control: the same answers WITH the marker are read. */
    @Test fun theSameAnswerWithTheEndMarkerIsRead() {
        assertEquals(RestoreResult.Failed("tmux"), restore("CR_RESTORE FAILED tmux\n\n${StdinScript.MARK}0\n"))
        assertEquals(RestoreResult.Exists(null), restore("CR_RESTORE EXISTS -\n\n${StdinScript.MARK}0\n"))
    }
}
