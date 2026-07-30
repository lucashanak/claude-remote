package com.clauderemote.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Detection is deliberately liberal — the server decides what is really a file.
 * So these tests split in two: which strings are worth *asking* about, and what
 * the rewrite does once the answer comes back.
 */
class FilePathLinksTest {

    private fun candidates(text: String) = detectFilePathCandidates(text).map { it.path }

    // --- candidates: the shapes that must survive to the server ------------

    @Test
    fun findsAbsoluteAndRelativePaths() {
        assertEquals(listOf("/home/lucas/report.pdf"), candidates("Saved to /home/lucas/report.pdf now."))
        assertEquals(listOf("shared/src/main/Foo.kt"), candidates("Edited shared/src/main/Foo.kt"))
        assertEquals(listOf("~/notes/todo.md"), candidates("It lives in ~/notes/todo.md"))
    }

    @Test
    fun handlesDiacritics() {
        assertEquals(listOf("/home/l/zpráva.pdf"), candidates("Uložil jsem to do /home/l/zpráva.pdf dnes."))
        assertEquals(listOf("résumé.docx"), candidates("See `résumé.docx` please."))
    }

    @Test
    fun handlesDigitLeadingExtension() {
        assertEquals(listOf("/home/l/archive.7z"), candidates("Packed into /home/l/archive.7z here."))
    }

    @Test
    fun handlesSpacesAndPunctuationInsideBackticks() {
        assertEquals(listOf("My Report.pdf"), candidates("Wrote `My Report.pdf` for you."))
        assertEquals(listOf("data(1).csv"), candidates("See `data(1).csv` there."))
        assertEquals(listOf("it's.txt"), candidates("Check `it's.txt` now."))
    }

    @Test
    fun handlesExtensionlessNamesInsideBackticks() {
        assertEquals(listOf("Makefile"), candidates("Edit `Makefile` first."))
        assertEquals(listOf("Dockerfile"), candidates("Then `Dockerfile` too."))
    }

    @Test
    fun stopsBeforeLineNumberSuffix() {
        assertEquals(listOf("src/App.kt"), candidates("Look at src/App.kt:42 for the bug."))
    }

    @Test
    fun stripsTrailingSentencePunctuation() {
        assertEquals(listOf("docs/api.md"), candidates("See docs/api.md, then build."))
        assertEquals(listOf("docs/api.md"), candidates("See docs/api.md."))
    }

    // --- non-candidates: never worth a round-trip -------------------------

    @Test
    fun ignoresUrls() {
        assertEquals(emptyList(), candidates("Fetch https://example.com/thing.png please."))
        assertEquals(emptyList(), candidates("Docs at http://host/a/b.html"))
    }

    @Test
    fun ignoresBareWordsWithoutSlashOrBackticks() {
        // Without delimiters there is no way to know where a bare word ends.
        assertEquals(emptyList(), candidates("Wrote output.png for you."))
    }

    @Test
    fun bareRunsNeedAnExtensionSoProseCostsNoRoundTrip() {
        // These contain '/', so only the extension rule keeps them out. An
        // extensionless file still works when Claude backticks it.
        assertEquals(emptyList(), candidates("Use and/or, N/A, 24/7 — e.g. this."))
        assertEquals(emptyList(), candidates("Built in /usr/local/bin/mytool today."))
        assertEquals(listOf("/usr/local/bin/mytool"), candidates("Built in `/usr/local/bin/mytool` today."))
    }

    @Test
    fun ignoresCommandsAndFlagsInBackticks() {
        assertEquals(emptyList(), candidates("Run `npm run build` first."))
        assertEquals(emptyList(), candidates("Pass `--verbose` to it."))
        assertEquals(emptyList(), candidates("Call `foo.bar()` first."))
        assertEquals(emptyList(), candidates("Try `cat a.txt | wc -l` now."))
    }

    @Test
    fun ignoresDirectories() {
        assertEquals(emptyList(), candidates("Check /var/log/ for details."))
    }

