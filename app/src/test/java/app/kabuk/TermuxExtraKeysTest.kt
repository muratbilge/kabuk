package app.kabuk

import android.view.KeyEvent
import app.kabuk.term.TermuxExtraKeys
import app.kabuk.term.TermuxProperties
import app.kabuk.ui.KeyAction
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class TermuxExtraKeysTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun documentedRowsAndPopupMacro() {
        val rows = TermuxExtraKeys.parse("""[['ESC','/','-','HOME','UP','END','PGUP'],
            ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN'],
            [{key: 'KEYBOARD', popup: {macro: 'CTRL d', display: 'exit'}},
             {key: '/', popup: '\\'}, {macro: 'CTRL ALT t', display: 'new'}]]""")
        assertEquals(3, rows.size)
        assertEquals(KeyAction.Code(KeyEvent.KEYCODE_DPAD_UP), rows[0][4].action)
        assertEquals("↑", rows[0][4].label)
        assertEquals("exit", rows[2][0].up!!.label)
        assertEquals(KeyAction.Sequence(listOf(KeyAction.Ctrl, KeyAction.Text("d"))), rows[2][0].up!!.action)
        assertEquals(KeyAction.Text("\\"), rows[2][1].up!!.action)
        assertEquals(KeyAction.Sequence(listOf(KeyAction.Ctrl, KeyAction.Alt, KeyAction.Text("t"))), rows[2][2].action)
    }

    @Test fun allRequestedKeysAndStyles() {
        val names = listOf("ESC", "TAB", "CTRL", "ALT", "SHIFT", "FN", "UP", "DOWN", "LEFT", "RIGHT",
            "HOME", "END", "PGUP", "PGDN", "BKSP", "DEL", "INS", "KEYBOARD", "DRAWER", "PASTE") + (1..12).map { "F$it" }
        names.forEach { assertFalse(it, TermuxExtraKeys.action(it) is KeyAction.Text) }
        assertEquals("UP", TermuxExtraKeys.parse("[['UP']]", "none")[0][0].label)
        assertEquals("ESC", TermuxExtraKeys.parse("[['ESC']]", "default")[0][0].label)
        assertEquals("⎋", TermuxExtraKeys.parse("[['ESC']]", "all")[0][0].label)
        assertEquals(emptyList<List<Any>>(), TermuxExtraKeys.parse("[]"))
    }

    @Test fun malformedLayoutsFallBackWithRedactedError() {
        listOf("secret", "{}", "[[]]", "[[null]]", "[[{display:'secret'}]]", "[['ok']] trailing", "[[{macro:17}]]").forEach {
            val result = TermuxProperties.apply(mapOf("extra-keys" to it), app.kabuk.data.Prefs()).second
            assertEquals(it, 1, result.errors.size)
            assertFalse(result.describe().contains("secret"))
        }
    }

    @Test fun continuationAndFileRemovalRestoreDefaults() {
        val home = temp.newFolder()
        val dir = java.io.File(home, ".termux").apply { mkdir() }
        java.io.File(dir, "termux.properties").writeText("extra-keys = [['ESC'], \\\n ['TAB']]\nterminal-cursor-style=bar\nterminal-cursor-blink-rate=500\nuse-black-ui=true\nfullscreen=true\nshortcut.create-session=ctrl+t")
        java.io.File(dir, "colors.properties").writeText("background=#123\ncolor15=#abcdef\ncursor=invalid")
        val (snapshot, result) = TermuxProperties.load(home)
        assertEquals(2, snapshot.keys!!.size)
        assertEquals(2, snapshot.cursorStyle)
        assertEquals(500, snapshot.blinkRate)
        assertTrue(snapshot.black)
        assertEquals(0xff112233.toInt(), snapshot.colors[257])
        assertEquals(0xffabcdef.toInt(), snapshot.colors[15])
        assertEquals(listOf("fullscreen", "shortcut.create-session"), result.ignored)
        assertEquals(1, result.errors.size)
        dir.listFiles()!!.forEach { it.delete() }
        val (reset, absent) = TermuxProperties.load(home)
        assertNull(reset.keys)
        assertTrue(reset.colors.isEmpty())
        assertEquals(0, reset.cursorStyle)
        assertFalse(absent.found)
    }

    @Test fun invalidAdditionalSettingsAreReported() {
        val r = TermuxProperties.apply(mapOf("terminal-cursor-style" to "bad", "terminal-cursor-blink-rate" to "-1",
            "extra-keys-style" to "bad", "use-black-ui" to "yes", "hide-soft-keyboard-on-startup" to "1"), app.kabuk.data.Prefs()).second
        assertEquals(5, r.errors.size)
    }
}
