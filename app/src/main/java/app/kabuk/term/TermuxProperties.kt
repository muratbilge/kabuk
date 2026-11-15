package app.kabuk.term

import app.kabuk.data.Prefs
import java.io.File

/** Phone-only settings. Reload replaces the snapshot, including removal of old overrides. */
object TermuxProperties {
    private val legacyKeys = setOf("terminal-transcript-rows", "bell-character", "volume-keys")
    val SUPPORTED = listOf("terminal-transcript-rows", "bell-character", "volume-keys", "extra-keys",
        "extra-keys-style", "terminal-cursor-style", "terminal-cursor-blink-rate", "use-black-ui",
        "hide-soft-keyboard-on-startup")
    val help = "Kabuk reads: ${SUPPORTED.joinToString(", ")}, colors.properties and font.ttf. " +
        "Phone tabs only; transcript size applies to new tabs. fullscreen and shortcut.* are ignored. " +
        "DRAWER opens Kabuk's servers screen; swipe up for popup keys.\n"
    data class Snapshot(
        val props: Map<String, String> = emptyMap(),
        val keys: List<List<app.kabuk.ui.Key>>? = null,
        val colors: Map<Int, Int> = emptyMap(),
        val font: android.graphics.Typeface? = null,
        val cursorStyle: Int = 0,
        val blinkRate: Int = 0,
        val black: Boolean = false,
        val hideKeyboard: Boolean = false,
    ) {
        fun prefs(base: Prefs) = apply(props.filterKeys { it in legacyKeys }, base).first
    }
    class Result(val applied: List<String>, val ignored: List<String>, val errors: List<String>, val found: Boolean) {
        fun describe(): String = buildString {
            if (!found) append("No ~/.termux settings files; using Kabuk defaults.\n")
            if (applied.isNotEmpty()) append("Applied: ${applied.joinToString(", ")}\n")
            errors.forEach { append("Not applied: $it\n") }
            if (ignored.isNotEmpty()) append("Ignored (not supported in Kabuk): ${ignored.joinToString(", ")}\n")
            if (!found) append(help)
        }
    }

