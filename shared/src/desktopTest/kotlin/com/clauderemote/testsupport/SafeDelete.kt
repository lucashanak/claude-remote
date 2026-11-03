package com.clauderemote.testsupport

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

/**
 * Removes the tree at [root] without ever following a symbolic link: a link (to a file or to a directory) is
 * deleted as a link and what it points at is left alone. Kotlin's own recursive delete on a [File] follows links to
 * directories; a test that left a link to `/tmp` in its scratch tree once deleted part of `/tmp` that way,
 * including the socket of the live tmux server.
 *
 * Refuses (throws before touching anything) unless [root] lies strictly inside [tmpDir] (default `java.io.tmpdir`) (never the directory itself or an ancestor
 * of it, and never inside the live tmux socket directory `tmux-<uid>`) or strictly below `build/t` or `build/tmp` of a Gradle project. A [root] that does not
 * exist is a no-op (after the location check). The check uses the real path of the PARENT of [root] (the leaf may itself be a link, which is
 * then deleted as a link).
 */
fun safeDeleteTree(root: File, tmpDir: File = File(System.getProperty("java.io.tmpdir"))) {
    val path = root.toPath().toAbsolutePath().normalize()
    val parent = path.parent ?: throw IllegalArgumentException("refusing to delete the file system root: $root")
    if (!Files.isDirectory(parent)) return // nothing there
    val realPath = parent.toRealPath().resolve(path.fileName)
    if (!isDeletableLocation(realPath, tmpDir)) {
        throw IllegalArgumentException("refusing to delete $root: it is not strictly inside the temp directory or the Gradle test sandbox")
    }
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(path, EnumSet.noneOf(FileVisitOption::class.java), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file) // a symlink is a "file" here because links are not followed
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

private fun isDeletableLocation(realPath: Path, tmpDir: File): Boolean {
    val tmp = if (tmpDir.exists()) tmpDir.toPath().toRealPath() else null
    if (tmp != null && tmp.startsWith(realPath)) return false // the temp directory itself or one of its ancestors
    if (tmp != null && realPath.startsWith(tmp)) {
        // never the directory of the live tmux socket, whatever the caller meant
        val uid = try { Files.getAttribute(File("/proc/self").toPath(), "unix:uid").toString() } catch (_: Exception) { null }
        return uid == null || !realPath.startsWith(tmp.resolve("tmux-$uid"))
    }
    // strictly below `build/t` or `build/tmp` of a Gradle project (the test sandbox: home, tmux directory, temp dirs)
    val names = (0 until realPath.nameCount).map { realPath.getName(it).toString() }
    return names.indices.any { i -> names[i] == "build" && i + 2 < names.size && names[i + 1] in setOf("t", "tmp") }
}
