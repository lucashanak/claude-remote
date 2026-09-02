package com.clauderemote.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for PathCompletion.kt — the shell-like typeahead behind the path
 * field. [fuzzyMatch] and [PathCompletion.commonPrefix] are pure scoring/
 * string functions; [PathCompletion.suggest] is where the ranking rules that
 * actually matter to the picker (recent > project > folder, siblings of what
 * you typed beat an unrelated hit anywhere else in the tree) get exercised.
 */
class PathCompletionTest {

    // --- fuzzyMatch ---

    @Test
    fun fuzzyMatch_scoreOrdering_exactBeatsPrefixBeatsSubstringBeatsSubsequence() {
        val exact = PathCompletion.fuzzyMatch("cr", "cr")
        val prefix = PathCompletion.fuzzyMatch("crfoo", "cr")
        val substring = PathCompletion.fuzzyMatch("xcr", "cr")
        val subsequence = PathCompletion.fuzzyMatch("xcxr", "cr")

        checkNotNull(exact); checkNotNull(prefix); checkNotNull(substring); checkNotNull(subsequence)
        assertTrue(exact.score > prefix.score, "exact ${exact.score} should beat prefix ${prefix.score}")
        assertTrue(prefix.score > substring.score, "prefix ${prefix.score} should beat substring ${substring.score}")
        assertTrue(substring.score > subsequence.score, "substring ${substring.score} should beat subsequence ${subsequence.score}")
    }

    @Test
    fun fuzzyMatch_shorterTargetWinsATieWithinTheSameMatchKind() {
        // Both are prefix matches for "ai", so the tie-break is target length.
        val short = PathCompletion.fuzzyMatch("ai-x", "ai")
        val long = PathCompletion.fuzzyMatch("ai-xyz", "ai")
        checkNotNull(short); checkNotNull(long)
        assertTrue(short.score > long.score, "shorter target should win: ${short.score} vs ${long.score}")
    }

    @Test
    fun fuzzyMatch_nonMatchReturnsNull() {
        assertNull(PathCompletion.fuzzyMatch("hello", "xyz"))
    }

    @Test
    fun fuzzyMatch_emptyQueryMatchesAnything() {
        val match = PathCompletion.fuzzyMatch("anything", "")
        assertEquals(PathCompletion.Match(1, emptyList()), match)
    }

    @Test
    fun fuzzyMatch_isCaseInsensitive() {
        val match = PathCompletion.fuzzyMatch("HELLO", "hello")
        checkNotNull(match)
        // Same score as a same-case exact match.
        assertEquals(PathCompletion.fuzzyMatch("hello", "hello"), match)
    }

    @Test
    fun fuzzyMatch_matchedIndices_prefix() {
        val match = PathCompletion.fuzzyMatch("hello", "he")
        checkNotNull(match)
        assertEquals(listOf(0, 1), match.indices)
    }

    @Test
    fun fuzzyMatch_matchedIndices_substring() {
        val match = PathCompletion.fuzzyMatch("xhellox", "hel")
        checkNotNull(match)
        assertEquals(listOf(1, 2, 3), match.indices)
    }

    // --- commonPrefix ---

    @Test
    fun commonPrefix_emptyList_isEmptyString() {
        assertEquals("", PathCompletion.commonPrefix(emptyList()))
    }

    @Test
    fun commonPrefix_singleCandidate_isTheWholeString() {
        assertEquals("~/claude-remote", PathCompletion.commonPrefix(listOf("~/claude-remote")))
    }

    @Test
    fun commonPrefix_sharedPrefixOfSeveral() {
        val prefix = PathCompletion.commonPrefix(listOf("~/claude-remote", "~/claude-app", "~/claude"))
        assertEquals("~/claude", prefix)
    }

    @Test
    fun commonPrefix_noSharedPrefix_isEmptyString() {
        assertEquals("", PathCompletion.commonPrefix(listOf("~/foo", "/bar")))
    }

    @Test
    fun commonPrefix_identicalStrings() {
        assertEquals("~/a", PathCompletion.commonPrefix(listOf("~/a", "~/a", "~/a")))
    }

    // --- suggest ---

    private val claudeRemote = RemoteDirEntry("~/claude-remote", "claude-remote", isProject = true, mtimeSeconds = 100)
    private val coffee = RemoteDirEntry("~/coffee", "coffee", isProject = false, mtimeSeconds = 50)
    private val other = RemoteDirEntry("~/other", "other", isProject = false, mtimeSeconds = 10)
    private val shared = RemoteDirEntry("~/claude-remote/shared", "shared", isProject = false, mtimeSeconds = 10)
    private val androidApp = RemoteDirEntry("~/claude-remote/androidApp", "androidApp", isProject = false, mtimeSeconds = 5)

    private val tree = RemoteDirTree(
        root = "~",
        childrenByParent = mapOf(
            "~" to listOf(claudeRemote, coffee, other),
            "~/claude-remote" to listOf(shared, androidApp),
        ),
        listedParents = setOf("~", "~/claude-remote"),
    )

