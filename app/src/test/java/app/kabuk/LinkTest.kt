package app.kabuk

import app.kabuk.term.linkIn
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkTest {
    /** rows as the screen has them, inside one pane of [width] columns; tap at ([row], [col]) */
    private fun tap(rows: List<String>, row: Int, col: Int, width: Int = 30, softWrapped: Set<Int> = emptySet()) =
        linkIn(row, col, 0, width - 1, 0, rows.lastIndex) { rows[it].padEnd(width) to (it in softWrapped) }

    private val url = "https://github.com/muratbilge/kabuk/blob/main/README.md"

    @Test fun urlWrappedByTheProgram() {
        // Claude/herdr break the URL at the pane edge and indent the rest
        val rows = listOf(
            "● See $url".take(30),
            "  " + "● See $url".drop(30).take(28),
            "  " + "● See $url".drop(58),
            "",
        )
        assertEquals(url, tap(rows, 0, 10))   // tap on the first part
        assertEquals(url, tap(rows, 2, 4))    // tap on the last part
    }

    @Test fun shortLineEndsTheLink() {
        val rows = listOf("open https://example.com now", "  and then this line")
        assertEquals("https://example.com", tap(rows, 0, 8))
    }

    @Test fun scrollbarAndBorderAreNotPartOfIt() {
        // herdr's scrollbar sits in the last column right after the text
        val rows = listOf("x https://example.com/a/b/c▐", "")
        assertEquals("https://example.com/a/b/c", tap(rows, 0, 5, width = 28))
    }

    @Test fun softWrapStillWorks() {
        val long = "https://example.com/" + "a".repeat(30)
        val rows = listOf(long.take(30), long.drop(30))
        assertEquals(long, tap(rows, 1, 3, softWrapped = setOf(0)))
    }
}
