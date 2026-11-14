package app.kabuk.term

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deleting a tree without ever following a symlink. Kotlin's `File.deleteRecursively` walks with `File.isDirectory`,
 * which follows links: a link to `/sdcard` (`~/storage/shared`) inside a deleted dir would empty shared storage.
 * Here a symlink is unlinked, never descended ([Files.walkFileTree] without FOLLOW_LINKS).
 */
object SafeDelete {
    /** Deletes [f] (file, symlink or dir tree). True when nothing is left at [f]. Never throws. */
    fun tree(f: File): Boolean {
        val p = f.toPath()
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return true
        return try {
            if (Files.isSymbolicLink(p) || !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(p)
            } else {
                Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
                    // a read-only dir (some packages ship 0555 ones) can't have its entries removed otherwise
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        dir.toFile().let { if (!it.canWrite() || !it.canExecute()) { it.setWritable(true, true); it.setExecutable(true, true) } }
                        return FileVisitResult.CONTINUE
                    }
                    // without FOLLOW_LINKS a symlink to a dir arrives here as a file: unlinked, not entered
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        runCatching { Files.deleteIfExists(file) }
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        runCatching { Files.deleteIfExists(file) }
                        return FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        runCatching { Files.deleteIfExists(dir) }
                        return FileVisitResult.CONTINUE
                    }
                })
            }
            !Files.exists(p, LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            false
        }
    }
}
