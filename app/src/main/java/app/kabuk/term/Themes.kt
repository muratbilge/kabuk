package app.kabuk.term

import com.termux.terminal.TerminalColors
import java.util.Properties

/** Terminal color schemes (foreground, background, cursor, 16 ANSI colors). */
object Themes {
    private val schemes: Map<String, List<String>> = linkedMapOf(
        // fg, bg, cursor, then color0..color15
        "Kabuk" to listOf("#C8D3F5", "#0E1116", "#7AA2F7",
            "#1B1E28", "#F7768E", "#9ECE6A", "#E0AF68", "#7AA2F7", "#BB9AF7", "#7DCFFF", "#A9B1D6",
            "#414868", "#FF7A93", "#B9F27C", "#FF9E64", "#7DA6FF", "#C0A8FF", "#B4F9F8", "#E6E9EF"),
        "Dracula" to listOf("#F8F8F2", "#282A36", "#F8F8F2",
            "#21222C", "#FF5555", "#50FA7B", "#F1FA8C", "#BD93F9", "#FF79C6", "#8BE9FD", "#F8F8F2",
            "#6272A4", "#FF6E6E", "#69FF94", "#FFFFA5", "#D6ACFF", "#FF92DF", "#A4FFFF", "#FFFFFF"),
        "Gruvbox" to listOf("#EBDBB2", "#282828", "#EBDBB2",
            "#282828", "#CC241D", "#98971A", "#D79921", "#458588", "#B16286", "#689D6A", "#A89984",
            "#928374", "#FB4934", "#B8BB26", "#FABD2F", "#83A598", "#D3869B", "#8EC07C", "#EBDBB2"),
        "Solarized" to listOf("#839496", "#002B36", "#93A1A1",
            "#073642", "#DC322F", "#859900", "#B58900", "#268BD2", "#D33682", "#2AA198", "#EEE8D5",
            "#002B36", "#CB4B16", "#586E75", "#657B83", "#839496", "#6C71C4", "#93A1A1", "#FDF6E3"),
        "Light" to listOf("#24292F", "#FFFFFF", "#0969DA",
            "#24292F", "#CF222E", "#116329", "#4D2D00", "#0969DA", "#8250DF", "#1B7C83", "#6E7781",
            "#57606A", "#A40E26", "#1A7F37", "#633C01", "#218BFF", "#A475F9", "#3192AA", "#8C959F"),
    )

    val names get() = schemes.keys.toList()

    fun background(name: String): Long =
        ("FF" + (schemes[name] ?: schemes.getValue("Kabuk"))[1].removePrefix("#")).toLong(16)

    fun apply(name: String, tabs: List<TermTab>) {
        val c = schemes[name] ?: schemes.getValue("Kabuk")
        val p = Properties()
        p["foreground"] = c[0]; p["background"] = c[1]; p["cursor"] = c[2]
        for (i in 0 until 16) p["color$i"] = c[3 + i]
        TerminalColors.COLOR_SCHEME.updateWith(p)
        tabs.forEach { it.emulator?.mColors?.reset(); it.onScreenUpdate?.invoke() }
    }
}
