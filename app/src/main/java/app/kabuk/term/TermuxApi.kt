package app.kabuk.term

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.webkit.URLUtil
import android.widget.Toast
import app.kabuk.MainActivity
import app.kabuk.R
import app.kabuk.log.KLog
import app.kabuk.term.KabukCommands.Action
import app.kabuk.term.KabukCommands.Answer
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Termux:API's commands inside Kabuk (`termux-clipboard-get`, `termux-toast`, `termux-notification`,
 * `termux-battery-status`, …): same names, options and output (JSON where Termux:API prints JSON). Termux's
 * `termux-api` package reaches the separate Termux:API app through `am broadcast`; here the names are links to
 * libkabuk-open.so in files/kabuk/bin (first on PATH, so they win over the package's scripts) and in busybox's
 * bin, and arrive through [KabukCommands]' request channel. [parse] is pure (TermuxApiTest); [execute] runs on the
 * main thread. A command that reads stdin when it has no text argument answers [Action.NeedStdin] first.
 * Logs (tag CMD): the command name and the outcome only, never clipboard, notification, share or speech text,
 * paths or URLs (scheme + host at most).
 */
object TermuxApi {
    private const val TAG = "CMD"
    private const val NOT_IN_KABUK = "is not supported in Kabuk"
    const val CHANNEL = "scripts"
    /** termux-notification --priority high|max: heads-up */
    const val CHANNEL_HIGH = "scripts-high"
    /** Binder transactions are limited to ~1 MB: text shared through an intent stays well below */
    private const val MAX_SHARE_TEXT = 200_000

    /** Implemented here. */
    val SUPPORTED = listOf(
        "termux-clipboard-get", "termux-clipboard-set", "termux-toast", "termux-vibrate", "termux-torch",
        "termux-tts-speak", "termux-notification", "termux-notification-remove", "termux-share",
        "termux-battery-status", "termux-wifi-connectioninfo", "termux-volume", "termux-media-scan", "termux-download",
    )

    /** termux-api's other commands: "… is not supported in Kabuk", exit 1 (docs/LOCAL-TOOLS.md lists them). */
    val UNSUPPORTED = mapOf(
        "termux-sms-send" to "SMS", "termux-sms-list" to "SMS", "termux-sms-inbox" to "SMS",
        "termux-telephony-call" to "phone calls", "termux-telephony-cellinfo" to "telephony",
        "termux-telephony-deviceinfo" to "telephony", "termux-camera-photo" to "the camera",
        "termux-camera-info" to "the camera", "termux-microphone-record" to "the microphone",
        "termux-location" to "location", "termux-sensor" to "sensors", "termux-fingerprint" to "the fingerprint sensor",
        "termux-contact-list" to "contacts", "termux-call-log" to "the call log",
        "termux-infrared-frequencies" to "infrared", "termux-infrared-transmit" to "infrared", "termux-nfc" to "NFC",
        "termux-usb" to "USB devices", "termux-wallpaper" to "the wallpaper", "termux-dialog" to "dialogs",
        "termux-storage-get" to "the file picker", "termux-saf-create" to "the storage access framework",
        "termux-saf-dirs" to "the storage access framework", "termux-saf-ls" to "the storage access framework",
        "termux-saf-managedir" to "the storage access framework", "termux-saf-mkdir" to "the storage access framework",
        "termux-saf-read" to "the storage access framework", "termux-saf-rm" to "the storage access framework",
        "termux-saf-stat" to "the storage access framework", "termux-saf-write" to "the storage access framework",
        "termux-keystore" to "the keystore", "termux-job-scheduler" to "the job scheduler",
        "termux-notification-list" to "reading notifications (needs notification access)",
        "termux-notification-channel" to "own notification channels (Kabuk posts to its \"Scripts\" channels; --priority high picks \"Scripts (important)\")",
        "termux-brightness" to "changing the brightness (needs the special \"modify system settings\" permission)",
        "termux-wifi-scaninfo" to "Wi-Fi scans (needs location)", "termux-wifi-enable" to "switching Wi-Fi",
        "termux-media-player" to "the media player", "termux-speech-to-text" to "speech recognition",
        "termux-tts-engines" to "listing TTS engines", "termux-audio-info" to "audio info",
        "termux-api-start" to "the Termux:API service", "termux-api-stop" to "the Termux:API service",
    )

    val NAMES = SUPPORTED + UNSUPPORTED.keys

    /** What a Termux:API command asks for. */
    sealed class Req {
        data object ClipboardGet : Req()
        data class ClipboardSet(val text: String) : Req()
        data class Toast(val text: String, val short: Boolean, val gravity: String) : Req()
        data class Vibrate(val ms: Long, val force: Boolean) : Req()
        data class Torch(val on: Boolean) : Req()
        data class Speak(val text: String, val engine: String?, val language: String?, val region: String?,
                         val variant: String?, val pitch: Float, val rate: Float, val stream: Int) : Req()
        data class Notify(val n: NotificationSpec) : Req()
        data class NotifyRemove(val id: String) : Req()
        /** [file]: an absolute path, else [text] (stdin) */
        data class Share(val file: String?, val text: String?, val action: String, val type: String?,
                         val chooser: Boolean, val title: String?) : Req()
        data object Battery : Req()
        data object Wifi : Req()
        /** both null: list the streams */
        data class Volume(val stream: String?, val volume: Int?) : Req()
        data class MediaScan(val paths: List<String>, val recursive: Boolean, val verbose: Boolean) : Req()
        data class Download(val url: String, val title: String?, val description: String?, val path: String?) : Req()
    }

    data class Button(val text: String, val action: String?)

    data class NotificationSpec(
        val id: String?, val title: String?, val content: String, val ongoing: Boolean = false,
        val priority: String = "default", val sound: Boolean = false, val vibrate: LongArray? = null,
        val action: String? = null, val onDelete: String? = null, val buttons: List<Button> = emptyList(),
        val alertOnce: Boolean = false, val group: String? = null, val image: String? = null,
    ) {
        override fun equals(other: Any?) = other is NotificationSpec && toString() == other.toString()
        override fun hashCode() = toString().hashCode()
        override fun toString() = "NotificationSpec(id=$id, title=$title, content=$content, ongoing=$ongoing, " +
            "priority=$priority, sound=$sound, vibrate=${vibrate?.toList()}, action=$action, onDelete=$onDelete, " +
            "buttons=$buttons, alertOnce=$alertOnce, group=$group, image=$image)"
    }

    // ---- option parsing (getopts-like: short options may be bundled, options end at the first operand) ----

    /** An option's canonical key and whether it takes a value. */
    internal class Opt(val key: String, val arg: Boolean)

    internal class Parsed(val opts: List<Pair<String, String?>>, val rest: List<String>) {
        fun has(k: String) = opts.any { it.first == k }
        fun value(k: String) = opts.lastOrNull { it.first == k }?.second
    }

    /** Parses [args]; returns the error message for an unknown option or a missing value. */
    internal fun getopt(name: String, args: List<String>, short: Map<Char, Opt>, long: Map<String, Opt> = emptyMap()): Any {
        val opts = mutableListOf<Pair<String, String?>>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--" -> { i++; break }
                a.startsWith("--") && long.isNotEmpty() -> {
                    val n = a.substring(2).substringBefore('=')
                    val o = long[n] ?: return "$name: unrecognized option '--$n'"
                    i++
                    if (!o.arg) { if ('=' in a) return "$name: option '--$n' doesn't allow an argument"; opts += o.key to null }
                    else if ('=' in a) opts += o.key to a.substringAfter('=')
                    else { if (i >= args.size) return "$name: option '--$n' requires an argument"; opts += o.key to args[i++] }
                }
                a.startsWith("-") && a.length > 1 -> {
                    i++
                    var j = 1
                    while (j < a.length) {
                        val c = a[j++]
                        val o = short[c] ?: return "$name: illegal option -$c"
                        if (!o.arg) { opts += o.key to null; continue }
                        val v = if (j < a.length) a.substring(j) else if (i < args.size) args[i++] else return "$name: option requires an argument -- $c"
                        opts += o.key to v
                        break
                    }
                }
                else -> break
            }
        }
        return Parsed(opts, args.subList(i, args.size).toList())
    }

    private fun opts(vararg o: Pair<Char, String>) = o.associate { (c, k) -> c to Opt(k.removeSuffix(":"), k.endsWith(":")) }

    private fun usage(text: String) = Action.Reply(0, text)
    private fun bad(msg: String, usage: String) = Action.Reply(1, "$msg\n$usage")

    /**
     * What Termux:API's [name] with [args] asks for; null when [name] isn't one. [stdin] is the program's input once
     * it was handed over (null: not yet; the command asks for it with [Action.NeedStdin] when it needs it).
     */
    fun parse(name: String, args: List<String>, cwd: String, stdin: String? = null): Action? {
        UNSUPPORTED[name]?.let { what ->
            return Action.Reply(1, "$name: $what $NOT_IN_KABUK (there is no Termux:API app; see termux-info for what works)\n")
        }
        return when (name) {
            "termux-clipboard-get" -> when {
                args.any { it == "-h" } -> usage(CLIP_GET_USAGE)
                args.isNotEmpty() -> bad("$name: no arguments expected", CLIP_GET_USAGE)
                else -> Action.Api(Req.ClipboardGet)
            }
            "termux-clipboard-set" -> when {
                args.size == 1 && args[0] == "-h" -> usage(CLIP_SET_USAGE)
                args.isNotEmpty() -> Action.Api(Req.ClipboardSet(args.joinToString(" ")))
                stdin == null -> Action.NeedStdin(onlyIfPiped = false)
                else -> Action.Api(Req.ClipboardSet(stdin))
            }
            "termux-toast" -> {
                val spec = opts('h' to "h", 's' to "s", 'g' to "g:", 'b' to "b:", 'c' to "c:")
                val p = getopt(name, args, spec) as? Parsed ?: return bad(getopt(name, args, spec) as String, TOAST_USAGE)
                if (p.has("h")) return usage(TOAST_USAGE)
                val g = p.value("g") ?: "middle"
                if (g !in setOf("top", "middle", "bottom")) return bad("$name: invalid gravity '$g' (top, middle or bottom)", TOAST_USAGE)
                val text = if (p.rest.isNotEmpty()) p.rest.joinToString(" ") else stdin ?: return Action.NeedStdin(onlyIfPiped = false)
                Action.Api(Req.Toast(text, p.has("s"), g))
            }
            "termux-vibrate" -> {
                val spec = opts('h' to "h", 'f' to "f", 'd' to "d:")
                val p = getopt(name, args, spec) as? Parsed ?: return bad(getopt(name, args, spec) as String, VIBRATE_USAGE)
                if (p.has("h")) return usage(VIBRATE_USAGE)
                if (p.rest.isNotEmpty()) return bad("$name: unexpected argument ${p.rest[0]}", VIBRATE_USAGE)
                val ms = (p.value("d") ?: "1000").toLongOrNull()?.takeIf { it in 1..60_000 }
                    ?: return bad("$name: invalid duration '${p.value("d")}' (1..60000 ms)", VIBRATE_USAGE)
                Action.Api(Req.Vibrate(ms, p.has("f")))
            }
            "termux-torch" -> when {
                args == listOf("on") -> Action.Api(Req.Torch(true))
                args == listOf("off") -> Action.Api(Req.Torch(false))
                args == listOf("-h") -> usage(TORCH_USAGE)
                else -> bad("$name: say on or off", TORCH_USAGE)
            }
            "termux-tts-speak" -> parseSpeak(name, args, stdin)
            "termux-notification" -> parseNotification(name, args, cwd, stdin)
            "termux-notification-remove" -> when {
                args == listOf("-h") -> usage(NOTIFY_REMOVE_USAGE)
                args.size != 1 || args[0].isEmpty() -> bad("$name: give one notification id", NOTIFY_REMOVE_USAGE)
                else -> Action.Api(Req.NotifyRemove(args[0]))
            }
            "termux-share" -> {
                val spec = opts('h' to "h", 'a' to "a:", 'c' to "c:", 'd' to "d", 't' to "t:")
                val p = getopt(name, args, spec) as? Parsed ?: return bad(getopt(name, args, spec) as String, SHARE_USAGE)
                if (p.has("h")) return usage(SHARE_USAGE)
                val action = p.value("a") ?: "view"
                if (action !in setOf("edit", "send", "view")) return bad("$name: invalid action '$action' (edit, send or view)", SHARE_USAGE)
                if (p.rest.size > 1) return bad("$name: only one file can be shared", SHARE_USAGE)
                val file = p.rest.firstOrNull()?.let { KabukCommands.resolve(cwd, it) }
                if (file == null && stdin == null) return Action.NeedStdin(onlyIfPiped = false)
                Action.Api(Req.Share(file, if (file == null) stdin else null, action, p.value("c"), !p.has("d"), p.value("t")))
            }
            "termux-battery-status" -> if (args.isEmpty()) Action.Api(Req.Battery)
                else if (args == listOf("-h")) usage(BATTERY_USAGE) else bad("$name: no arguments expected", BATTERY_USAGE)
            "termux-wifi-connectioninfo" -> if (args.isEmpty()) Action.Api(Req.Wifi)
                else if (args == listOf("-h")) usage(WIFI_USAGE) else bad("$name: no arguments expected", WIFI_USAGE)
            "termux-volume" -> when {
                args.isEmpty() -> Action.Api(Req.Volume(null, null))
                args == listOf("-h") -> usage(VOLUME_USAGE)
                args.size != 2 -> bad("$name: give a stream and a volume", VOLUME_USAGE)
                args[0] !in STREAMS -> bad("$name: invalid audio stream '${args[0]}'", VOLUME_USAGE)
                else -> args[1].toIntOrNull()?.takeIf { it >= 0 }?.let { Action.Api(Req.Volume(args[0], it)) }
                    ?: bad("$name: invalid volume '${args[1]}'", VOLUME_USAGE)
            }
            "termux-media-scan" -> {
                val spec = opts('h' to "h", 'r' to "r", 'v' to "v")
                val p = getopt(name, args, spec) as? Parsed ?: return bad(getopt(name, args, spec) as String, MEDIA_SCAN_USAGE)
                if (p.has("h")) return usage(MEDIA_SCAN_USAGE)
                if (p.rest.isEmpty()) return bad("$name: no files given", MEDIA_SCAN_USAGE)
                Action.Api(Req.MediaScan(p.rest.map { KabukCommands.resolve(cwd, it) }, p.has("r"), p.has("v")))
            }
            "termux-download" -> {
                val spec = opts('h' to "h", 'd' to "d:", 't' to "t:", 'p' to "p:")
                val p = getopt(name, args, spec) as? Parsed ?: return bad(getopt(name, args, spec) as String, DOWNLOAD_USAGE)
                if (p.has("h")) return usage(DOWNLOAD_USAGE)
                if (p.rest.size != 1) return bad("$name: give one URL", DOWNLOAD_USAGE)
                val url = UrlOpener.accept(p.rest[0]) ?: return bad("$name: only http:// and https:// URLs can be downloaded", DOWNLOAD_USAGE)
                Action.Api(Req.Download(url, p.value("t"), p.value("d"), p.value("p")?.let { KabukCommands.resolve(cwd, it) }))
            }
            else -> null
        }
    }

    /** Termux:API's stream names (termux-volume) → AudioManager streams. */
    internal val STREAMS = linkedMapOf(
        "call" to AudioManager.STREAM_VOICE_CALL, "system" to AudioManager.STREAM_SYSTEM, "ring" to AudioManager.STREAM_RING,
        "music" to AudioManager.STREAM_MUSIC, "alarm" to AudioManager.STREAM_ALARM, "notification" to AudioManager.STREAM_NOTIFICATION,
    )

    private val SPEAK_STREAMS = mapOf(
        "ALARM" to AudioManager.STREAM_ALARM, "MUSIC" to AudioManager.STREAM_MUSIC, "NOTIFICATION" to AudioManager.STREAM_NOTIFICATION,
        "RING" to AudioManager.STREAM_RING, "SYSTEM" to AudioManager.STREAM_SYSTEM, "VOICE_CALL" to AudioManager.STREAM_VOICE_CALL,
    )

    private fun parseSpeak(name: String, args: List<String>, stdin: String?): Action {
        val spec = opts('h' to "h", 'e' to "e:", 'l' to "l:", 'n' to "n:", 'v' to "v:", 'p' to "p:", 'r' to "r:", 's' to "s:")
        val p = getopt(name, args, spec) as? Parsed ?: return bad(getopt(name, args, spec) as String, SPEAK_USAGE)
        if (p.has("h")) return usage(SPEAK_USAGE)
        val pitch = (p.value("p") ?: "1.0").toFloatOrNull()?.takeIf { it > 0 } ?: return bad("$name: invalid pitch '${p.value("p")}'", SPEAK_USAGE)
        val rate = (p.value("r") ?: "1.0").toFloatOrNull()?.takeIf { it > 0 } ?: return bad("$name: invalid rate '${p.value("r")}'", SPEAK_USAGE)
        val stream = SPEAK_STREAMS[(p.value("s") ?: "NOTIFICATION").uppercase()] ?: return bad("$name: invalid stream '${p.value("s")}'", SPEAK_USAGE)
        val text = if (p.rest.isNotEmpty()) p.rest.joinToString(" ") else stdin ?: return Action.NeedStdin(onlyIfPiped = false)
        return Action.Api(Req.Speak(text, p.value("e"), p.value("l"), p.value("n"), p.value("v"), pitch, rate, stream))
    }

    private val NOTIFY_SHORT = opts('h' to "help", 'c' to "content:", 'i' to "id:", 't' to "title:")
    private val NOTIFY_LONG = mapOf(
        "help" to Opt("help", false), "help-actions" to Opt("help-actions", false), "content" to Opt("content", true),
        "id" to Opt("id", true), "title" to Opt("title", true), "ongoing" to Opt("ongoing", false),
        "priority" to Opt("priority", true), "sound" to Opt("sound", false), "vibrate" to Opt("vibrate", true),
        "action" to Opt("action", true), "on-delete" to Opt("on-delete", true), "alert-once" to Opt("alert-once", false),
        "group" to Opt("group", true), "image-path" to Opt("image-path", true),
        "button1" to Opt("button1", true), "button1-action" to Opt("button1-action", true),
        "button2" to Opt("button2", true), "button2-action" to Opt("button2-action", true),
        "button3" to Opt("button3", true), "button3-action" to Opt("button3-action", true),
        // accepted and ignored: Android decides these per channel, or Kabuk has no equivalent
        "led-color" to Opt("led-color", true), "led-on" to Opt("led-on", true), "led-off" to Opt("led-off", true),
        "icon" to Opt("icon", true), "channel" to Opt("channel", true), "type" to Opt("type", true),
    )

    private fun parseNotification(name: String, args: List<String>, cwd: String, stdin: String?): Action {
        val p = getopt(name, args, NOTIFY_SHORT, NOTIFY_LONG) as? Parsed
            ?: return bad(getopt(name, args, NOTIFY_SHORT, NOTIFY_LONG) as String, NOTIFY_USAGE)
        if (p.has("help")) return usage(NOTIFY_USAGE)
        if (p.has("help-actions")) return usage(NOTIFY_ACTIONS_HELP)
        if (p.rest.isNotEmpty()) return bad("$name: unexpected argument ${p.rest[0]}", NOTIFY_USAGE)
        val type = p.value("type") ?: "default"
        if (type != "default") return Action.Reply(1, "$name: --type $type $NOT_IN_KABUK (only default)\n")
        val priority = p.value("priority") ?: "default"
        if (priority !in setOf("default", "high", "low", "max", "min")) return bad("$name: invalid priority '$priority'", NOTIFY_USAGE)
        val vibrate = p.value("vibrate")?.let { v ->
            v.split(',').map { it.trim().toLongOrNull()?.takeIf { ms -> ms in 0..10_000 } ?: return bad("$name: invalid vibration pattern '$v'", NOTIFY_USAGE) }
                .toLongArray()
        }
        val content = p.value("content") ?: stdin ?: return Action.NeedStdin(onlyIfPiped = true)
        val buttons = (1..3).mapNotNull { n -> p.value("button$n")?.let { Button(it, p.value("button$n-action")) } }
        return Action.Api(Req.Notify(NotificationSpec(
            id = p.value("id"), title = p.value("title"), content = content.trimEnd('\n'), ongoing = p.has("ongoing"),
            priority = priority, sound = p.has("sound"), vibrate = vibrate, action = p.value("action"),
            onDelete = p.value("on-delete"), buttons = buttons, alertOnce = p.has("alert-once"), group = p.value("group"),
            image = p.value("image-path")?.let { KabukCommands.resolve(cwd, it) },
        )))
    }

    // ---- JSON as Termux:API prints it (JsonWriter with a two-space indent) ----

    internal fun json(fields: List<Pair<String, Any?>>, indent: String = ""): String {
        if (fields.isEmpty()) return "{}"
        val inner = "$indent  "
        return fields.joinToString(",\n", "{\n", "\n$indent}") { (k, v) -> "$inner${quote(k)}: ${value(v)}" }
    }

    internal fun jsonArray(items: List<List<Pair<String, Any?>>>): String =
        if (items.isEmpty()) "[]" else items.joinToString(",\n", "[\n", "\n]") { "  " + json(it, "  ") }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Boolean, is Int, is Long -> v.toString()
        is Double -> if (v.isFinite()) v.toString() else "null"
        is Float -> if (v.isFinite()) v.toDouble().toString() else "null"
        else -> quote(v.toString())
    }

    internal fun quote(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c < ' ' || c == ' ' || c == ' ' -> b.append(String.format(Locale.ROOT, "\\u%04x", c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }

    /** What the ACTION_BATTERY_CHANGED sticky intent and BatteryManager say. */
    data class BatteryInfo(
        val present: Boolean, val technology: String?, val health: Int, val plugged: Int, val status: Int,
        val temperatureTenths: Int, val voltage: Int, val level: Int, val scale: Int,
        val current: Long?, val currentAverage: Long?, val chargeCounter: Long?, val energy: Long?, val cycle: Int?,
    )

    internal fun batteryJson(b: BatteryInfo): String {
        val health = when (b.health) {
            BatteryManager.BATTERY_HEALTH_COLD -> "COLD"; BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "UNSPECIFIED_FAILURE"; else -> "UNKNOWN"
        }
        val plugged = when (b.plugged) {
            0 -> "UNPLUGGED"; BatteryManager.BATTERY_PLUGGED_AC -> "PLUGGED_AC"; BatteryManager.BATTERY_PLUGGED_USB -> "PLUGGED_USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "PLUGGED_WIRELESS"; 8 -> "PLUGGED_DOCK"; else -> "PLUGGED_${b.plugged}"
        }
        val status = when (b.status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"; BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"; BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
            else -> "UNKNOWN"
        }
        val pct = if (b.scale > 0 && b.level >= 0) Math.round(b.level * 100f / b.scale) else -1
        return json(listOf(
            "present" to b.present, "technology" to b.technology, "health" to health, "plugged" to plugged,
            "status" to status, "temperature" to b.temperatureTenths / 10.0, "voltage" to b.voltage,
            "current" to b.current, "current_average" to b.currentAverage, "percentage" to pct, "level" to b.level,
            "scale" to b.scale, "charge_counter" to b.chargeCounter, "energy" to b.energy,
        ) + listOfNotNull(b.cycle?.let { "cycle" to it })) + "\n"
    }

    /** WifiInfo's fields; [ssid] as Android reports it ("\"name\"" or "<unknown ssid>" without location access). */
    data class WifiState(
        val bssid: String?, val frequency: Int, val ip: Int, val linkSpeed: Int, val mac: String?, val networkId: Int,
        val rssi: Int, val ssid: String?, val hidden: Boolean, val supplicant: String,
    )

    internal fun wifiJson(w: WifiState): String = json(listOf(
        "bssid" to w.bssid, "frequency_mhz" to w.frequency, "ip" to ipv4(w.ip), "link_speed_mbps" to w.linkSpeed,
        "mac_address" to w.mac, "network_id" to w.networkId, "rssi" to w.rssi,
        "ssid" to (w.ssid?.removeSurrounding("\"") ?: "<unknown ssid>"), "ssid_hidden" to w.hidden,
        "supplicant_state" to w.supplicant,
    )) + "\n"

    /** WifiInfo.getIpAddress is little-endian */
    internal fun ipv4(ip: Int) = "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"

    internal fun volumeJson(levels: List<Triple<String, Int, Int>>): String =
        jsonArray(levels.map { (s, v, m) -> listOf("stream" to s, "volume" to v, "max_volume" to m) }) + "\n"

    /** Regular files under [paths] (dirs: their files; with [recursive] the whole tree), never following links. */
    internal fun mediaFiles(paths: List<String>, recursive: Boolean, limit: Int = 100_000): List<String> {
        val out = mutableListOf<String>()
        for (p in paths) {
            val path = File(p).toPath()
            val attrs = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: continue
            if (attrs.isRegularFile) { out += p; continue }
            if (!attrs.isDirectory) continue
            Files.walkFileTree(path, emptySet(), if (recursive) Int.MAX_VALUE else 1, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, a: BasicFileAttributes): FileVisitResult {
                    if (a.isRegularFile) out += file.toString()
                    return if (out.size >= limit) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
            })
            if (out.size >= limit) break
        }
        return out
    }

    // ---- on the phone (main thread) ----

    /** Runs [req]; [done] gets the answer (later for text-to-speech, which has to start its engine first). */
    fun execute(ctx: Context, name: String, req: Req, done: (Answer) -> Unit) {
        if (req is Req.Speak) { speak(ctx, name, req, done); return }
        val a = try {
            run(ctx, name, req)
        } catch (e: SecurityException) {
            KLog.w(TAG, "$name: not allowed (${e.javaClass.simpleName})")
            Answer(1, "$name: Android does not allow this: ${e.message}\n")
        }
        done(a)
    }

    private fun ok(name: String, what: String = "ok", text: String = ""): Answer { KLog.i(TAG, "$name: $what"); return Answer(0, text) }
    private fun fail(name: String, why: String, text: String): Answer { KLog.w(TAG, "$name: $why"); return Answer(1, "$name: $text\n") }

    private fun run(ctx: Context, name: String, req: Req): Answer = when (req) {
        Req.ClipboardGet -> {
            // Android 10+ gives the clipboard only to the app in front (with focus)
            if (Build.VERSION.SDK_INT >= 29 && !TabManager.foreground)
                fail(name, "refused, Kabuk not in front", "Android lets only the app on screen read the clipboard: switch to Kabuk and run it again")
            else {
                val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                ok(name, "read (${text.length} chars)", text)
            }
        }
        is Req.ClipboardSet -> {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("termux-clipboard-set", req.text))
            ok(name, "set (${req.text.length} chars)")
        }
        is Req.Toast -> {
            val t = Toast.makeText(ctx, req.text, if (req.short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG)
            when (req.gravity) {
                "top" -> t.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 120)
                "middle" -> t.setGravity(Gravity.CENTER, 0, 0)
            }
            t.show()
            ok(name, "shown (${req.gravity})")
        }
        is Req.Vibrate -> vibrate(ctx, name, req)
        is Req.Torch -> torch(ctx, name, req.on)
        is Req.Speak -> error("async")
        is Req.Notify -> notify(ctx, name, req.n)
        is Req.NotifyRemove -> {
            ctx.getSystemService(NotificationManager::class.java).cancel(tag(req.id), NOTIFICATION_ID)
            ok(name, "removed")
        }
        is Req.Share -> share(ctx, name, req)
        Req.Battery -> ok(name, text = batteryJson(battery(ctx)))
        Req.Wifi -> wifi(ctx, name)
        is Req.Volume -> volume(ctx, name, req)
        is Req.MediaScan -> error("off the main thread: mediaScan")
        is Req.Download -> download(ctx, name, req)
    }

    private fun vibrate(ctx: Context, name: String, req: Req.Vibrate): Answer {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (!req.force && am.ringerMode == AudioManager.RINGER_MODE_SILENT) return ok(name, "skipped (silent mode)")
        val v = ctx.getSystemService(Vibrator::class.java)
        if (v == null || !v.hasVibrator()) return fail(name, "no vibrator", "this phone has no vibrator")
        val attrs = AudioAttributes.Builder().setUsage(if (req.force) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION).build()
        v.vibrate(VibrationEffect.createOneShot(req.ms, VibrationEffect.DEFAULT_AMPLITUDE), attrs)
        return ok(name, "${req.ms} ms")
    }

    private fun torch(ctx: Context, name: String, on: Boolean): Answer {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = runCatching {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull() ?: return fail(name, "no flash", "this phone has no flashlight Kabuk can use")
        return try {
            cm.setTorchMode(id, on)
            ok(name, if (on) "on" else "off")
        } catch (e: Exception) {
            fail(name, "failed (${e.javaClass.simpleName})", "the flashlight is busy or unavailable (${e.message})")
        }
    }

    /** One engine per process (per -e), started on first use; utterances wait for its init. */
    private var tts: TextToSpeech? = null
    private var ttsEngine: String? = null
    private var ttsReady = false
    private val ttsWaiting = mutableListOf<() -> Unit>()
    private val utterance = AtomicInteger()

    /**
     * One termux-tts-speak: answered when its last piece was spoken (or at the first error). Main thread. [voice]
     * and [pitch]/[rate]/[params] are what its pieces are spoken with, also when they are queued again.
     */
    private class SpeechJob(
        val name: String, val chars: Int, var left: Int, val done: (Answer) -> Unit,
        val voice: android.speech.tts.Voice?, val pitch: Float, val rate: Float, val params: Bundle,
    ) {
        var answered = false
        /** the engine began one of its pieces (onStart): the speech cap counts from here */
        var started = false
        var cap: Runnable? = null
        fun finish(a: Answer) {
            if (answered) return
            answered = true
            cap?.let { TabManager.main.removeCallbacks(it) }
            done(a)
        }
    }

    /**
     * What Kabuk has handed to the engine and not yet heard back about, in speaking order (utterance id → job and
     * text). TextToSpeech can only stop everything it has queued, so cancelling one request [cancel]s its pieces
     * and gives back the others' pieces, which the caller queues again after stop() (a piece that was being spoken
     * starts over). Ids it no longer knows (the stopped ones' onStop) are ignored. Main thread.
     */
    internal class SpeechQueue<J : Any> {
        class Piece<J>(val job: J, val text: String)
        private val queued = LinkedHashMap<String, Piece<J>>()
        fun add(id: String, job: J, text: String) { queued[id] = Piece(job, text) }
        /** [id] was spoken or failed: its job, null if it is not (or no longer) queued */
        fun take(id: String): J? = queued.remove(id)?.job
        fun jobOf(id: String): J? = queued[id]?.job
        /** the job whose piece the engine speaks now (or next) */
        fun head(): J? = queued.values.firstOrNull()?.job
        fun has(job: J) = queued.values.any { it.job === job }
        fun jobs(): Set<J> = queued.values.mapTo(LinkedHashSet()) { it.job }
        fun ids(): List<String> = queued.keys.toList()
        fun clear() = queued.clear()
        /** forgets [job]'s pieces without touching the others (what the engine already has may still be spoken) */
        fun drop(job: J) { queued.values.removeAll { it.job === job } }
        /**
         * Drops [job]'s pieces. null when it had none queued (nothing to stop); else the other jobs' pieces in
         * order, which leave the queue too: the caller stops the engine and queues them again with new ids.
         */
        fun cancel(job: J): List<Piece<J>>? {
            if (!has(job)) return null
            val rest = queued.values.filter { it.job !== job }
            queued.clear()
            return rest
        }
    }
    private val speaking = SpeechQueue<SpeechJob>()
    /** tests: the utterance ids Kabuk waits for, in order */
    internal fun speakingIds(): List<String> = speaking.ids()

    /**
     * How long a termux-tts-speak may take before Kabuk gives up (engine stuck): 30 s + 150 ms per character of
     * the text, at most 10 min.
     */
    internal fun speechCapMs(chars: Int): Long = (30_000L + 150L * chars).coerceAtMost(600_000L)

    /** Main thread: queues [text] for [job] with its voice and settings; false when the engine refused it. */
    private fun send(t: TextToSpeech, job: SpeechJob, text: String, settings: Boolean): Boolean {
        if (settings) {
            job.voice?.let { runCatching { t.setVoice(it) } }
            t.setPitch(job.pitch)
            t.setSpeechRate(job.rate)
        }
        val id = "kabuk-${utterance.incrementAndGet()}"
        speaking.add(id, job, text)
        if (t.speak(text, TextToSpeech.QUEUE_ADD, job.params, id) == TextToSpeech.SUCCESS) return true
        speaking.take(id)
        return false
    }

    /**
     * Main thread: drops [job]'s queued pieces. The engine can only stop all of its speech, so the other requests'
     * pieces are queued again right after (in their order; one being spoken starts over).
     */
    private fun abandon(job: SpeechJob) {
        val rest = speaking.cancel(job) ?: return
        val t = tts ?: return
        t.stop()
        var last: SpeechJob? = null
        val failed = LinkedHashSet<SpeechJob>()
        for (p in rest) {
            if (p.job in failed) continue
            if (!send(t, p.job, p.text, settings = p.job !== last)) failed += p.job
            last = p.job
        }
        if (rest.isNotEmpty()) KLog.i(TAG, "speech of ${rest.map { it.job }.toSet().size} other request(s) queued again")
        for (j in failed) {
            speaking.drop(j)
            j.finish(fail(j.name, "speak failed", "the text-to-speech engine refused the text"))
        }
    }

    /** Main thread: the program of the request answered through [done] is gone (Ctrl-C): stop its speech only. */
    fun cancelSpeech(done: (Answer) -> Unit) {
        val job = speaking.jobs().firstOrNull { it.done === done } ?: return
        abandon(job)
        if (!job.answered) KLog.i(TAG, "${job.name}: speech stopped (the program ended)")
        job.finish(Answer(1, "${job.name}: interrupted\n"))
    }

    /**
     * [text] in pieces the engine accepts ([max] chars, TextToSpeech.getMaxSpeechInputLength): line by line like
     * Termux:API reads stdin, a longer line split at the last space (or hard). Blank lines are dropped.
     */
    internal fun speechPieces(text: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.split('\n')) {
            var line = raw.trim()
            while (line.length > max) {
                val cut = line.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
                out += line.substring(0, cut).trim()
                line = line.substring(cut).trim()
            }
            if (line.isNotEmpty()) out += line
        }
        return out.filter { it.isNotEmpty() }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { TabManager.main.post { started(utteranceId) } }
        override fun onDone(utteranceId: String?) { TabManager.main.post { piece(utteranceId, null) } }
        @Deprecated("API 21", ReplaceWith("onError(utteranceId, errorCode)"))
        override fun onError(utteranceId: String?) { TabManager.main.post { piece(utteranceId, "the text-to-speech engine could not speak the text") } }
        override fun onError(utteranceId: String?, errorCode: Int) {
            TabManager.main.post { piece(utteranceId, "the text-to-speech engine could not speak the text (error $errorCode)") }
        }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { TabManager.main.post { piece(utteranceId, "speech was stopped") } }
    }

    /**
     * Main thread: the speech cap of [job], from now. It counts from the job's first spoken piece ([started]), so a
     * request waiting behind another one's long text is not given up on; when it fires while the job still waits
     * and the engine is busy with another request that did start, it waits another round. A stuck engine starts
     * nothing, so then the cap ends the job at the head of the queue, and after it the next one.
     */
    private fun armCap(job: SpeechJob) {
        job.cap?.let { TabManager.main.removeCallbacks(it) }
        val r = Runnable {
            if (job.answered) return@Runnable
            val head = speaking.head()
            if (!job.started && head != null && head !== job && head.started) { armCap(job); return@Runnable }
            abandon(job)
            KLog.w(TAG, "${job.name}: speech did not finish within ${speechCapMs(job.chars) / 1000} s, stopped")
            job.finish(Answer(1, "${job.name}: speech did not finish (engine stuck?)\n"))
        }
        job.cap = r
        TabManager.main.postDelayed(r, speechCapMs(job.chars))
    }

    /** Main thread: the engine began piece [id]; the first piece of a request restarts its cap. */
    private fun started(id: String?) {
        val job = speaking.jobOf(id ?: return) ?: return
        if (job.started) return
        job.started = true
        if (!job.answered) armCap(job)
    }

    /** Main thread: one piece is spoken ([error] null) or failed (the rest of its request is dropped). */
    private fun piece(id: String?, error: String?) {
        val job = speaking.take(id ?: return) ?: return
        if (error != null) {
            abandon(job)
            if (!job.answered) KLog.w(TAG, "${job.name}: ${error.substringBefore(" (")}")
            job.finish(Answer(1, "${job.name}: $error\n"))
        } else if (--job.left == 0) {
            if (!job.answered) KLog.i(TAG, "${job.name}: spoken (${job.chars} chars)")
            job.finish(Answer(0, ""))
        }
    }

    private fun speak(ctx: Context, name: String, req: Req.Speak, done: (Answer) -> Unit) {
        val say = say@{
            val t = tts ?: return@say done(fail(name, "no engine", "no text-to-speech engine"))
            if (req.language != null) {
                val loc = Locale(req.language, req.region.orEmpty(), req.variant.orEmpty())
                if (t.setLanguage(loc) < TextToSpeech.LANG_AVAILABLE) KLog.w(TAG, "$name: language not available")
            }
            val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, req.stream) }
            val pieces = speechPieces(req.text, runCatching { TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(4000))
            if (pieces.isEmpty()) return@say done(ok(name, "nothing to say"))
            // the voice in effect now (this request's -l, else what the engine uses): kept for a re-queue
            val voice = runCatching { t.voice }.getOrNull()
            val job = SpeechJob(name, req.text.length, pieces.size, done, voice, req.pitch, req.rate, params)
            armCap(job)
            t.setPitch(req.pitch)
            t.setSpeechRate(req.rate)
            for (p in pieces) {
                if (!send(t, job, p, settings = false)) {
                    abandon(job)
                    return@say job.finish(fail(name, "speak failed", "the text-to-speech engine refused the text"))
                }
            }
        }
        if (tts != null && ttsEngine == req.engine) {
            if (ttsReady) say() else ttsWaiting += say
            return
        }
        // another engine: what the old one still had queued is cut off
        speaking.jobs().forEach { it.finish(Answer(1, "${it.name}: interrupted (another termux-tts-speak switched the engine)\n")) }
        speaking.clear()
        tts?.shutdown()
        ttsReady = false
        ttsWaiting.clear()
        ttsWaiting += say
        ttsEngine = req.engine
        val listener = TextToSpeech.OnInitListener { status ->
            TabManager.main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.setOnUtteranceProgressListener(progress)
                    ttsWaiting.toList().also { ttsWaiting.clear() }.forEach { it() }
                } else {
                    tts = null
                    val w = ttsWaiting.toList(); ttsWaiting.clear()
                    KLog.w(TAG, "$name: engine did not start ($status)")
                    w.forEach { _ -> done(Answer(1, "$name: the text-to-speech engine did not start (is one installed?)\n")) }
                }
            }
        }
        tts = if (req.engine != null) TextToSpeech(ctx, listener, req.engine) else TextToSpeech(ctx, listener)
    }

    /**
     * Called by [app.kabuk.Notifications.ensureChannels]: "Scripts" and, for `--priority high|max`, "Scripts
     * (important)" (heads-up). Both silent: --sound and --vibrate are played per notification.
     */
    fun createChannel(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Scripts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifications from programs in the Phone tab (termux-notification)"
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HIGH, "Scripts (important)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "termux-notification --priority high or max: shown on top of other apps"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    /** The channel a notification with termux-notification's --priority goes to. */
    internal fun channelFor(priority: String) = if (priority == "high" || priority == "max") CHANNEL_HIGH else CHANNEL

    private const val NOTIFICATION_ID = 1
    private fun tag(id: String) = "script:$id"
    private val autoId = AtomicInteger()

    private fun notify(ctx: Context, name: String, n: NotificationSpec): Answer {
        app.kabuk.Notifications.ensureChannels(ctx)
        val id = n.id ?: "kabuk-${System.currentTimeMillis()}-${autoId.incrementAndGet()}"
        fun pending(slot: Int, cmd: String?): PendingIntent? = cmd?.let {
            val i = Intent(ctx, ScriptActionReceiver::class.java)
                .setData(Uri.parse("kabuk-script://notification/${Uri.encode(id)}/$slot"))
                .putExtra(ScriptActionReceiver.EXTRA_COMMAND, it)
            PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val b = Notification.Builder(ctx, channelFor(n.priority))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentText(n.content)
            .setStyle(Notification.BigTextStyle().bigText(n.content))
            .setOngoing(n.ongoing)
            .setOnlyAlertOnce(n.alertOnce)
            .setAutoCancel(n.action == null && !n.ongoing)
        n.title?.let { b.setContentTitle(it) }
        n.group?.let { b.setGroup(it) }
        b.setContentIntent(pending(0, n.action) ?: PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        pending(4, n.onDelete)?.let { b.setDeleteIntent(it) }
        n.buttons.forEachIndexed { i, btn ->
            val pi = pending(i + 1, btn.action ?: "")
            b.addAction(Notification.Action.Builder(null, btn.text, pi).build())
        }
        n.image?.let { path ->
            val bmp = runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var s = 1
                while (o.outWidth / s > 2048 || o.outHeight / s > 2048) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
            }.getOrNull() ?: return fail(name, "image unreadable", "--image-path: cannot read the image")
            b.setLargeIcon(bmp).setStyle(Notification.BigPictureStyle().bigPicture(bmp))
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(tag(id), NOTIFICATION_ID, b.build())
        if (n.sound) runCatching {
            RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        }
        n.vibrate?.let { pattern ->
            runCatching { ctx.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0) + pattern, -1)) }
        }
        val off = !app.kabuk.Notifications.enabled(ctx)
        return if (off) fail(name, "notifications are off", "notifications are switched off for Kabuk (Android settings → Apps → Kabuk → Notifications)")
            else ok(name, "posted (${n.buttons.size} buttons, ${listOfNotNull(n.action, n.onDelete).size + n.buttons.count { it.action != null }} actions)")
    }

    private fun share(ctx: Context, name: String, req: Req.Share): Answer {
        val i: Intent
        if (req.file != null) {
            val f = File(req.file)
            if (!f.exists()) return Answer(1, "$name: ${req.file}: No such file or directory\n")
            if (f.isDirectory) return Answer(1, "$name: ${req.file}: is a directory\n")
            if (!f.canRead()) return Answer(1, "$name: ${req.file}: Permission denied\n")
            val uri = KabukCommands.contentUri(ctx, f)
                ?: return Answer(1, "$name: ${req.file}: only files in Kabuk's home/usr or on shared storage can be shared\n")
            val type = req.type ?: KabukCommands.mimeType(f.name)
            i = when (req.action) {
                "send" -> Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).also { it.clipData = ClipData.newRawUri("", uri) }
                "edit" -> Intent(Intent.ACTION_EDIT).setDataAndType(uri, type)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                else -> Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.putExtra(Intent.EXTRA_TITLE, req.title ?: f.name)
        } else {
            val text = req.text.orEmpty()
            if (text.length > MAX_SHARE_TEXT) return Answer(1, "$name: the text is too long to share (${text.length} chars); save it to a file and share that\n")
            i = Intent(Intent.ACTION_SEND).setType(req.type ?: "text/plain").putExtra(Intent.EXTRA_TEXT, text)
            req.title?.let { i.putExtra(Intent.EXTRA_SUBJECT, it).putExtra(Intent.EXTRA_TITLE, it) }
        }
        val what = if (req.file != null) "a file (${req.action})" else "text"
        KLog.i(TAG, "$name: $what${if (req.chooser) ", chooser" else ""}")
        val target = if (req.chooser) Intent.createChooser(i, req.title).also { c -> c.addFlags(i.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) } else i
        return KabukCommands.startActivity(ctx, name, target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null,
            if (req.chooser) "the share sheet" else "an app to ${req.action} $what")
    }

    private fun battery(ctx: Context): BatteryInfo {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: Intent()
        val bm = ctx.getSystemService(BatteryManager::class.java)
        fun prop(p: Int): Long? = bm?.getLongProperty(p)?.takeIf { it != Long.MIN_VALUE }
        return BatteryInfo(
            present = i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true),
            technology = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN),
            plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
            temperatureTenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
            voltage = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
            level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            current = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            currentAverage = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            chargeCounter = prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            energy = prop(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
            cycle = if (Build.VERSION.SDK_INT >= 34) i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 } else null,
        )
    }

    @Suppress("DEPRECATION")
    private fun wifi(ctx: Context, name: String): Answer {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            ?: return fail(name, "no Wi-Fi", "this phone has no Wi-Fi")
        val info = wm.connectionInfo ?: return Answer(0, json(listOf("API_ERROR" to "No current connection")) + "\n")
        val ssid = info.ssid?.takeIf { it != WifiManager.UNKNOWN_SSID && it.isNotEmpty() }
        return ok(name, text = wifiJson(WifiState(
            bssid = info.bssid, frequency = info.frequency, ip = info.ipAddress, linkSpeed = info.linkSpeed,
            mac = info.macAddress, networkId = info.networkId, rssi = info.rssi, ssid = ssid,
            hidden = info.hiddenSSID, supplicant = info.supplicantState?.name ?: "UNKNOWN",
        )))
    }

    private fun volume(ctx: Context, name: String, req: Req.Volume): Answer {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (req.stream == null) {
            return ok(name, text = volumeJson(STREAMS.map { (s, id) -> Triple(s, am.getStreamVolume(id), am.getStreamMaxVolume(id)) }))
        }
        val id = STREAMS.getValue(req.stream)
        val max = am.getStreamMaxVolume(id)
        return try {
            am.setStreamVolume(id, req.volume!!.coerceAtMost(max), 0)
            ok(name, "${req.stream} set")
        } catch (e: SecurityException) {
            fail(name, "not allowed (do not disturb)", "Android does not allow changing the ${req.stream} volume now (Do Not Disturb is on?)")
        }
    }

    /** Off the main thread (walking a tree can take a while). */
    fun mediaScan(ctx: Context, name: String, req: Req.MediaScan): Answer {
        val files = mediaFiles(req.paths, req.recursive)
        if (files.isNotEmpty()) MediaScannerConnection.scanFile(ctx, files.toTypedArray(), null, null)
        KLog.i(TAG, "$name: ${files.size} file(s)")
        val verbose = if (req.verbose) files.joinToString("") { "$it\n" } else ""
        return Answer(0, verbose + "Finished scanning ${files.size} file(s)\n")
    }

    private fun download(ctx: Context, name: String, req: Req.Download): Answer {
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return fail(name, "no download manager", "this phone has no download manager")
        val r = DownloadManager.Request(Uri.parse(req.url))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setTitle(req.title ?: URLUtil.guessFileName(req.url, null, null))
        req.description?.let { r.setDescription(it) }
        try {
            if (req.path != null) r.setDestinationUri(Uri.fromFile(File(req.path)))
            else r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(req.url, null, null))
            dm.enqueue(r)
        } catch (e: Exception) {
            return fail(name, "refused (${e.javaClass.simpleName})", "the download manager refused this (${e.message}); " +
                "without -p files go to the Download folder")
        }
        return ok(name, "queued ${UrlOpener.origin(req.url)}")
    }

    // ---- usage texts (Termux:API's) ----

    private const val CLIP_GET_USAGE = "Usage: termux-clipboard-get\nGet the system clipboard text.\n"
    private const val CLIP_SET_USAGE = "Usage: termux-clipboard-set [text]\nSet the system clipboard text. The text to set is either supplied as arguments or read from stdin if no arguments are given.\n"
    private const val TOAST_USAGE = """Usage: termux-toast [-b bgcolor] [-c color] [-g gravity] [-s] [text]
Show text in a Toast (a transient popup). The text to show is either supplied as arguments or read from stdin if no arguments are given.
  -h  show this help
  -b  set background color (ignored in Kabuk)
  -c  set text color (ignored in Kabuk)
  -g  set position of toast: [top, middle, or bottom] (default: middle)
  -s  only show the toast for a short while
"""
    private const val VIBRATE_USAGE = """Usage: termux-vibrate [-d duration] [-f]
Vibrate the device.
  -d duration  the duration to vibrate in ms (default:1000)
  -f           force vibration even in silent mode
"""
    private const val TORCH_USAGE = "Usage: termux-torch [on | off]\nToggle LED Torch on device\n"
    private const val SPEAK_USAGE = """Usage: termux-tts-speak [-e engine] [-l language] [-n region] [-v variant] [-p pitch] [-r rate] [-s stream] [text-to-speak]
Speak text with a system text-to-speech (TTS) engine. The text to speak is either supplied as arguments or read from stdin if no arguments are given.
  -e engine    TTS engine to use (package name, e.g. com.google.android.tts)
  -l language  language to speak in (may be unsupported by the engine)
  -n region    region of language to speak in
  -v variant   variant of the language to speak in
  -p pitch     pitch to use in speech. 1.0 is the normal pitch
  -r rate      speech rate to use. 1.0 is the normal speech rate
  -s stream    audio stream to use (default:NOTIFICATION), one of: ALARM, MUSIC, NOTIFICATION, RING, SYSTEM, VOICE_CALL
"""
    private const val NOTIFY_USAGE = """Usage: termux-notification [options]
Display a system notification. Content text is specified using -c/--content or read from stdin.
Please read --help-actions for help with notification actions.
  --action action          action to execute when pressing the notification
  --alert-once             do not alert when the notification is edited
  --button1 text           text to show on the first notification button
  --button1-action action  action to execute on the first notification button
  --button2 text           text to show on the second notification button
  --button2-action action  action to execute on the second notification button
  --button3 text           text to show on the third notification button
  --button3-action action  action to execute on the third notification button
  -c/--content content     content to show in the notification. Will take
                           precedence over stdin.
  --group group            notification group (notifications with the same
                           group are shown together)
  -h/--help                show this help
  --help-actions           show the help for actions
  -i/--id id               notification id (will overwrite any previous notification
                           with the same id)
  --image-path path        absolute path to an image which will be shown in the
                           notification
  --on-delete action       action to execute when the the notification is cleared
  --ongoing                pin the notification
  --priority prio          notification priority (high/low/max/min/default);
                           high and max show on top ("Scripts (important)")
  --sound                  play a sound with the notification
  -t/--title title         notification title to show
  --vibrate pattern        vibrate pattern, comma separated as in 500,1000,200
Accepted and ignored in Kabuk: --icon, --led-color, --led-on, --led-off, --channel. --type media is not supported.
Kabuk's channels: "Scripts" (default, low, min) and "Scripts (important)" (high, max; heads-up).
"""
    private const val NOTIFY_ACTIONS_HELP = """This help refers to the arguments to options like --action, --on-delete, --button1-action.

In Kabuk an action is a shell command line. It runs with `sh -c` in the background, in the Phone tab's
environment (Termux's sh and PATH once the packages are installed, else busybox), in your home folder; its output
is discarded. So it can call termux-toast, termux-open-url, or a script of yours:

  termux-notification -t Backup --button1 Run --button1-action "~/bin/backup.sh"
  termux-notification -c hi --action "termux-toast tapped"

Without --action, tapping the notification opens Kabuk.
"""
    private const val NOTIFY_REMOVE_USAGE = "Usage: termux-notification-remove notification-id\nRemove a notification previously shown with termux-notification --id.\n"
    private const val SHARE_USAGE = """Usage: termux-share [-a action] [-c content-type] [-d] [-t title] [file]
Share a file specified as argument or the text received on stdin if no file argument is given.
  -a action        which action to performed on the shared content:
                     edit/send/view (default:view)
  -c content-type  content-type to use (default: guessed from file extension,
                     text/plain for stdin)
  -d               share to the default receiver if one is selected
                     instead of showing a chooser
  -t title         title to use for shared content (default: shared file name)
"""
    private const val BATTERY_USAGE = "Usage: termux-battery-status\nGet the status of the device battery.\n"
    private const val WIFI_USAGE = "Usage: termux-wifi-connectioninfo\nGet information about the current wifi connection.\n"
    private const val VOLUME_USAGE = """Usage: termux-volume stream volume
Change volume of audio stream
Valid audio streams are: alarm, music, notification, ring, system, call
Call w/o arguments to show information about each audio stream.
"""
    private const val MEDIA_SCAN_USAGE = """Usage: termux-media-scan [-v] [-r] file [file...]
Scan the specified file(s) and add it to the media content provider.
  -r  scan directories recursively
  -v  verbose mode
"""
    private const val DOWNLOAD_USAGE = """Usage: termux-download [-d description] [-t title] [-p path] url-to-download
Download a resource using the system download manager.
  -d description  description for the download request notification
  -t title        title for the download request notification
  -p path         path to save the file (on shared storage; default: the Download folder)
"""
}

