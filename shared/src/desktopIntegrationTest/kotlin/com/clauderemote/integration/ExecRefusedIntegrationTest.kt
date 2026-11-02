package com.clauderemote.integration

import com.clauderemote.session.service.ExecUnavailable
import com.clauderemote.session.service.ExecUnavailableReason
import com.clauderemote.session.service.PooledRemoteExec
import com.clauderemote.session.service.UngatedExecChannels
import com.jcraft.jsch.ChannelExec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A server that refuses the channel (`MaxSessions`) surfaces as the typed [ExecUnavailableReason.REFUSED]. */
class ExecRefusedIntegrationTest {
    private var fixture: SshdFixture? = null

    @After fun tearDown() { fixture?.close() }

    @Test fun maxSessionsRefusalIsTypedRefused() = runBlocking {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        val f = SshdFixture.start("MaxSessions 4").also { fixture = it }
        val s = f.openRawSession()
        val held = (1..4).map { (s.openChannel("exec") as ChannelExec).also { c -> c.setCommand("sleep 20"); c.connect(5000) } }
        try {
            val exec = PooledRemoteExec(UngatedExecChannels { s })
            val e = assertFailsWith<ExecUnavailable> { exec.run("srv", "true") }
            assertEquals(ExecUnavailableReason.REFUSED, e.reason, e.message)
            assertTrue(e.message!!.contains("channel is not opened"), e.message)
        } finally {
            held.forEach { it.disconnect() }; s.disconnect()
        }
    }
}
