package app.kabuk.term

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.kabuk.log.KLog
import app.kabuk.service.SessionService
import java.io.File

/**
 * The Termux app's commands inside Kabuk: `am`/`termux-am`, `termux-open` (and `xdg-open <file>`),
 * `termux-wake-lock`/`termux-wake-unlock`, `termux-reload-settings`, and Termux:API's ([TermuxApi]). They are names
 * of libkabuk-open.so (scripts/tools/kabuk-open.c), which drops a request into files/open; [UrlOpener]'s watcher
 * hands it here and this writes the answer the program prints. A command that reads its input asks for it first
 * ([Action.NeedStdin]: the program then hands its stdin over as `<base>.in`, [handleStdin]).
 * [parse] is pure (KabukCommandsTest, TermuxApiTest); [execute] does it on the main thread.
 * Logs (tag CMD): the command and the intent's action / the URI's scheme + host, never full URIs, paths or extras.
 */
object KabukCommands {
    private const val TAG = "CMD"
    private const val MAX_REQ = 65536
    /** what kabuk-open hands over at most from stdin (MAX_STDIN in kabuk-open.c) */
    private const val MAX_STDIN = 1 shl 20

    /** The names [parse] answers (each is a link to libkabuk-open.so in files/kabuk/bin). */
    val NAMES = listOf("am", "termux-am", "termux-open", "termux-wake-lock", "termux-wake-unlock", "termux-reload-settings") +
        TermuxApi.NAMES

    /** What a request asks for. */
    sealed class Action {
        data class WakeLock(val on: Boolean) : Action()
        /** termux-open: [target] is a URL (with a scheme) or an absolute file path */
        data class Open(val target: String, val send: Boolean = false, val chooser: Boolean = false, val type: String? = null) : Action()
        data object ReloadSettings : Action()
        data class Start(val intent: IntentSpec) : Action()
        data class Broadcast(val intent: IntentSpec) : Action()
        /** print [text], exit [code] (help, version, errors) */
        data class Reply(val code: Int, val text: String) : Action()
        /** a Termux:API command ([TermuxApi]) */
        data class Api(val req: TermuxApi.Req) : Action()
        /** the command needs the program's stdin (only when it isn't a terminal: [onlyIfPiped]); parse again with it */
        data class NeedStdin(val onlyIfPiped: Boolean) : Action()
    }

    /** An intent as `am` describes it. */
    data class IntentSpec(
        val action: String? = null, val data: String? = null, val type: String? = null,
        val pkg: String? = null, val cls: String? = null, val categories: List<String> = emptyList(),
        val flags: Int = 0, val extras: List<Extra> = emptyList(),
    )

    data class Extra(val key: String, val value: Any?)

    /** The answer written back: exit code + text. */
    data class Answer(val code: Int, val text: String)

    private const val NOT_IN_KABUK = "is not supported in Kabuk"
    private const val AM_USAGE = """usage: am start [options] [URI|COMPONENT]
       am broadcast [options] [URI|COMPONENT]
       am startservice -a com.termux.service_wake_lock|com.termux.service_wake_unlock
Kabuk supports this subset of Android's am (it has no app_process access):
  -a ACTION  -d DATA_URI  -t MIME_TYPE  -c CATEGORY  -n PACKAGE/CLASS  -f FLAGS
  --es|-e KEY VALUE  --esn KEY  --ez KEY true|false  --ei KEY INT  --el KEY LONG  --ef KEY FLOAT
  --eu KEY URI  --esa KEY A,B  --user 0|current  --grant-read-uri-permission  -W (ignored)
"""
    private const val OPEN_USAGE = """Usage: termux-open [options] path-or-url
Open a file or URL in an external app.
  --send               if the file should be shared for sending
  --view               if the file should be shared for viewing (default)
  --chooser            if an app chooser should always be shown
  --content-type type  specify the content type to use
"""

    /** A decoded request; [keepAlive]: the program understands `<base>.wait` keep-alives ("kabuk-req 2"). */
    data class Request(val cwd: String, val name: String, val args: List<String>, val keepAlive: Boolean)

