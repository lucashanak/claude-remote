package com.clauderemote.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for RemoteDirTree.kt: the path arithmetic, the one-shot scan command,
 * its parsing, and the merge semantics that back the lazy-deepen picker. See
 * the file's own doc comment for why this all exists — one round trip instead
 * of one SSH exec per click.
 */
class RemoteDirTreeTest {

    // --- RemotePath.normalize ---

    @Test
    fun normalize_blankOrWhitespace_becomesHome() {
        assertEquals("~", RemotePath.normalize(""))
        assertEquals("~", RemotePath.normalize("   "))
    }

    @Test
    fun normalize_collapsesDoubleSlashes() {
        assertEquals("~/a/b", RemotePath.normalize("~/a//b"))
        assertEquals("/var/log", RemotePath.normalize("/var///log"))
    }

    @Test
    fun normalize_stripsTrailingSlash() {
        assertEquals("~/a/b", RemotePath.normalize("~/a/b/"))
    }

    @Test
    fun normalize_rootPaths() {
        // "/" must not be stripped down to "" — it stays the root.
        assertEquals("/", RemotePath.normalize("/"))
        assertEquals("/", RemotePath.normalize("///"))
    }

    // --- RemotePath.parent ---

    @Test
    fun parent_walksUpHomeRelativePaths() {
        assertEquals("~/a", RemotePath.parent("~/a/b"))
        assertEquals("~", RemotePath.parent("~/a"))
    }

    @Test
    fun parent_isNullAtBothRoots() {
        assertEquals(null, RemotePath.parent("~"))
        assertEquals(null, RemotePath.parent("/"))
    }

    @Test
    fun parent_ofTopLevelAbsoluteDir_isSlash() {
        assertEquals("/", RemotePath.parent("/foo"))
    }

    @Test
    fun parent_ofBareRelativeName_isNull() {
        // No leading "~" or "/" means there is no slash to cut on at all — this
        // is not the same case as "/foo", which does have a parent ("/").
        assertEquals(null, RemotePath.parent("foo"))
    }

    // --- RemotePath.isRoot / join / name ---

    @Test
    fun isRoot_trueOnlyForHomeAndSlash() {
        assertTrue(RemotePath.isRoot("~"))
        assertTrue(RemotePath.isRoot("/"))
        assertFalse(RemotePath.isRoot("~/a"))
        assertFalse(RemotePath.isRoot("/a"))
    }

    @Test
    fun join_appendsASegment() {
        assertEquals("~/foo", RemotePath.join("~", "foo"))
        assertEquals("~/a/b", RemotePath.join("~/a", "b"))
    }

    @Test
    fun join_ontoSlash_doesNotDoubleTheSlash() {
        assertEquals("/foo", RemotePath.join("/", "foo"))
    }

    @Test
    fun name_lastSegment() {
        assertEquals("b", RemotePath.name("~/a/b"))
        assertEquals("foo", RemotePath.name("/foo"))
    }

    @Test
    fun name_ofRootsIsTheRootItself() {
        assertEquals("~", RemotePath.name("~"))
        assertEquals("/", RemotePath.name("/"))
    }

    // --- RemotePath.crumbs ---

    @Test
    fun crumbs_homeRelativePath() {
        val crumbs = RemotePath.crumbs("~/a/b")
        assertEquals(3, crumbs.size)
        assertEquals(listOf("~", "a", "b"), crumbs.map { it.label })
        assertEquals(listOf("~", "~/a", "~/a/b"), crumbs.map { it.path })
    }

    @Test
    fun crumbs_absolutePath() {
        val crumbs = RemotePath.crumbs("/var/log")
        assertEquals(listOf("/", "var", "log"), crumbs.map { it.label })
        assertEquals(listOf("/", "/var", "/var/log"), crumbs.map { it.path })
    }

    @Test
    fun crumbs_atHomeItself_isASingleCrumb() {
        val crumbs = RemotePath.crumbs("~")
        assertEquals(listOf(PathCrumb("~", "~")), crumbs)
    }

    // --- RemotePath.toShellArg ---

    @Test
    fun toShellArg_home_isUnquotedDollarHome() {
        // "~" must stay unquoted so the shell expands it — quoting it would look
        // up a literal "~" directory and silently return nothing.
        assertEquals("\"\$HOME\"", RemotePath.toShellArg("~"))
    }

    @Test
    fun toShellArg_homeRelative_splitsIntoDollarHomePlusQuotedRemainder() {
        assertEquals("\"\$HOME\"'/foo'", RemotePath.toShellArg("~/foo"))
    }

