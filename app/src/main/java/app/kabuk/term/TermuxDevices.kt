package app.kabuk.term

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import app.kabuk.log.KLog
import app.kabuk.term.KabukCommands.Action
import app.kabuk.term.KabukCommands.Answer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Termux:API's commands that need a runtime permission, a dialog on screen or streamed output: `termux-location`,
 * `termux-dialog`, `termux-camera-info`/`-photo`, `termux-sensor`, `termux-storage-get`, `termux-microphone-record`,
 * `termux-media-player`. Same channel as [TermuxApi] (names of libkabuk-open.so, requests through [KabukCommands]);
 * [parse] is pure (TermuxDevicesTest), [execute] runs on the main thread with the request's [Session].
 *
 * Permissions are asked by [ApiActivity] (transparent) only while Kabuk is on screen; the program waits through
 * kabuk-open's keep-alive and gets "permission denied", exit 1, when refused. In the background the command says so
 * instead of asking. Logs (tag CMD): the command name and the outcome only, never locations, dialog answers, file
 * paths or recorded content.
 */
object TermuxDevices {
    private const val TAG = "CMD"

    /** A running request as the command sees it ([KabukCommands]' Call). */
    interface Session {
        /** the program prints streamed output (kabuk-open 5); else [emit]'s first piece is the whole answer */
        val streaming: Boolean
        /** the program ended (Ctrl-C) before the answer */
        val gone: Boolean
        /** the final answer (once) */
        fun answer(a: Answer)
        /** one piece of streamed output; false: stop producing (the program is gone, or it was the only piece) */
        fun emit(text: String): Boolean
        /** [f] runs (on a background thread) when the program is found gone */
        fun onGone(f: () -> Unit)
    }

    val NAMES = listOf(
        "termux-location", "termux-dialog", "termux-camera-info", "termux-camera-photo", "termux-sensor",
        "termux-storage-get", "termux-microphone-record", "termux-media-player",
    )

    sealed class Req {
        /** [request]: once, last or updates; [intervalMs]: the time between updates */
        data class Location(val provider: String, val request: String, val intervalMs: Long) : Req()
        data class Dialog(val spec: DialogSpec) : Req()
        data object CameraInfo : Req()
        /** [camera] null: "0" like Termux:API, else (no camera 0) the first back camera */
        data class CameraPhoto(val camera: String?, val file: String) : Req()
        data object SensorList : Req()
        /** [names]: parts of sensor names (case ignored, the shortest match wins); [limit] 0 = until Ctrl-C */
        data class Sensors(val names: List<String>, val all: Boolean, val delayMs: Long, val limit: Int) : Req()
        data class StorageGet(val file: String) : Req()
        /** [limitMs] 0 = unlimited, null = the default (15 min) */
        data class MicRecord(val file: String?, val limitMs: Int?, val encoder: String?, val bitrate: Int?,
                             val sampleRate: Int?, val channels: Int?) : Req()
        data object MicInfo : Req()
        data object MicQuit : Req()
        /** [cmd]: play (with [file]: a new track, else resume), pause, stop, info */
        data class Player(val cmd: String, val file: String?) : Req()
    }

    /** termux-dialog: what to show. [range]: min, max, start (counter). */
    data class DialogSpec(
        val widget: String, val title: String? = null, val hint: String? = null, val multiLine: Boolean = false,
        val numeric: Boolean = false, val password: Boolean = false, val values: List<String> = emptyList(),
        val range: List<Int>? = null, val dateFormat: String? = null,
    )

    /** termux-dialog's answer: [code] -1 OK/Yes, -2 Cancel/No/dismissed, 0 a sheet item. */
    data class DialogResult(val code: Int, val text: String, val index: Int = -1,
                            val values: List<Pair<Int, String>> = emptyList(), val error: String? = null)

    // ---- parsing ----

    private fun opts(vararg o: Pair<Char, String>) = o.associate { (c, k) -> c to TermuxApi.Opt(k.removeSuffix(":"), k.endsWith(":")) }
    private fun usage(text: String) = Action.Reply(0, text)
    private fun bad(msg: String, usage: String) = Action.Reply(1, "$msg\n$usage")
    private fun dev(r: Req) = Action.Device(r)