    @Test
    fun suggest_blankInput_recentsFirstThenRootChildren() {
        val result = PathCompletion.suggest("", tree, recents = listOf("~/other"))
        assertEquals(
            listOf(
                PathSuggestion("~/other", RemoteDirKind.RECENT),
                PathSuggestion("~/claude-remote", RemoteDirKind.PROJECT),
                PathSuggestion("~/coffee", RemoteDirKind.FOLDER),
            ),
            result,
        )
    }

    @Test
    fun suggest_trailingSlash_listsThatDirectorysChildren() {
        val result = PathCompletion.suggest("~/claude-remote/", tree)
        assertEquals(
            listOf(
                PathSuggestion("~/claude-remote/shared", RemoteDirKind.FOLDER),
                PathSuggestion("~/claude-remote/androidApp", RemoteDirKind.FOLDER),
            ),
            result,
        )
    }

    @Test
    fun suggest_recentBeatsProjectBeatsFolder_forAnEqualQualityNameMatch() {
        // Three entries named identically, reachable only via the whole-tree
        // fuzzy pass (not as siblings of the query, which has no parent), so
        // the only thing separating them is kind — exercising the 400/200/0
        // kind bonus in isolation.
        val recentMatch = RemoteDirEntry("~/x/match", "match", isProject = false, mtimeSeconds = 1)
        val projectMatch = RemoteDirEntry("~/y/match", "match", isProject = true, mtimeSeconds = 1)
        val folderMatch = RemoteDirEntry("~/z/match", "match", isProject = false, mtimeSeconds = 1)
        val kindTree = RemoteDirTree(
            root = "~",
            childrenByParent = mapOf(
                "~/x" to listOf(recentMatch),
                "~/y" to listOf(projectMatch),
                "~/z" to listOf(folderMatch),
            ),
            listedParents = setOf("~/x", "~/y", "~/z"),
        )

        val result = PathCompletion.suggest("match", kindTree, recents = listOf("~/x/match"))
        assertEquals(listOf("~/x/match", "~/y/match", "~/z/match"), result.map { it.path })
    }

    @Test
    fun suggest_siblingsOfTypedParent_outrankAnUnrelatedWholeTreeHit() {
        // "target" is only a prefix match for "tar" (score 800-ish) as a
        // sibling of "~/proj"; "tar" itself is an EXACT match (score ~1000)
        // elsewhere in the tree. The sibling bonus must still put it first.
        val siblingEntry = RemoteDirEntry("~/proj/target", "target", isProject = false, mtimeSeconds = 1)
        val unrelatedEntry = RemoteDirEntry("~/other/tar", "tar", isProject = false, mtimeSeconds = 1)
        val siblingTree = RemoteDirTree(
            root = "~",
            childrenByParent = mapOf(
                "~/proj" to listOf(siblingEntry),
                "~/other" to listOf(unrelatedEntry),
            ),
            listedParents = setOf("~/proj", "~/other"),
        )

        val result = PathCompletion.suggest("~/proj/tar", siblingTree)
        assertEquals("~/proj/target", result.first().path)
        // The sibling entry is also part of allEntries() for this same tree, so
        // this also proves it isn't double-counted (the `seen` set dedupes it).
        assertEquals(listOf("~/proj/target", "~/other/tar"), result.map { it.path })
    }

    @Test
    fun suggest_limitIsRespected() {
        val manyEntries = (1..20).map {
            RemoteDirEntry("~/many/folder$it", "folder$it", isProject = false, mtimeSeconds = it.toLong())
        }
        val bigTree = RemoteDirTree("~", mapOf("~/many" to manyEntries), setOf("~/many"))
        val result = PathCompletion.suggest("folder", bigTree, limit = 3)
        assertEquals(3, result.size)
    }

    @Test
    fun suggest_matchedIndicesAreOffsetIntoTheFullPathNotTheBareName() {
        // Querying "remo" hits "claude-remote" as a substring starting at
        // name-index 7, but the suggestion's path is "~/claude-remote" — two
        // characters longer than the bare name — so the highlighted indices
        // must shift by that offset to still point at "remo" in the path.
        val result = PathCompletion.suggest("remo", tree)
        val hit = result.first { it.path == "~/claude-remote" }
        assertEquals(listOf(9, 10, 11, 12), hit.matchedIndices)

        val highlighted = hit.matchedIndices.minOrNull()!!.let { start ->
            hit.path.substring(start, hit.matchedIndices.max() + 1)
        }
        assertEquals("remo", highlighted)
    }

    @Test
    fun suggest_neverReturnsDuplicatePaths() {
        // "~/other" is both a recent AND a root child in the fixture tree —
        // the blank-input test already proves distinctBy handles this for
        // recents-vs-tree; this proves it for a plain fuzzy query too, where a
        // sibling is also reachable via the whole-tree fuzzy pass.
        val result = PathCompletion.suggest("other", tree, recents = listOf("~/other"))
        assertEquals(result.map { it.path }.distinct(), result.map { it.path })
    }
}