    @Test
    fun toShellArg_absolutePath_isSingleQuoted() {
        assertEquals("'/abs/p'", RemotePath.toShellArg("/abs/p"))
    }

    @Test
    fun toShellArg_singleQuoteInPath_isEscapedWithQuoteBackslashQuoteQuote() {
        // Standard POSIX trick for embedding a ' inside a '...'-quoted string:
        // close the quote, escape a literal quote, reopen the quote.
        assertEquals("'/o'\\''brien'", RemotePath.toShellArg("/o'brien"))
    }

    // --- RemoteDirScan.command ---

    @Test
    fun command_containsQuotedMarkersAndDefaultDepthAndPruning() {
        val cmd = RemoteDirScan.command("~")
        // The markers MUST be single-quoted in the emitted shell command: bare
        // `<<<` is a here-string operator and `>>>` a redirect, so an unquoted
        // `echo <<<CR-ROOT>>>` never prints the marker and the whole scan parses
        // as empty. Asserting the bare constant (unquoted) would pass even if
        // this quoting regressed, so this checks the quoted form specifically.
        assertTrue(cmd.contains("echo '${RemoteDirScan.ROOT_MARKER}'"))
        assertTrue(cmd.contains("echo '${RemoteDirScan.DIRS_MARKER}'"))
        assertTrue(cmd.contains("echo '${RemoteDirScan.PROJ_MARKER}'"))
        assertTrue(cmd.contains("-mindepth 1"))
        assertTrue(cmd.contains("-maxdepth 3"))
        assertTrue(cmd.contains("-name 'node_modules'"))
        assertTrue(cmd.contains("-printf"))
    }

    @Test
    fun command_customDepth_projectSectionScansOneLevelDeeper() {
        // find prunes AT the marker directory, so ".git"/".claude" never show up
        // in the dirs section themselves — the project scan has to look one
        // level past whatever depth the dirs scan used to still catch them.
        val cmd = RemoteDirScan.command("~", depth = 5)
        assertTrue(cmd.contains("-maxdepth 5"))
        assertTrue(cmd.contains("-maxdepth 6"))
    }

    @Test
    fun command_gitIsPrunedForDirsPassButNotForTheProjectsPass() {
        // The dirs pass prunes ".git" like any other heavy/uninteresting
        // directory — no reason to descend into it for the picker. The projects
        // pass, though, must NOT prune ".git": that is exactly the marker it is
        // searching for, and pruning it there would make no directory ever
        // register as a project. This inversion is easy to break by sharing one
        // prune list between the two finds.
        val cmd = RemoteDirScan.command("~")
        val projSectionStart = cmd.indexOf("echo '${RemoteDirScan.PROJ_MARKER}'")
        assertTrue(projSectionStart > 0)
        val dirsSection = cmd.substring(0, projSectionStart)
        val projSection = cmd.substring(projSectionStart)

        // The dirs section's first `find` clause is the heavy-dir prune list —
        // it runs before the dot-dir handling, so cut it off there.
        val dirsPruneClause = dirsSection.substringAfter("-maxdepth 3 \\(").substringBefore("\\) -prune -o \\( -type d -name '.*'")
        assertTrue(dirsPruneClause.contains("-name '.git'"), "dirs pass should prune .git: $dirsPruneClause")

        // The projects section's first `find` clause is its own heavy-dir prune
        // list (maxdepth 4 = depth + 1 here), separate from the `-o \( markers \)`
        // clause that follows it and actually looks for .git/.claude.
        val projPruneClause = projSection.substringAfter("-maxdepth 4 \\(").substringBefore("\\) -prune -o \\(")
        assertFalse(projPruneClause.contains("-name '.git'"), "projects pass must not prune .git: $projPruneClause")
    }

    // --- RemoteDirScan.parse ---

    /**
     * A realistic three-section transcript: root `~` resolves to `/home/lucas`,
     * two of the five listed dirs are projects, one is hidden, and the dirs
     * section also carries a stray no-separator line and a blank line — both
     * of which must be silently skipped rather than blowing up the parse.
     */
    private val fakeScanOutput = buildString {
        appendLine(RemoteDirScan.ROOT_MARKER)
        appendLine("/home/lucas")
        appendLine(RemoteDirScan.DIRS_MARKER)
        appendLine("1699999999.1234567890 /home/lucas/claude-remote")
        appendLine("1699999500.0 /home/lucas/other-project")
        appendLine("1699990000.0 /home/lucas/.claude")
        appendLine("1699999000.0 /home/lucas/claude-remote/shared")
        appendLine("1699998000.0 /home/lucas/claude-remote/androidApp")
        appendLine("1699999999.0 /home/lucas") // the scan root itself — must be excluded
        appendLine("") // blank line
        appendLine("no-separator-in-this-line") // malformed — no space
        appendLine(RemoteDirScan.PROJ_MARKER)
        appendLine("/home/lucas/claude-remote")
        appendLine("/home/lucas/other-project")
    }

