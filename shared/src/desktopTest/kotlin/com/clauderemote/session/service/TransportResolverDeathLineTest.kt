package com.clauderemote.session.service

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.ServerTransport
import com.clauderemote.model.SshServer
import com.clauderemote.session.TabManager
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Review B-2 T4 + V13 (dávka B, kolo 3): metric M7 reads the line `maybeCountTsEarlyDeath` writes with `TS_DEATH_RE` of
 * `tools/relay-metrics/extract_metrics.py`. Nothing else ties the two together: if the line lost its ` on <server>` or its
 * ` transport=<id>`, M7 would silently go back to merging deaths by time. The regex is read from the extractor itself.
 */
class TransportResolverDeathLineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lines = CopyOnWriteArrayList<String>()
    private val saved = FileLogger.platformLog

    @BeforeTest fun setUp() {
        FileLogger.platformLog = { _, _, message, _ -> lines += message }
    }

    @AfterTest fun tearDown() {
        FileLogger.platformLog = saved
        scope.cancel()
    }

    /** `TS_DEATH_RE` as the extractor compiles it (a raw string; its syntax is the subset Java and Python share). */
    private fun tsDeathRe(): Regex {
        val py = File("../tools/relay-metrics/extract_metrics.py")
        val src = py.readText()
        val literal = Regex("""^TS_DEATH_RE = re\.compile\(r"(.*)"\)$""", RegexOption.MULTILINE).find(src)
        assertNotNull(literal, "TS_DEATH_RE in ${py.absolutePath}")
        return Regex(literal.groupValues[1])
    }

    @Test fun theDeathLineOfATabIsTheOneTheMetricsExtractorReads() {
        val r = TransportResolver(scope, ConnectionRegistry(ServerStorage(FakeKeyValueStore()), TabManager()))
        val server = SshServer(
            id = "s", name = "my box", host = "ssh.example.org", username = "u",
            useCloudflareProxy = true, tailscaleHost = "100.64.0.7", transport = ServerTransport.TAILSCALE,
        )
        val tab = ClaudeSession(
            id = "t1", server = server, folder = "~/x", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
            tmuxSessionName = "claude-x--t1", connectionType = ConnectionType.SSH,
        )
        val transport = Any()
        r.recordConnectSuccess(tab.id, server, server.forTransport(ServerTransport.TAILSCALE))
        r.maybeCountTsEarlyDeath(tab, transport)
        val line = lines.singleOrNull { it.startsWith("Tailscale transport died") }
        assertNotNull(line, "the death line: $lines")
        val m = tsDeathRe().find(line)
        assertNotNull(m, "TS_DEATH_RE matches \"$line\"")
        assertEquals("my box", m.groupValues[2], "the server, from \"$line\"")
        assertEquals(Integer.toHexString(System.identityHashCode(transport)), m.groupValues[3], "the transport's identity, from \"$line\"")
    }

    /**
     * Review B-3 kod L4 (dávka B, kolo 4): "died young" was the TAB's age. A tab that joins a transport that has been up for
     * hours and dies with it 30 s later counted as an early death of a TS transport. The line now also says how old the
     * transport is (from the first tab that connected on it), and M7 reads that (`TS_DEATH_RE` group 4).
     */
    @Test fun theDeathLineSaysHowOldTheTransportIsNotJustTheTab() {
        val r = TransportResolver(scope, ConnectionRegistry(ServerStorage(FakeKeyValueStore()), TabManager()))
        val server = SshServer(
            id = "s", name = "my box", host = "ssh.example.org", username = "u",
            useCloudflareProxy = true, tailscaleHost = "100.64.0.7", transport = ServerTransport.TAILSCALE,
        )
        fun tab(id: String) = ClaudeSession(
            id = id, server = server, folder = "~/x", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
            tmuxSessionName = "claude-x--$id", connectionType = ConnectionType.SSH,
        )
        val transport = Any()
        val eff = server.forTransport(ServerTransport.TAILSCALE)
        r.recordConnectSuccess("t1", server, eff, transport)   // the transport's first tab: it is new
        Thread.sleep(400)
        r.recordConnectSuccess("t2", server, eff, transport)   // a later tab joins it
        r.maybeCountTsEarlyDeath(tab("t2"), transport)
        val line = lines.singleOrNull { it.startsWith("Tailscale transport died") }
        assertNotNull(line, "the death line: $lines")
        val m = tsDeathRe().find(line)
        assertNotNull(m, "TS_DEATH_RE matches \"$line\"")
        val tabMs = m.groupValues[1].toLong()
        val transportAge = m.groupValues[4].toLongOrNull()
        assertNotNull(transportAge, "the transport's age, from \"$line\"")
        assertTrue(transportAge >= 400 && transportAge >= tabMs + 300, "the transport is older than the tab: \"$line\"")
    }
}
