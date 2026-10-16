package com.clauderemote.ui

import com.clauderemote.ui.InlineImages.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An image the app will not fetch must still READ. No `imageTransformer` is
 * wired into the markdown renderer, so an `![alt](target)` left in the source
 * draws absolutely nothing — no alt text, no link, a silent hole where Claude
 * put a picture. Those targets are therefore rewritten as text.
 *
 * Which targets stay out of the fetch path is [InlineImagesTest]'s subject;
 * this file is only about what the user sees instead.
 */
class InlineImagesUnshowableTest {

    private fun texts(md: String) = InlineImages.split(md).filterIsInstance<Segment.Text>().map { it.markdown }
    private fun images(md: String) = InlineImages.split(md).filterIsInstance<Segment.Image>()

    @Test
    fun aRemoteImageOnItsOwnLineBecomesReadableText() {
        val md = "![the chart](https://example.test/chart.png)"
        assertTrue(images(md).isEmpty())
        assertEquals(listOf("the chart (https://example.test/chart.png)"), texts(md))
    }

    @Test
    fun aRemoteImageWithNoAltShowsItsTarget() {
        assertEquals(listOf("https://example.test/a.png"), texts("![](https://example.test/a.png)"))
    }

    @Test
    fun anSvgAndAPdfAreShownAsTextToo() {
        // Not image paths the decoder handles, but still something Claude
        // pointed at — the name is the useful part.
        assertEquals(listOf("diagram (/tmp/d.svg)"), texts("![diagram](/tmp/d.svg)"))
        assertEquals(listOf("report (/tmp/report.pdf)"), texts("![report](/tmp/report.pdf)"))
    }

    @Test
    fun aDataUriIsCutShortInsteadOfBuryingTheAnswer() {
        val md = "![tiny](data:image/png;base64," + "A".repeat(4000) + ")"
        val text = texts(md).single()
        assertTrue(text.startsWith("tiny (data:image/png;base64,A"), text.take(60))
        assertTrue(text.endsWith("…)"), text.takeLast(20))
        assertTrue(text.length < 120, "a data: URI must not be printed whole, was ${text.length} chars")
    }

    @Test
    fun aRemoteImageInsideATableRowKeepsTheTableWhole() {
        val md = "| a | b |\n|---|---|\n| x | ![shot](https://example.test/x.png) |"
        assertEquals(listOf("| a | b |\n|---|---|\n| x | shot (https://example.test/x.png) |"), texts(md))
    }

    @Test
    fun aGlobbedPathIsShownRatherThanSilentlyDropped() {
        assertEquals(listOf("wildcard (/home/u/*.png)"), texts("![wildcard](/home/u/*.png)"))
    }

    @Test
    fun remoteImagesInsideCodeAreStillLeftExactlyAsWritten() {
        val inline = "Write `![a](https://example.test/a.png)` to embed one."
        assertEquals(listOf(inline), texts(inline))
        val fenced = "```\n![a](https://example.test/a.png)\n```"
        assertEquals(listOf(fenced), texts(fenced))
        val escaped = """Literally \![a](https://example.test/a.png) — not an image."""
        assertEquals(listOf(escaped), texts(escaped))
    }

    @Test
    fun aShowableImageStillSplitsOutAsAnImage() {
        // The fetch path is untouched: a server file on its own line is still
        // an Image segment, not text.
        assertEquals(
            listOf(Segment.Image("the bug", "/tmp/shot.png")),
            InlineImages.split("![the bug](/tmp/shot.png)"),
        )
    }
}
