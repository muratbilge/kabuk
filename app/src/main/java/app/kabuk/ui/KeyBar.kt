package app.kabuk.ui

import android.view.KeyEvent
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.kabuk.text.Choice
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kabuk.data.Settings
import app.kabuk.term.Modifiers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class KeyAction {
    /** special key, encoded by the emulator (arrows, Home, F-keys...) */
    data class Code(val keyCode: Int) : KeyAction()
    /** typed text: Ctrl/Alt from the bar apply to it */
    data class Text(val s: String) : KeyAction()
    /** bytes sent exactly as written */
    data class Raw(val s: String) : KeyAction()
    data object Ctrl : KeyAction()
    data object Alt : KeyAction()
    data object Shift : KeyAction()
    data object Paste : KeyAction()
    data object PromptBox : KeyAction()
    data object Keyboard : KeyAction()
    data object TextMode : KeyAction()
    /** back from the keys to the answers of an open menu */
    data object Replies : KeyAction()
    /** ⚡ quick messages */
    data object Snippets : KeyAction()
}

data class Key(val label: String, val action: KeyAction, val up: Key? = null, val repeat: Boolean = false)

private fun code(label: String, kc: Int, up: Key? = null, repeat: Boolean = false) = Key(label, KeyAction.Code(kc), up, repeat)
private fun text(label: String, s: String = label, up: Key? = null) = Key(label, KeyAction.Text(s), up)

/** Two rows tuned for Claude Code; swipe a key up for the small label in its corner. */
val defaultKeys: List<List<Key>> = listOf(
    listOf(
        code("ESC", KeyEvent.KEYCODE_ESCAPE, up = text("~")),
        Key("⇧TAB", KeyAction.Raw("\u001b[Z"), up = text("|")),
        text("/", up = text("\\")),
        Key("⚡", KeyAction.Snippets, up = text("@")),
        code("↑", KeyEvent.KEYCODE_DPAD_UP, up = code("PGUP", KeyEvent.KEYCODE_PAGE_UP), repeat = true),
        Key("^C", KeyAction.Raw("\u0003"), up = Key("^D", KeyAction.Raw("\u0004"))),
        code("⏎", KeyEvent.KEYCODE_ENTER, up = Key("NL", KeyAction.Raw("\n"))),
        Key("PASTE", KeyAction.Paste, up = Key("TEXT", KeyAction.TextMode)),
    ),
    listOf(
        code("TAB", KeyEvent.KEYCODE_TAB, up = text("-")),
        Key("CTRL", KeyAction.Ctrl),
        Key("ALT", KeyAction.Alt, up = Key("SHIFT", KeyAction.Shift)),
        code("←", KeyEvent.KEYCODE_DPAD_LEFT, up = code("HOME", KeyEvent.KEYCODE_MOVE_HOME), repeat = true),
        code("↓", KeyEvent.KEYCODE_DPAD_DOWN, up = code("PGDN", KeyEvent.KEYCODE_PAGE_DOWN), repeat = true),
        code("→", KeyEvent.KEYCODE_DPAD_RIGHT, up = code("END", KeyEvent.KEYCODE_MOVE_END), repeat = true),
        Key("✎", KeyAction.PromptBox),
        Key("⌨", KeyAction.Keyboard),
    ),
)

enum class Mod { OFF, ONCE, LOCKED }

/** State of the sticky modifiers: tap = next key only, double-tap = locked until tapped again. */
class ModifierState : Modifiers {
    var ctrl by mutableStateOf(Mod.OFF)
    var alt by mutableStateOf(Mod.OFF)
    var shift by mutableStateOf(Mod.OFF)
    /** physical volume keys held down (Termux style) */
    var volumeCtrl = false
    var volumeFn = false
    private val lastTap = LongArray(3)

    fun tap(which: Int) {
        val now = System.currentTimeMillis()
        val double = now - lastTap[which] < 350
        lastTap[which] = now
        fun next(m: Mod) = when {
            m == Mod.OFF -> Mod.ONCE
            m == Mod.ONCE && double -> Mod.LOCKED
            else -> Mod.OFF
        }
        when (which) { 0 -> ctrl = next(ctrl); 1 -> alt = next(alt); else -> shift = next(shift) }
    }

    override fun takeCtrl(): Boolean = volumeCtrl || take(ctrl) { ctrl = it }
    override fun takeAlt(): Boolean = take(alt) { alt = it }
    override fun takeShift(): Boolean = take(shift) { shift = it }
    override fun fn(): Boolean = volumeFn

    private inline fun take(m: Mod, set: (Mod) -> Unit): Boolean {
        if (m == Mod.ONCE) set(Mod.OFF)
        return m != Mod.OFF
    }
}

/**
 * The key bar. While an agent shows a numbered menu, its answers take the place of the top row:
 * same height, so the terminal never resizes (a resize makes the agent redraw and the menu jump).
 * [onChoice] gets null for Esc.
 */
@Composable
fun KeyBar(
    mods: ModifierState, tabId: Long?, onAction: (KeyAction) -> Unit, modifier: Modifier = Modifier,
    promptOpen: Boolean = false, choices: List<Choice> = emptyList(), onChoice: (Choice?) -> Unit = {},
) {
    // "show me the keys" lasts until this menu is gone
    var keysInstead by remember(choices.isEmpty()) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(Surface1).padding(horizontal = 2.dp, vertical = 1.dp)) {
        defaultKeys.forEachIndexed { i, row ->
            // keys and the answer row share one height: switching between them never resizes the terminal
            Row(Modifier.fillMaxWidth().height(KeyRowHeight)) {
                if (i == 0 && choices.isNotEmpty() && !keysInstead) {
                    ReplyRow(choices, onChoice, onKeys = { keysInstead = true })
                } else {
                    row.forEachIndexed { j, k ->
                        // with a menu open, the last key brings the answers back
                        val key = if (i == 0 && j == row.lastIndex && choices.isNotEmpty()) RepliesKey else k
                        KeyButton(
                            key, mods, tabId,
                            onAction = { a -> if (a == KeyAction.Replies) keysInstead = false else onAction(a) },
                            Modifier.weight(1f), lit = k.action == KeyAction.PromptBox && promptOpen,
                        )
                    }
                }
            }
        }
    }
}

