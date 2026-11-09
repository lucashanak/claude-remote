package com.clauderemote.voice

import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Desktop Soniox read-aloud: the javax.sound sink over a fake line, and the Soniox → system-synth routing. */
class DesktopSonioxTtsTest {

    @AfterTest
    fun tearDown() = DesktopReadAloud.stop()

    /** A SourceDataLine that records calls; write() accepts everything. */
    private class LineRecorder {
        val calls = CopyOnWriteArrayList<String>()
        var format: AudioFormat? = null
        val line: SourceDataLine = Proxy.newProxyInstance(
            SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java),
        ) { _, m, args ->
            calls += m.name
            when (m.name) {
                "write" -> args!![2] as Int
                "getBufferSize" -> 12000 // what the mixer granted, not what was asked
                "toString" -> "LineRecorder"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as SourceDataLine
    }

    @Test
    fun sinkOpensPcm16MonoAndMapsTransport() {
        val rec = LineRecorder()
        var bufferAsked = 0
        val sink = JavaSoundSink { fmt, buf -> rec.format = fmt; bufferAsked = buf; rec.line }
        assertEquals(12000, sink.open(8000), "the drain tail follows the real buffer")
        val f = rec.format!!
        assertEquals(8000f, f.sampleRate)
        assertEquals(16, f.sampleSizeInBits)
        assertEquals(1, f.channels)
        assertTrue(!f.isBigEndian)
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, f.encoding)
        assertEquals(8000, bufferAsked, "0.5 s of buffer")

        // Not started yet = paused: a write must not block in the line.
        assertEquals(0, sink.write(ByteArray(100), 0, 100))
        assertTrue("write" !in rec.calls)
        sink.play()
        assertEquals(100, sink.write(ByteArray(100), 0, 100))
        assertTrue(rec.calls.containsAll(listOf("start", "write")))

        sink.pause()
        assertEquals("stop", rec.calls.last())
        assertEquals(0, sink.write(ByteArray(100), 0, 100))
        sink.flush()
        assertEquals("flush", rec.calls.last())
        sink.halt()
        assertTrue(rec.calls.takeLast(2) == listOf("stop", "flush"))
        sink.release()
        assertEquals("close", rec.calls.last())
        assertEquals(-1, sink.write(ByteArray(2), 0, 2), "released")
    }

    @Test
    fun noKeyUsesTheSystemSynthesiser() {
        val spoken = AtomicReference<String?>(null)
        val r = DesktopReadAloud.speakBlocking(
            "Ahoj světe.", 100, apiKey = "", voice = "Adrian",
            sockets = { error("must not connect") },
            sink = { FakeSink() },
            systemSpeak = { t, _, _ -> spoken.set(t); null },
        )
        assertNull(r)
        assertEquals("Ahoj světe.", spoken.get())
    }

    @Test
    fun sonioxReadsAndSystemSynthStaysSilent() {
        val server = FakeServer()
        val sink = FakeSink()
        val spoken = AtomicReference<String?>(null)
        val result = AtomicReference<String?>("pending")
        val done = CountDownLatch(1)
        thread {
            result.set(
                DesktopReadAloud.speakBlocking(
                    "Jedna věta.", 100, apiKey = "k", voice = "Adrian",
                    sockets = server, sink = { sink },
                    systemSpeak = { t, _, _ -> spoken.set(t); null },
                ),
            )
            done.countDown()
        }
        val sock = server.awaitSocket(0)
        sock.open()
        val id = sock.awaitStream(0)
        assertTrue(!sock.configOf(id).has("speed"), "100 % sends no speed")
        sock.audio(id, ByteArray(400) { 0x21 })
        sock.end(id)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(result.get())
        assertNull(spoken.get())
        assertTrue(sink.nonSilentPcm().isNotEmpty())
    }

    @Test
    fun refusedKeyFallsBackToTheSystemSynthesiserForTheWholeText() {
        val server = FakeServer()
        val spoken = AtomicReference<String?>(null)
        val rate = AtomicReference<Int?>(null)
        val done = CountDownLatch(1)
        thread {
            DesktopReadAloud.speakBlocking(
                "První odstavec.\n\nDruhý odstavec.", 120, apiKey = "bad", voice = "Adrian",
                sockets = server, sink = { FakeSink() },
                systemSpeak = { t, r, _ -> rate.set(r); spoken.set(t); null },
            )
            done.countDown()
        }
        server.awaitSocket(0).fail(401)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("První odstavec.\n\nDruhý odstavec.", spoken.get())
        assertEquals(120, rate.get())
    }

