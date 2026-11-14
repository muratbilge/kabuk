package app.kabuk

import app.kabuk.term.Bootstrap
import app.kabuk.term.FsOps
import app.kabuk.term.TermuxEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The bootstrap extractor, dpkg checksum fix and the apt hook against small synthetic inputs. */
class TermuxBootstrapTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(entries: Map<String, ByteArray>): File {
        val f = tmp.newFile("bootstrap.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
        }
        return f
    }

    private val elf = "\u007fELF\u0000/data/data/com.termux/files/usr/lib\u0000".toByteArray()

    @Test fun extractRelocatesFilesLinksAndModes() {
        val z = zip(linkedMapOf(
            "bin/bash" to elf,
            "etc/profile" to "PATH=/data/data/com.termux/files/usr/bin\n".toByteArray(),
            "share/doc/x" to "no prefix here\n".toByteArray(),
            "SYMLINKS.txt" to ("bash←./bin/sh\n/data/data/com.termux/files/usr/share/k/a.gpg←./etc/apt/trusted.gpg.d/a.gpg\n").toByteArray(),
        ))
        val into = tmp.newFolder("staging")
        val seen = mutableListOf<Pair<Int, Int>>()
        val st = Bootstrap.extract(z, into, FsOps.Nio, progress = { d, t -> seen += d to t })
        assertEquals(3, st.files); assertEquals(2, st.rewritten); assertEquals(1, st.binary); assertEquals(2, st.links)
        val bash = File(into, "bin/bash").readBytes()
        assertEquals(elf.size, bash.size)
        assertEquals("\u007fELF\u0000/data/data/app.kabuk//files/usr/lib\u0000", String(bash))
        assertEquals("PATH=/data/data/app.kabuk/files/usr/bin\n", File(into, "etc/profile").readText())
        assertEquals("bash", Files.readSymbolicLink(File(into, "bin/sh").toPath()).toString())
        assertEquals("/data/data/app.kabuk/files/usr/share/k/a.gpg",
            Files.readSymbolicLink(File(into, "etc/apt/trusted.gpg.d/a.gpg").toPath()).toString())
        val perms = Files.getPosixFilePermissions(File(into, "bin/bash").toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE), perms)
        assertFalse(File(into, "etc/profile").canExecute())
        assertEquals(4 to 4, seen.last())
    }

    @Test fun extractStopsWhenCancelled() {
        val z = zip(linkedMapOf("a" to "1".toByteArray(), "b" to "2".toByteArray()))
        var n = 0
        val r = runCatching { Bootstrap.extract(z, tmp.newFolder("s"), FsOps.Nio, cancelled = { n++ >= 1 }) }
        assertTrue(r.exceptionOrNull() is Bootstrap.Cancelled)
    }

    @Test fun refusesPathTraversal() {
        val z = zip(linkedMapOf("../evil" to "x".toByteArray()))
        assertTrue(runCatching { Bootstrap.extract(z, tmp.newFolder("s"), FsOps.Nio) }.isFailure)
    }

    @Test fun conffileAndMd5sumsRecomputed() {
        val prefix = tmp.newFolder("usr")
        File(prefix, "etc").mkdirs()
        val conf = File(prefix, "etc/bash.bashrc").apply { writeText("relocated content\n") }
        val want = Bootstrap.md5(conf)
        File(prefix, "var/lib/dpkg/info").mkdirs()
        File(prefix, "var/lib/dpkg/status").writeText(
            "Package: bash\nConffiles:\n /data/data/app.kabuk/files/usr/etc/bash.bashrc 00000000000000000000000000000000\n" +
                " /data/data/app.kabuk/files/usr/etc/gone 11111111111111111111111111111111 obsolete\nVersion: 5\n"
        )
        File(prefix, "var/lib/dpkg/info/bash.md5sums").writeText(
            "00000000000000000000000000000000  data/data/app.kabuk/files/usr/etc/bash.bashrc\n"
        )
        assertEquals(2, Bootstrap.fixDpkgChecksums(prefix))
        val status = File(prefix, "var/lib/dpkg/status").readText()
        assertTrue(status, status.contains(" /data/data/app.kabuk/files/usr/etc/bash.bashrc $want\n"))
        assertTrue(status.contains(" /data/data/app.kabuk/files/usr/etc/gone 11111111111111111111111111111111 obsolete\n"))
        assertEquals("$want  data/data/app.kabuk/files/usr/etc/bash.bashrc\n", File(prefix, "var/lib/dpkg/info/bash.md5sums").readText())
        assertEquals(0, Bootstrap.fixDpkgChecksums(prefix))
    }

    @Test fun mirrorChoiceAndHookFiles() {
        val staging = tmp.newFolder("staging")
        File(staging, "etc/termux/mirrors/${TermuxEnv.MIRROR_GROUP}").mkdirs()
        TermuxEnv.writeMirrorChoice(staging, FsOps.Nio)
        assertEquals("mirrors/europe", Files.readSymbolicLink(File(staging, "etc/termux/chosen_mirrors").toPath()).toString())
        TermuxEnv.writeAptHook(staging, File("/data/data/app.kabuk/files/usr"), FsOps.Nio)
        val conf = File(staging, "etc/apt/apt.conf.d/99kabuk-relocate").readText()
        assertTrue(conf.contains("DPkg::Pre-Install-Pkgs { \"/data/data/app.kabuk/files/usr/libexec/kabuk/relocate-debs\"; };"))
        assertTrue(conf.contains("APT::Keep-Downloaded-Packages \"false\";"))
        assertTrue(File(staging, "libexec/kabuk/relocate-debs").canExecute())
        TermuxEnv.writeFirstUpdate(staging, File("/data/data/app.kabuk/files/usr"))
        val first = File(staging, "etc/profile.d/${TermuxEnv.FIRST_UPDATE}").readText()
        assertTrue(first.contains("pkg update"))
        assertTrue(first.contains("rm -f \"/data/data/app.kabuk/files/usr/etc/profile.d/zz-kabuk-first-update.sh\""))
    }

    /** The apt hook itself, run with the host's sh + dpkg-deb on a tiny Termux-style .deb (skipped without dpkg-deb). */
    @Test fun relocateDebsHook() {
        assumeTrue(File("/usr/bin/dpkg-deb").canExecute() && File("/bin/sh").canExecute())
        val root = tmp.newFolder("pkg")
        val usr = File(root, "data/data/com.termux/files/usr").apply { mkdirs() }
        File(usr, "bin").mkdirs()
        File(usr, "bin/tool").apply { writeBytes("\u007fELF\u0000/data/data/com.termux/files/usr/lib\u0000".toByteArray()); setExecutable(true, false) }
        File(usr, "bin/script").apply { writeText("#!/data/data/com.termux/files/usr/bin/sh\necho hi\n"); setExecutable(true, false) }
        Files.createSymbolicLink(File(usr, "bin/link").toPath(), File("/data/data/com.termux/files/usr/bin/tool").toPath())
        File(root, "DEBIAN").mkdirs()
        File(root, "DEBIAN/control").writeText("Package: t\nVersion: 1\nArchitecture: all\nMaintainer: x\nDescription: t\n")
        File(root, "DEBIAN/md5sums").writeText("x  y\n")
        File(root, "DEBIAN/postinst").apply { writeText("#!/data/data/com.termux/files/usr/bin/sh\nexit 0\n") }
        Files.setPosixFilePermissions(File(root, "DEBIAN/postinst").toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        val deb = File(tmp.root, "t_1_all.deb")
        assertEquals(0, sh(listOf("dpkg-deb", "--root-owner-group", "--nocheck", "-b", root.path, deb.path)))
        // the hook's shebang points at the phone's sh; run it with the host's sh and PATH instead
        val script = File(tmp.root, "relocate-debs").apply { writeText(TermuxEnv.relocateDebsScript(tmp.root.path)) }
        val p = ProcessBuilder("/bin/sh", script.path).redirectErrorStream(true)
        p.environment()["TMPDIR"] = tmp.root.path
        val proc = p.start()
        proc.outputStream.use { it.write("${deb.path}\n".toByteArray()) }
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS))
        assertEquals(out, 0, proc.exitValue())
        assertTrue(out, out.contains("kabuk: relocated t_1_all.deb"))
        val x = tmp.newFolder("x")
        assertEquals(0, sh(listOf("dpkg-deb", "-R", deb.path, x.path)))
        val newUsr = File(x, "data/data/app.kabuk/files/usr")
        assertFalse(File(x, "data/data/com.termux").exists())
        val tool = File(newUsr, "bin/tool").readBytes()
        assertEquals("\u007fELF\u0000/data/data/app.kabuk//files/usr/lib\u0000", String(tool))
        assertTrue(File(newUsr, "bin/tool").canExecute())
        assertEquals("#!/data/data/app.kabuk/files/usr/bin/sh\necho hi\n", File(newUsr, "bin/script").readText())
        assertTrue("sed must not reset the mode", File(newUsr, "bin/script").canExecute())
        assertEquals("/data/data/app.kabuk/files/usr/bin/tool", Files.readSymbolicLink(File(newUsr, "bin/link").toPath()).toString())
        val sums = File(x, "DEBIAN/md5sums").readText()
        assertTrue(sums, sums.contains("data/data/app.kabuk/files/usr/bin/script"))
        assertEquals("#!/data/data/app.kabuk/files/usr/bin/sh\nexit 0\n", File(x, "DEBIAN/postinst").readText())
        assertTrue(File(x, "DEBIAN/postinst").canExecute())
    }

    /** `dpkg -i x.deb` by hand: the wrapper relocates a copy with the real hook, then runs the (here: fake) dpkg. */
    @Test fun dpkgWrapperRelocatesDebsGivenByHand() {
        assumeTrue(File("/usr/bin/dpkg-deb").canExecute() && File("/bin/sh").canExecute())
        val files = tmp.newFolder("files")
        val usr = File(files, "usr")
        File(usr, "bin").mkdirs(); File(usr, "tmp").mkdirs(); File(usr, "libexec/kabuk").mkdirs()
        // the scripts' shebangs point at usr/bin/sh: the host's sh stands in
        Files.createSymbolicLink(File(usr, "bin/sh").toPath(), File("/bin/sh").toPath())
        File(usr, "libexec/kabuk/relocate-debs").apply { writeText(TermuxEnv.relocateDebsScript(usr.path)); setExecutable(true, true) }
        val log = File(tmp.root, "dpkg.log")
        File(usr, "bin/dpkg").apply {
            writeText("#!/bin/sh\necho \"args: $*\" >> ${log.path}\nfor a; do case \"${'$'}a\" in *.deb) dpkg-deb -c \"${'$'}a\" >> ${log.path};; esac; done\nexit 7\n")
            setExecutable(true, true)
        }
        val root = tmp.newFolder("pkg")
        File(root, "data/data/com.termux/files/usr/bin").mkdirs()
        File(root, "data/data/com.termux/files/usr/bin/hello").writeText("#!/data/data/com.termux/files/usr/bin/sh\necho hi\n")
        File(root, "DEBIAN").mkdirs()
        File(root, "DEBIAN/control").writeText("Package: hello\nVersion: 1\nArchitecture: all\nMaintainer: x\nDescription: t\n")
        val deb = File(tmp.root, "hello_1_all.deb")
        assertEquals(0, sh(listOf("dpkg-deb", "--root-owner-group", "--nocheck", "-b", root.path, deb.path)))
        val before = deb.readBytes()
        val wrapper = File(tmp.root, "dpkg-wrapper").apply { writeText(TermuxEnv.dpkgWrapper(files.path)) }
        val p = ProcessBuilder("/bin/sh", wrapper.path, "-i", deb.path).redirectErrorStream(true)
        p.environment()["TMPDIR"] = usr.path + "/tmp"
        val proc = p.start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS))
        assertEquals("the real dpkg's exit code: $out", 7, proc.exitValue())
        assertTrue(out, out.contains("kabuk: relocated 1-hello_1_all.deb"))
        val l = log.readText()
        assertTrue(l, l.contains("./data/data/app.kabuk/files/usr/bin/hello"))
        assertFalse(l, l.contains("com.termux"))
        assertTrue("the user's .deb stays as it was", before.contentEquals(deb.readBytes()))
        assertEquals("temp dir removed", 0, File(usr, "tmp").list()!!.size)
        // anything but an install goes straight through
        log.delete()
        val q = ProcessBuilder("/bin/sh", wrapper.path, "--compare-versions", "1", "lt", "2").redirectErrorStream(true).start()
        q.inputStream.readBytes()
        assertTrue(q.waitFor(30, TimeUnit.SECONDS))
        assertEquals("args: --compare-versions 1 lt 2\n", log.readText())
    }

    private fun sh(cmd: List<String>): Int {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        return p.waitFor()
    }
}
