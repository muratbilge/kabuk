package app.kabuk.text

/**
 * Puts back together text that a program wrapped itself (Claude Code, herdr, ...). On screen those are
 * real line breaks plus indentation, so the terminal can't know they were "soft".
 *
 * A line is joined to the next one when the next line's first word would not have fit after it, i.e. the
 * program had to break there. Short lines, blank lines, list items and deeper-indented blocks stay as they are.
 */
object Unwrap {
    private val listStart = Regex("""^(\s*)([-*+•●⎿]|\d+[.):]|[a-zA-Z][.)])\s""")
    private val bullet = Regex("""^\s*[●⎿•]\s""")
    private val boxChars = Regex("""^[\s│┃|╭╮╰╯─━┌┐└┘├┤┬┴┼]*$""")

    /**
     * [width] = the column the text was wrapped at; by default the longest line.
     * [available] = how wide the text could have been (pane / terminal width). When no line comes near
     * it, nothing was wrapped (e.g. a few short commands) and lines are kept as they are.
     */
    fun unwrap(text: String, width: Int? = null, available: Int? = null): String {
        val lines = text.replace("\t", "    ").lines().map { it.trimEnd() }
        if (lines.size < 2) return text.trim()
        val w = width ?: lines.maxOf { it.length }
        val noWrapping = available != null && w < available - 12
        // common indentation, ignoring bullet lines ("● text" hangs its body 2 columns in)
        val indent = lines.filter { it.isNotBlank() && !bullet.containsMatchIn(it) }
            .minOfOrNull { it.length - it.trimStart().length } ?: 0
        val out = StringBuilder()
        var prev: String? = null      // last line of the paragraph being built (as on screen)
        var prevIndent = 0            // indentation of the paragraph's first line
        for (raw in lines) {
            val line = raw.drop(minOf(indent, raw.length - raw.trimStart().length))
            val lead = line.length - line.trimStart().length
            val p = prev
            val join = !noWrapping && p != null && p.isNotBlank() && line.isNotBlank() &&
                !boxChars.matches(p) && !boxChars.matches(line) &&
                !listStart.containsMatchIn(line) &&
                // continuation lines sit at the paragraph's indentation or its hanging indent (bullet width)
                lead <= prevIndent + 4 &&
                p.length + indent + 1 + firstWord(line).length > w
            if (join) {
                val t = line.trimStart()
                // a long path or URL broken mid-word: no space between the halves
                val glue = if (p!!.length + indent >= w && looksLikePath(lastWord(p))) "" else " "
                out.append(glue).append(t)
                prev = line
            } else {
                if (out.isNotEmpty()) out.append('\n')
                out.append(line)
                prev = line
                prevIndent = lead
            }
        }
        return out.toString().trim('\n')
    }

    private fun firstWord(s: String) = s.trimStart().substringBefore(' ')
    private fun lastWord(s: String) = s.trimEnd().substringAfterLast(' ')
    private fun looksLikePath(s: String) = s.contains('/') || s.contains("://") || (s.contains('.') && !s.endsWith('.'))
}
