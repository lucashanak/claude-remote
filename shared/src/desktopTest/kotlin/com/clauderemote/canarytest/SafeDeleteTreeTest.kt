package com.clauderemote.canarytest

import com.clauderemote.testsupport.safeDeleteTree
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [safeDeleteTree] must delete a link as a link. Every link here points inside the test's own temp root: the
 * "victim" is a sibling directory that the tree under deletion links to, never a real path outside the root.
 */
class SafeDeleteTreeTest {
    private lateinit var base: File

    @BeforeTest fun setUp() { base = Files.createTempDirectory("safe-delete-test").toFile() }

    @AfterTest fun tearDown() { safeDeleteTree(base) }

    private fun victim(): File = File(base, "victim").apply {
        File(this, "sub").mkdirs()
        File(this, "sub/precious.txt").writeText("keep me")
        File(this, "top.txt").writeText("keep me too")
    }

    @Test fun aLinkToADirectoryIsDeletedAsALinkAndItsTargetSurvives() {
        val v = victim()
        val tree = File(base, "tree").apply { mkdirs() }
        File(tree, "plain.txt").writeText("x")
        Files.createSymbolicLink(File(tree, "dirlink").toPath(), v.toPath())
        Files.createSymbolicLink(File(tree, "filelink").toPath(), File(v, "top.txt").toPath())
        Files.createSymbolicLink(File(tree, "dangling").toPath(), File(base, "nowhere").toPath())
        safeDeleteTree(tree)
        assertTrue(!Files.exists(tree.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS), "the tree is gone")
        assertEquals("keep me", File(v, "sub/precious.txt").readText())
        assertEquals("keep me too", File(v, "top.txt").readText())
    }

    @Test fun aRootThatIsItselfALinkIsDeletedAsALinkOnly() {
        val v = victim()
        val link = File(base, "rootlink")
        Files.createSymbolicLink(link.toPath(), v.toPath())
        safeDeleteTree(link)
        assertTrue(!Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep me", File(v, "sub/precious.txt").readText())
    }

    @Test fun aNestedTreeIsDeletedCompletely() {
        val tree = File(base, "deep/a/b/c").apply { mkdirs() }
        File(tree, "f").writeText("x")
        safeDeleteTree(File(base, "deep"))
        assertTrue(!File(base, "deep").exists())
    }

    @Test fun aMissingTreeIsANoOp() {
        safeDeleteTree(File(base, "never-existed"))
    }

    @Test fun refusesALocationOutsideTheTempDirectoryAndTheTestSandbox() {
        // the path does not exist, so even a broken check could not delete anything
        val outside = File("/var/safe-delete-test-${System.nanoTime()}-does-not-exist")
        assertFailsWith<IllegalArgumentException> { safeDeleteTree(outside) }
        assertFailsWith<IllegalArgumentException> { safeDeleteTree(File("/")) }
    }

    @Test fun refusesTheTempDirectoryItselfAndItsAncestors() {
        // a FAKE temp directory inside the test root, so that even a broken check deletes nothing but this test's own files
        val fakeTmp = File(base, "faketmp/inner").apply { mkdirs() }
        File(fakeTmp, "f").writeText("x")
        assertFailsWith<IllegalArgumentException> { safeDeleteTree(fakeTmp, tmpDir = fakeTmp) }
        assertFailsWith<IllegalArgumentException> { safeDeleteTree(fakeTmp.parentFile, tmpDir = fakeTmp) }
        assertTrue(File(fakeTmp, "f").exists())
    }

    @Test fun refusesWhenTheParentOfTheRootIsALinkToTheLiveSocketDirectory() {
        // the incident pattern: the link is not the thing deleted but a directory on the way to it. All of it lives in a FAKE temp
        // directory inside the test root; the "live" directory is a tmux-<uid> directory in there.
        val uid = ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim()
        val fakeTmp = File(base, "faketmp").apply { mkdirs() }
        val liveDir = File(fakeTmp, "tmux-$uid").apply { mkdirs() }
        File(liveDir, "default").writeText("")
        File(liveDir, "sub").mkdirs()
        File(liveDir, "sub/precious").writeText("keep")
        val work = File(fakeTmp, "work").apply { mkdirs() }
        Files.createSymbolicLink(File(work, "dirlink").toPath(), liveDir.toPath())
        assertFailsWith<IllegalArgumentException> { safeDeleteTree(File(work, "dirlink/default"), tmpDir = fakeTmp) }
        assertFailsWith<IllegalArgumentException> { safeDeleteTree(File(work, "dirlink/sub"), tmpDir = fakeTmp) }
        assertTrue(File(liveDir, "default").exists() && File(liveDir, "sub/precious").exists(), "nothing behind the link was deleted")
    }

    @Test fun refusesWhenTheParentOfTheRootIsALinkToALocationOutsideTheTempDirectory() {
        val outside = File(base, "outside").apply { mkdirs() }
        File(outside, "child").apply { mkdirs() }
        File(outside, "child/precious").writeText("keep")
        val fakeTmp = File(base, "faketmp").apply { mkdirs() }
        Files.createSymbolicLink(File(fakeTmp, "dirlink").toPath(), outside.toPath())
        assertFailsWith<IllegalArgumentException> { safeDeleteTree(File(fakeTmp, "dirlink/child"), tmpDir = fakeTmp) }
        assertEquals("keep", File(outside, "child/precious").readText())
    }

    @Test fun neverDeletesInsideTheLiveSocketDirectoryShape() {
        // a FAKE temp dir inside the test root with a tmux-<uid> directory in it: the helper must refuse that subtree
        val uid = ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim()
        val fakeTmp = File(base, "faketmp").apply { mkdirs() }
        val liveShape = File(fakeTmp, "tmux-$uid/default").apply { parentFile.mkdirs(); writeText("") }
        assertFailsWith<IllegalArgumentException> { safeDeleteTree(liveShape.parentFile, tmpDir = fakeTmp) }
        assertFailsWith<IllegalArgumentException> { safeDeleteTree(liveShape, tmpDir = fakeTmp) }
        assertTrue(liveShape.exists(), "the refused tree is untouched")
        File(fakeTmp, "other").apply { mkdirs() }
        safeDeleteTree(File(fakeTmp, "other"), tmpDir = fakeTmp) // a neighbour is fine
        assertTrue(!File(fakeTmp, "other").exists())
    }
}

/**
 * The one-way rule from the 2026-11-02 incident (a test deleted part of `/tmp`, including the live tmux socket,
 * through a link): no test and no fixture deletes a tree with Kotlin's recursive delete, which follows links to
 * directories. They use `safeDeleteTree` from `testsupport`.
 */
class NoFollowingDeleteInTestsSyntaxTest {
    private val call = Regex("""\.delete""" + """Recursively\(""")

