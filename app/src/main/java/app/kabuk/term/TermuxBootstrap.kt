package app.kabuk.term

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Byte rewriting of Termux's prefix (pure, unit-tested). Termux builds every package for
 * `/data/data/com.termux/files/usr`; `com.termux` and `app.kabuk/` have the same length, so a binary file is rewritten
 * in place (`/data/data/app.kabuk//files/usr` is a valid path). A text file (no NUL byte) gets the clean
 * `data/data/app.kabuk`, so scripts, dpkg's database and symlinks carry normal paths.
 */
internal object Relocator {
    private val FROM = "data/data/com.termux".toByteArray()
    /** same length: for binaries (ELF strings, RUNPATH, pyc) */
    private val TO_BIN = "data/data/app.kabuk/".toByteArray()
    /** shorter, clean: for text (scripts, dpkg database, configs) */
    private val TO_TEXT = "data/data/app.kabuk".toByteArray()

    fun isBinary(b: ByteArray) = b.any { it == 0.toByte() }

    /** The rewritten bytes, or null when [b] has no Termux prefix. */
    fun rewrite(b: ByteArray): ByteArray? {
        var i = indexOf(b, FROM, 0)
        if (i < 0) return null
        val to = if (isBinary(b)) TO_BIN else TO_TEXT
        val out = java.io.ByteArrayOutputStream(b.size)
        var from = 0
        while (i >= 0) {
            out.write(b, from, i - from); out.write(to)
            from = i + FROM.size
            i = indexOf(b, FROM, from)
        }
        out.write(b, from, b.size - from)
        return out.toByteArray()
    }

    fun rewritePath(s: String) = s.replace("data/data/com.termux", "data/data/app.kabuk")

    private fun indexOf(h: ByteArray, n: ByteArray, start: Int): Int {
        val first = n[0]
        var i = start
        val last = h.size - n.size
        outer@ while (i <= last) {
            if (h[i] != first) { i++; continue }
            for (j in 1 until n.size) if (h[i + j] != n[j]) { i++; continue@outer }
            return i
        }
        return -1
    }
}

/** File operations the extractor needs; Android uses android.system.Os, the JVM tests java.nio. */
internal interface FsOps {
    fun chmod(f: File, mode: Int)
    fun symlink(target: String, link: File)

    object Nio : FsOps {
        override fun chmod(f: File, mode: Int) {
            val s = StringBuilder()
            for (shift in intArrayOf(6, 3, 0)) {
                val bits = (mode shr shift) and 7
                s.append(if (bits and 4 != 0) 'r' else '-').append(if (bits and 2 != 0) 'w' else '-').append(if (bits and 1 != 0) 'x' else '-')
            }
            Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString(s.toString()))
        }
        override fun symlink(target: String, link: File) { Files.createSymbolicLink(link.toPath(), File(target).toPath()) }
    }
}

/** Extracting Termux's bootstrap zip the way the Termux app does, with [Relocator] applied, entry by entry. */
internal object Bootstrap {
    class Stats { var files = 0; var rewritten = 0; var binary = 0; var links = 0; var bytes = 0L }

    /** Thrown when [extract]'s cancel check says stop. */
    class Cancelled : Exception("cancelled")

    /** Number of entries [extract] will report progress against (from the zip's central directory). */
    fun entryCount(zip: File): Int = ZipFile(zip).use { it.size() }

