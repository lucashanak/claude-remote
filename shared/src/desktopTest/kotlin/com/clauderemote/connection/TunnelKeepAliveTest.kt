package com.clauderemote.connection

import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kolo 3 (adv N1): the Cloudflare keepalive declares a tunnel dead by the keepalives it SENT without an answer, never by
 * the idle time against the interval of the moment. `onResume` shortens the interval 60 s -> 10 s; an idle healthy
 * tunnel 31-59 s after its last byte (its next background keepalive not yet due) was closed at once, without a single
 * keepalive (probe U1/P1). The clock is driven by hand: one tick every 250 ms of the test's time, like the ticker.
 */
class TunnelKeepAliveTest {
    private class Rig(intervalMs: Int, count: Int = 2) {
        val input = KeepAliveInput(PipedInputStream(PipedOutputStream()))
        val dead = mutableListOf<String>()
        val ka = TunnelKeepAlive(input, 10_000) { dead += it }
        // an unconnected JSch session: its sendKeepAliveMsg throws, which the keepalive ignores (as on a dying link)
        val session: com.jcraft.jsch.Session = com.jcraft.jsch.JSch().getSession("u", "127.0.0.1", 1).apply {
            setServerAliveInterval(intervalMs); setServerAliveCountMax(count)
        }
        val t0: Long
        var now: Long

        init {
            ka.arm(session)
            t0 = input.lastByteNanos
            now = t0
        }

        /** Ticks every 250 ms up to [atMs] after the arm (the ticker's pace). */
        fun runTo(atMs: Long) {
            val end = t0 + atMs * 1_000_000
            while (now + 250_000_000 <= end) { now += 250_000_000; ka.tick(now) }
        }

        /** The ticker stood still (a frozen process) and runs once at [atMs]. */
        fun jumpTo(atMs: Long) { now = t0 + atMs * 1_000_000; ka.tick(now) }

        /** A byte arrived now (the keepalive's answer). */
        fun answer() {
            KeepAliveInput::class.java.getDeclaredField("lastByteNanos").apply { isAccessible = true }.setLong(input, now)
        }

        fun sent(): Int = TunnelKeepAlive::class.java.getDeclaredField("sentSinceByte").apply { isAccessible = true }.getInt(ka)

        fun elapsedMs() = (now - t0) / 1_000_000
    }

    @Test fun theReturnFromTheBackgroundDoesNotCloseAnIdleHealthyTunnel() {
        for (idleS in listOf(31L, 45L, 59L)) {
            val r = Rig(intervalMs = 60_000)
            r.runTo(idleS * 1_000)                              // background, idle: no keepalive due yet
            assertTrue(r.dead.isEmpty(), "background, ${idleS}s idle: ${r.dead}")
            r.session.setServerAliveInterval(10_000)            // onResume: setBackgroundMode(false)
            r.runTo(idleS * 1_000 + 250)
            assertTrue(r.dead.isEmpty(), "back from the background after ${idleS}s idle the tunnel lives: ${r.dead}")
            assertEquals(1, r.sent(), "a keepalive went out instead")
            r.answer()
            r.runTo(idleS * 1_000 + 25_000)                     // answered: a black hole from here is not yet 3 intervals old
            assertTrue(r.dead.isEmpty(), "${r.dead}")
        }
    }

    @Test fun aBlackHoleAfterTheReturnIsDeadAfterCountKeepalivesAndOneMoreInterval() {
        val r = Rig(intervalMs = 60_000)
        r.runTo(45_000)
        r.session.setServerAliveInterval(10_000)
        val switchedMs = r.elapsedMs()
        var deadAt = -1L
        while (r.elapsedMs() < switchedMs + 40_000 && deadAt < 0) { r.runTo(r.elapsedMs() + 250); if (r.dead.isNotEmpty()) deadAt = r.elapsedMs() }
        assertTrue(deadAt >= 0, "a black hole is still found")
        val took = deadAt - switchedMs
        assertTrue(took in 20_000..30_000 + 750, "keepalives at +0 and +10 s, dead at +20 s (well within (count+1) x interval): $took ms")
        assertTrue("2 keepalive(s) unanswered" in r.dead.single(), r.dead.single())
    }

    @Test fun aBlackHoleAtTheForegroundIntervalIsDeadAfterThreeIntervalsNotBefore() {
        val r = Rig(intervalMs = 10_000)
        r.runTo(29_750)
        assertTrue(r.dead.isEmpty(), "not before 3 intervals: ${r.dead}")
        assertEquals(2, r.sent())
        r.runTo(30_750)
        assertEquals(1, r.dead.size, "dead by interval x (count + 1) plus a tick or so")
    }

    @Test fun aFrozenProcessWakesWithAKeepaliveNotADeath() {
        val r = Rig(intervalMs = 10_000)
        r.jumpTo(120_000)                                       // no tick ran for 2 minutes, the clock did
        assertTrue(r.dead.isEmpty(), "no false death after a freeze: ${r.dead}")
        assertEquals(1, r.sent())
        r.answer()
        r.runTo(125_000)
        assertTrue(r.dead.isEmpty())
    }

    @Test fun anAnswerStartsTheCountOver() {
        val r = Rig(intervalMs = 10_000)
        r.runTo(20_250)
        assertEquals(2, r.sent())
        r.answer()
        r.runTo(20_500)
        assertEquals(0, r.sent(), "the byte resets the count")
        r.runTo(49_000)
        assertTrue(r.dead.isEmpty(), "three intervals from the answer, not from the first keepalive: ${r.dead}")
    }
}