    /** What [name] with [args] asks for; null when [name] isn't one of [NAMES]. */
    fun parse(name: String, args: List<String>, cwd: String): Action? = when (name) {
        "termux-location" -> parseLocation(name, args)
        "termux-dialog" -> parseDialog(name, args)
        "termux-camera-info" -> when {
            args == listOf("-h") -> usage(CAMERA_INFO_USAGE)
            args.isNotEmpty() -> bad("$name: no arguments expected", CAMERA_INFO_USAGE)
            else -> dev(Req.CameraInfo)
        }
        "termux-camera-photo" -> {
            val spec = opts('h' to "h", 'c' to "c:")
            val p = TermuxApi.getopt(name, args, spec) as? TermuxApi.Parsed ?: return bad(TermuxApi.getopt(name, args, spec) as String, CAMERA_PHOTO_USAGE)
            when {
                p.has("h") -> usage(CAMERA_PHOTO_USAGE)
                p.rest.isEmpty() -> bad("$name: missing file argument", CAMERA_PHOTO_USAGE)
                p.rest.size > 1 -> bad("$name: too many arguments", CAMERA_PHOTO_USAGE)
                else -> dev(Req.CameraPhoto(p.value("c"), KabukCommands.resolve(cwd, p.rest[0])))
            }
        }
        "termux-sensor" -> parseSensor(name, args)
        "termux-storage-get" -> {
            val spec = opts('h' to "h")
            val p = TermuxApi.getopt(name, args, spec) as? TermuxApi.Parsed ?: return bad(TermuxApi.getopt(name, args, spec) as String, STORAGE_GET_USAGE)
            when {
                p.has("h") -> usage(STORAGE_GET_USAGE)
                p.rest.size > 1 -> bad("$name: too many arguments", STORAGE_GET_USAGE)
                p.rest.isEmpty() -> bad("$name: no output file specified", STORAGE_GET_USAGE)
                else -> dev(Req.StorageGet(KabukCommands.resolve(cwd, p.rest[0])))
            }
        }
        "termux-microphone-record" -> parseMic(name, args, cwd)
        "termux-media-player" -> parsePlayer(name, args, cwd)
        else -> null
    }

    private val PROVIDERS = setOf("gps", "network", "passive")
    private val REQUESTS = setOf("once", "last", "updates")

    private fun parseLocation(name: String, args: List<String>): Action {
        val spec = opts('h' to "h", 'p' to "p:", 'r' to "r:", 'd' to "d:")
        val p = TermuxApi.getopt(name, args, spec) as? TermuxApi.Parsed ?: return bad(TermuxApi.getopt(name, args, spec) as String, LOCATION_USAGE)
        if (p.has("h")) return usage(LOCATION_USAGE)
        val provider = p.value("p") ?: "gps"
        if (provider !in PROVIDERS) return Action.Reply(1, "$name: Unsupported provider: '$provider'\n")
        val request = p.value("r") ?: "once"
        if (request !in REQUESTS) return Action.Reply(1, "$name: Unsupported request: '$request'\n")
        val interval = (p.value("d") ?: "5000").toLongOrNull()?.takeIf { it in 0..86_400_000 }
            ?: return bad("$name: invalid interval '${p.value("d")}' (milliseconds)", LOCATION_USAGE)
        if (p.rest.isNotEmpty()) return Action.Reply(1, "$name: too many arguments\n")
        return dev(Req.Location(provider, request, interval))
    }

    val WIDGETS = listOf("confirm", "checkbox", "counter", "date", "radio", "sheet", "spinner", "speech", "text", "time")
    private const val F_HINT = 1; private const val F_MULTI = 2; private const val F_PASS = 4
    private const val F_RANGE = 8; private const val F_VALUES = 16; private const val F_NUM = 32