    /** Request file bytes → [Request]; null when it isn't one. */
    internal fun decode(bytes: ByteArray): Request? {
        val parts = String(bytes, Charsets.UTF_8).split('\u0000')
        // NUL-terminated: the last split part is the empty tail
        if (parts.size < 4 || parts.last().isNotEmpty()) return null
        val keepAlive = when (parts[0]) { "kabuk-req 1" -> false; "kabuk-req 2" -> true; else -> return null }
        return Request(parts[1], parts[2], parts.subList(3, parts.size - 1), keepAlive)
    }

    /** What [name] with [args], run in [cwd], asks for; [stdin]: the program's input, once handed over. */
    fun parse(name: String, args: List<String>, cwd: String, stdin: String? = null): Action = when (name) {
        "termux-wake-lock", "termux-wake-unlock" ->
            if (args.isNotEmpty()) Action.Reply(1, "usage: $name\n")
            else Action.WakeLock(name == "termux-wake-lock")
        "termux-reload-settings" ->
            if (args.isNotEmpty()) Action.Reply(0, "usage: termux-reload-settings\nRe-reads ~/.termux/termux.properties (the settings Kabuk supports).\n")
            else Action.ReloadSettings
        "termux-open" -> parseOpen(args, cwd)
        "am", "termux-am" -> parseAm(name, args, cwd)
        else -> TermuxApi.parse(name, args, cwd, stdin) ?: Action.Reply(2, "kabuk: unknown command $name\n")
    }

    private fun parseOpen(args: List<String>, cwd: String): Action {
        var send = false; var chooser = false; var type: String? = null
        val rest = mutableListOf<String>()
        var i = 0
        var opts = true
        while (i < args.size) {
            val a = args[i++]
            when {
                !opts || !a.startsWith("-") || a == "-" -> rest += a
                a == "--" -> opts = false
                a == "--send" -> send = true
                a == "--view" -> send = false
                a == "--chooser" -> chooser = true
                a == "--content-type" -> type = args.getOrNull(i++) ?: return Action.Reply(1, "termux-open: --content-type needs a type\n")
                a.startsWith("--content-type=") -> type = a.substringAfter('=')
                a == "-h" || a == "--help" -> return Action.Reply(0, OPEN_USAGE)
                else -> return Action.Reply(1, "termux-open: unknown option $a\n$OPEN_USAGE")
            }
        }
        if (rest.size != 1) return Action.Reply(1, OPEN_USAGE)
        val t = rest[0]
        return Action.Open(if (hasScheme(t)) t else resolve(cwd, t), send, chooser, type)
    }

    /** `https:`, `mailto:` …; not a bare file name with a colon in it (`./a:b` has none in front of the colon). */
    internal fun hasScheme(s: String) = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(s) && !s.startsWith("/")

    internal fun resolve(cwd: String, path: String): String =
        (if (path.startsWith("/")) File(path) else File(cwd, path)).normalize().path