    @Test
    fun parse_rewritesAbsolutePathsToDisplayForm() {
        val tree = RemoteDirScan.parse("~", fakeScanOutput)
        val paths = tree.children("~").map { it.path }
        assertTrue(paths.all { it.startsWith("~") })
        assertTrue("~/claude-remote" in paths)
        assertTrue("~/other-project" in paths)
        assertTrue("~/.claude" in paths)
    }

    @Test
    fun parse_childrenAreGroupedByDisplayParent() {
        val tree = RemoteDirScan.parse("~", fakeScanOutput)
        assertEquals(
            setOf("~/claude-remote", "~/other-project", "~/.claude"),
            tree.children("~").map { it.path }.toSet(),
        )
        assertEquals(
            setOf("~/claude-remote/shared", "~/claude-remote/androidApp"),
            tree.children("~/claude-remote").map { it.path }.toSet(),
        )
    }

    @Test
    fun parse_marksDirsListedInTheProjectSection() {
        val tree = RemoteDirScan.parse("~", fakeScanOutput)
        val byPath = tree.children("~").associateBy { it.path } +
            tree.children("~/claude-remote").associateBy { it.path }
        assertTrue(byPath.getValue("~/claude-remote").isProject)
        assertTrue(byPath.getValue("~/other-project").isProject)
        assertFalse(byPath.getValue("~/.claude").isProject)
        assertFalse(byPath.getValue("~/claude-remote/shared").isProject)
        assertFalse(byPath.getValue("~/claude-remote/androidApp").isProject)
    }

    @Test
    fun parse_mtimeIsTruncatedFromTheFloatFindPrintfForm() {
        val tree = RemoteDirScan.parse("~", fakeScanOutput)
        val claudeRemote = tree.children("~").first { it.path == "~/claude-remote" }
        assertEquals(1699999999L, claudeRemote.mtimeSeconds)
    }

    @Test
    fun parse_ordering_projectsFirstThenHiddenLastThenRecencyThenName() {
        val tree = RemoteDirScan.parse("~", fakeScanOutput)
        // claude-remote and other-project are both projects, so they sort above
        // .claude regardless of its mtime; between the two projects the newer
        // one (claude-remote) wins; .claude is neither a project nor tied with
        // one, so it falls to last even though its mtime beats nothing here.
        assertEquals(
            listOf("~/claude-remote", "~/other-project", "~/.claude"),
            tree.children("~").map { it.path },
        )
        // Neither shared nor androidApp is a project, so this level sorts
        // purely by recency: shared is newer.
        assertEquals(
            listOf("~/claude-remote/shared", "~/claude-remote/androidApp"),
            tree.children("~/claude-remote").map { it.path },
        )
    }

    @Test
    fun parse_excludesTheScanRootFromItsOwnChildren() {
        val tree = RemoteDirScan.parse("~", fakeScanOutput)
        assertTrue(tree.allEntries().none { it.path == "~" })
    }

    @Test
    fun parse_hasListingIsTrueForAnEmptyDirsSection() {
        // This is the "no subfolders" case: the DIRS section is present but
        // empty, so the directory really was scanned and really has nothing in
        // it — as opposed to never having been scanned at all.
        val output = buildString {
            appendLine(RemoteDirScan.ROOT_MARKER)
            appendLine("/home/lucas")
            appendLine(RemoteDirScan.DIRS_MARKER)
            appendLine(RemoteDirScan.PROJ_MARKER)
        }
        val tree = RemoteDirScan.parse("~", output)
        assertTrue(tree.hasListing("~"))
        assertTrue(tree.children("~").isEmpty())
    }

    @Test
    fun parse_missingDirsMarker_yieldsAnEmptyUnlistedTree() {
        // Contrast with the previous test: no DIRS marker at all means the scan
        // itself failed or never ran, so the root must NOT be marked as listed.
        val tree = RemoteDirScan.parse("~", "some noise\nwith no markers at all\n")
        assertTrue(tree.isEmpty)
        assertFalse(tree.hasListing("~"))
    }