    private fun parseDialog(name: String, args: List<String>): Action {
        var rest = args
        val widget = when {
            args.isEmpty() -> "text"
            args[0] in WIDGETS -> args[0].also { rest = args.drop(1) }
            !Regex("-[a-z]").containsMatchIn(args[0]) -> return Action.Reply(1, "ERROR: Illegal argument ${args[0]}\n\n$DIALOG_USAGE")
            else -> "text"
        }
        val spec = opts('h' to "h", 'l' to "l", 'm' to "m", 'n' to "n", 'p' to "p", 'r' to "r:", 'i' to "i:", 't' to "t:", 'd' to "d:", 'v' to "v:")
        val p = TermuxApi.getopt(name, rest, spec) as? TermuxApi.Parsed
            ?: return Action.Reply(1, (TermuxApi.getopt(name, rest, spec) as String) + "\n")
        if (p.has("h")) return usage(DIALOG_USAGE)
        if (p.has("l")) return usage("Supported widgets:\n\n" + WIDGETS.joinToString("") { widgetUsage(it) + "\n" })
        var flags = 0
        if (p.has("i")) flags = flags or F_HINT
        if (p.has("m")) flags = flags or F_MULTI
        if (p.has("p")) flags = flags or F_PASS
        if (p.has("r")) flags = flags or F_RANGE
        if (p.has("v")) flags = flags or F_VALUES
        if (p.has("n")) flags = flags or F_NUM
        fun invalid() = Action.Reply(1, "ERROR: Invalid option(s) detected for '$widget'\n\nUsage '$widget'\n${widgetUsage(widget)}")
        when (widget) {
            "text" -> if (flags and (F_RANGE or F_VALUES) != 0 || (flags and F_MULTI != 0 && flags and F_NUM != 0)) return invalid()
            "confirm", "speech" -> if (flags and (F_MULTI or F_PASS or F_RANGE or F_VALUES or F_NUM) != 0) return invalid()
            "counter" -> if (flags and (F_MULTI or F_PASS or F_VALUES or F_NUM) != 0) return invalid()
            "date", "time" -> if (flags and (F_HINT or F_MULTI or F_PASS or F_RANGE or F_VALUES or F_NUM) != 0) return invalid()
            else -> {
                if (!p.has("v")) return Action.Reply(1, "ERROR: '$widget' must be called with $OPT_VALUES_DESC\n")
                if (flags and (F_HINT or F_MULTI or F_PASS or F_RANGE or F_NUM) != 0) return invalid()
            }
        }
        if (p.rest.isNotEmpty()) return Action.Reply(1, "ERROR: Too many arguments!\n$DIALOG_USAGE")
        if (widget == "speech") return Action.Reply(1, "$name: the speech widget is not supported in Kabuk (no speech recognition)\n")
        val range = p.value("r")?.let { r ->
            val nums = r.split(',').map { it.trim().toIntOrNull() ?: return Action.Reply(1, "$name: -r takes numbers: min,max,start\n") }
            if (nums.size != 3) return Action.Reply(1, "$name: Invalid range! Must be 3 int values!\n")
            nums
        }
        val values = p.value("v")?.let(::dialogValues).orEmpty()
        if (widget in setOf("checkbox", "radio", "sheet", "spinner") && values.isEmpty()) return Action.Reply(1, "$name: -v has no values\n")
        return dev(Req.Dialog(DialogSpec(widget, p.value("t"), p.value("i"), p.has("m"), p.has("n"), p.has("p"), values, range, p.value("d"))))
    }

    /** -v "a,b\,c": split at commas not preceded by a backslash, trimmed, `\,` → `,` (as Termux:API) */
    internal fun dialogValues(v: String): List<String> = v.split(Regex("(?<!\\\\),")).map { it.trim().replace("\\,", ",") }

