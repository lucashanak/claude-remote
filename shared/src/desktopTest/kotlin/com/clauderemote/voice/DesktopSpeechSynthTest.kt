package com.clauderemote.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Everything the read-aloud button decides BEFORE it touches the OS: which
 * synthesiser to run, how the rate maps onto each one's scale, and how the
 * text reaches it. Nothing here spawns a process or needs a synthesiser
 * installed.
 */
class DesktopSpeechSynthTest {

    private fun installed(vararg programs: String): (String) -> Boolean =
        { it in programs.toSet() }

    @Test
    fun `mac prefers say, others prefer spd-say`() {
        assertTrue(isMacOs("Mac OS X"))
        assertTrue(isMacOs("macOS"))
        assertTrue(!isMacOs("Linux"))
        assertEquals("say", speechCandidates("Mac OS X").first())
        assertEquals("spd-say", speechCandidates("Linux").first())
        // espeak is the shared fallback on both.
        assertTrue(speechCandidates("Mac OS X").containsAll(listOf("espeak-ng", "espeak")))
        assertTrue(speechCandidates("Linux").containsAll(listOf("espeak-ng", "espeak")))
    }

    @Test
    fun `falls back down the candidate list and gives up cleanly`() {
        val onlyEspeak = resolveSpeechCommand("Linux", 100, "hi", installed("espeak"))
        assertEquals("espeak", onlyEspeak?.program)
        val ngWins = resolveSpeechCommand("Linux", 100, "hi", installed("espeak", "espeak-ng"))
        assertEquals("espeak-ng", ngWins?.program)
        // No synthesiser at all is a null, never an exception.
        assertNull(resolveSpeechCommand("Linux", 100, "hi", installed()))
    }

    @Test
    fun `say and espeak take the text on stdin, spd-say on argv after a dash-dash`() {
        val say = resolveSpeechCommand("Mac OS X", 100, "hello", installed("say"))!!
        assertTrue(say.textOnStdin)
        assertContentEquals(listOf("say", "-r", "175"), say.argv())

        val espeak = resolveSpeechCommand("Linux", 100, "hello", installed("espeak-ng"))!!
        assertTrue(espeak.textOnStdin)
        assertContentEquals(listOf("espeak-ng", "-s", "175", "--stdin"), espeak.argv())

        // -w is what keeps the process alive for the whole utterance, and the
        // `--` is what stops a message starting with "-" being read as options.
        val spd = resolveSpeechCommand("Linux", 100, "-h is not a flag here", installed("spd-say"))!!
        assertTrue(!spd.textOnStdin)
        assertContentEquals(
            listOf("spd-say", "-w", "-r", "0", "--", "-h is not a flag here"),
            spd.argv(),
        )
    }

    @Test
    fun `words per minute scales and clamps to the settings range`() {
        assertEquals(175, wordsPerMinute(100))
        assertEquals(87, wordsPerMinute(50))
        assertEquals(350, wordsPerMinute(200))
        assertEquals(43, wordsPerMinute(0))    // clamped to 25 %
        assertEquals(700, wordsPerMinute(900)) // clamped to 400 %
    }

    @Test
    fun `spd-say rate maps percent onto its relative scale`() {
        assertEquals(0, spdSayRate(100))
        assertEquals(-100, spdSayRate(25))
        assertEquals(100, spdSayRate(400))
        assertEquals(-100, spdSayRate(1))
        assertEquals(100, spdSayRate(1000))
        // Monotonic across the whole range, and inside the documented bounds.
        var previous = -101
        for (pct in 25..400) {
            val r = spdSayRate(pct)
            assertTrue(r in -100..100, "rate $r out of range at $pct%")
            assertTrue(r >= previous, "rate went backwards at $pct%")
            previous = r
        }
    }

    @Test
    fun `path lookup splits on the platform separator and ignores blanks`() {
        val sep = File.pathSeparatorChar
        val path = "/usr/bin$sep$sep/opt/tools/bin"
        val exists = { p: String -> p == "/opt/tools/bin${File.separatorChar}say" }
        assertTrue(isOnPath("say", path, exists))
        assertTrue(!isOnPath("spd-say", path, exists))
        assertTrue(!isOnPath("say", null, exists))
        assertTrue(!isOnPath("say", "", exists))
    }
}