    // --- RemoteDirScan.parseFallback ---

    @Test
    fun parseFallback_parsesLsStyleOutputWithTrailingSlashes() {
        val output = "~/claude-remote/shared/\n~/claude-remote/androidApp/\n~/claude-remote/\n"
        val tree = RemoteDirScan.parseFallback("~/claude-remote", output)

        val entries = tree.children("~/claude-remote")
        assertEquals(
            setOf("~/claude-remote/shared", "~/claude-remote/androidApp"),
            entries.map { it.path }.toSet(),
        )
        assertTrue(entries.all { !it.isProject })
        assertTrue(entries.all { it.mtimeSeconds == 0L })
        // The root itself was echoed back by `ls -1d $root/*/` style globbing in
        // this fake output and must not appear as its own child.
        assertTrue(tree.allEntries().none { it.path == "~/claude-remote" })
    }

    // --- RemoteDirTree.merge ---

    @Test
    fun merge_newerListingReplacesTheOlderOneForASharedParent() {
        val staleFoo = RemoteDirEntry("~/foo", "foo", isProject = false, mtimeSeconds = 100)
        val bar = RemoteDirEntry("~/bar", "bar", isProject = false, mtimeSeconds = 50)
        val old = RemoteDirTree("~", mapOf("~" to listOf(staleFoo, bar)), setOf("~", "~/foo", "~/bar"))

        val freshFoo = RemoteDirEntry("~/foo", "foo", isProject = true, mtimeSeconds = 200)
        val fresh = RemoteDirTree("~", mapOf("~" to listOf(freshFoo)), setOf("~", "~/foo"))

        val merged = old.merge(fresh)

        // The newer listing for "~" wins WHOLESALE — bar is gone, not merged in
        // alongside the fresh foo, because a directory's listing is a single
        // atomic read, not a set of individually-mergeable entries.
        assertEquals(listOf(freshFoo), merged.children("~"))
    }

    @Test
    fun merge_listedParentsIsTheUnionOfBoth() {
        val old = RemoteDirTree("~", mapOf("~" to listOf(RemoteDirEntry("~/bar", "bar", false, 50))), setOf("~", "~/bar"))
        val fresh = RemoteDirTree("~", mapOf("~/foo" to emptyList()), setOf("~/foo"))
        val merged = old.merge(fresh)

        assertTrue(merged.hasListing("~"))
        assertTrue(merged.hasListing("~/bar"))
        assertTrue(merged.hasListing("~/foo"))
        // "~/bar" is still known even though it dropped out of childrenByParent
        // for "~" in this particular merge scenario — contains() falls back to
        // the union of listedParents, not just the current children map.
        assertTrue(merged.contains("~/bar"))
    }

    // --- RemoteDirTree.contains ---

    @Test
    fun contains_trueForAListedDirectory() {
        val tree = RemoteDirTree("~", emptyMap(), setOf("~", "~/foo"))
        assertTrue(tree.contains("~/foo"))
    }

    @Test
    fun contains_trueForADirOnlyPresentAsAChildEntry() {
        // "~/bar" was never scanned itself (not in listedParents) but it shows
        // up as a child of "~", which is enough to say the tree contains it.
        val bar = RemoteDirEntry("~/bar", "bar", isProject = false, mtimeSeconds = 0)
        val tree = RemoteDirTree("~", mapOf("~" to listOf(bar)), setOf("~"))
        assertTrue(tree.contains("~/bar"))
    }

    @Test
    fun contains_falseForAnUnknownPath() {
        val tree = RemoteDirTree("~", emptyMap(), setOf("~"))
        assertFalse(tree.contains("~/nowhere"))
    }

    // --- RemoteDirEntry.isHidden ---

    @Test
    fun isHidden_defaultsFromALeadingDot() {
        assertTrue(RemoteDirEntry("~/.claude", ".claude", isProject = false, mtimeSeconds = 0).isHidden)
        assertFalse(RemoteDirEntry("~/claude-remote", "claude-remote", isProject = false, mtimeSeconds = 0).isHidden)
    }

    // --- RemoteDirScan fallback: the absolute→display rewrite ---
    //
    // Regression guard. `ls -1d` prints ABSOLUTE paths. When the fallback did
    // not strip the expanded root, every entry was filed under "/home/lucas"
    // while the picker asked for "~", so a BSD/macOS server showed "No
    // subfolders" no matter what was actually there.