    @Test
    fun ignoresCodeBlocksAndExistingLinks() {
        val fenced = "Here:\n\n```bash\ncat /etc/hosts.conf\n```"
        assertEquals(emptyList(), candidates(fenced))
        assertEquals(emptyList(), candidates("Run this:\n\n    cp src/a.txt dst/b.txt\n"))
        assertEquals(emptyList(), candidates("See [the doc](docs/readme.md) for more."))
    }

    // --- linkify: only what the server confirmed --------------------------

    @Test
    fun linkifyOnlyWrapsVerifiedPaths() {
        val text = "Created a/one.txt and b/two.txt."
        val out = linkifyRemoteFilePaths(text, setOf("a/one.txt"))
        assertTrue(out.contains("[a/one.txt](${REMOTE_FILE_SCHEME}a/one.txt)"), out)
        assertTrue(out.contains("and b/two.txt."), out)
        assertTrue(!out.contains("${REMOTE_FILE_SCHEME}b/two.txt"), out)
    }

    @Test
    fun linkifyWithNothingVerifiedLeavesTextAlone() {
        val text = "Saved to out/report.pdf."
        assertEquals(text, linkifyRemoteFilePaths(text, emptySet()))
    }

    @Test
    fun linkifyKeepsInlineCodeBackticksInsideTheLabel() {
        assertEquals(
            "Wrote [`output.png`](${REMOTE_FILE_SCHEME}output.png) for you.",
            linkifyRemoteFilePaths("Wrote `output.png` for you.", setOf("output.png")),
        )
    }

    @Test
    fun linkifyUsesAngleBracketsWhenThePathNeedsThem() {
        // A bare destination can't hold spaces or parens — CommonMark needs <>.
        val out = linkifyRemoteFilePaths("Wrote `My Report.pdf` ok.", setOf("My Report.pdf"))
        assertEquals("Wrote [`My Report.pdf`](<${REMOTE_FILE_SCHEME}My Report.pdf>) ok.", out)

        val parens = linkifyRemoteFilePaths("See `data(1).csv` here.", setOf("data(1).csv"))
        assertTrue(parens.contains("(<${REMOTE_FILE_SCHEME}data(1).csv>)"), parens)
    }

    @Test
    fun linkifyIsIdempotent() {
        val once = linkifyRemoteFilePaths("Saved to out/report.pdf.", setOf("out/report.pdf"))
        assertEquals(once, linkifyRemoteFilePaths(once, setOf("out/report.pdf")))
    }

    @Test
    fun matchRangeCoversTheLabelIncludingBackticks() {
        val text = "Wrote `output.png` here."
        val m = detectFilePathCandidates(text).single()
        assertEquals("`output.png`", text.substring(m.start, m.end))
        assertEquals("output.png", m.path)
    }

    // --- path resolution --------------------------------------------------

    @Test
    fun resolvesRelativePathsAgainstTheSessionFolder() {
        assertEquals("/repo/out.png", resolveSessionPath("out.png", "/repo"))
        assertEquals("/repo/out.png", resolveSessionPath("out.png", "/repo/"))
        assertEquals("/abs/x.txt", resolveSessionPath("/abs/x.txt", "/repo"))
        assertEquals("~/x.txt", resolveSessionPath("~/x.txt", "/repo"))
    }

    // --- cache ------------------------------------------------------------

    @Test
    fun cacheReturnsNullUntilTold() {
        val c = RemotePathCache()
        assertNull(c["/a/b.txt"])
        c["/a/b.txt"] = true
        assertEquals(true, c["/a/b.txt"])
    }

    @Test
    fun cacheKeepsHitsButExpiresMisses() {
        val c = RemotePathCache(missTtlMs = 0)
        c["/hit.txt"] = true
        c["/miss.txt"] = false
        assertEquals(true, c["/hit.txt"], "a confirmed file should stay cached")
        // A miss must go stale so a file created later still becomes a link.
        assertNull(c["/miss.txt"])
    }
}
