package app.kabuk

import app.kabuk.term.lastLinkIn
import app.kabuk.term.linkIn
import org.junit.Assert.assertNull
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

    private fun last(rows: List<String>, width: Int = 30, softWrapped: Set<Int> = emptySet()) =
        lastLinkIn(0, rows.lastIndex, width) { rows[it].padEnd(width) to (it in softWrapped) }

    @Test fun lowestHttpLinkOnScreenForTheChip() {
        // rclone authorize: a long OAuth URL soft-wrapped over three rows, an older link further up
        val oauth = "http://127.0.0.1:53682/auth?state=" + "Q".repeat(40)
        val rows = listOf("see https://rclone.org/x", "Please go to the following link: ".take(30),
            oauth.take(30), oauth.drop(30).take(30), oauth.drop(60), "Waiting for code...", "")
        assertEquals(oauth, last(rows, softWrapped = setOf(2, 3)))
        assertEquals("https://rclone.org/x", last(rows.take(2)))
        assertEquals("https://b.org", last(listOf("https://a.org and https://b.org")))
        assertNull("www. without a scheme is no program's browser link", last(listOf("www.example.com", "$ ")))
    }
}
