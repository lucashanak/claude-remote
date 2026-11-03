package com.clauderemote.util

import com.clauderemote.session.TabManager
import com.clauderemote.session.service.CommLogFormat
import com.clauderemote.session.service.ConnectionRegistry
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommLogFormatTest {
    @Test fun dataTailIsAppendedKeyValueAtTheEnd() {
        assertEquals(" | attached=4 warm=0 parked=2 exec=1", CommLogFormat.dataTail(4, 0, 2, 1))
        val line = "data/60s: fg sessions=5(et=0) conn=2 | content term=1KB tr=0KB poll=0KB" + CommLogFormat.dataTail(1, 2, 3, 4)
        assertTrue(line.startsWith("data/60s: fg sessions=5(et=0) conn=2 | content term=1KB"))
        val kv = line.substringAfterLast(" | ").split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(mapOf("attached" to "1", "warm" to "2", "parked" to "3", "exec" to "4"), kv)
    }

    @Test fun attachLineFormat() {
        assertEquals(
            "Attach s1 kind=cold ms=950 phase=shell=300,prompt=100,decide=5,attach=45,firstByte=500",
            CommLogFormat.attach("s1", true, 950, mapOf("firstByte" to 500, "attach" to 45, "decide" to 5, "prompt" to 100, "shell" to 300)),
        )
        assertEquals("Attach s2 kind=warm ms=80 phase=attach=30,firstByte=50",
            CommLogFormat.attach("s2", false, 80, mapOf("attach" to 30, "firstByte" to 50)))
        assertEquals("Attach s3 kind=warm ms=1 phase=-", CommLogFormat.attach("s3", false, 1))
    }

    @Test fun attachPhaseValueIsKeyValueInsideTheValue() {
        val line = CommLogFormat.attach("s1", true, 950, mapOf("shell" to 120, "prompt" to 30))
        val phase = line.substringAfter(" phase=")
        assertEquals("shell=120,prompt=30", phase)
        assertEquals(mapOf("shell" to "120", "prompt" to "30"), phase.split(',').associate { it.substringBefore('=') to it.substringAfter('=') })
    }

    @Test fun data60sLineKeepsTheOldPrefixByteForByteAndEndsWithTheTail() {
        val registry = ConnectionRegistry(ServerStorage(FakeKeyValueStore()), TabManager())
        val prefix = "data/60s: fg sessions=5(et=0) conn=2 | content term=1KB tr=0KB poll=0KB | sysRx=3KB sysTx=4KB"
        val line = CommLogFormat.data60sLine(prefix, registry)
        assertTrue(line.startsWith(prefix), line)
        assertEquals(prefix + " | attached=0 warm=0 parked=0 exec=0", line) // empty registry: nothing attached, nothing else yet
        assertTrue(line.endsWith(" | attached=0 warm=0 parked=0 exec=0"))
    }

    @Test fun logShipperRemoteCapIs40MbAndKeepIs36Mb() {
        assertEquals(40L * 1024 * 1024, LogShipper.REMOTE_CAP_BYTES)
        assertEquals(36L * 1024 * 1024, LogShipper.REMOTE_KEEP_BYTES)
        // at the measured 3.75 MB/day the kept part covers >= 9 days (the metrics need 7)
        assertTrue(LogShipper.REMOTE_KEEP_BYTES / (3.75 * 1024 * 1024) >= 9.0)
    }

    @Test fun logShipperCommandUsesTheConstantsAndTrimsToALineBoundary() {
        val cmd = LogShipper.remoteAppendCommand("app-1")
        assertTrue("-gt ${LogShipper.REMOTE_CAP_BYTES} ]" in cmd, cmd)          // the cap decides
        assertTrue("tail -c ${LogShipper.REMOTE_KEEP_BYTES} " in cmd, cmd)      // the keep size is what remains
        assertTrue("tail -c ${LogShipper.REMOTE_CAP_BYTES}" !in cmd, cmd)
        assertTrue("tail -n +2" in cmd && "sed -i" !in cmd, "the cut must drop the first (partial) line portably: $cmd")
        assertTrue("/app-1.log" in cmd && cmd.endsWith("cat >> \"\$F\""), cmd)
        // the 10 MB / 5 MB numbers of the old command are gone
        assertTrue("10485760" !in cmd && "5242880" !in cmd, cmd)
    }
}
