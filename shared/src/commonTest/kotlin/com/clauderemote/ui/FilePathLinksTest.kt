package com.clauderemote.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The detector runs over every assistant message, so a false positive turns
 * ordinary prose into a bogus download link. These tests pin both directions.
 */
class FilePathLinksTest {

    private fun paths(text: String) = detectRemoteFilePaths(text).map { it.path }

    // --- accepted ---------------------------------------------------------

    @Test
    fun findsAbsolutePathInProse() {
        assertEquals(listOf("/home/lucas/report.pdf"), paths("Saved it to /home/lucas/report.pdf now."))
    }

    @Test
    fun findsRepoRelativePath() {
        assertEquals(listOf("shared/src/main/Foo.kt"), paths("Edited shared/src/main/Foo.kt"))
    }

    @Test
    fun findsHomeRelativePath() {
        assertEquals(listOf("~/notes/todo.md"), paths("It lives in ~/notes/todo.md"))
    }

    @Test
    fun findsBareFilenameOnlyInsideBackticks() {
        assertEquals(listOf("output.png"), paths("Wrote `output.png` for you."))
        assertEquals(emptyList(), paths("Wrote output.png for you."))
    }

    @Test
    fun stripsTrailingSentencePunctuation() {
        assertEquals(listOf("docs/api.md"), paths("See docs/api.md, then build."))
        assertEquals(listOf("docs/api.md"), paths("See docs/api.md."))
    }

    @Test
    fun stopsBeforeLineNumberSuffix() {
        // Claude Code's `file:line` convention — the path is still the target.
        assertEquals(listOf("src/App.kt"), paths("Look at src/App.kt:42 for the bug."))
    }

    @Test
    fun findsMultiplePaths() {
        assertEquals(
            listOf("a/one.txt", "b/two.txt"),
            paths("Created a/one.txt and b/two.txt."),
        )
    }

    // --- rejected ---------------------------------------------------------

    @Test
    fun ignoresUrls() {
        assertEquals(emptyList(), paths("Fetch https://example.com/thing.png please."))
        assertEquals(emptyList(), paths("Docs at http://host/a/b.html"))
    }

    @Test
    fun ignoresProseWithSlashesOrDots() {
        assertEquals(emptyList(), paths("Use and/or, N/A, 24/7 — e.g. this."))
    }

    @Test
    fun ignoresVersionNumbers() {
        assertEquals(emptyList(), paths("Bumped to v1.2.3 and 10.4.5 today."))
    }

    @Test
    fun ignoresDirectories() {
        assertEquals(emptyList(), paths("Everything is under /home/lucas/claude-remote now."))
        assertEquals(emptyList(), paths("Check /var/log/ for details."))
    }

    @Test
    fun ignoresFencedCodeBlocks() {
        val md = """
            Here is the script:

            ```bash
            cat /etc/hosts.conf
            ```
        """.trimIndent()
        assertEquals(emptyList(), paths(md))
    }

    @Test
    fun ignoresIndentedCodeBlocks() {
        assertEquals(emptyList(), paths("Run this:\n\n    cp src/a.txt dst/b.txt\n"))
    }

    @Test
    fun ignoresExistingMarkdownLinks() {
        assertEquals(emptyList(), paths("See [the doc](docs/readme.md) for more."))
    }

    @Test
    fun ignoresFunctionCallsInBackticks() {
        assertEquals(emptyList(), paths("Call `foo.bar()` first."))
    }

    // --- linkify ----------------------------------------------------------

    @Test
    fun linkifyWrapsPlainPath() {
        assertEquals(
            "Saved to [out/report.pdf](${REMOTE_FILE_SCHEME}out/report.pdf).",
            linkifyRemoteFilePaths("Saved to out/report.pdf."),
        )
    }

    @Test
    fun linkifyKeepsInlineCodeBackticks() {
        // Backticks stay INSIDE the link label so the path keeps its monospace
        // styling instead of turning into plain link text.
        assertEquals(
            "Wrote [`output.png`](${REMOTE_FILE_SCHEME}output.png) for you.",
            linkifyRemoteFilePaths("Wrote `output.png` for you."),
        )
    }

    @Test
    fun linkifyLeavesTextWithoutPathsUntouched() {
        val text = "Nothing to download here, e.g. no files at all."
        assertEquals(text, linkifyRemoteFilePaths(text))
    }

    @Test
    fun linkifyIsIdempotentOverAlreadyLinkedText() {
        val once = linkifyRemoteFilePaths("Saved to out/report.pdf.")
        assertEquals(once, linkifyRemoteFilePaths(once))
    }

    @Test
    fun linkifyHandlesMultiplePathsInOneLine() {
        val out = linkifyRemoteFilePaths("Created a/one.txt and b/two.txt.")
        assertTrue(out.contains("[a/one.txt](${REMOTE_FILE_SCHEME}a/one.txt)"), out)
        assertTrue(out.contains("[b/two.txt](${REMOTE_FILE_SCHEME}b/two.txt)"), out)
    }

    @Test
    fun matchRangesPointAtTheOriginalText() {
        val text = "Saved to out/report.pdf."
        val m = detectRemoteFilePaths(text).single()
        assertEquals(m.path, text.substring(m.start, m.end))
    }
}
