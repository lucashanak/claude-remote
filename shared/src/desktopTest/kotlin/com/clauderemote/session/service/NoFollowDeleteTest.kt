package com.clauderemote.session.service

import com.clauderemote.testsupport.safeDeleteTree
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoFollowDeleteTest {
    @Test fun aSymlinkIsRemovedAsALinkAndItsTargetSurvives() {
        // both trees live inside one temporary root of this test; nothing points outside it
        val root = Files.createTempDirectory("nofollow-test").toFile()
        try {
            val victim = File(root, "victim").apply { mkdirs() }
            File(victim, "keep.txt").writeText("x")
            val doomed = File(root, "doomed").apply { mkdirs() }
            File(doomed, "f").writeText("y")
            Files.createSymbolicLink(File(doomed, "link").toPath(), victim.toPath())
            safeDeleteTree(doomed)
            assertFalse(doomed.exists())
            assertEquals("x", File(victim, "keep.txt").readText(), "the link's target must not be entered")
        } finally { safeDeleteTree(root) }
        assertFalse(root.exists())
    }

    @Test fun aRootOutsideTheTempDirIsRefused() {
        assertFailsWith<IllegalArgumentException> { safeDeleteTree(File("/")) }
        assertFailsWith<IllegalArgumentException> { safeDeleteTree(File(System.getProperty("java.io.tmpdir"))) }
        assertFailsWith<IllegalArgumentException> { safeDeleteTree(File(System.getProperty("java.io.tmpdir"), "../x")) }
        assertTrue(File(System.getProperty("java.io.tmpdir")).exists())
    }
}