    @Test
    fun handOverKeepsTheClampedRateAndStaysSpeakingThroughTheGap() {
        val server = FakeServer()
        val rate = AtomicReference<Int?>(null)
        val speakingInGap = AtomicReference<Boolean?>(null)
        val done = CountDownLatch(1)
        thread {
            DesktopReadAloud.speakBlocking(
                "Text.", 250, apiKey = "k", voice = "Adrian",
                sockets = server, sink = { FakeSink() },
                systemSpeak = { _, r, _ -> rate.set(r); null },
                beforeHandOver = { speakingInGap.set(DesktopReadAloud.isSpeaking()) },
            )
            done.countDown()
        }
        server.awaitSocket(0).fail(401)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(130, rate.get(), "same pace as Soniox read, not a jump to 250 %")
        assertEquals(true, speakingInGap.get(), "Soniox ended, say not started yet: still speaking")
        assertTrue(!DesktopReadAloud.isSpeaking())
    }

    @Test
    fun stopAfterSonioxFailedButBeforeTheHandOverSaysNothing() {
        val server = FakeServer()
        val spoken = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        thread {
            DesktopReadAloud.speakBlocking(
                "Text.", 100, apiKey = "bad", voice = "Adrian",
                sockets = server, sink = { FakeSink() },
                systemSpeak = { t, _, _ -> spoken.set(t); null },
                beforeHandOver = { DesktopReadAloud.stop() },
            )
            done.countDown()
        }
        server.awaitSocket(0).fail(401)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(spoken.get())
    }

    @Test
    fun stopDuringTheHandOverReachesTheSystemSynthesiser() {
        val server = FakeServer()
        val sawCancel = AtomicReference<Boolean?>(null)
        val done = CountDownLatch(1)
        thread {
            DesktopReadAloud.speakBlocking(
                "Text.", 100, apiKey = "bad", voice = "Adrian",
                sockets = server, sink = { FakeSink() },
                // A stop landing while say is being spawned: the synthesiser
                // must be able to see it through `cancelled`.
                systemSpeak = { _, _, cancelled ->
                    val before = cancelled()
                    DesktopReadAloud.stop()
                    sawCancel.set(!before && cancelled()); null
                },
            )
            done.countDown()
        }
        server.awaitSocket(0).fail(401)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(true, sawCancel.get())
    }

    @Test
    fun aKeyOkHttpCannotSendFallsBackInsteadOfThrowing() {
        val spoken = AtomicReference<String?>(null)
        val r = DesktopReadAloud.speakBlocking(
            "Text.", 100, apiKey = "k\u200b", voice = "Adrian",
            sink = { FakeSink() },
            systemSpeak = { t, _, _ -> spoken.set(t); null },
        )
        assertNull(r)
        assertEquals("Text.", spoken.get())
    }

    @Test
    fun rateAboveSonioxRangeIsClamped() {
        val server = FakeServer()
        thread {
            DesktopReadAloud.speakBlocking(
                "Text.", 250, apiKey = "k", voice = "Adrian",
                sockets = server, sink = { FakeSink() }, systemSpeak = { _, _, _ -> null },
            )
        }
        val sock = server.awaitSocket(0)
        sock.open()
        assertEquals(1.3, sock.configOf(sock.awaitStream(0)).getDouble("speed"))
    }

    @Test
    fun stopDuringSonioxDoesNotStartTheFallback() {
        val server = FakeServer()
        val spoken = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        thread {
            DesktopReadAloud.speakBlocking(
                "Text.", 100, apiKey = "k", voice = "Adrian",
                sockets = server, sink = { FakeSink() },
                systemSpeak = { t, _, _ -> spoken.set(t); null },
                fallbackAfterMs = 60_000,
            )
            done.countDown()
        }
        server.awaitSocket(0)
        DesktopReadAloud.stop()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(spoken.get())
        assertTrue(!DesktopReadAloud.isSpeaking())
    }

    @Test
    fun noAudioDeviceFallsBack() {
        val server = FakeServer()
        val spoken = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        thread {
            DesktopReadAloud.speakBlocking(
                "Text.", 100, apiKey = "k", voice = "Adrian",
                sockets = server, sink = { FakeSink().apply { failOpen = true } },
                systemSpeak = { t, _, _ -> spoken.set(t); null },
            )
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("Text.", spoken.get())
    }
}