    private fun parseAm(name: String, args: List<String>, cwd: String): Action {
        if (args.isEmpty() || args[0] in setOf("-h", "--help", "help", "--am-help")) return Action.Reply(0, AM_USAGE)
        if (args[0] == "--version") return Action.Reply(0, "kabuk-am 1 (Kabuk's subset of am)\n")
        val sub = args[0]
        val kind = when (sub) {
            "start", "start-activity" -> "start"
            "broadcast" -> "broadcast"
            "startservice", "start-service", "start-foreground-service", "startforegroundservice" -> "service"
            else -> return Action.Reply(1, "$name: '$sub' $NOT_IN_KABUK (supported: start, broadcast, " +
                "startservice for the Termux wake lock)\n")
        }
        var action: String? = null; var data: String? = null; var type: String? = null
        var pkg: String? = null; var cls: String? = null
        val cats = mutableListOf<String>(); var flags = 0
        val extras = mutableListOf<Extra>()
        var i = 1
        fun need(opt: String, n: Int = 1): List<String>? =
            if (i + n <= args.size) args.subList(i, i + n).toList().also { i += n } else null
        fun missing(opt: String) = Action.Reply(1, "$name: $opt needs an argument\n")
        while (i < args.size) {
            val a = args[i++]
            when (a) {
                "-a" -> action = need(a)?.get(0) ?: return missing(a)
                "-d" -> data = need(a)?.get(0) ?: return missing(a)
                "-t" -> type = need(a)?.get(0) ?: return missing(a)
                "-c" -> cats += need(a)?.get(0) ?: return missing(a)
                "-n" -> {
                    val c = need(a)?.get(0) ?: return missing(a)
                    val comp = component(c) ?: return Action.Reply(1, "$name: bad component name: $c\n")
                    pkg = comp.first; cls = comp.second
                }
                "-f" -> {
                    val f = need(a)?.get(0) ?: return missing(a)
                    flags = parseInt(f) ?: return Action.Reply(1, "$name: bad flags: $f\n")
                }
                "-e", "--es" -> need(a, 2)?.let { extras += Extra(it[0], it[1]) } ?: return missing(a)
                "--esn" -> need(a)?.let { extras += Extra(it[0], null) } ?: return missing(a)
                "--ez" -> need(a, 2)?.let {
                    val b = when (it[1].lowercase()) { "true", "t", "1" -> true; "false", "f", "0" -> false; else -> return Action.Reply(1, "$name: bad boolean: ${it[1]}\n") }
                    extras += Extra(it[0], b)
                } ?: return missing(a)
                "--ei" -> need(a, 2)?.let { extras += Extra(it[0], parseInt(it[1]) ?: return Action.Reply(1, "$name: bad int: ${it[1]}\n")) } ?: return missing(a)
                "--el" -> need(a, 2)?.let { extras += Extra(it[0], it[1].toLongOrNull() ?: return Action.Reply(1, "$name: bad long: ${it[1]}\n")) } ?: return missing(a)
                "--ef" -> need(a, 2)?.let { extras += Extra(it[0], it[1].toFloatOrNull() ?: return Action.Reply(1, "$name: bad float: ${it[1]}\n")) } ?: return missing(a)
                "--eu" -> need(a, 2)?.let { extras += Extra(it[0], Uri.parse(it[1])) } ?: return missing(a)
                "--esa" -> need(a, 2)?.let { extras += Extra(it[0], it[1].split(',').toTypedArray()) } ?: return missing(a)
                "--user" -> {
                    val u = need(a)?.get(0) ?: return missing(a)
                    if (u !in setOf("0", "current", "all", "-2", "-3")) return Action.Reply(1, "$name: --user $u $NOT_IN_KABUK (only this user)\n")
                }
                "--grant-read-uri-permission" -> flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                "--grant-write-uri-permission" -> flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                "--include-stopped-packages" -> flags = flags or Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                "-W", "--activity-new-task", "--receiver-foreground" -> {}
                else -> {
                    if (a.startsWith("-")) return Action.Reply(1, "$name: option $a $NOT_IN_KABUK\n$AM_USAGE")
                    if (i < args.size) return Action.Reply(1, "$name: unexpected argument after $a\n")
                    // the trailing target: a component, a URI, or a package
                    val comp = if (':' !in a) component(a) else null
                    when {
                        comp != null -> { pkg = comp.first; cls = comp.second }
                        ':' in a -> data = a
                        else -> pkg = a
                    }
                }
            }
        }
        val spec = IntentSpec(action, data, type, pkg, cls, cats, flags, extras)
        return mapAm(name, kind, spec, cwd)
    }

