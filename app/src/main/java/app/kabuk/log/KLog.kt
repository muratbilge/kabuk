package app.kabuk.log

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import app.kabuk.BuildConfig
import org.json.JSONObject
import java.io.File

/**
 * Kabuk's logger. Every line goes to logcat (tag "Kabuk.<tag>") and, while Settings -> Diagnostics -> "Live log" is on,
 * is queued and shipped in batches to the DevBox log receiver (read there with scripts/livelog). Uncaught exceptions
 * are written to a file first and shipped on the next start. Never log passwords, keys or prompt text.
 *
 *   KLog.i("SSH", "connected to $host"); KLog.w("AGENT", "poll failed"); KLog.e("AGENT", "watcher died", throwable)
 */
object KLog {
    private val outbox = LogOutbox()
    private lateinit var prefs: SharedPreferences
    private var crashFile: File? = null
    @Volatile private var ready = false

    /** Settings -> Diagnostics switch; ON by default while the app is young. */
    var liveLog: Boolean
        get() = !ready || prefs.getBoolean(KEY, true)
        set(v) { if (ready) prefs.edit().putBoolean(KEY, v).apply(); if (v) i("LOG", "live log on") }

    fun i(tag: String, msg: String) = log('I', tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log('W', tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log('E', tag, msg, t)

    fun init(ctx: Context) {
        if (ready) return
        prefs = ctx.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        crashFile = File(ctx.filesDir, "crash.json")
        ready = true
        installCrashHandler()
        i("APP", "=== Kabuk ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}")
        sendPendingCrash()
        startShipper()
    }

    private fun log(level: Char, tag: String, msg: String, t: Throwable?) {
        when (level) {
            'E' -> Log.e("Kabuk.$tag", msg, t)
            'W' -> Log.w("Kabuk.$tag", msg, t)
            else -> Log.i("Kabuk.$tag", msg)
        }
        if (liveLog) outbox.append(line(System.currentTimeMillis(), level, tag, msg, t))
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                crashFile?.writeText(line(System.currentTimeMillis(), 'E', "CRASH", "uncaught in ${thread.name}", e))
            }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun sendPendingCrash() {
        val f = crashFile?.takeIf { it.isFile } ?: return
        runCatching { if (liveLog) outbox.append(f.readText().trim()) }
        f.delete()
    }

    private fun startShipper() {
        val shipper = LogShipper(
            BuildConfig.LOGSINK_URL, BuildConfig.LOGSINK_TOKEN,
            Build.MODEL.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(40).ifEmpty { "phone" }, outbox, { liveLog },
        )
        Thread({
            while (true) {
                val wait = when (runCatching { shipper.shipOnce() }.getOrDefault(ShipResult.OFFLINE)) {
                    ShipResult.SENT -> 0L
                    ShipResult.NOTHING, ShipResult.DISABLED -> 5_000L
                    ShipResult.OFFLINE, ShipResult.REJECTED -> 60_000L
                }
                if (wait > 0) Thread.sleep(wait)
            }
        }, "kabuk-livelog").apply { isDaemon = true }.start()
    }

    /** One JSON record per line; control characters and length are cleaned so a line is always one line. */
    internal fun line(t: Long, level: Char, tag: String, msg: String, err: Throwable?): String =
        JSONObject().apply {
            put("t", t); put("l", level.toString()); put("tag", tag); put("msg", clean(msg, 400))
            if (err != null) put("stack", clean(err.stackTraceToString(), 6000, keepNewlines = true))
        }.toString()

    private fun clean(s: String, max: Int, keepNewlines: Boolean = false) =
        s.map { if (it.isISOControl() && !(keepNewlines && it == '\n')) ' ' else it }.joinToString("").take(max)

    private const val KEY = "live_log_enabled"
}
