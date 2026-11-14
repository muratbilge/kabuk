package app.kabuk.term

import app.kabuk.data.Prefs
import app.kabuk.data.Settings
import java.io.File

/**
 * `termux-reload-settings`: the few `~/.termux/termux.properties` settings that mean something in Kabuk are
 * applied to Kabuk's own settings (the same ones as in Settings, so they also count for SSH tabs); everything else is
 * reported as ignored. [parse] and [apply] are pure (TermuxPropertiesTest).
 */
object TermuxProperties {
    /** what Kabuk takes over, for the help text */
    val SUPPORTED = listOf("terminal-transcript-rows", "bell-character", "volume-keys")

    class Result(val applied: List<String>, val ignored: List<String>, val errors: List<String>, val found: Boolean) {
        fun describe(): String = buildString {
            if (!found) {
                append("No ~/.termux/termux.properties: nothing to reload.\n")
                append("Kabuk reads: ${SUPPORTED.joinToString(", ")}.\n")
                return@buildString
            }
            if (applied.isNotEmpty()) append("Applied: ${applied.joinToString(", ")}\n")
            errors.forEach { append("Not applied: $it\n") }
            if (ignored.isNotEmpty()) append("Ignored (not supported in Kabuk): ${ignored.joinToString(", ")}\n")
            if (applied.isEmpty() && errors.isEmpty() && ignored.isEmpty()) append("No settings in termux.properties.\n")
            append("Kabuk reads: ${SUPPORTED.joinToString(", ")}; extra-keys are ignored (Kabuk has its own key bar), " +
                "colors.properties and font.ttf too (Settings → Theme).\n")
        }
    }

    /** `key=value` / `key = value` / `key: value` lines; `#` and `!` comments; later keys win. */
    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
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
                if (rows == null || rows !in 100..50000) errors += "$k=$v (100..50000)"
                else { n = n.copy(scrollbackLines = rows); applied += "$k=$rows (new tabs)" }
            }
            "bell-character" -> when (v.lowercase()) {
                "vibrate" -> { n = n.copy(bellVibrate = true); applied += "$k=vibrate" }
                "ignore" -> { n = n.copy(bellVibrate = false); applied += "$k=ignore" }
                "beep" -> errors += "$k=beep (Kabuk can vibrate or ignore the bell)"
                else -> errors += "$k=$v (vibrate|beep|ignore)"
            }
            // Termux: "virtual" = Volume Down/Up act as Ctrl/Fn, "volume" (default) = they change the volume
            "volume-keys" -> when (v.lowercase()) {
                "virtual" -> { n = n.copy(volumeKeys = true); applied += "$k=virtual" }
                "volume" -> { n = n.copy(volumeKeys = false); applied += "$k=volume" }
                else -> errors += "$k=$v (virtual|volume)"
            }
            else -> ignored += k
        }
        return n to Result(applied, ignored, errors, found = true)
    }

    /** Reads [home]/.termux/termux.properties (or ~/.config/termux/termux.properties) and applies it. */
    fun reload(home: File): Result {
        val f = listOf(File(home, ".termux/termux.properties"), File(home, ".config/termux/termux.properties"))
            .firstOrNull { it.isFile } ?: return Result(emptyList(), emptyList(), emptyList(), found = false)
        val props = parse(runCatching { if (f.length() > 262144) "" else f.readText() }.getOrDefault(""))
        var result: Result? = null
        Settings.update { p -> apply(props, p).also { result = it.second }.first }
        return result!!
    }
}
