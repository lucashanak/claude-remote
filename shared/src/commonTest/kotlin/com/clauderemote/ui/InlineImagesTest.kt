package com.clauderemote.ui

import com.clauderemote.ui.InlineImages.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [InlineImages] decides which images Claude's answer shows inline. The rules
 * that matter are the ones that keep things OUT, and the ones that keep the
 * surrounding markdown in one piece.
 */
class InlineImagesTest {

    private fun images(md: String) = InlineImages.split(md).filterIsInstance<Segment.Image>()
    private fun texts(md: String) = InlineImages.split(md).filterIsInstance<Segment.Text>().map { it.markdown }

    @Test
    fun anImageOnItsOwnLineIsShown() {
        assertEquals(
            listOf(
                Segment.Text("Here it is:\n\n"),
                Segment.Image("the bug", "/tmp/shot.png"),
                Segment.Text("\nSee the red box."),
            ),
            InlineImages.split("Here it is:\n\n![the bug](/tmp/shot.png)\n\nSee the red box."),
        )
    }

    @Test
    fun severalImagesOnOneOtherwiseEmptyLineAreAllShown() {
        assertEquals(listOf("/a.png", "/b.png"), images("  ![a](/a.png) ![b](/b.png)  ").map { it.path })
    }

    @Test
    fun relativeAndHomePathsAreAccepted() {
        assertEquals(
            listOf("shots/a.png", "~/b.jpg", "./c.webp"),
            images("![](shots/a.png)\n![](~/b.jpg)\n![](./c.webp)").map { it.path },
        )
    }

    // ---- structure must survive: embedded images become text ------------

    /** A table split in two rendered as two broken half-tables. */
    @Test
    fun anImageInATableRowIsRewrittenAsTextAndTheTableStaysWhole() {
        val md = "| a | b |\n|---|---|\n| x | ![shot](/x.png) |"
        assertTrue(images(md).isEmpty())
        assertEquals(listOf("| a | b |\n|---|---|\n| x | shot (/x.png) |"), texts(md))
    }

    @Test
    fun anImageInAListItemKeepsTheItemTogether() {
        assertEquals(listOf("- see /x.png here"), texts("- see ![](/x.png) here"))
    }

    @Test
    fun anImageInAQuoteStaysInsideTheQuote() {
        assertEquals(listOf("> look: a (/x.png)"), texts("> look: ![a](/x.png)"))
    }

    @Test
    fun anImageInsideALinkBecomesTheLinkText() {
        assertEquals(listOf("[thumb (/t.png)](/big.png)"), texts("[![thumb](/t.png)](/big.png)"))
    }

    /** A bare path is something Claude mentioned, not something it showed. */
    @Test
    fun aBarePathIsNotShown() {
        assertTrue(images("I edited /home/lucas/app/res/icon.png and resized it.").isEmpty())
    }

    // ---- code is an example ---------------------------------------------

    @Test
    fun imagesInsideFencedCodeAreLeftAlone() {
        val md = "Use this syntax:\n\n```markdown\n![alt](/tmp/x.png)\n```\n\nThat's all."
        assertEquals(listOf(Segment.Text(md)), InlineImages.split(md))
    }

    @Test
    fun imagesInsideTildeFencesAreLeftAlone() {
        assertTrue(images("~~~\n![a](/x.png)\n~~~").isEmpty())
    }

    /**
     * Claude shows markdown that contains a fence by wrapping it in a longer
     * one. The inner three-backtick line must not close the outer block.
     */
    @Test
    fun anInnerShorterFenceDoesNotCloseALongerOne() {
        val md = "````\n```\n![ex](/a.png)\n```\n![still an example](/b.png)\n````\n![real](/c.png)"
        assertEquals(listOf("/c.png"), images(md).map { it.path })
    }

    @Test
    fun aLineWithAnInfoStringDoesNotCloseAFence() {
        assertTrue(images("```\n```kotlin\n![x](/a.png)\n```").isEmpty())
    }

    @Test
    fun indentedCodeIsLeftAlone() {
        assertTrue(images("    ![a](/x.png)").isEmpty())
        assertTrue(images("\t![a](/x.png)").isEmpty())
    }

    @Test
    fun imagesInsideInlineCodeAreLeftAlone() {
        val md = "Write `![alt](/tmp/x.png)` to show one."
        assertEquals(listOf(md), texts(md))
    }

    @Test
    fun anEscapedImageIsLeftAlone() {
        val md = """Literally \![a](/x.png) — not an image."""
        assertEquals(listOf(md), texts(md))
    }

    @Test
    fun anImageAfterAClosedFenceIsShown() {
        assertEquals(listOf("/b.png"), images("```\n![ex](/a.png)\n```\n![real](/b.png)").map { it.path })
    }

    // ---- what is never fetched ------------------------------------------

    @Test
    fun remoteUrlsAreNotFetched() {
        assertTrue(images("![](https://example.com/a.png)\n![](data:image/png;base64,AAAA)\n![](file:/etc/a.png)").isEmpty())
    }

    @Test
    fun nonImageTargetsStayText() {
        assertTrue(images("![report](/tmp/report.pdf)").isEmpty())
    }

    /** SFTP globs these, so they would fetch the wrong file or none. */
    @Test
    fun globCharactersAreRefused() {
        assertTrue(images("![](/tmp/b.jpg?v=2)\n![](/home/u/*.png)\n![](/tmp/[ab].png)").isEmpty())
    }

    @Test
    fun angleBracketsAndTitlesAreUnwrapped() {
        assertEquals(
            listOf("/tmp/with space.png", "/tmp/t.png"),
            images("![a](</tmp/with space.png>)\n![b](/tmp/t.png \"a title\")").map { it.path },
        )
    }

    @Test
    fun theExtensionCheckIgnoresCase() {
        assertEquals(listOf("/tmp/A.PNG"), images("![](/tmp/A.PNG)").map { it.path })
    }

    @Test
    fun textWithoutImagesIsReturnedUntouched() {
        val md = "No pictures here, just **markdown**."
        assertEquals(listOf(Segment.Text(md)), InlineImages.split(md))
    }
}