    /** Termux's own intents (what termux-tools' scripts send) become Kabuk's; the rest is a plain intent. */
    private fun mapAm(name: String, kind: String, s: IntentSpec, cwd: String): Action {
        val termux = s.pkg == "com.termux"
        if (kind == "service") return when {
            s.action == "com.termux.service_wake_lock" && (s.pkg == null || termux) -> Action.WakeLock(true)
            s.action == "com.termux.service_wake_unlock" && (s.pkg == null || termux) -> Action.WakeLock(false)
            else -> Action.Reply(1, "$name: starting services $NOT_IN_KABUK (only the Termux wake lock: termux-wake-lock)\n")
        }
        if (kind == "broadcast" && termux && s.cls == "com.termux.app.TermuxOpenReceiver") {
            val d = s.data ?: return Action.Reply(1, "$name: termux-open needs -d\n")
            val chooser = s.extras.any { it.key == "chooser" && it.value == true }
            val type = s.extras.firstOrNull { it.key == "content-type" }?.value as? String
            return Action.Open(if (hasScheme(d)) d else resolve(cwd, d), s.action == Intent.ACTION_SEND, chooser, type)
        }
        if (kind == "broadcast" && s.action == "com.termux.app.reload_style") return Action.ReloadSettings
        if (s.pkg?.startsWith("com.termux") == true) {
            val what = if (s.pkg.startsWith("com.termux.api")) "Termux:API" else "the Termux app (${s.pkg})"
            return Action.Reply(1, "$name: this intent is for $what; that $NOT_IN_KABUK\n")
        }
        if (kind == "start") {
            if (s.action == null && s.data == null && s.pkg == null) return Action.Reply(1, "$name: start needs -a, -d or -n\n")
            // plain "open this web page": the same way as xdg-open (works from the background too)
            if ((s.action == null || s.action == Intent.ACTION_VIEW) && s.pkg == null && s.type == null && s.extras.isEmpty() &&
                s.categories.isEmpty() && s.data != null && UrlOpener.accept(s.data) != null) return Action.Open(s.data)
            return Action.Start(s)
        }
        if (s.action == null && s.pkg == null) return Action.Reply(1, "$name: broadcast needs -a or -n\n")
        return Action.Broadcast(s)
    }

    /** `pkg/cls` or `pkg/.Cls` → (pkg, full class) */
    internal fun component(s: String): Pair<String, String>? {
        val slash = s.indexOf('/')
        if (slash <= 0 || slash == s.length - 1) return null
        val pkg = s.substring(0, slash)
        val c = s.substring(slash + 1)
        return pkg to (if (c.startsWith(".")) pkg + c else c)
    }

    private fun parseInt(s: String): Int? =
        if (s.startsWith("0x", true)) s.substring(2).toLongOrNull(16)?.toInt() else s.toIntOrNull()

    // ---- on the phone ----

    /** Requests waiting for their stdin (base name → request), dropped after an hour (the program may wait on a tty). */
    private class Pending(val req: Request, val at: Long)
    private val pending = HashMap<String, Pending>()

    /** From the watcher thread: read + delete the request, run it on the main thread, write the answer. */
    fun handle(dir: File, f: File) {
        val bytes = runCatching { if (f.length() > MAX_REQ) null else f.readBytes() }.getOrNull()
        f.delete()
        val base = f.name.removeSuffix(".req")
        val req = bytes?.let(::decode)
        if (req == null) { answer(dir, base, Answer(2, "kabuk: unreadable request\n")); return }
        TabManager.main.post { run(dir, base, req, null) }
    }

    /** From the watcher thread: the stdin a request asked for (`<base>.in`). */
    fun handleStdin(dir: File, f: File) {
        val bytes = runCatching { if (f.length() > MAX_STDIN) null else f.readBytes() }.getOrNull()
        f.delete()
        val base = f.name.removeSuffix(".in")
        TabManager.main.post {
            val p = pending.remove(base)
            when {
                p == null -> answerLater(dir, base, Answer(2, "kabuk: input for an unknown request\n"))
                bytes == null -> answerLater(dir, base, Answer(1, "${p.req.name}: input too long or unreadable\n"))
                else -> run(dir, base, p.req, String(bytes, Charsets.UTF_8))
            }
        }
    }