/**
 * termux-notification's --action / --buttonN-action / --on-delete: runs the command line with `sh -c` in the
 * Phone tab's environment, in the background (output discarded). Not exported; the PendingIntents are immutable and
 * explicit, so only Kabuk's own notifications reach it. Logs the exit code only, never the command.
 */
class ScriptActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra(EXTRA_COMMAND)?.takeIf { it.isNotBlank() } ?: return
        val ctx = context.applicationContext
        Thread({
            runCatching {
                val setup = LocalTools.prepare(ctx)
                val sh = if (setup.termux) File(TermuxEnv.prefix(ctx), "bin/sh").path
                    else setup.env.firstOrNull { it.startsWith("SHELL=") }?.substringAfter('=') ?: "/system/bin/sh"
                val pb = ProcessBuilder(sh, "-c", cmd).directory(setup.home).redirectErrorStream(true)
                    .redirectOutput(File("/dev/null"))
                pb.environment().clear()
                setup.env.forEach { kv -> kv.indexOf('=').takeIf { it > 0 }?.let { pb.environment()[kv.substring(0, it)] = kv.substring(it + 1) } }
                val p = pb.start()
                p.outputStream.close()
                KLog.i("CMD", "notification action finished (exit ${p.waitFor()})")
            }.onFailure { KLog.w("CMD", "notification action failed: ${it.javaClass.simpleName}") }
        }, "notification-action").start()
    }

    companion object {
        const val EXTRA_COMMAND = "app.kabuk.extra.COMMAND"
    }
}
