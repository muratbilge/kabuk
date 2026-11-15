package app.kabuk

import app.kabuk.data.Prefs
import app.kabuk.term.TermuxProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxPropertiesTest {
    private val sample = """
        |### termux.properties as Termux ships it, with a few settings switched on
        |# allow-external-apps = true
        |extra-keys = [['ESC','/','-','HOME','UP','END','PGUP'], ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]
        |terminal-transcript-rows = 20000
        |bell-character=ignore
        |volume-keys: virtual
        |use-black-ui = true
        |! old-style comment
        |""".trimMargin()

    @Test fun parsesTermuxStyleLines() {
        val p = TermuxProperties.parse(sample)
        assertEquals("20000", p["terminal-transcript-rows"])
        assertEquals("ignore", p["bell-character"])
        assertEquals("virtual", p["volume-keys"])
        assertTrue(p["extra-keys"]!!.startsWith("[['ESC'"))
        assertFalse(p.containsKey("allow-external-apps"))
    }

    @Test fun appliesTheSupportedOnesAndListsTheRest() {
        val (n, r) = TermuxProperties.apply(TermuxProperties.parse(sample), Prefs())
        assertEquals(20000, n.scrollbackLines)
        assertFalse(n.bellVibrate)
        assertTrue(n.volumeKeys)
        assertTrue(r.ignored.isEmpty())
        assertEquals(5, r.applied.size)
        val text = r.describe()
        assertTrue(text, text.contains("terminal-transcript-rows=20000"))
        assertTrue(text, text.contains("Phone tabs only"))
        assertFalse(text, text.contains("extra-keys are ignored"))
    }

    @Test fun badValuesAreReportedNotApplied() {
        val (n, r) = TermuxProperties.apply(mapOf("terminal-transcript-rows" to "5", "bell-character" to "beep"), Prefs())
        assertEquals(Prefs().scrollbackLines, n.scrollbackLines)
        assertEquals(Prefs().bellVibrate, n.bellVibrate)
        assertEquals(2, r.errors.size)
        assertTrue(r.applied.isEmpty())
    }
}