    /**
     * Extracts [zip] into [into] (a staging dir): every file relocated, SYMLINKS.txt turned into relocated symlinks,
     * programs 0700 like the Termux app does. [progress] gets (entries done, total); [cancelled] is polled per entry.
     */
    fun extract(
        zip: File, into: File, fs: FsOps,
        progress: (Int, Int) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false },
    ): Stats {
        val st = Stats()
        val links = mutableListOf<Pair<String, String>>()
        val root = into.canonicalFile
        ZipFile(zip).use { z ->
            val total = z.size()
            var done = 0
            for (e in z.entries()) {
                if (cancelled()) throw Cancelled()
                val name = e.name
                require(!name.split('/').contains("..") && !name.startsWith("/")) { "bad entry in the bootstrap" }
                done++
                if (name == "SYMLINKS.txt") {
                    String(z.getInputStream(e).use { it.readBytes() }).lineSequence().filter { it.isNotBlank() }.forEach { line ->
                        val p = line.split('←')
                        require(p.size == 2) { "bad symlink line in the bootstrap" }
                        links += Relocator.rewritePath(p[0]) to p[1]
                    }
                    progress(done, total)
                    continue
                }
                val f = File(root, name)
                if (e.isDirectory) { f.mkdirs(); progress(done, total); continue }
                f.parentFile!!.mkdirs()
                val bytes = z.getInputStream(e).use { it.readBytes() }
                val out = Relocator.rewrite(bytes)
                if (out != null) { st.rewritten++; if (Relocator.isBinary(bytes)) st.binary++ }
                f.writeBytes(out ?: bytes)
                st.files++; st.bytes += bytes.size
                // the Termux app's rule: programs and apt's helpers are 0700, everything else keeps the default
                if (isProgram(name)) fs.chmod(f, "700".toInt(8))
                progress(done, total)
            }
        }
        for ((target, link) in links) {
            if (cancelled()) throw Cancelled()
            require(!link.split('/').contains("..") && !link.startsWith("/")) { "bad symlink in the bootstrap" }
            val f = File(root, link.removePrefix("./"))
            f.parentFile!!.mkdirs()
            fs.symlink(target, f)
            st.links++
        }
        return st
    }

    fun isProgram(name: String) = name.startsWith("bin/") || name.startsWith("libexec") ||
        name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods")

    /**
     * dpkg keeps an MD5 for every conffile (status) and file (info/<pkg>.md5sums). Relocated files no longer match:
     * dpkg would then treat each relocated conffile as "modified by the user" and ask on every upgrade.
     * Recomputes both from the relocated files. Returns how many checksums changed.
     */
    fun fixDpkgChecksums(prefix: File): Int {
        var changed = 0
        fun local(path: String): File? {
            // paths in dpkg's db are absolute (relocated): map /data/data/<pkg>/files/usr/... into the staging dir
            val marker = "/files/usr/"
            val i = path.indexOf(marker)
            return if (i < 0) null else File(prefix, path.substring(i + marker.length))
        }
        val status = File(prefix, "var/lib/dpkg/status")
        if (status.isFile) {
            val conffile = Regex("""^ (/\S+) ([0-9a-f]{32})(.*)$""")
            val lines = status.readLines().map { line ->
                val m = conffile.find(line) ?: return@map line
                val f = local(m.groupValues[1])?.takeIf { it.isFile } ?: return@map line
                val sum = md5(f)
                if (sum != m.groupValues[2]) changed++
                " ${m.groupValues[1]} $sum${m.groupValues[3]}"
            }
            status.writeText(lines.joinToString("\n", postfix = "\n"))
        }
        val entry = Regex("""^([0-9a-f]{32})  (.+)$""")
        File(prefix, "var/lib/dpkg/info").listFiles { f -> f.name.endsWith(".md5sums") }?.forEach { sums ->
            val out = sums.readLines().map { line ->
                val m = entry.find(line) ?: return@map line
                val f = local("/" + m.groupValues[2])?.takeIf { it.isFile } ?: return@map line
                val sum = md5(f)
                if (sum != m.groupValues[1]) changed++
                "$sum  ${m.groupValues[2]}"
            }
            sums.writeText(out.joinToString("\n", postfix = "\n"))
        }
        return changed
    }

    fun md5(f: File): String = digest("MD5", f)
    fun sha256(f: File): String = digest("SHA-256", f)

    private fun digest(alg: String, f: File): String {
        val md = MessageDigest.getInstance(alg)
        f.inputStream().use { inp ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
