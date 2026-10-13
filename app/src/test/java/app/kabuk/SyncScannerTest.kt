package app.kabuk

import app.kabuk.term.SyncScanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncScannerTest {
    private val bsu = "\u001b[?2026h"
    private val esu = "\u001b[?2026l"

    @Test fun wholeFrame() {
        val s = SyncScanner()
        s.feed("$bsu\u001b[Hframe".toByteArray())
        assertTrue(s.inside)
        s.feed("more$esu".toByteArray())
        assertFalse(s.inside)
    }

    @Test fun markersSplitAcrossChunks() {
        val s = SyncScanner()
        val all = "x$bsu" + "redraw" + esu + "y" + bsu
        // every split point must give the same result as one chunk
        for (cut in 1 until all.length) {
            val t = SyncScanner()
            t.feed(all.substring(0, cut).toByteArray())
            t.feed(all.substring(cut).toByteArray())
            assertTrue("cut at $cut", t.inside)
        }
        s.feed(all.toByteArray()); assertTrue(s.inside)
    }

    @Test fun otherModesAndEscapesIgnored() {
        val s = SyncScanner()
        s.feed("\u001b[?2004h\u001b[?1049h\u001b\u001b[?2026h".toByteArray())
        assertTrue(s.inside)
        s.feed("\u001b[?2025l\u001b[?20266l".toByteArray())
        assertTrue(s.inside)
        s.reset(); assertFalse(s.inside)
    }
}