    /** `key=value` / `key = value` / `key: value` lines; `#` and `!` comments; later keys win. */
    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        // Join Java-properties continuations without unescaping JSON backslashes.
        val logical = text.replace(Regex("\\\\\\r?\\n[ \\t]*"), "")
        for (raw in logical.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
            val i = line.indexOfFirst { it == '=' || it == ':' }
            if (i <= 0) continue
            out[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        return out
    }

    /** The new settings and what happened to each key. */
    fun apply(props: Map<String, String>, p: Prefs): Pair<Prefs, Result> {
        var n = p
        val applied = mutableListOf<String>(); val ignored = mutableListOf<String>(); val errors = mutableListOf<String>()
        for ((k, v) in props) when (k) {
            "terminal-transcript-rows" -> {
                val rows = v.toIntOrNull()
                if (rows == null || rows !in 100..50000) errors += "$k (invalid value; 100..50000)"
                else { n = n.copy(scrollbackLines = rows); applied += "$k=$rows (new tabs)" }
            }
            "bell-character" -> when (v.lowercase()) {
                "vibrate" -> { n = n.copy(bellVibrate = true); applied += "$k=vibrate" }
                "ignore" -> { n = n.copy(bellVibrate = false); applied += "$k=ignore" }
                "beep" -> errors += "$k=beep (Kabuk can vibrate or ignore the bell)"
                else -> errors += "$k (invalid value; vibrate|beep|ignore)"
            }
            // Termux: "virtual" = Volume Down/Up act as Ctrl/Fn, "volume" (default) = they change the volume
            "volume-keys" -> when (v.lowercase()) {
                "virtual" -> { n = n.copy(volumeKeys = true); applied += "$k=virtual" }
                "volume" -> { n = n.copy(volumeKeys = false); applied += "$k=volume" }
                else -> errors += "$k (invalid value; virtual|volume)"
            }
            "extra-keys" -> try {
                TermuxExtraKeys.parse(v, props["extra-keys-style"]?.takeIf { it in TermuxExtraKeys.styles } ?: "default")
                applied += k
            } catch (_: Exception) { errors += "$k (invalid layout; using Kabuk bar)" }
            "extra-keys-style" -> if (v in TermuxExtraKeys.styles) applied += k else errors += "$k (invalid style)"
            "terminal-cursor-style" -> if (v in listOf("block", "underline", "bar")) applied += k else errors += "$k (block|underline|bar)"
            "terminal-cursor-blink-rate" -> if (v.toIntOrNull()?.let { it == 0 || it in 100..2000 } == true) applied += k else errors += "$k (0 or 100..2000 ms)"
            "use-black-ui", "hide-soft-keyboard-on-startup" -> if (v in listOf("true", "false")) applied += k else errors += "$k (true|false)"
            else -> ignored += k
        }
        return n to Result(applied, ignored, errors, found = true)
    }

    /** Errors name the setting/file only, never include file contents or exception messages. */
    fun load(home: File): Pair<Snapshot, Result> {
        val dir = File(home, ".termux")
        val errors = mutableListOf<String>()
        fun read(f: File): String? {
            if (!f.exists()) return null
            return try {
                require(f.isFile && f.length() <= 262144)
                f.readText()
            } catch (_: Exception) { errors += "${f.name} (unreadable or too large)"; null }
        }
        val primary = File(dir, "termux.properties")
        val file = if (primary.exists()) primary else File(home, ".config/termux/termux.properties")
        val props = read(file)?.let(::parse).orEmpty()
        val result = apply(props, Prefs()).second
        val keys = props["extra-keys"]?.let { runCatching {
            TermuxExtraKeys.parse(it, props["extra-keys-style"]?.takeIf { s -> s in TermuxExtraKeys.styles } ?: "default")
        }.getOrNull() }
        val colors = mutableMapOf<Int, Int>()
        read(File(dir, "colors.properties"))?.let { text ->
            for ((k, v) in parse(text)) {
                val index = when (k) {
                    "foreground" -> 256; "background" -> 257; "cursor" -> 258
                    else -> k.removePrefix("color").toIntOrNull()?.takeIf { k.startsWith("color") && it in 0..15 }
                }
                if (index == null) continue
                val color = parseColor(v)
                if (color == null) errors += "colors.properties: $k (invalid color)" else colors[index] = color
            }
        }
        val fontFile = File(dir, "font.ttf")
        val font = if (fontFile.exists()) try {
            require(fontFile.isFile && fontFile.length() in 1..(32L * 1024 * 1024))
            android.graphics.Typeface.createFromFile(fontFile)
        } catch (_: Exception) { errors += "font.ttf (invalid or unreadable; using Kabuk font)"; null } else null
        val snap = Snapshot(props, keys, colors, font,
            listOf("block", "underline", "bar").indexOf(props["terminal-cursor-style"]).coerceAtLeast(0),
            props["terminal-cursor-blink-rate"]?.toIntOrNull()?.takeIf { it == 0 || it in 100..2000 } ?: 0,
            props["use-black-ui"] == "true", props["hide-soft-keyboard-on-startup"] == "true")
        return snap to Result(result.applied + listOfNotNull(
            "colors.properties".takeIf { colors.isNotEmpty() }, "font.ttf".takeIf { font != null }),
            result.ignored, result.errors + errors, file.exists() || File(dir, "colors.properties").exists() || fontFile.exists())
    }

    fun parseColor(value: String): Int? {
        val hex = when {
            value.matches(Regex("#[0-9a-fA-F]{6}")) -> value.drop(1)
            value.matches(Regex("#[0-9a-fA-F]{3}")) -> value.drop(1).map { "$it$it" }.joinToString("")
            else -> return null
        }
        return (0xff000000L or hex.toLong(16)).toInt()
    }

    fun reload(home: File): Result {
        val (snapshot, result) = load(home)
        TabManager.tabs.value.filterIsInstance<LocalTab>().forEach { it.applyProperties(snapshot) }
        return result
    }
}