    private fun repoRoot(): File {
        val shimDir = File(System.getProperty("clauderemote.test.shimDir") ?: error("clauderemote.test.shimDir is not set: run through Gradle"))
        return shimDir.parentFile.parentFile
    }

    private fun testSources(root: File): List<File> = root.walkTopDown()
        .onEnter { it.name !in setOf("build", ".git", ".gradle", ".idea", "node_modules") }
        .filter { it.isFile && it.extension == "kt" }
        .filter { f -> val rel = f.relativeTo(root).path.replace('\\', '/'); Regex("""(^|/)src/[A-Za-z]*[Tt]est[A-Za-z]*/""").containsMatchIn(rel) }
        .toList()

    @Test fun theScannerSeesTheRepositoryTestSources() {
        assertTrue(testSources(repoRoot()).any { it.name == "SafeDeleteTree.kt" || it.name == "SafeDeleteTreeTest.kt" }, "the scan must see the test sources")
    }

    private fun offendersIn(root: File): List<String> = testSources(root)
        .filter { it.name != "SafeDelete.kt" && it.name != "SafeDeleteTreeTest.kt" }
        .filter { call.containsMatchIn(it.readText()) }
        .map { it.relativeTo(root).path.replace('\\', '/') }

    @Test fun theScannerReportsAFileThatUsesTheForbiddenCall() {
        val root = Files.createTempDirectory("syntax-scan-test").toFile()
        try {
            File(root, "m/src/desktopTest/kotlin").mkdirs()
            File(root, "m/src/desktopTest/kotlin/Bad.kt").writeText("fun f(d: java.io.File) { d." + "deleteRecursively" + "() }")
            File(root, "m/src/desktopTest/kotlin/Good.kt").writeText("fun g() = 1")
            File(root, "m/src/main/kotlin").mkdirs()
            File(root, "m/src/main/kotlin/NotATest.kt").writeText("fun h(d: java.io.File) { d." + "deleteRecursively" + "() }")
            assertEquals(listOf("m/src/desktopTest/kotlin/Bad.kt"), offendersIn(root))
        } finally {
            safeDeleteTree(root)
        }
    }

    @Test fun theScannerExemptsNoFileExceptTheHelperAndItsOwnTest() {
        // every file that once had an exemption (or could get one) is reported when it carries the call
        val root = Files.createTempDirectory("syntax-scan-exempt-test").toFile()
        try {
            val names = listOf(
                "desktopIntegrationTest/SshdFixture.kt", "desktopTest/HookMergeTest.kt", "desktopTest/DriftTombstoneLogicTest.kt",
                "desktopTest/ManifestCommandSyntaxTest.kt", "desktopTest/StreamdScriptSyntaxTest.kt", "desktopTest/LiveFileCanaryTest.kt",
                "commonTest/Whatever.kt",
            )
            for (n in names) File(root, "m/src/$n").apply { parentFile.mkdirs(); writeText("fun f(d: java.io.File) { d." + "deleteRecursively" + "() }") }
            File(root, "m/src/desktopTest/SafeDelete.kt").writeText("fun f(d: java.io.File) { d." + "deleteRecursively" + "() }")
            assertEquals(names.map { "m/src/$it" }.sorted(), offendersIn(root).sorted())
        } finally {
            safeDeleteTree(root)
        }
    }

    @Test fun noTestSourceOutsideTheHelperUsesTheLinkFollowingRecursiveDelete() {
        val offenders = offendersIn(repoRoot())
        assertTrue(offenders.isEmpty(), "use safeDeleteTree (testsupport) instead of the recursive delete that follows links in: $offenders")
    }
}
