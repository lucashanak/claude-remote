package com.clauderemote.ui

import com.clauderemote.ui.InlineImages.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [InlineImages] decides which images Claude's answer shows inline. The rules
 * that matter are the ones that keep things OUT: examples in code, remote URLs,
 * non-image files, and bare paths that were only mentioned.
 */
class InlineImagesTest {

    private fun images(md: String) = InlineImages.split(md).filterIsInstance<Segment.Image>()

    @Test
    fun aMarkdownImageWithAServerPathIsShown() {
        val segments = InlineImages.split("Here it is:\n\n![the bug](/tmp/shot.png)\n\nSee the red box.")
        assertEquals(
            listOf(
                Segment.Text("Here it is:\n\n"),
                Segment.Image("the bug", "/tmp/shot.png"),
                Segment.Text("\n\nSee the red box."),
            ),
            segments,
        )
    }

    @Test
    fun relativeAndHomePathsAreAccepted() {
        assertEquals(
            listOf("shots/a.png", "~/b.jpg", "./c.webp"),
            images("![](shots/a.png) ![](~/b.jpg) ![](./c.webp)").map { it.path },
        )
    }

    @Test
    fun severalImagesOnOneLineKeepTheTextBetweenThem() {
        val segments = InlineImages.split("before ![a](/x.png) middle ![b](/y.png) after")
        assertEquals(
            listOf(
                Segment.Text("before "),
                Segment.Image("a", "/x.png"),
                Segment.Text(" middle "),
                Segment.Image("b", "/y.png"),
                Segment.Text(" after"),
            ),
            segments,
        )
    }

    /** A bare path is something Claude mentioned, not something it showed. */
    @Test
    fun aBarePathIsNotShown() {
        assertTrue(images("I edited /home/lucas/app/res/icon.png and resized it.").isEmpty())
    }

    /** Inside a code block the syntax is an example. */
    @Test
    fun imagesInsideFencedCodeAreLeftAlone() {
        val md = "Use this syntax:\n\n```markdown\n![alt](/tmp/x.png)\n```\n\nThat's all."
        assertTrue(images(md).isEmpty())
        assertEquals(listOf(Segment.Text(md)), InlineImages.split(md))
    }

    @Test
    fun imagesInsideTildeFencesAreLeftAlone() {
        assertTrue(images("~~~\n![a](/x.png)\n~~~").isEmpty())
    }

    @Test
    fun imagesInsideInlineCodeAreLeftAlone() {
        assertTrue(images("Write `![alt](/tmp/x.png)` to show one.").isEmpty())
    }

    /** After the fence closes, a real image renders again. */
    @Test
    fun anImageAfterAClosedFenceIsShown() {
        val md = "```\n![ex](/a.png)\n```\n![real](/b.png)"
        assertEquals(listOf("/b.png"), images(md).map { it.path })
    }

    /** The app must not fetch arbitrary internet content on Claude's say-so. */
    @Test
    fun remoteUrlsAreNotFetched() {
        assertTrue(images("![](https://example.com/a.png) ![](http://x/b.jpg) ![](data:image/png;base64,AAAA)").isEmpty())
    }

    @Test
    fun nonImageTargetsStayText() {
        assertTrue(images("![report](/tmp/report.pdf) ![log](/var/log/x.txt)").isEmpty())
    }

    @Test
    fun angleBracketsAndTitlesAreUnwrapped() {
        assertEquals(
            listOf("/tmp/with space.png", "/tmp/t.png"),
            images("""![a](</tmp/with space.png>) ![b](/tmp/t.png "a title")""").map { it.path },
        )
    }

    @Test
    fun theExtensionCheckIgnoresCaseAndQuery() {
        assertEquals(listOf("/tmp/A.PNG", "/tmp/b.jpg?v=2"), images("![](/tmp/A.PNG) ![](/tmp/b.jpg?v=2)").map { it.path })
    }

    @Test
    fun textWithoutImagesIsReturnedUntouched() {
        val md = "No pictures here, just **markdown**."
        assertEquals(listOf(Segment.Text(md)), InlineImages.split(md))
    }
}
