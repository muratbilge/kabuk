package app.kabuk

import androidx.test.core.app.ApplicationProvider
import app.kabuk.term.TermTab
import app.kabuk.term.TerminalCanvasView
import app.kabuk.term.TermuxExtraKeys
import app.kabuk.term.TermuxProperties
import app.kabuk.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TermuxKeyInputTest {
    @Test fun macroSendsControlAndLiteralTokensAndRestoresStickyModifiers() {
        val sent = StringBuilder()
        val tab = object : TermTab(999, "test", 0) {
            override val emulator = FakeTab(998, "emulator", "").emulator
            override fun write(data: ByteArray, offset: Int, count: Int) { sent.append(String(data, offset, count)) }
            override fun resize(columns: Int, rows: Int) {}
            override fun close() {}
        }
        val mods = ModifierState().apply { shift = Mod.LOCKED }
        val view = TerminalCanvasView(ApplicationProvider.getApplicationContext(), mods).apply { this.tab = tab }
        val ui = TerminalUi(view, mods)
        val action = TermuxExtraKeys.parse("[[{macro:'CTRL c echo SPACE hi ENTER'}]]")[0][0].action
        keyAction(ui, action, {})
        assertEquals("\u0003echo hi\r", sent.toString())
        assertEquals(Mod.LOCKED, mods.shift)
        assertEquals(Mod.OFF, mods.ctrl)
    }

    @Test fun emptyFontFallsBackAndBundledFontLoadsWithoutPropertiesFile() {
        val home = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "font-test").apply { mkdirs() }
        val dir = File(home, ".termux").apply { mkdirs() }
        val font = File(dir, "font.ttf")
        font.writeBytes(byteArrayOf())
        val bad = TermuxProperties.load(home)
        assertNull(bad.first.font)
        assertTrue(bad.second.errors.any { it.startsWith("font.ttf") })
        ApplicationProvider.getApplicationContext<android.content.Context>().resources.openRawResource(R.font.jetbrains_mono).use { input ->
            font.outputStream().use { input.copyTo(it) }
        }
        val good = TermuxProperties.load(home)
        assertNotNull(good.first.font)
        assertTrue(good.second.found)
        assertTrue(good.second.errors.isEmpty())
        font.delete()
    }
}
