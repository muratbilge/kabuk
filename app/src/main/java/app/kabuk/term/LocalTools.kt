package app.kabuk.term

import android.content.Context
import android.os.Build
import app.kabuk.BuildConfig
import app.kabuk.log.KLog
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The Phone tab's own programs. They ship as `lib<name>.so` in the APK's jniLibs, so the installer extracts them to
 * nativeLibraryDir: since Android 10 (W^X) that is the only place a targetSdk >= 29 app may exec its own files.
 * `filesDir/bin` holds symlinks to them (busybox: one per applet), so the exec'd inode always stays in
 * nativeLibraryDir and the shell finds the tools on PATH. See docs/LOCAL-TOOLS.md.
 */
object LocalTools {
    private const val TAG = "TOOLS"
    /** Programs Kabuk ships, in jniLibs as lib<name>.so; a later one keeps its name over a busybox applet. */
    val TOOLS = listOf("busybox")
    /** Arguments that make each tool print its version (first output line is logged). */
    private val VERSION_ARGS = mapOf("busybox" to listOf("--help"))
    @Volatile private var tested = false

    /** What [LocalTab] needs to start its shell. */
    class Setup(val shell: String, val env: Array<String>, val home: File)

    /** Makes bin/home/tmp (bin is rebuilt only after an install or update) and returns the shell + environment. */
    fun prepare(ctx: Context): Setup {
        val files = ctx.filesDir
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        val bin = File(files, "bin")
        val home = File(files, "home").apply { mkdirs() }
        val tmp = File(ctx.cacheDir, "tmp").apply { mkdirs() }
        val busybox = runCatching {
            BinDir(bin, nativeDir).sync("${BuildConfig.VERSION_CODE} ${nativeDir.absolutePath}")
            File(bin, "busybox").takeIf { File(nativeDir, "libbusybox.so").isFile }
        }.onFailure { KLog.w(TAG, "bin dir setup failed", it) }.getOrNull()
        if (busybox != null) writeProfileOnce(home)
        if (!tested) { tested = true; Thread({ selfTest(bin, nativeDir) }, "tools-selftest").start() }
        val shell = if (busybox != null) File(bin, "sh").absolutePath else "/system/bin/sh"
        val env = arrayOf(
            "TERM=xterm-256color", "COLORTERM=truecolor", "LANG=en_US.UTF-8",
            "HOME=${home.absolutePath}", "TMPDIR=${tmp.absolutePath}", "PREFIX=${files.absolutePath}",
            "PATH=${bin.absolutePath}:/system/bin:/system/xbin", "SHELL=$shell",
            "ANDROID_DATA=/data", "ANDROID_ROOT=/system",
        )
        return Setup(shell, env, home)
    }

    /** A starting .profile for busybox ash; the user's own edits win, so it is never rewritten. */
    internal fun writeProfileOnce(home: File) {
        val f = File(home, ".profile")
        if (f.exists()) return
        f.writeText(
            """
            |# Kabuk Phone tab (busybox ash). Kabuk made this file once and never changes it again: edit freely.
            |PS1='\w \$ '
            |alias ll='ls -l'
            |alias la='ls -la'
            |""".trimMargin()
        )
    }

    /** One live-log line per tool: proves exec from nativeLibraryDir works on this phone (paths and versions only). */
    private fun selfTest(bin: File, nativeDir: File) {
        KLog.i(TAG, "abi=${Build.SUPPORTED_ABIS.firstOrNull()}, nativeLibraryDir=${nativeDir.absolutePath}, " +
            "bin=${bin.list()?.size ?: 0} links")
        for (tool in TOOLS) {
            if (!File(nativeDir, "lib$tool.so").isFile) { KLog.w(TAG, "$tool: missing (not in this APK)"); continue }
            val r = run(listOf(File(bin, tool).absolutePath) + VERSION_ARGS[tool].orEmpty())
            if (r.exit == 0) KLog.i(TAG, "$tool: ok (${r.firstLine})") else KLog.w(TAG, "$tool: FAILED (${r.describe()})")
        }
    }

    internal class Result(val exit: Int, val output: String, val error: String? = null) {
        val firstLine get() = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        fun describe() = listOfNotNull("exit $exit", error, firstLine.takeIf { it.isNotEmpty() }).joinToString(" / ")
    }

    /** Runs a command with a 5 s limit; never throws. */
    internal fun run(cmd: List<String>, timeoutSec: Long = 5): Result = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = StringBuilder()
        val reader = Thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }.apply { start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly(); Result(-1, "", "timeout after ${timeoutSec}s")
        } else {
            reader.join(1000); Result(p.exitValue(), out.toString())
        }
    } catch (e: Exception) {
        Result(-1, "", e.message ?: e.javaClass.simpleName)
    }
}

/**
 * `filesDir/bin`: symlinks `<name> -> <nativeDir>/lib<tool>.so`. nativeLibraryDir moves on every install/update, so
 * the dir is rebuilt whenever [sync]'s stamp (versionCode + nativeLibraryDir) changes, into a fresh dir that then
 * replaces the old one.
 */
internal class BinDir(
    private val bin: File,
    private val nativeDir: File,
    private val tools: List<String> = LocalTools.TOOLS,
    /** names Kabuk itself puts into bin later (helper scripts); applets never take them */
    private val reserved: Set<String> = emptySet(),
    /** busybox's applet names, read by running the freshly linked `busybox --list` */
    private val listApplets: (File) -> List<String> = { bb ->
        LocalTools.run(listOf(bb.absolutePath, "--list")).takeIf { it.exit == 0 }?.output?.lines().orEmpty()
    },
) {
    private val stampFile get() = File(bin, ".stamp")

    /** Returns true when the dir was (re)built. */
    fun sync(stamp: String): Boolean {
        if (bin.isDirectory && runCatching { stampFile.readText() }.getOrNull() == stamp) return false
        val fresh = File(bin.parentFile, "${bin.name}.new")
        fresh.deleteRecursively(); fresh.mkdirs()
        val present = tools.filter { File(nativeDir, "lib$it.so").isFile }
        present.forEach { link(fresh, it, it) }
        if ("busybox" in present) {
            val taken = tools.toSet() + reserved
            listApplets(File(fresh, "busybox")).map(String::trim)
                .filter { it.isNotEmpty() && '/' !in it && it !in taken && it != "." && it != ".." }
                .forEach { link(fresh, it, "busybox") }
        }
        File(fresh, ".stamp").writeText(stamp)
        bin.deleteRecursively()
        if (!fresh.renameTo(bin)) error("cannot move $fresh to $bin")
        return true
    }

    private fun link(dir: File, name: String, tool: String) {
        Files.createSymbolicLink(File(dir, name).toPath(), File(nativeDir, "lib$tool.so").toPath())
    }
}
