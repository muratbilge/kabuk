package app.kabuk.term

import android.view.KeyEvent
import app.kabuk.ui.Key
import app.kabuk.ui.KeyAction
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Android's JSONTokener also accepts Termux's single quotes and unquoted object keys. */
object TermuxExtraKeys {
    val styles = setOf("default", "arrows-only", "arrows-all", "all", "none")
    fun parse(value: String, style: String = "default"): List<List<Key>> {
        require(style in styles)
        val tokener = JSONTokener(value)
        val rows = tokener.nextValue() as? JSONArray ?: error("expected rows")
        require(tokener.nextClean() == '\u0000')
        require(rows.length() <= 16)
        return (0 until rows.length()).map { r ->
            val row = rows.getJSONArray(r)
            require(row.length() in 1..32)
            (0 until row.length()).map { key(row.get(it), style) }
        }
    }

    private fun key(value: Any, style: String, popup: Boolean = false): Key {
        val obj = value as? JSONObject
        require(value is String || obj != null)
        fun string(name: String): String? = if (obj?.has(name) == true) {
            obj.get(name).also { require(it is String) } as String
        } else null
        val macro = string("macro")
        val name = if (value is String) value else string("key") ?: macro ?: error("missing key")
        require(name.isNotEmpty())
        val action = if (macro != null) {
            val parts = macro.trim().split(Regex("\\s+"))
            require(parts.size <= 256 && parts.none { it.isEmpty() })
            KeyAction.Sequence(parts.map(::action))
        } else action(name)
        val label = string("display") ?: label(name, style)
        val up = if (!popup && obj?.has("popup") == true) key(obj.get("popup"), style, true) else null
        return Key(label, action, up, repeat = action is KeyAction.Code)
    }

    private fun label(name: String, style: String): String {
        val arrows = mapOf("UP" to "↑", "DOWN" to "↓", "LEFT" to "←", "RIGHT" to "→")
        val common = mapOf("TAB" to "↹", "BKSP" to "⌫", "DEL" to "⌦", "ENTER" to "↲", "KEYBOARD" to "⌨", "DRAWER" to "☰", "PASTE" to "⎘", "-" to "―")
        val navigation = mapOf("HOME" to "⇱", "END" to "⇲", "PGUP" to "⇑", "PGDN" to "⇓")
        val all = mapOf("ESC" to "⎋", "CTRL" to "⎈", "ALT" to "⎇")
        return when (style) {
            "none" -> name
            "arrows-only" -> (arrows + mapOf("-" to "―"))[name] ?: name
            "arrows-all" -> (arrows + common + navigation)[name] ?: name
            "all" -> (arrows + common + navigation + all)[name] ?: name
            else -> (arrows + common)[name] ?: name
        }
    }

    fun action(name: String): KeyAction {
        val code = when (name) {
            "ESC" -> KeyEvent.KEYCODE_ESCAPE; "TAB" -> KeyEvent.KEYCODE_TAB
            "UP" -> KeyEvent.KEYCODE_DPAD_UP; "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
            "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT; "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "HOME" -> KeyEvent.KEYCODE_MOVE_HOME; "END" -> KeyEvent.KEYCODE_MOVE_END
            "PGUP" -> KeyEvent.KEYCODE_PAGE_UP; "PGDN" -> KeyEvent.KEYCODE_PAGE_DOWN
            "BKSP" -> KeyEvent.KEYCODE_DEL; "DEL" -> KeyEvent.KEYCODE_FORWARD_DEL
            "INS" -> KeyEvent.KEYCODE_INSERT; "ENTER" -> KeyEvent.KEYCODE_ENTER
            else -> if (name.matches(Regex("F([1-9]|1[0-2])"))) KeyEvent.KEYCODE_F1 + name.drop(1).toInt() - 1 else null
        }
        return code?.let(KeyAction::Code) ?: when (name) {
            "CTRL" -> KeyAction.Ctrl; "ALT" -> KeyAction.Alt; "SHIFT" -> KeyAction.Shift
            "FN" -> KeyAction.Fn; "KEYBOARD" -> KeyAction.Keyboard; "DRAWER" -> KeyAction.Drawer
            "PASTE" -> KeyAction.Paste; "SPACE" -> KeyAction.Text(" "); else -> KeyAction.Text(name)
        }
    }
}