    /** Main thread. */
    private fun run(dir: File, base: String, req: Request, stdin: String?) {
        val (cwd, name, args) = req
        val action = runCatching { parse(name, args, cwd, stdin) }
            .getOrElse { e -> KLog.w(TAG, "$name: parse failed", e); Action.Reply(1, "$name: ${e.javaClass.simpleName}\n") }
        if (action is Action.NeedStdin) {
            val now = System.currentTimeMillis()
            pending.entries.removeAll { now - it.value.at > 3_600_000 }
            if (pending.size >= 64) pending.remove(pending.minByOrNull { it.value.at }!!.key)
            pending[base] = Pending(req, now)
            answerLater(dir, base, null, if (action.onlyIfPiped) "stdin-if-piped" else "stdin")
            return
        }
        val call = Call(dir, base, name)
        if (req.keepAlive) call.keepAlive()
        val reply = { a: Answer -> call.answer(a) }
        val failed = { e: Throwable -> KLog.w(TAG, "$name failed", e); Answer(1, "$name: ${e.javaClass.simpleName}: ${e.message}\n") }
        if (action is Action.Api) {
            val r = action.req
            // walking a tree can take a while: not on the main thread
            if (r is TermuxApi.Req.MediaScan) {
                Thread({ reply(runCatching { TermuxApi.mediaScan(TabManager.app, name, r) }.getOrElse(failed)) }, "cmd-media-scan").start()
                return
            }
            runCatching { TermuxApi.execute(TabManager.app, name, r, reply) }.onFailure { reply(failed(it)) }
            return
        }
        reply(runCatching { execute(TabManager.app, name, action) }.getOrElse(failed))
    }

    /** How often a running request gets a keep-alive (kabuk-open waits 10 s), and for how long at most. */
    private const val KEEP_ALIVE_MS = 3_000L
    private const val KEEP_ALIVE_MAX_MS = 3_600_000L
    private val ticker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cmd-keepalive").apply { isDaemon = true } }

    /**
     * One running request. With [keepAlive] it creates `<base>.wait` every [KEEP_ALIVE_MS] until it is answered, so
     * the program keeps waiting (speech until it is spoken, a long media scan). It stops when the program is gone
     * (Ctrl-C: kill(pid, 0) says ESRCH); the answer is then dropped instead of left behind in the dir. Answers once.
     */
    private class Call(val dir: File, val base: String, val name: String) {
        private var done = false
        private var gone = false
        private var tick: java.util.concurrent.ScheduledFuture<*>? = null
        private val wait get() = File(dir, "$base.wait")

        fun keepAlive() {
            val started = System.currentTimeMillis()
            val pid = pidOf(base)
            tick = ticker.scheduleWithFixedDelay({
                synchronized(this) {
                    if (done || gone) return@synchronized
                    when {
                        pid != null && !alive(pid) -> {
                            gone = true; wait.delete(); tick?.cancel(false)
                            KLog.i(TAG, "$name: the program ended before the answer (Ctrl-C?)")
                        }
                        System.currentTimeMillis() - started > KEEP_ALIVE_MAX_MS -> tick?.cancel(false)
                        else -> runCatching { wait.writeBytes(ByteArray(0)) }
                    }
                }
            }, KEEP_ALIVE_MS, KEEP_ALIVE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }

        fun answer(a: Answer) = Thread({
            synchronized(this) {
                if (done) return@synchronized
                done = true
                tick?.cancel(false)
                wait.delete()
                if (!gone) KabukCommands.answer(dir, base, a)
            }
        }, "cmd-answer").start()
    }

    /** The program's pid from the request's base name (`<ms>-<pid>`). */
    internal fun pidOf(base: String): Int? = base.substringAfterLast('-', "").toIntOrNull()?.takeIf { it > 0 }

    /**
     * kill(pid, 0): only ESRCH means the program is gone. Any other refusal counts as alive (a program started
     * through `adb shell run-as` runs in another SELinux domain: its /proc entry and signals are denied to the app).
     */
    private fun alive(pid: Int) = try {
        android.system.Os.kill(pid, 0); true
    } catch (e: android.system.ErrnoException) {
        e.errno != android.system.OsConstants.ESRCH
    }

    private fun answerLater(dir: File, base: String, a: Answer?, raw: String? = null) =
        Thread({ if (raw != null) write(dir, base, raw) else answer(dir, base, a!!) }, "cmd-answer").start()

    private fun answer(dir: File, base: String, a: Answer) = write(dir, base, "${a.code}\n${a.text}")

