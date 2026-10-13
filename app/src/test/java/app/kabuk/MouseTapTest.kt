package app.kabuk

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kabuk.term.QuietSessionClient
import app.kabuk.term.TermTab
import app.kabuk.term.TerminalCanvasView
import app.kabuk.ui.ModifierState
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A tab that records what the terminal sends back to the program. */
class RecordingTab : TermTab(1, "t", 0) {
    val sent = StringBuilder()
    private val out = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) { sent.append(String(data, offset, count)) }
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }
    override val emulator = TerminalEmulator(out, 40, 20, 100, QuietSessionClient)
    fun feed(s: String) = s.toByteArray().let { emulator.append(it, it.size) }
    override fun write(data: ByteArray, offset: Int, count: Int) { sent.append(String(data, offset, count)) }
    override fun resize(columns: Int, rows: Int) = emulator.resize(columns, rows)
    override fun close() {}
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MouseTapTest {
    private fun view(tab: RecordingTab) = TerminalCanvasView(ApplicationProvider.getApplicationContext(), ModifierState()).apply {
        measure(1000, 1000); layout(0, 0, 1000, 1000)
        this.tab = tab
    }

    @Test fun tapIsAClickWhenTheProgramWantsTheMouse() {
        val t = RecordingTab()
        val v = view(t)
        t.feed("\u001b[?1000h\u001b[?1006h")   // what herdr/tmux send: click tracking, SGR encoding
        v.tapAt(5f, 5f)                           // top-left cell
        assertEquals("\u001b[<0;1;1M\u001b[<0;1;1m", t.sent.toString())
    }

    @Test fun tapSendsNothingToPlainPrograms() {
        val t = RecordingTab()
        val v = view(t)
        t.feed("hello")
        v.tapAt(5f, 5f)
        assertTrue(t.sent.isEmpty())
    }
}