    @Test
    fun fallbackCommand_echoesTheExpandedRootAndTheDirsMarker() {
        val cmd = RemoteDirScan.fallbackCommand("~")
        assertTrue(cmd.contains("echo '${RemoteDirScan.ROOT_MARKER}'"), cmd)
        assertTrue(cmd.contains("echo '${RemoteDirScan.DIRS_MARKER}'"), cmd)
        assertTrue(cmd.contains("pwd"), cmd)
        assertTrue(cmd.contains("ls -1d"), cmd)
    }

    @Test
    fun parseFallback_rewritesAbsolutePathsToTheDisplayRoot() {
        val output = buildString {
            appendLine(RemoteDirScan.ROOT_MARKER)
            appendLine("/home/lucas")
            appendLine(RemoteDirScan.DIRS_MARKER)
            appendLine("/home/lucas/claude-remote/")
            appendLine("/home/lucas/rag/")
        }
        val tree = RemoteDirScan.parseFallback("~", output)

        // Filed under the DISPLAY root, which is what the picker asks for.
        assertEquals(listOf("~/claude-remote", "~/rag"), tree.children("~").map { it.path })
        assertTrue(tree.hasListing("~"))
        assertTrue(tree.contains("~/rag"))
        // No metadata is available from `ls`, so nothing is ranked as a project.
        assertTrue(tree.children("~").none { it.isProject })
        assertTrue(tree.children("~").all { it.mtimeSeconds == 0L })
    }

    @Test
    fun parseFallback_excludesTheRootItselfAfterRewriting() {
        val output = buildString {
            appendLine(RemoteDirScan.ROOT_MARKER)
            appendLine("/home/lucas/claude-remote")
            appendLine(RemoteDirScan.DIRS_MARKER)
            appendLine("/home/lucas/claude-remote/")
            appendLine("/home/lucas/claude-remote/shared/")
        }
        val tree = RemoteDirScan.parseFallback("~/claude-remote", output)
        assertEquals(listOf("~/claude-remote/shared"), tree.children("~/claude-remote").map { it.path })
    }

    @Test
    fun parseFallback_absoluteRootPrefixOnlyMatchesAtAPathBoundary() {
        // "/home/luc" must not swallow "/home/lucas/x" by bare prefix. Since
        // that path is not under the scan root either, it is dropped outright
        // rather than kept under a fabricated parent — see the out-of-root test
        // below for why keeping it was a problem.
        val output = buildString {
            appendLine(RemoteDirScan.ROOT_MARKER)
            appendLine("/home/luc")
            appendLine(RemoteDirScan.DIRS_MARKER)
            appendLine("/home/lucas/x/")
        }
        val tree = RemoteDirScan.parseFallback("~", output)
        assertEquals(emptyList(), tree.children("/home/lucas").map { it.path })
        assertEquals(emptyList(), tree.children("~").map { it.path })
        assertFalse(tree.contains("/home/lucas/x"))
    }

    // --- Crafted server output ---
    //
    // Directory names may contain newlines and `find` prints them raw, so one
    // planted name (a hostile `git clone` is enough — no server compromise)
    // arrives as EXTRA LINES that parse as well-formed entries.

    @Test
    fun parse_dropsEntriesOutsideTheScanRoot() {
        val output = buildString {
            appendLine(RemoteDirScan.ROOT_MARKER)
            appendLine("/home/lucas")
            appendLine(RemoteDirScan.DIRS_MARKER)
            appendLine("1700000000.0 /home/lucas/real")
            // What a directory named "x\n1700000000 /etc/passwd_dir\ny" injects.
            appendLine("1700000000.0 /etc/passwd_dir")
            appendLine(RemoteDirScan.PROJ_MARKER)
        }
        val tree = RemoteDirScan.parse("~", output)

        assertEquals(listOf("~/real"), tree.children("~").map { it.path })
        // Not filed under a fabricated "/" parent, and not discoverable — this is
        // what kept it out of completion and out of `contains`, which would
        // otherwise have suppressed the "no such folder" warning for it.
        assertEquals(emptyList(), tree.children("/").map { it.path })
        assertFalse(tree.contains("/etc/passwd_dir"))
        assertTrue(tree.allEntries().none { it.path.startsWith("/etc") })
    }

