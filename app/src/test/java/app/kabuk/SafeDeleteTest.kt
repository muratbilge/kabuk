package app.kabuk

import app.kabuk.term.SafeDelete
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** Deleting usr / usr-staging / ~/storage must unlink symlinks, never walk into what they point at. */
class SafeDeleteTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun symlinkToADirOutsideSurvivesWithItsContents() {
        val outside = tmp.newFolder("sdcard")
        File(outside, "photo.jpg").writeText("keep me")
        File(outside, "sub").mkdirs(); File(outside, "sub/a.txt").writeText("me too")
        val tree = tmp.newFolder("usr")
        File(tree, "bin").mkdirs(); File(tree, "bin/tool").writeText("x")
        File(tree, "home/storage").mkdirs()
        Files.createSymbolicLink(File(tree, "home/storage/shared").toPath(), outside.toPath())
        Files.createSymbolicLink(File(tree, "bin/dangling").toPath(), File("/nonexistent/x").toPath())
        Files.createSymbolicLink(File(tree, "lib").toPath(), File(outside, "sub").toPath())

        assertTrue(SafeDelete.tree(tree))
        assertFalse(tree.exists())
        assertEquals("keep me", File(outside, "photo.jpg").readText())
        assertEquals("me too", File(outside, "sub/a.txt").readText())
    }

    @Test fun aSymlinkAsTheRootIsUnlinkedOnly() {
        val outside = tmp.newFolder("target")
        File(outside, "f").writeText("keep")
        val link = File(tmp.root, "link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertTrue(SafeDelete.tree(link))
        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", File(outside, "f").readText())
    }

    @Test fun readOnlyDirsFilesAndMissingPaths() {
        val tree = tmp.newFolder("t")
        val ro = File(tree, "share/ro").apply { mkdirs() }
        File(ro, "f").writeText("x")
        ro.setWritable(false, false)
        assertTrue(SafeDelete.tree(tree))
        assertFalse(tree.exists())
        assertTrue(SafeDelete.tree(File(tmp.root, "never-was")))
        val file = tmp.newFile("plain")
        assertTrue(SafeDelete.tree(file))
        assertFalse(file.exists())
    }
}