    private fun write(dir: File, base: String, text: String) {
        runCatching {
            val tmp = File(dir, ".$base.res.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(dir, "$base.res"))
        }.onFailure { KLog.w(TAG, "could not answer a command: ${it.javaClass.simpleName}") }
    }

    /** Main thread. ([Action.Api] and [Action.NeedStdin] are handled by [run].) */
    internal fun execute(ctx: Context, name: String, action: Action): Answer = when (action) {
        is Action.Api, is Action.NeedStdin -> error("handled by run")
        is Action.Reply -> {
            if (action.code != 0) KLog.i(TAG, "$name: refused or bad usage (exit ${action.code})")
            Answer(action.code, action.text)
        }
        is Action.WakeLock -> {
            SessionService.setWakeLock(ctx, action.on)
            KLog.i(TAG, "$name: wake lock ${if (action.on) "on" else "off"}")
            Answer(0, "")
        }
        Action.ReloadSettings -> {
            val r = TermuxProperties.reload(File(TermuxEnv.root(ctx), "home"))
            KLog.i(TAG, "$name: ${r.applied.size} applied, ${r.ignored.size} ignored")
            Answer(0, r.describe())
        }
        is Action.Open -> open(ctx, name, action)
        is Action.Start -> startActivity(ctx, name, intent(action.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), action.intent)
        is Action.Broadcast -> {
            ctx.sendBroadcast(intent(action.intent))
            KLog.i(TAG, "$name broadcast: ${describe(action.intent)}")
            Answer(0, "Broadcasting: ${describe(action.intent)}\nBroadcast sent\n")
        }
    }

    internal fun startActivity(ctx: Context, name: String, i: Intent, spec: IntentSpec?, label: String? = null): Answer {
        val what = spec?.let(::describe) ?: label.orEmpty()
        if (!TabManager.foreground) {
            // Android blocks activity starts from the background: offer it for when the user comes back
            KLog.i(TAG, "$name in the background: $what (offered)")
            TabManager.note("A program wants to open ${label ?: "an app"}", "Open") {
                runCatching { ctx.startActivity(i) }.onFailure { TabManager.toast("No app on this phone can do that") }
            }
            return Answer(0, if (spec != null) "Starting: $what (waits until you return to Kabuk)\n" else "")
        }
        return try {
            ctx.startActivity(i)
            KLog.i(TAG, "$name: $what")
            Answer(0, if (spec != null) "Starting: $what\n" else "")
        } catch (e: ActivityNotFoundException) {
            KLog.w(TAG, "$name: no activity for $what")
            Answer(1, "$name: no app on this phone handles this${if (spec != null) " ($what)" else ""}\n")
        } catch (e: SecurityException) {
            KLog.w(TAG, "$name: not allowed: $what")
            Answer(1, "$name: Android does not allow Kabuk to start this (${e.message})\n")
        }
    }

    /** "Intent { act=… dat=scheme://host… cmp=… }" with the data cut to scheme + host (for the log and the answer) */
    internal fun describe(s: IntentSpec) = listOfNotNull(
        s.action?.let { "act=$it" }, s.data?.let { "dat=" + safeUri(it) }, s.type?.let { "typ=$it" },
        s.pkg?.let { p -> "cmp=$p" + (s.cls?.let { "/$it" } ?: "") },
        s.categories.takeIf { it.isNotEmpty() }?.let { "cat=$it" }, s.extras.takeIf { it.isNotEmpty() }?.let { "(${it.size} extras)" },
    ).joinToString(" ", "Intent { ", " }")

    internal fun safeUri(u: String): String {
        val m = Regex("^([A-Za-z][A-Za-z0-9+.-]*):(//([^/?#]*))?").find(u) ?: return "…"
        val host = m.groupValues[3].substringAfterLast('@')
        return m.groupValues[1] + ":" + (if (m.groupValues[2].isNotEmpty()) "//$host" else "") + "…"
    }

    internal fun intent(s: IntentSpec): Intent {
        val i = Intent()
        s.action?.let { i.action = it }
        val uri = s.data?.let { Uri.parse(it) }
        when {
            uri != null && s.type != null -> i.setDataAndType(uri, s.type)
            uri != null -> i.data = uri
            s.type != null -> i.type = s.type
        }
        if (s.pkg != null) { if (s.cls != null) i.component = ComponentName(s.pkg, s.cls) else i.setPackage(s.pkg) }
        s.categories.forEach { i.addCategory(it) }
        i.addFlags(s.flags)
        for (e in s.extras) when (val v = e.value) {
            null -> i.putExtra(e.key, null as String?)
            is String -> i.putExtra(e.key, v)
            is Boolean -> i.putExtra(e.key, v)
            is Int -> i.putExtra(e.key, v)
            is Long -> i.putExtra(e.key, v)
            is Float -> i.putExtra(e.key, v)
            is Uri -> i.putExtra(e.key, v)
            is Array<*> -> i.putExtra(e.key, v.map { it.toString() }.toTypedArray())
        }
        return i
    }

    /** URL schemes termux-open may hand to other apps (no file:, content:, intent:, javascript:). */
    private val OPEN_SCHEMES = setOf("http", "https", "mailto", "tel", "sms", "geo", "market")

    private fun open(ctx: Context, name: String, a: Action.Open): Answer {
        if (hasScheme(a.target)) {
            val scheme = a.target.substringBefore(':').lowercase()
            if (scheme == "http" || scheme == "https") {
                val url = UrlOpener.accept(a.target) ?: return Answer(1, "$name: not a valid URL\n")
                if (!a.send && !a.chooser) { UrlOpener.open(url, fromProgram = true); return Answer(0, "") }
            } else if (scheme !in OPEN_SCHEMES) {
                return Answer(1, "$name: $scheme: URLs can't be opened from Kabuk (files, http(s), mailto, tel, sms, geo, market)\n")
            }
            val uri = Uri.parse(a.target)
            val i = if (a.send) Intent(Intent.ACTION_SEND).setType(a.type ?: "text/plain").putExtra(Intent.EXTRA_TEXT, a.target)
                else Intent(Intent.ACTION_VIEW, uri).also { if (a.type != null) it.setDataAndType(uri, a.type) }
            return startActivity(ctx, name, wrap(i, a.chooser), null, safeUri(a.target))
        }
        val f = File(a.target)
        if (!f.exists()) return Answer(1, "$name: ${a.target}: No such file or directory\n")
        if (f.isDirectory) return Answer(1, "$name: ${a.target}: is a directory (open a file)\n")
        if (!f.canRead()) return Answer(1, "$name: ${a.target}: Permission denied\n")
        val uri = contentUri(ctx, f) ?: return Answer(1, "$name: ${a.target}: only files in Kabuk's home/usr or on shared storage can be opened\n")
        val type = a.type ?: mimeType(f.name)
        val i = if (a.send) Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
            .also { it.clipData = ClipData.newRawUri("", uri) }
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        KLog.i(TAG, "$name: a file ($type, ${if (a.send) "send" else "view"})")
        return startActivity(ctx, name, wrap(i, a.chooser), null, "a file ($type)")
    }

    private fun wrap(i: Intent, chooser: Boolean): Intent =
        (if (chooser) Intent.createChooser(i, null).also { c -> c.addFlags(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) } else i)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    internal fun mimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "txt", "log", "sh", "py", "kt", "c", "h", "md", "conf", "cfg", "ini", "json", "yaml", "yml", "toml", "csv" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /**
     * A content:// URI from [FILES_AUTHORITY] (res/xml/file_paths.xml: filesDir and shared storage). A path under
     * /data/data/<pkg>/files is mapped onto filesDir first: on Android 15 /data/user/0 and /data/data are separate
     * mounts, so the provider's canonical-path check would refuse it as written.
     */
    internal fun contentUri(ctx: Context, f: File): Uri? {
        val canon = f.canonicalFile
        val dataFiles = "/data/data/${ctx.packageName}/files/"
        @Suppress("DEPRECATION") val shared = Environment.getExternalStorageDirectory().canonicalFile
        val file = when {
            canon.path.startsWith(dataFiles) -> File(ctx.filesDir, canon.path.removePrefix(dataFiles))
            canon.path.startsWith(ctx.filesDir.canonicalPath + "/") -> canon
            canon.path.startsWith(shared.path + "/") -> canon
            else -> return null
        }
        return runCatching { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file) }
            .onFailure { KLog.w(TAG, "no content URI: ${it.javaClass.simpleName}") }.getOrNull()
    }
}