/** low enough to leave the terminal its rows; the whole cell is the touch target */
val KeyRowHeight = 42.dp

private val RepliesKey = Key("1·2·3", KeyAction.Replies)

@Composable
private fun RowScope.ReplyRow(choices: List<Choice>, onChoice: (Choice?) -> Unit, onKeys: () -> Unit) {
    val view = LocalView.current
    fun tap(c: Choice?) {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        onChoice(c)
    }
    val few = choices.size <= 4
    Row(
        Modifier.weight(1f).fillMaxHeight().then(if (few) Modifier else Modifier.horizontalScroll(rememberScrollState())),
    ) {
        choices.forEachIndexed { i, c ->
            val no = c.label.startsWith("No", ignoreCase = true)
            val (bg, fg) = when {
                i == 0 -> Accent to OnAccent
                no -> Color(0xFF6E2335) to Color(0xFFFFD9DF)
                else -> Color(0xFF2A3B66) to Color(0xFFD9E2FF)
            }
            Row(
                (if (few) Modifier.weight(if (i == 0) 1.15f else 1f) else Modifier.widthIn(min = 110.dp, max = 180.dp))
                    .fillMaxHeight().padding(2.dp).clip(RoundedCornerShape(8.dp)).background(bg)
                    .clickable { tap(c) }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(c.key, color = fg, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.width(6.dp))
                Text(c.label, color = fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    // Esc at the far end, away from "1 Yes"
    Box(
        Modifier.width(54.dp).fillMaxHeight().padding(2.dp).clip(RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF6B7591), RoundedCornerShape(8.dp)).clickable { tap(null) },
        contentAlignment = Alignment.Center,
    ) { Text("Esc", color = Bad, fontFamily = Mono, fontSize = 14.sp) }
    Box(
        Modifier.width(44.dp).fillMaxHeight().padding(2.dp).clip(RoundedCornerShape(8.dp)).background(KeyFill)
            .clickable(onClick = onKeys)
            .semantics { contentDescription = "Show the keys" },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.Keyboard, null, tint = Muted, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun KeyButton(key: Key, mods: ModifierState, tabId: Long?, onAction: (KeyAction) -> Unit, modifier: Modifier, lit: Boolean = false) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // the gesture handler lives across recompositions: always call the newest action (current tab)
    val action by androidx.compose.runtime.rememberUpdatedState(onAction)
    var pressed by remember { mutableStateOf(false) }
    var swiped by remember { mutableStateOf(false) }
    val modState = when {
        key.action == KeyAction.Ctrl -> mods.ctrl
        key.action == KeyAction.Alt -> mods.alt
        lit -> Mod.ONCE   // a toggle that's on (✎ while the message box is open)
        else -> Mod.OFF
    }
    val bg = when {
        modState == Mod.LOCKED -> Blue
        modState == Mod.ONCE -> Color(0xFF2A3B66)
        pressed -> Color(0xFF3A4254)
        else -> KeyFill
    }
    val fg = when (modState) {
        Mod.LOCKED -> Color(0xFF0A1F4D)
        Mod.ONCE -> Color(0xFFD9E2FF)
        else -> Text1
    }

    Box(
        modifier
            .fillMaxHeight()
            .semantics { contentDescription = keyDescription(key) }
            .pointerInput(key, tabId) {
                val swipeDist = 22.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true; swiped = false
                    if (Settings.current.vibrateKeys) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    var repeated = false
                    val job: Job? = if (key.repeat) scope.launch {
                        delay(380)
                        while (true) { repeated = true; action(key.action); delay(45) }
                    } else null
                    // switching tabs cancels this gesture: the finally stops a held key's repeat
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (key.up != null && down.position.y - ch.position.y > swipeDist && !swiped) {
                                swiped = true
                                job?.cancel()
                                if (Settings.current.vibrateKeys) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }
                        }
                        job?.cancel()
                        if (!repeated) action(if (swiped) key.up!!.action else key.action)
                    } finally {
                        job?.cancel()
                        pressed = false
                        swiped = false
                    }
                }
            }
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        val label = if (swiped && key.up != null) key.up.label else key.label
        if (key.up != null && !swiped) {
            Text(
                key.up.label, color = Color(0xFF9AA3B8), fontSize = 10.sp, fontFamily = Mono, maxLines = 1,
                lineHeight = 10.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 3.dp),
            )
        }
        Text(
            label, color = fg, fontSize = if (label.length > 3) 11.sp else 15.sp, fontWeight = FontWeight.Medium,
            fontFamily = Mono, maxLines = 1,
            modifier = if (key.up != null && !swiped) Modifier.padding(top = 11.dp) else Modifier,
        )
    }
}

private fun keyDescription(k: Key): String {
    val name = when (k.action) {
        KeyAction.PromptBox -> "Message box"
        KeyAction.Keyboard -> "Keyboard"
        KeyAction.Replies -> "Answers"
        KeyAction.Snippets -> "Quick messages"
        else -> k.label
    }
    return if (k.up != null) "$name, swipe up: ${k.up.label}" else name
}