    @Test
    fun parse_dropsForgedProjectEntriesOutsideTheScanRoot() {
        val output = buildString {
            appendLine(RemoteDirScan.ROOT_MARKER)
            appendLine("/home/lucas")
            appendLine(RemoteDirScan.DIRS_MARKER)
            appendLine("1700000000.0 /home/lucas/real")
            appendLine(RemoteDirScan.PROJ_MARKER)
            appendLine("/etc/passwd_dir")
        }
        val tree = RemoteDirScan.parse("~", output)
        // The forged project path is dropped, so it cannot mark anything.
        assertEquals(listOf("~/real"), tree.children("~").map { it.path })
        assertTrue(tree.children("~").none { it.isProject })
    }

    // --- hasListing: only directories the walk DESCENDED into ---
    //
    // The picker asks for a listing only when it has none, so a directory
    // wrongly marked "listed" renders "No subfolders" forever and never asks.
    // These pin the three edges where that used to happen.

    private fun depthScan() = RemoteDirScan.parse(
        "~",
        buildString {
            appendLine(RemoteDirScan.ROOT_MARKER)
            appendLine("/home/lucas")
            appendLine(RemoteDirScan.DIRS_MARKER)
            appendLine("1700000000.0 /home/lucas/a")
            appendLine("1700000000.0 /home/lucas/a/b")
            appendLine("1700000000.0 /home/lucas/a/b/c")
            appendLine("1700000000.0 /home/lucas/empty")
            appendLine("1700000000.0 /home/lucas/.claude")
            appendLine(RemoteDirScan.PROJ_MARKER)
        },
        depth = 3,
    )

    @Test
    fun hasListing_trueForADirectoryWhoseChildrenWereSeen() {
        val tree = depthScan()
        assertTrue(tree.hasListing("~"))
        assertTrue(tree.hasListing("~/a"))
        assertTrue(tree.hasListing("~/a/b"))
    }

    @Test
    fun hasListing_trueForAnEmptyDirectoryAboveTheDepthLimit() {
        // Descended into and found empty — "no subfolders" is the truth here,
        // and refetching it would be a wasted round trip.
        val tree = depthScan()
        assertTrue(tree.hasListing("~/empty"))
        assertEquals(emptyList(), tree.children("~/empty").map { it.path })
    }

    @Test
    fun hasListing_falseAtTheDepthLimitSoLazyDeepenStillFires() {
        // "~/a/b/c" sits AT maxdepth: it was printed but never descended into,
        // so its children are unknown, not absent.
        val tree = depthScan()
        assertFalse(tree.hasListing("~/a/b/c"))
    }

    @Test
    fun hasListing_falseForADotDirectoryEvenAboveTheDepthLimit() {
        // Dot-directories are printed but pruned, so `~/.claude` is reachable to
        // select AND still browsable — it must not claim to be listed.
        val tree = depthScan()
        assertTrue(tree.children("~").any { it.path == "~/.claude" })
        assertFalse(tree.hasListing("~/.claude"))
    }

    @Test
    fun parseFallback_marksOnlyTheRootAsListed() {
        // The `ls` fallback sees exactly one level. Marking its children listed
        // made every folder on a BSD/macOS server show "No subfolders".
        val output = buildString {
            appendLine(RemoteDirScan.ROOT_MARKER)
            appendLine("/home/lucas")
            appendLine(RemoteDirScan.DIRS_MARKER)
            appendLine("/home/lucas/claude-remote/")
            appendLine("/home/lucas/rag/")
        }
        val tree = RemoteDirScan.parseFallback("~", output)
        assertTrue(tree.hasListing("~"))
        assertFalse(tree.hasListing("~/claude-remote"))
        assertFalse(tree.hasListing("~/rag"))
    }

    @Test
    fun hasListing_afterMergingADeepenScanTheDeepenedDirectoryIsListed() {
        // The lazy-deepen round trip is what closes the gap: scan "~/a/b/c" and
        // merge it in, and the picker stops asking.
        val shallow = depthScan()
        assertFalse(shallow.hasListing("~/a/b/c"))

        val deepened = RemoteDirScan.parse(
            "~/a/b/c",
            buildString {
                appendLine(RemoteDirScan.ROOT_MARKER)
                appendLine("/home/lucas/a/b/c")
                appendLine(RemoteDirScan.DIRS_MARKER)
                appendLine("1700000000.0 /home/lucas/a/b/c/deep")
                appendLine(RemoteDirScan.PROJ_MARKER)
            },
        )
        val merged = shallow.merge(deepened)
        assertTrue(merged.hasListing("~/a/b/c"))
        assertEquals(listOf("~/a/b/c/deep"), merged.children("~/a/b/c").map { it.path })
        // The original root's listing survives the merge.
        assertTrue(merged.hasListing("~"))
        assertEquals("~", merged.root)
    }
}
