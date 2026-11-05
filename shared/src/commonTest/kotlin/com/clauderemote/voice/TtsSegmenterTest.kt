package com.clauderemote.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TtsSegmenterTest {

    private fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    @Test
    fun `short text is one segment`() {
        assertEquals(listOf("Ahoj, jak se máš?"), ttsSegments("  Ahoj, jak se máš?  "))
        assertEquals(emptyList<String>(), ttsSegments(" \n\n  "))
    }

    @Test
    fun `every segment fits both Soniox limits and no word is lost`() {
        // Czech with diacritics: ~1.15 bytes per char, so the byte limit binds
        // before the char limit once maxChars is generous.
        val sentence = "Příliš žluťoučký kůň úpěl ďábelské ódy a šel dál po cestě. "
        val text = (1..400).joinToString("") { sentence } // ~24 k chars, one paragraph
        val segs = ttsSegments(text, maxChars = 5000, maxBytes = 4000)
        assertTrue(segs.size > 5)
        for (s in segs) {
            assertTrue(utf8Len(s) <= 4000, "segment of ${utf8Len(s)} bytes")
            assertTrue(s.length <= 5000)
        }
        assertEquals(words(text), segs.flatMap { words(it) })
    }

    @Test
    fun `long paragraph is cut at sentence ends`() {
        val text = (1..60).joinToString(" ") { "Věta číslo $it končí tečkou." }
        val segs = ttsSegments(text, maxChars = 200, mergeTarget = 0)
        assertTrue(segs.size > 1)
        for (s in segs) {
            assertTrue(s.length <= 200)
            assertTrue(s.endsWith("."), "cut mid-sentence: '$s'")
        }
        assertEquals(words(text), segs.flatMap { words(it) })
    }

    @Test
    fun `falls back to clause, space and finally a hard cut`() {
        val clauses = (1..40).joinToString(", ") { "klauzule $it" }
        ttsSegments(clauses, maxChars = 100, mergeTarget = 0).dropLast(1).forEach {
            assertTrue(it.endsWith(","), "expected a clause cut: '$it'")
        }
        val noSpace = "x".repeat(250)
        val hard = ttsSegments(noSpace, maxChars = 100, mergeTarget = 0)
        assertEquals(listOf(100, 100, 50), hard.map { it.length })
    }

    @Test
    fun `short paragraphs merge up to the target, long ones stay apart`() {
        val text = "Jedna.\n\nDvě.\n\nTři.\n\n" + "Dlouhý odstavec. ".repeat(30)
        val segs = ttsSegments(text, maxChars = 900, mergeTarget = 350)
        assertEquals("Jedna.\n\nDvě.\n\nTři.", segs.first())
        assertEquals(2, segs.size)
    }

    @Test
    fun `surrogate pairs are never split`() {
        val text = "😀".repeat(300) // 600 UTF-16 units, 1200 bytes, no spaces
        val segs = ttsSegments(text, maxChars = 101, maxBytes = 4000, mergeTarget = 0)
        for (s in segs) {
            assertTrue(!s.first().isLowSurrogate() && !s.last().isHighSurrogate())
        }
        assertEquals(text, segs.joinToString(""))
    }

    @Test
    fun `utf8 length counts Czech and emoji correctly`() {
        assertEquals(1, utf8Len("a"))
        assertEquals(2, utf8Len("ř"))
        assertEquals(3, utf8Len("…"))
        assertEquals(4, utf8Len("😀"))
    }
}
