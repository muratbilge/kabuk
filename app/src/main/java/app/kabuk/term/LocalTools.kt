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
    val TOOLS = listOf("busybox", "rclone", "kabuk-open")
    /** More names for a tool (name -> tool); never taken by a busybox applet. kabuk-open hands URLs to [UrlOpener]. */
    val ALIASES = mapOf("xdg-open" to "kabuk-open", "open" to "kabuk-open", "termux-open-url" to "kabuk-open",
        // Termux app + Termux:API commands (term/KabukCommands.kt, TermuxApi.kt); not `am`, /system/bin/am stays
        // reachable in busybox's shell
        "termux-open" to "kabuk-open", "termux-wake-lock" to "kabuk-open", "termux-wake-unlock" to "kabuk-open",
        "termux-reload-settings" to "kabuk-open") +
        TermuxApi.NAMES.associateWith { "kabuk-open" }
    /**
     * busybox applets never linked because Android's own /system/bin version works for an app and busybox's does not:
     * busybox ping/traceroute want a raw socket (root), /system/bin/ping uses ICMP "ping sockets" and netd's DNS.
     * (Android 15 has ping, ping6 and traceroute6 in /system/bin but no IPv4 traceroute, so busybox keeps that one.)
     * With no link in bin, PATH finds the /system/bin one.
     */
    val PREFER_SYSTEM = setOf("ping", "ping6", "traceroute6")
    /** busybox is linked against bionic's libc.so at API 28 (glob/getrandom/syncfs); older Android can't load it. */
    const val BUSYBOX_MIN_SDK = 28
    /** Arguments that make each tool print its version (first output line is logged). */
    private val VERSION_ARGS = mapOf("busybox" to listOf("--help"), "rclone" to listOf("version"), "kabuk-open" to listOf("--version"))
    @Volatile private var tested = false

    /** What [LocalTab] needs to start its shell. */
    class Setup(val shell: String, val env: Array<String>, val home: File, val args: Array<String> = arrayOf("-sh"),
                val termux: Boolean = false)

    /**
     * Makes bin/home/tmp (bin is rebuilt only after an install or update) and returns the shell + environment:
     * Termux's login shell when [termux] and its packages are installed ([TermuxEnv]), else busybox.
     */
    fun prepare(ctx: Context, termux: Boolean = true): Setup {
        // canonical: filesDir is /data/user/0/<pkg>/files, a symlink path; the shell's getcwd() sees /data/data/...
        // and ash's \w only shows "~" when HOME is that same string
        val files = ctx.filesDir.canonicalFile
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        val bin = File(files, "bin")
        val home = homeDir(files).apply { mkdirs() }
        val tmp = File(ctx.cacheDir, "tmp").apply { mkdirs() }
        val open = File(files, "open")
        runCatching { UrlOpener.start(open) }.onFailure { KLog.w(TAG, "URL opener not started", it) }
        val busybox = runCatching {
            BinDir(bin, nativeDir).sync("${BuildConfig.VERSION_CODE} ${nativeDir.absolutePath} ${BinDir.LAYOUT}")
            File(bin, "busybox").takeIf { File(nativeDir, "libbusybox.so").isFile && Build.VERSION.SDK_INT >= BUSYBOX_MIN_SDK }
        }.onFailure { KLog.w(TAG, "bin dir setup failed", it) }.getOrNull()
        if (busybox != null) writeProfileOnce(home)
        val shell = if (busybox != null) File(bin, "sh").absolutePath else "/system/bin/sh"
        val env = arrayOf(
            "TERM=xterm-256color", "COLORTERM=truecolor", "LANG=en_US.UTF-8",
            "HOME=${home.absolutePath}", "TMPDIR=${tmp.absolutePath}", "PREFIX=${files.absolutePath}",
            "PATH=${bin.absolutePath}:/system/bin:/system/xbin", "SHELL=$shell",
            "ANDROID_DATA=/data", "ANDROID_ROOT=/system", "KABUK_OPEN_DIR=${open.absolutePath}",
            // rclone and other Go/CLI tools: no browser detection guesswork, xdg-open on PATH is the browser
            "BROWSER=xdg-open",
            "KABUK_VERSION=${BuildConfig.VERSION_NAME}", "KABUK_VERSION_CODE=${BuildConfig.VERSION_CODE}",
        )
        // with the tab's environment: rclone wants a HOME, and the test should see what the shell sees
        if (!tested) { tested = true; Thread({ selfTest(bin, nativeDir, env) }, "tools-selftest").start() }
        if (termux && TermuxEnv.supported(ctx) && TermuxEnv.installed(ctx)) {
            runCatching { return TermuxEnv.setup(ctx, open) }.onFailure { KLog.w("TERMUX", "Termux shell setup failed, using busybox", it) }
        }
        return Setup(shell, env, home)
    }

    /** HOME, as one canonical path, so the shell's cwd (which it reads back with getcwd) is the same string. */
    internal fun homeDir(filesDir: File): File = File(filesDir, "home").canonicalFile

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
    private fun selfTest(bin: File, nativeDir: File, env: Array<String>) {
        KLog.i(TAG, "abi=${Build.SUPPORTED_ABIS.firstOrNull()}, nativeLibraryDir=${nativeDir.absolutePath}, " +
            "bin=${bin.list()?.size ?: 0} links")
        for (tool in TOOLS) {
            if (!File(nativeDir, "lib$tool.so").isFile) { KLog.w(TAG, "$tool: missing (not in this APK)"); continue }
            val r = run(listOf(File(bin, tool).absolutePath) + VERSION_ARGS[tool].orEmpty(), env = env)
            if (r.exit == 0) KLog.i(TAG, "$tool: ok (${r.firstLine})") else KLog.w(TAG, "$tool: FAILED (${r.describe()})")
        }
    }

    internal class Result(val exit: Int, val output: String, val error: String? = null) {
        val firstLine get() = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        fun describe() = listOfNotNull("exit $exit", error, firstLine.takeIf { it.isNotEmpty() }).joinToString(" / ")
    }

    /** Runs a command with a 5 s limit; never throws. */
    internal fun run(cmd: List<String>, timeoutSec: Long = 5, env: Array<String>? = null): Result = try {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        env?.forEach { kv -> kv.indexOf('=').takeIf { it > 0 }?.let { pb.environment()[kv.substring(0, it)] = kv.substring(it + 1) } }
        val p = pb.start()
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
    /** extra names for a tool (name -> tool), linked when the tool is present; applets never take them */
    private val aliases: Map<String, String> = LocalTools.ALIASES,
    /** more names applets must never take (for tools Kabuk may add later) */
    private val reserved: Set<String> = emptySet(),
    /** applets left out so PATH finds Android's own program (/system/bin/ping) */
    private val preferSystem: Set<String> = LocalTools.PREFER_SYSTEM,
    /** busybox's applet names, read by running the freshly linked `busybox --list` */
    private val listApplets: (File) -> List<String> = { bb ->
        LocalTools.run(listOf(bb.absolutePath, "--list")).takeIf { it.exit == 0 }?.output?.lines().orEmpty()
    },
) {
    companion object {
        /**
         * Part of the stamp: bumped when what gets linked changes (2: [LocalTools.PREFER_SYSTEM] left out, 3: termux-*
         * names, 4: Termux:API names).
         */
        const val LAYOUT = 5
    }

    private val stampFile get() = File(bin, ".stamp")

    /** Returns true when the dir was (re)built. */
    fun sync(stamp: String): Boolean {
        if (bin.isDirectory && runCatching { stampFile.readText() }.getOrNull() == stamp) return false
        val fresh = File(bin.parentFile, "${bin.name}.new")
        SafeDelete.tree(fresh); fresh.mkdirs()
        val present = tools.filter { File(nativeDir, "lib$it.so").isFile }
        present.forEach { link(fresh, it, it) }
        aliases.filter { (name, tool) -> tool in present && name !in tools }.forEach { (name, tool) -> link(fresh, name, tool) }
        if ("busybox" in present) {
            val taken = tools.toSet() + reserved + aliases.keys + preferSystem
            listApplets(File(fresh, "busybox")).map(String::trim)
                .filter { it.isNotEmpty() && '/' !in it && it !in taken && it != "." && it != ".." }
                .forEach { link(fresh, it, "busybox") }
        }
        File(fresh, ".stamp").writeText(stamp)
        SafeDelete.tree(bin)
        if (!fresh.renameTo(bin)) error("cannot move $fresh to $bin")
        return true
    }

    private fun link(dir: File, name: String, tool: String) {
        Files.createSymbolicLink(File(dir, name).toPath(), File(nativeDir, "lib$tool.so").toPath())
    }
}
