package app.kabuk.text

/** One choice of a numbered menu on screen: pressing [key] picks it. */
data class Choice(val key: String, val label: String, val selected: Boolean)

/** An agent's open question: the lines that ask it ("Bash command … Do you want to proceed?") and its answers. */
data class Pending(val prompt: String, val choices: List<Choice>) {
    /** identifies this exact question (the command asked about, not only the buttons, which are often the same) */
    val fingerprint get() = prompt + "\n" + choices.joinToString("|") { it.key + "." + it.label }
}

/**
 * Finds a numbered choice menu near the bottom of the screen, like Claude Code's
 * "Do you want to proceed?  ❯ 1. Yes  2. Yes, and don't ask again  3. No" or Codex's approval list.
 */
object QuickReplies {
    // anything left of a pane border (herdr's sidebar) is skipped
    private val option = Regex("""^(?:.*[│┃]\s*)?[\s|]*([❯›>▶→]?)\s*(\d)[.)]\s+(\S.*)$""")

    // an agent's input line (❯ / ›) or a shell prompt ending in $ # %, also right of herdr's sidebar
    private val inputPrompt = Regex("""^(?:.*[│┃])?[\s|]*[❯›>]\s*(\D.*)?$|^.*[$#%]\s*$""")
    private const val BOX = "│┃|"
    /** borders and herdr's scrollbar, which sits in the last column next to the text */
    private val EDGE = "│┃|▕▐▏".toCharArray()

    private fun blank(line: String) = line.trim().trim(*EDGE).isBlank()

    /**
     * An option's wrapped second line: indented by 4+ after the line start or after a border
     * (dialog box, herdr's sidebar).
     */
    private fun continuation(line: String): Boolean {
        val l = line.trimEnd().trimEnd(*EDGE)
        val starts = listOf(0) + l.indices.filter { l[it] in BOX }.map { it + 1 }
        return starts.any { st ->
            val rest = l.substring(st)
            val body = rest.trimStart(' ')
            body.isNotEmpty() && body[0] !in BOX && rest.length - body.length >= 4
        }
    }

    /** [screen] = visible lines, top to bottom. Returns the menu (2-9 items numbered from 1) or empty. */
    fun find(screenRows: List<String>, lookBack: Int = 24): List<Choice> = scan(screenRows, lookBack).first

    /** the question above a menu in an agent's recent output (from herdr), or null if there is no menu */
    fun pending(text: String): Pending? {
        val rows = text.lines()
        val (choices, start) = scan(rows, 60)
        if (choices.isEmpty()) return null
        // up to 4 meaningful lines right above the first option: what is being asked
        val above = rows.subList(0, start.coerceIn(0, rows.size)).map(::stripBox)
            .filter { it.isNotBlank() && it.any(Char::isLetterOrDigit) }
        return Pending(above.takeLast(4).joinToString("\n"), choices)
    }

    private fun stripBox(s: String) = s.trim().trimStart(*EDGE).trimEnd(*EDGE).trim().trimStart('╭', '╰').trim()

    /** the menu and the row its first option is on */
    private fun scan(screenRows: List<String>, lookBack: Int): Pair<List<Choice>, Int> {
        // a short session leaves empty rows at the bottom: count from the last line with text
        val screen = screenRows.dropLastWhile(::blank)
        val start = (screen.size - lookBack).coerceAtLeast(0)
        var best: List<Choice> = emptyList()
        var bestEnd = -1
        var bestStart = -1
        var current = mutableListOf<Choice>()
        var currentStart = -1
        var expected = 1
        fun close(end: Int) {
            if (current.size >= 2) { best = current; bestEnd = end; bestStart = currentStart }
            current = mutableListOf(); expected = 1
        }
        for (i in start until screen.size) {
            val m = option.find(screen[i].trimEnd())
            if (m != null && m.groupValues[2].toInt() == expected) {
                if (expected == 1) currentStart = i
                current += Choice(m.groupValues[2], clean(m.groupValues[3]), m.groupValues[1].isNotEmpty())
                expected++
                continue
            }
            if (m != null && m.groupValues[2] == "1") {
                close(i - 1)
                currentStart = i
                current += Choice("1", clean(m.groupValues[3]), m.groupValues[1].isNotEmpty())
                expected = 2
                continue
            }
            // inside a menu: blank lines and indented wrap-arounds of an option belong to it
            if (current.isNotEmpty() && (blank(screen[i]) || continuation(screen[i]))) continue
            close(i - 1)
        }
        close(screen.size - 1)
        // an agent's input prompt below the list means it's just text in the answer, not a question
        if (bestEnd >= 0 && (bestEnd + 1 until screen.size).any { inputPrompt.matches(screen[it]) }) return emptyList<Choice>() to -1
        return best to bestStart
    }

    private fun clean(s: String) = s.trim().trimEnd(*EDGE).trim()
        .let { if (it.length > 28) it.take(26).trimEnd() + "…" else it }
}
