package com.clauderemote.voice

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The read-aloud process lifecycle, driven with ordinary commands (`cat`,
 * `sleep`, `sh`) instead of a synthesiser — no audio device is involved.
 *
 * What matters here is that speakBlocking ALWAYS returns: a stuck call is a
 * button stuck in its "speaking" state, which sends the next tap into the stop
 * branch and makes read-aloud look dead until it is tapped an even number of
 * times.
 */
class DesktopSpeechProcessTest {

    private fun fixed(cmd: SpeechCommand?): (Int, String) -> SpeechCommand? = { _, _ -> cmd }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test
    fun `a clean run reports no error and leaves nothing speaking`() {
        // `cat` consumes the text on stdin and exits 0 — the same shape as
        // `say` / `espeak --stdin`.
        val error = DesktopSpeech.speakBlocking(
            "hello there",
            100,
            fixed(SpeechCommand("cat", emptyList(), textOnStdin = true)),
        )
        assertNull(error)
        assertTrue(!DesktopSpeech.isSpeaking())
    }

    @Test
    fun `a synthesiser that fails is reported, not swallowed and not hung`() {
        val error = DesktopSpeech.speakBlocking(
            "hello",
            100,
            fixed(SpeechCommand("sh", listOf("-c", "exit 3"), textOnStdin = false)),
        )
        assertNotNull(error)
        assertTrue(error.contains("3"), "expected the exit code in: $error")
        assertTrue(!DesktopSpeech.isSpeaking())
    }

    @Test
    fun `a program that cannot be spawned returns a message instead of throwing`() {
        val error = DesktopSpeech.speakBlocking(
            "hello",
            100,
            fixed(SpeechCommand("cr-no-such-synthesiser", emptyList(), textOnStdin = true)),
        )
        assertNotNull(error)
        assertTrue(!DesktopSpeech.isSpeaking())
    }

    @Test
    fun `no synthesiser installed is not an exception`() {
        val error = DesktopSpeech.speakBlocking("hello", 100, fixed(null))
        assertNotNull(error)
        assertTrue(!DesktopSpeech.isSpeaking())
    }

    @Test
    fun `stop ends the call promptly and without an error`() {
        val result = AtomicReference<String?>("not finished")
        val worker = Thread {
            result.set(
                DesktopSpeech.speakBlocking(
                    "hello",
                    100,
                    fixed(SpeechCommand("sleep", listOf("30"), textOnStdin = false)),
                )
            )
        }
        worker.isDaemon = true
        worker.start()
        assertTrue(waitUntil(5_000) { DesktopSpeech.isSpeaking() }, "process never started")

        DesktopSpeech.stop()
        worker.join(5_000)
        assertTrue(!worker.isAlive, "speakBlocking did not return after stop")
        // A user-requested stop is not a failure: the button just resets.
        assertNull(result.get())
        assertTrue(!DesktopSpeech.isSpeaking())
    }

    @Test
    fun `a stop that lands while the process is starting still wins`() {
        // The resolver runs inside the spawn window, so stopping from it is the
        // race: the process does not exist yet, so there is nothing to kill.
        val startedAt = System.currentTimeMillis()
        val error = DesktopSpeech.speakBlocking("hello", 100) { _, _ ->
            DesktopSpeech.stop()
            SpeechCommand("sleep", listOf("30"), textOnStdin = false)
        }
        assertTrue(
            System.currentTimeMillis() - startedAt < 10_000,
            "the stop was lost — the utterance ran to completion",
        )
        assertNull(error)
        assertTrue(!DesktopSpeech.isSpeaking())
    }

    @Test
    fun `starting a second utterance kills the first`() {
        val first = AtomicReference<String?>("not finished")
        val worker = Thread {
            first.set(
                DesktopSpeech.speakBlocking(
                    "long one",
                    100,
                    fixed(SpeechCommand("sleep", listOf("30"), textOnStdin = false)),
                )
            )
        }
        worker.isDaemon = true
        worker.start()
        assertTrue(waitUntil(5_000) { DesktopSpeech.isSpeaking() }, "process never started")

        // Tapping a different speaker button: one playback at a time, app-wide.
        val second = DesktopSpeech.speakBlocking(
            "short one",
            100,
            fixed(SpeechCommand("cat", emptyList(), textOnStdin = true)),
        )
        assertNull(second)
        worker.join(5_000)
        assertTrue(!worker.isAlive, "the superseded utterance did not return")
        // Superseded, not failed — the losing button must reset quietly.
        assertNull(first.get())
    }

    @Test
    fun `the text really reaches the process stdin`() {
        // Round-trips the payload through a child process so a broken stdin
        // write (the whole delivery mechanism for say/espeak) can't pass.
        val marker = "spoken-payload-42"
        val error = DesktopSpeech.speakBlocking(
            marker,
            100,
            fixed(SpeechCommand("grep", listOf("-q", marker), textOnStdin = true)),
        )
        assertNull(error, "grep did not find the text on stdin")
        // And the opposite: grep exits 1 when the text never arrives.
        val missing = DesktopSpeech.speakBlocking(
            "something else",
            100,
            fixed(SpeechCommand("grep", listOf("-q", marker), textOnStdin = true)),
        )
        assertEquals("grep exited with 1", missing)
    }
}
