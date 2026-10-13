package app.kabuk

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kabuk.term.TerminalCanvasView
import app.kabuk.ui.ModifierState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PaneSelectionTest {
    @Test fun selectionStaysInsideTheHerdrPane() {
        val t = RecordingTab()   // 40 x 20
        val v = TerminalCanvasView(ApplicationProvider.getApplicationContext(), ModifierState()).apply {
            measure(1000, 1000); layout(0, 0, 1000, 1000); tab = t
        }
        t.resize(40, 20)
        // herdr wide layout: 12-column sidebar, a border, then the agent pane
        val side = listOf("w9 home", "wA webshop", "wE /home", "", "", "")
        val pane = listOf("● The fix: selection must", "  stay inside the pane you", "  started in.", "", "  next paragraph", "")
        val sb = StringBuilder()
        for (r in 0 until 20) {
            val l = side.getOrElse(r) { "" }.padEnd(12) + "│ " + pane.getOrElse(r) { "" }
            sb.append(l.padEnd(40).take(40))
        }
        t.feed("\u001b[H" + sb)
        // long-press on "fix" (row 0), then drag the end handle to the end of row 2
        v.cellCenter(20, 0).let { (x, y) -> v.startSelection(x, y) }
        v.dragging = 2
        v.endHandleFinger(39, 2).let { (x, y) -> v.dragHandle(x, y) }
        assertEquals("fix: selection must\nstay inside the pane you\nstarted in.", v.selectedTextRaw().lines().joinToString("\n") { it.trim() })
        assertEquals("fix: selection must stay inside the pane you started in.", v.selectedText())
    }
}
