package com.clauderemote.voice

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure parts of desktop dictation: the μ-law conversion that every captured
 * frame goes through, the session config Soniox parses, and the caret splice
 * that puts a phrase where the cursor was. No microphone, no socket.
 */
class DesktopDictationTest {

    private fun mulaw(sample: Int): Int = linearToMulaw(sample).toInt() and 0xFF

    @Test
    fun `mulaw matches the G711 reference vectors`() {
        assertEquals(0xFF, mulaw(0))
        assertEquals(0x80, mulaw(32767))
        assertEquals(0x00, mulaw(-32768))
        // Symmetric about zero: the sign bit is the only difference.
        assertEquals(mulaw(1000) and 0x7F, mulaw(-1000) and 0x7F)
        assertTrue(mulaw(1000) and 0x80 != 0)
        assertTrue(mulaw(-1000) and 0x80 == 0)
    }

    @Test
    fun `pcm frames halve in size and are read little-endian`() {
        // 0x0000 = silence, 0x8000 LE = -32768 (full negative scale).
        val pcm = byteArrayOf(0x00, 0x00, 0x00, 0x80.toByte())
        val out = pcm16leToMulaw(pcm, pcm.size)
        assertEquals(2, out.size)
        assertEquals(0xFF, out[0].toInt() and 0xFF)
        assertEquals(0x00, out[1].toInt() and 0xFF)
        // A trailing odd byte is a partial sample and is dropped, not read
        // past the end of the frame.
        assertEquals(1, pcm16leToMulaw(byteArrayOf(0x00, 0x00, 0x11), 3).size)
        // Only the `len` bytes the line actually delivered are converted.
        assertEquals(1, pcm16leToMulaw(ByteArray(64), 2).size)
    }

    @Test
    fun `soniox config states the raw format explicitly`() {
        val cfg = JSONObject(sonioxDictationConfig("key-123"))
        assertEquals("key-123", cfg.getString("api_key"))
        // mulaw is headerless, so rate and channels cannot be inferred.
        assertEquals("mulaw", cfg.getString("audio_format"))
        assertEquals(16000, cfg.getInt("sample_rate"))
        assertEquals(1, cfg.getInt("num_channels"))
        assertTrue(cfg.getBoolean("enable_endpoint_detection"))
        val hints = cfg.getJSONArray("language_hints")
        assertEquals(listOf("cs", "en"), (0 until hints.length()).map { hints.getString(it) })
    }

    @Test
    fun `splice inserts at the caret and leaves it after the phrase`() {
        val mid = spliceDictation(before = "run ", after = " now", phrase = "the build")
        assertEquals("run the build now", mid.text)
        assertEquals("run the build".length, mid.caret)

        // A word boundary is added only when one is missing.
        assertEquals("run the build", spliceDictation("run", "", "the build").text)
        assertEquals("run\nthe build", spliceDictation("run\n", "", "the build").text)

        // Empty edges: nothing is prefixed, nothing is appended.
        assertEquals("the build", spliceDictation("", "", "the build").text)
        assertEquals("run ", spliceDictation("run ", "", "").text)
        assertEquals(4, spliceDictation("run ", "", "").caret)
    }
}
