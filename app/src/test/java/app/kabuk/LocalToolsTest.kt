package app.kabuk

import app.kabuk.term.BinDir
import app.kabuk.term.LocalTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class LocalToolsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun nativeDir(vararg tools: String) = tmp.newFolder("lib-${tmp.root.list()!!.size}").apply {
        tools.forEach { File(this, "lib$it.so").writeText("elf") }
    }

    private fun target(f: File) = Files.readSymbolicLink(f.toPath()).toString()

    @Test fun linksEveryAppletToBusyboxInNativeDir() {
        val lib = nativeDir("busybox")
        val bin = File(tmp.root, "bin")
        var listedVia: File? = null
        val made = BinDir(bin, lib, reserved = setOf("xdg-open")) { listedVia = it; listOf("ls", "[[", "sh", "xdg-open", "busybox", "", "a/b") }.sync("32 /x")
        assertTrue(made)
        assertEquals("busybox --list runs through the bin link", "busybox", listedVia!!.name)
        assertEquals(setOf(".stamp", "busybox", "ls", "[[", "sh"), bin.list()!!.toSet())
        listOf("busybox", "ls", "[[", "sh").forEach { assertEquals(File(lib, "libbusybox.so").path, target(File(bin, it))) }
    }

    @Test fun rcloneAndTheUrlOpenerNamesWinOverApplets() {
        val lib = nativeDir("busybox", "rclone", "kabuk-open")
        val bin = File(tmp.root, "bin")
        // a busybox that (hypothetically) had applets with these names must not take them
        BinDir(bin, lib) { listOf("ls", "rclone", "xdg-open", "open", "termux-open-url", "kabuk-open") }.sync("34 /x")
        assertEquals(setOf(".stamp", "busybox", "ls", "rclone", "kabuk-open", "xdg-open", "open", "termux-open-url"), bin.list()!!.toSet())
        assertEquals(File(lib, "librclone.so").path, target(File(bin, "rclone")))
        listOf("kabuk-open", "xdg-open", "open", "termux-open-url").forEach { assertEquals(File(lib, "libkabuk-open.so").path, target(File(bin, it))) }
    }

    @Test fun urlOpenerNamesStayFreeWithoutTheHelper() {
        val lib = nativeDir("busybox")
        val bin = File(tmp.root, "bin")
        BinDir(bin, lib) { listOf("ls", "open", "xdg-open") }.sync("34 /x")
        assertEquals(setOf(".stamp", "busybox", "ls"), bin.list()!!.toSet())
    }

    @Test fun rebuildsOnlyWhenStampChanges() {
        val lib = nativeDir("busybox")
        val bin = File(tmp.root, "bin")
        var lists = 0
        val dir = { n: File -> BinDir(bin, n) { lists++; listOf("ls") } }
        assertTrue(dir(lib).sync("32 ${lib.path}"))
        assertFalse(dir(lib).sync("32 ${lib.path}"))
        assertEquals(1, lists)
        // an update moves nativeLibraryDir and bumps versionCode: the links must follow
        val moved = nativeDir("busybox")
        assertTrue(dir(moved).sync("33 ${moved.path}"))
        assertEquals(File(moved, "libbusybox.so").path, target(File(bin, "ls")))
        assertFalse(File(tmp.root, "bin.new").exists())
    }

    @Test fun withoutBusyboxTheDirIsEmpty() {
        val bin = File(tmp.root, "bin")
        assertTrue(BinDir(bin, nativeDir()) { error("not called") }.sync("1"))
        assertEquals(setOf(".stamp"), bin.list()!!.toSet())
    }

    @Test fun profileIsWrittenOnceAndNeverOverwritten() {
        val home = tmp.newFolder("home")
        LocalTools.writeProfileOnce(home)
        val f = File(home, ".profile")
        assertTrue(f.readText().contains("PS1="))
        f.writeText("my own")
        LocalTools.writeProfileOnce(home)
        assertEquals("my own", f.readText())
    }

    /**
     * The real x86_64 busybox (scripts/build-tools.sh) is a static bionic binary, which also runs on a Linux x86_64
     * host: link it like the app does and exec applets through the links. Skipped when the tools are not built.
     */
    @Test fun builtBusyboxAppletsExecThroughLinks() {
        val so = listOf("src/main/jniLibs/x86_64/libbusybox.so", "app/src/main/jniLibs/x86_64/libbusybox.so")
            .map(::File).firstOrNull { it.isFile }
        assumeTrue("busybox not built", so != null && System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64"))
        val lib = tmp.newFolder("native")
        Files.copy(so!!.toPath(), File(lib, "libbusybox.so").toPath())
        File(lib, "libbusybox.so").setExecutable(true)
        val bin = File(tmp.root, "bin")
        assertTrue(BinDir(bin, lib).sync("1"))
        val names = bin.list()!!.toSet()
        listOf("sh", "ls", "grep", "vi", "tar", "wget", "awk", "sed", "less", "clear", "reset").forEach { assertTrue("$it linked", it in names) }
        val b = bin.path   // absolute paths: the host's PATH would find the host's ls/grep
        val sh = LocalTools.run(listOf("$b/sh", "-c", "echo hello | $b/grep -c hell; $b/ls $b | $b/grep -x tar"))
        assertEquals(0, sh.exit)
        assertEquals(listOf("1", "tar"), sh.output.trim().lines())
        assertEquals(0, LocalTools.run(listOf(File(bin, "tar").path, "--help")).exit)
        assertTrue(LocalTools.run(listOf(File(bin, "busybox").path, "--help")).firstLine.startsWith("BusyBox v1.37.0"))
    }
}