    private fun parseSensor(name: String, args: List<String>): Action {
        if (args.isEmpty()) return Action.Reply(1, "No arguments supplied!\n$SENSOR_USAGE")
        val spec = opts('h' to "h", 'a' to "a", 'c' to "c", 'l' to "l", 's' to "s:", 'd' to "d:", 'n' to "n:")
        val p = TermuxApi.getopt(name, args, spec) as? TermuxApi.Parsed ?: return Action.Reply(1, (TermuxApi.getopt(name, args, spec) as String) + "\n")
        if (p.has("h")) return usage(SENSOR_USAGE)
        fun err(msg: String) = Action.Reply(1, "ERROR: $msg\n$SENSOR_USAGE")
        val keys = p.opts.map { it.first }.toSet()
        if ("c" in keys && keys != setOf("c")) return err("No other options should be specified with -c cleanup!")
        if ("l" in keys && keys != setOf("l")) return err("No other options should be specified with -l list!")
        if ("d" in keys && "a" !in keys && "s" !in keys) return err("Use -s <sensors> or -a option when using -d delay!")
        if ("a" in keys && "s" in keys) return err("Listed sensors will be ignored with -a all option!")
        // Kabuk stops a program's sensors when it ends: nothing is left to clean up
        if ("c" in keys) return Action.Reply(0, "Sensor cleanup unnecessary\n")
        if ("l" in keys) return dev(Req.SensorList)
        val delay = p.value("d")?.let { d -> d.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }?.toLongOrNull()
            ?: return err("Illegal argument! -d delay arg should be a number!") } ?: 1000L
        val limit = p.value("n")?.let { n -> n.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }?.toIntOrNull()
            ?: return err("Illegal argument! -n limit arg should be a number!") }?.coerceAtLeast(1) ?: 0
        if ("a" !in keys && "s" !in keys) return Action.Reply(1, "No arguments supplied!\n$SENSOR_USAGE")
        val names = p.value("s")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return dev(Req.Sensors(names, "a" in keys, delay.coerceIn(10, 3_600_000), limit))
    }

    private val ENCODERS = setOf("aac", "amr_wb", "amr_nb", "opus")

    private fun parseMic(name: String, args: List<String>, cwd: String): Action {
        if (args.isEmpty()) return usage(MIC_USAGE)
        val spec = opts('h' to "h", 'f' to "f:", 'l' to "l:", 'e' to "e:", 'b' to "b:", 'r' to "r:", 'c' to "c:", 'd' to "d", 'i' to "i", 'q' to "q")
        val p = TermuxApi.getopt(name, args, spec) as? TermuxApi.Parsed ?: return Action.Reply(1, (TermuxApi.getopt(name, args, spec) as String) + "\n\n$MIC_USAGE")
        for (sole in listOf("h", "i", "q")) if (p.has(sole) && (p.opts.size > 1 || p.rest.isNotEmpty()))
            return Action.Reply(1, "ERROR: No other options should be specified with -$sole!\n\n$MIC_USAGE")
        if (p.rest.isNotEmpty()) return Action.Reply(1, "$name: unexpected argument ${p.rest[0]}\n\n$MIC_USAGE")
        if (p.has("h")) return usage(MIC_USAGE)
        if (p.has("i")) return dev(Req.MicInfo)
        if (p.has("q")) return dev(Req.MicQuit)
        fun num(k: String): Int? = p.value(k)?.let { v -> v.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
        for (k in listOf("l", "b", "r", "c")) if (p.has(k) && num(k) == null)
            return Action.Reply(1, "$name: -$k needs a number, not '${p.value(k)}'\n\n$MIC_USAGE")
        val encoder = p.value("e")?.lowercase()
        if (encoder != null && encoder !in ENCODERS) return Action.Reply(1, "$name: unknown encoder '$encoder' (aac, amr_wb, amr_nb, opus)\n\n$MIC_USAGE")
        return dev(Req.MicRecord(
            file = p.value("f")?.let { KabukCommands.resolve(cwd, it) },
            limitMs = num("l")?.let { it * 1000 }, encoder = encoder, bitrate = num("b")?.let { it * 1000 },
            sampleRate = num("r"), channels = num("c"),
        ))
    }

    private fun parsePlayer(name: String, args: List<String>, cwd: String): Action = when (args.firstOrNull()) {
        null, "help" -> usage(PLAYER_USAGE)
        "play" -> when (args.size) {
            1 -> dev(Req.Player("play", null))
            2 -> dev(Req.Player("play", KabukCommands.resolve(cwd, args[1])))
            else -> Action.Reply(1, "Error! $name can only play one file at a time!\n")
        }
        "info", "pause", "stop" -> if (args.size > 1) Action.Reply(1, "Error! '${args[0]}' takes no arguments!\n") else dev(Req.Player(args[0], null))
        else -> Action.Reply(1, "$name: Invalid cmd: '${args[0]}'\n$PLAYER_USAGE")
    }

    // ---- JSON (Termux:API's JsonWriter / JSONObject.toString(2) layout) ----

    /** A JSON object for [pretty]; a [List] is an array. */
    class JObj(val fields: List<Pair<String, Any?>>) {
        constructor(vararg f: Pair<String, Any?>) : this(f.toList())
    }

    /** [v] as JSON with a two-space indent (or on one line when [compact]). */
    internal fun pretty(v: Any?, indent: String = "", compact: Boolean = false): String {
        val inner = "$indent  "
        return when (v) {
            is JObj -> when {
                v.fields.isEmpty() -> "{}"
                compact -> v.fields.joinToString(",", "{", "}") { (k, x) -> TermuxApi.quote(k) + ":" + pretty(x, compact = true) }
                else -> v.fields.joinToString(",\n", "{\n", "\n$indent}") { (k, x) -> "$inner${TermuxApi.quote(k)}: ${pretty(x, inner)}" }
            }
            is List<*> -> when {
                v.isEmpty() -> "[]"
                compact -> v.joinToString(",", "[", "]") { pretty(it, compact = true) }
                else -> v.joinToString(",\n", "[\n", "\n$indent]") { "$inner${pretty(it, inner)}" }
            }
            else -> TermuxApi.value(v)
        }
    }

    /** What a [Location] says. */
    data class Fix(val latitude: Double, val longitude: Double, val altitude: Double, val accuracy: Float,
                   val verticalAccuracy: Float?, val bearing: Float, val speed: Float, val elapsedMs: Long, val provider: String?)

    internal fun locationJson(f: Fix?, compact: Boolean = false): String {
        if (f == null) return pretty(JObj("API_ERROR" to "Failed to get location"), compact = compact)
        return pretty(JObj(listOfNotNull(
            "latitude" to f.latitude, "longitude" to f.longitude, "altitude" to f.altitude, "accuracy" to f.accuracy,
            f.verticalAccuracy?.let { "vertical_accuracy" to it }, "bearing" to f.bearing, "speed" to f.speed,
            "elapsedMs" to f.elapsedMs, "provider" to f.provider,
        )), compact = compact)
    }

    internal fun dialogJson(r: DialogResult): String = pretty(JObj(listOfNotNull(
        "code" to r.code, "text" to r.text,
        if (r.index > -1) "index" to r.index else null,
        if (r.values.isNotEmpty()) "values" to r.values.map { (i, t) -> JObj("index" to i, "text" to t) } else null,
        r.error?.let { "error" to it },
    ))) + "\n"

    /** JSONObject prints whole numbers without ".0" (sensor values) */
    private fun jsonNumber(f: Float): Any = f.toDouble().let { d -> if (d.isFinite() && d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong() else d }

    /** termux-sensor's readout: `{"<sensor name>": {"values": [...]}, ...}` in the order the sensors first reported. */
    internal fun sensorJson(readout: Map<String, FloatArray>): String =
        pretty(JObj(readout.map { (n, v) -> n to JObj("values" to v.map(::jsonNumber)) })) + "\n"

    /** The sensors termux-sensor -s listens to: per requested part the shortest name containing it (case ignored). */
    internal fun <S> pickSensors(available: List<S>, nameOf: (S) -> String, requested: List<String>, all: Boolean): List<S> {
        val sorted = available.sortedBy(nameOf)
        if (all) return sorted
        return requested.mapNotNull { r -> sorted.filter { nameOf(it).uppercase().contains(r.uppercase()) }.minByOrNull { nameOf(it).length } }.distinct()
    }

    /** termux-dialog date: the picked day at 00:00:00 in [tz], with -d's SimpleDateFormat pattern or Date.toString(). */
    internal fun dateText(year: Int, month0: Int, day: Int, format: String?, tz: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(tz)
        c.clear()
        c.set(year, month0, day, 0, 0, 0)
        if (format == null) return c.time.toString()
        return SimpleDateFormat(format, Locale.getDefault()).apply { timeZone = tz }.format(c.time)
    }

    internal fun timeText(hour: Int, minute: Int) = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

    /** HH:MM:SS (hours only when there are some), as Termux:API's media player and recorder print times */
    internal fun timeString(totalSeconds: Int): String {
        val h = totalSeconds / 3600; val m = totalSeconds % 3600 / 60; val s = totalSeconds % 60
        return (if (h > 0) String.format(Locale.ROOT, "%02d:", h) else "") + String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    // ---- on the phone (main thread) ----

    fun execute(ctx: Context, name: String, req: Req, s: Session) {
        when (req) {
            is Req.Location -> withPermission(ctx, name, LOCATION, "location", s) { location(ctx, name, req, s) }
            is Req.Dialog -> dialog(name, req.spec, s)
            Req.CameraInfo -> s.answer(TermuxCamera.info(ctx, name))
            is Req.CameraPhoto -> withPermission(ctx, name, arrayOf(Manifest.permission.CAMERA), "camera", s) {
                TermuxCamera.photo(ctx, name, req, s)
            }
            Req.SensorList -> {
                val sm = ctx.getSystemService(SensorManager::class.java)
                val names = sm?.getSensorList(Sensor.TYPE_ALL)?.map { it.name }.orEmpty()
                KLog.i(TAG, "$name: ${names.size} sensors listed")
                s.answer(Answer(0, pretty(JObj("sensors" to names)) + "\n"))
            }
            is Req.Sensors -> sensors(ctx, name, req, s)
            is Req.StorageGet -> storageGet(ctx, name, req, s)
            is Req.MicRecord -> withPermission(ctx, name, arrayOf(Manifest.permission.RECORD_AUDIO), "microphone", s) {
                s.answer(TermuxMedia.record(ctx, name, req))
            }
            Req.MicInfo -> s.answer(TermuxMedia.recordInfo())
            Req.MicQuit -> s.answer(TermuxMedia.recordQuit(ctx, name))
            is Req.Player -> s.answer(TermuxMedia.player(ctx, name, req))
        }
    }

    private val LOCATION = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * Main thread: runs [then] once one of [perms] is granted. Asks (through [ApiActivity]) only while Kabuk is on
     * screen; in the background, or when refused, the request is answered with exit 1.
     */
    private fun withPermission(ctx: Context, name: String, perms: Array<String>, what: String, s: Session, then: () -> Unit) {
        if (perms.any { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) { then(); return }
        if (!TabManager.foreground) {
            KLog.i(TAG, "$name: $what permission needed, Kabuk in the background")
            s.answer(Answer(1, "$name: Kabuk needs the $what permission, and Android asks only while Kabuk is on screen: " +
                "switch to Kabuk and run it again\n"))
            return
        }
        KLog.i(TAG, "$name: asking for the $what permission")
        ApiActivity.requestPermissions(ctx, perms) { granted ->
            when {
                s.gone -> KLog.i(TAG, "$name: $what permission ${if (granted) "granted" else "refused"} after the program ended")
                granted -> { KLog.i(TAG, "$name: $what permission granted"); then() }
                else -> {
                    KLog.i(TAG, "$name: $what permission refused")
                    s.answer(Answer(1, "$name: permission denied: Kabuk may not use the $what (allow it in Android settings → " +
                        "Apps → Kabuk → Permissions)\n"))
                }
            }
        }
    }

    private fun fix(l: Location?): Fix? = l?.let {
        Fix(it.latitude, it.longitude, it.altitude, it.accuracy,
            if (it.hasVerticalAccuracy()) it.verticalAccuracyMeters else 0f,
            it.bearing, it.speed, (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000, it.provider)
    }

    /** Location also in the background: Android before 10, or "Allow all the time" (ACCESS_BACKGROUND_LOCATION). */
    private fun backgroundLocation(ctx: Context) = android.os.Build.VERSION.SDK_INT < 29 ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** how long termux-location -r once waits for a fix */
    private const val ONCE_TIMEOUT_MS = 60_000L

    @Suppress("DEPRECATION", "MissingPermission")
    private fun location(ctx: Context, name: String, req: Req.Location, s: Session) {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return s.answer(Answer(1, "$name: this phone has no location service\n"))
        try {
            // Android hands an app in the background no location unless "Allow all the time" (Kabuk's session
            // service doesn't count: it isn't a location service); without this check the program would just wait
            if (!TabManager.foreground && !backgroundLocation(ctx)) {
                KLog.i(TAG, "$name: refused, Kabuk not in front and no background location")
                return s.answer(Answer(1, "$name: Android gives Kabuk the location only while it is on screen: switch to Kabuk, " +
                    "or allow \"All the time\" in Android settings → Apps → Kabuk → Permissions → Location for scripts in the background\n"))
            }
            if (req.request == "last") {
                val f = fix(lm.getLastKnownLocation(req.provider))
                KLog.i(TAG, "$name: last (${if (f == null) "none" else "known"})")
                return s.answer(Answer(0, locationJson(f) + "\n"))
            }
            if (req.provider != "passive" && !lm.isProviderEnabled(req.provider)) {
                KLog.i(TAG, "$name: provider ${req.provider} off")
                return s.answer(Answer(1, "$name: the ${req.provider} location provider is off (turn on Location in Android's quick settings)\n"))
            }
            val main = TabManager.main
            var timeout: Runnable? = null
            var count = 0
            val listener = object : LocationListener {
                override fun onLocationChanged(l: Location) {
                    if (req.request == "once") {
                        lm.removeUpdates(this); timeout?.let(main::removeCallbacks)
                        KLog.i(TAG, "$name: once (fix from ${l.provider})")
                        s.answer(Answer(0, locationJson(fix(l)) + "\n"))
                    } else {
                        count++
                        if (!s.emit(locationJson(fix(l), compact = true) + "\n")) { lm.removeUpdates(this); KLog.i(TAG, "$name: updates ended after $count") }
                    }
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            s.onGone { main.post { lm.removeUpdates(listener); timeout?.let(main::removeCallbacks); KLog.i(TAG, "$name: stopped (the program ended, $count updates)") } }
            if (req.request == "once") {
                timeout = Runnable {
                    lm.removeUpdates(listener)
                    KLog.i(TAG, "$name: no fix within ${ONCE_TIMEOUT_MS / 1000} s")
                    s.answer(Answer(1, "$name: no location within ${ONCE_TIMEOUT_MS / 1000} s (indoors? try -p network or -r last)\n"))
                }
                main.postDelayed(timeout, ONCE_TIMEOUT_MS)
                // not requestSingleUpdate: Android 12+ gives that only 30 s; this waits for the first fix up to our timeout
                lm.requestLocationUpdates(req.provider, 0L, 0f, listener, Looper.getMainLooper())
            } else {
                lm.requestLocationUpdates(req.provider, req.intervalMs, 0f, listener, Looper.getMainLooper())
                KLog.i(TAG, "$name: updates started (${req.provider})")
            }
        } catch (e: SecurityException) {
            KLog.w(TAG, "$name: not allowed (${e.javaClass.simpleName})")
            s.answer(Answer(1, "$name: Android does not allow this: ${e.message}\n"))
        } catch (e: IllegalArgumentException) {
            s.answer(Answer(1, "$name: this phone has no ${req.provider} location provider\n"))
        }
    }

    // ---- termux-dialog: shown by MainActivity (ui/ScriptDialog.kt) ----

    /** A dialog waiting for its answer; [answer] once. */
    class PendingDialog(val spec: DialogSpec, private val onAnswer: (DialogResult) -> Unit) {
        private var answered = false
        fun answer(r: DialogResult) { if (!answered) { answered = true; dialogs.update { it - this }; onAnswer(r) } }
    }

    /** The dialogs programs asked for, oldest first (MainActivity shows the first). */
    val dialogs = MutableStateFlow<List<PendingDialog>>(emptyList())

    private fun dialog(name: String, spec: DialogSpec, s: Session) {
        if (!TabManager.foreground) {
            KLog.i(TAG, "$name: refused, Kabuk not in front")
            return s.answer(Answer(1, "$name: Kabuk must be on screen to show a dialog: switch to Kabuk and run it again\n"))
        }
        val d = PendingDialog(spec) { r ->
            KLog.i(TAG, "$name: ${spec.widget} answered")
            s.answer(Answer(0, dialogJson(r)))
        }
        s.onGone { TabManager.main.post { if (d in dialogs.value) { dialogs.update { it - d }; KLog.i(TAG, "$name: dialog closed (the program ended)") } } }
        dialogs.update { it + d }
        KLog.i(TAG, "$name: ${spec.widget} shown")
    }

    // ---- termux-sensor ----

    private fun sensors(ctx: Context, name: String, req: Req.Sensors, s: Session) {
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return s.answer(Answer(1, "$name: this phone has no sensors\n"))
        val picked = pickSensors(sm.getSensorList(Sensor.TYPE_ALL), { it.name }, req.names, req.all)
        if (picked.isEmpty()) {
            KLog.i(TAG, "$name: no matching sensor")
            return s.answer(Answer(1, "No valid sensors were registered!\n"))
        }
        val readout = LinkedHashMap<String, FloatArray>()
        val main = TabManager.main
        var stopped = false
        var count = 0
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { if (!stopped) readout[e.sensor.name] = e.values.clone() }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        lateinit var tick: Runnable
        fun stop(why: String) {
            if (stopped) return
            stopped = true
            sm.unregisterListener(listener)
            main.removeCallbacks(tick)
            KLog.i(TAG, "$name: stopped ($why, $count readings)")
        }
        tick = Runnable {
            if (stopped) return@Runnable
            count++
            val more = s.emit(sensorJson(readout))
            when {
                !more -> stop(if (s.streaming) "the program ended" else "one reading: kabuk-open before 5")
                req.limit in 1..count -> { stop("limit"); s.answer(Answer(0, "")) }
                else -> main.postDelayed(tick, req.delayMs)
            }
        }
        s.onGone { main.post { stop("the program ended") } }
        picked.forEach { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        KLog.i(TAG, "$name: listening to ${picked.size} sensor(s)")
        main.postDelayed(tick, req.delayMs)
    }

    // ---- termux-storage-get ----

    private fun storageGet(ctx: Context, name: String, req: Req.StorageGet, s: Session) {
        val out = File(req.file)
        val dir = out.parentFile
        if (dir == null || !dir.isDirectory) return s.answer(Answer(1, "$name: ${dir ?: req.file}: No such directory\n"))
        if (!dir.canWrite() || (out.exists() && !out.canWrite())) return s.answer(Answer(1, "$name: ${req.file}: Permission denied\n"))
        if (out.isDirectory) return s.answer(Answer(1, "$name: ${req.file}: is a directory\n"))
        if (!TabManager.foreground) {
            KLog.i(TAG, "$name: refused, Kabuk not in front")
            return s.answer(Answer(1, "$name: Kabuk must be on screen to show the file picker: switch to Kabuk and run it again\n"))
        }
        KLog.i(TAG, "$name: file picker shown")
        ApiActivity.pickDocument(ctx) { uri ->
            if (uri == null) { KLog.i(TAG, "$name: no file chosen"); return@pickDocument s.answer(Answer(1, "$name: no file was chosen\n")) }
            if (s.gone) return@pickDocument
            Thread({
                val a = runCatching {
                    val n = ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    KLog.i(TAG, "$name: copied ($n bytes)")
                    Answer(0, "")
                }.getOrElse { e ->
                    KLog.w(TAG, "$name: copy failed (${e.javaClass.simpleName})")
                    Answer(1, "$name: could not copy the file: ${e.javaClass.simpleName}: ${e.message}\n")
                }
                s.answer(a)
            }, "cmd-storage-get").start()
        }
    }

    // ---- usage texts (Termux:API's) ----

    private const val LOCATION_USAGE = """usage: termux-location [-p provider] [-r request] [-d ms]
Get the device location.
  -p provider  location provider [gps/network/passive] (default: gps)
  -r request   kind of request to make [once/last/updates] (default: once)
  -d ms        (Kabuk) time between updates for -r updates (default: 5000)
-r updates prints one JSON object per line until Ctrl-C.
"""
    private const val OPT_VALUES_DESC = "[-v \",,,\"] comma delim values to use (required)"
    private const val DIALOG_USAGE = """Usage: termux-dialog widget [options]
Get user input w/ different widgets! Default: text
   -h, help   Show this help
   -l, list   List all widgets and their options
   -t, title  Set title of input dialog (optional)
"""

    private fun widgetUsage(w: String): String {
        val hint = "    [-i hint] text hint (optional)\n"; val title = "    [-t title] set title of dialog (optional)\n"
        val values = "    $OPT_VALUES_DESC\n"
        return "$w - " + when (w) {
            "confirm" -> "Show confirmation dialog\n$hint$title"
            "checkbox" -> "Select multiple values using checkboxes\n$values$title"
            "counter" -> "Pick a number in specified range\n    [-r min,max,start] comma delim of (3) numbers to use (optional)\n$title"
            "date" -> "Pick a date\n$title    [-d \"dd-MM-yyyy k:m:s\"] SimpleDateFormat Pattern for date widget output (optional)\n"
            "radio" -> "Pick a single value from radio buttons\n$values$title"
            "sheet" -> "Pick a value from sliding bottom sheet\n$values$title"
            "speech" -> "Obtain speech using device microphone (not supported in Kabuk)\n$hint$title"
            "spinner" -> "Pick a single value from a dropdown spinner\n$values$title"
            "text" -> "Input text (default if no widget specified)\n$hint    [-m] multiple lines instead of single (optional)*\n" +
                "    [-n] enter input as numbers (optional)*\n    [-p] enter input as password (optional)\n$title       * cannot use [-m] with [-n]\n"
            "time" -> "Pick a time value\n$title"
            else -> "\tUnknown usage for '$w'\n"
        }
    }

    private const val CAMERA_INFO_USAGE = "Usage: termux-camera-info\nGet information about device camera(s).\n"
    private const val CAMERA_PHOTO_USAGE = """Usage: termux-camera-photo [-c camera-id] output-file
Take a photo and save it to a file in JPEG format.
  -c camera-id  ID of the camera to use (see termux-camera-info), default: 0
"""
    private const val SENSOR_USAGE = """Usage: termux-sensor
Get information about types of sensors as well as live data
  -h, help           Show this help
  -a, all            Listen to all sensors (WARNING! may have battery impact)
  -c, cleanup        Perform cleanup (release sensor resources)
  -l, list           Show list of available sensors
  -s, sensors [,,,]  Sensors to listen to (can contain just partial name)
  -d, delay [ms]     Delay time in milliseconds before receiving new sensor update
  -n, limit [num]    Number of times to read sensor(s) (default: continuous) (min: 1)
"""
    private const val STORAGE_GET_USAGE = "Usage: termux-storage-get output-file\nRequest a file from the system and output it to the specified file.\n"
    private const val MIC_USAGE = """Usage: termux-microphone-record [args]
Record using microphone on your device

-h           Shows this help
-d           Start recording w/ defaults
-f <file>    Start recording to specific file
-l <limit>   Start recording w/ specified limit (in seconds, unlimited for 0)
-e <encoder> Start recording w/ specified encoder (aac, amr_wb, amr_nb, opus)
-b <bitrate> Start recording w/ specified bitrate (in kbps)
-r <rate>    Start recording w/ specified sampling rate (in Hz)
-c <count>   Start recording w/ specified channel count (1, 2, ...)
-i           Get info about current recording
-q           Quits recording
"""
    private const val PLAYER_USAGE = """Usage: termux-media-player cmd [args]

help        Shows this help
info        Displays current playback information
play        Resumes playback if paused
play <file> Plays specified media file
pause       Pauses playback
stop        Quits playback
"""
}
