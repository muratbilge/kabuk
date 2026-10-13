package app.kabuk

import app.kabuk.text.Unwrap
import org.junit.Assert.assertEquals
import org.junit.Test

class UnwrapTest {
    // exactly what Claude Code showed on the phone inside herdr (wrapped at 54 columns)
    private val claude = """
● The 30-minute download link has now closed, as planne
  Before it did, your phone (192.168.1.33) downloaded
  APK once at 00:14, so you should have it.

  If the install didn't work, or you need it again, tel
  and I'll reopen the link.
""".trimIndent()

    @Test fun joinsClaudeParagraphsAndDropsIndent() {
        assertEquals(
            "● The 30-minute download link has now closed, as planne Before it did, your phone (192.168.1.33) downloaded APK once at 00:14, so you should have it.\n" +
                "\n" +
                "If the install didn't work, or you need it again, tel and I'll reopen the link.",
            Unwrap.unwrap(claude),
        )
    }

    @Test fun selectionInsideParagraphLosesIndentation() {
        val sel = "  Before it did, your phone (192.168.1.33) downloaded\n  APK once at 00:14, so you should have it."
        assertEquals("Before it did, your phone (192.168.1.33) downloaded APK once at 00:14, so you should have it.",
            Unwrap.unwrap(sel, 54))
    }

    @Test fun keepsShortLinesAndLists() {
        val t = "Steps:\n- first thing\n- second thing that is longer\n1. numbered\nshort\nlines"
        assertEquals(t, Unwrap.unwrap(t, 40))
    }

    @Test fun keepsMenuLineAfterParagraph() {
        val t = "  same way Termux does, so the keyboard fix mostly\n  comes from the new prompt box and key bar.\n  1: Learn more   2: Knew this already   0: Dismiss"
        assertEquals(
            "same way Termux does, so the keyboard fix mostly comes from the new prompt box and key bar.\n1: Learn more   2: Knew this already   0: Dismiss",
            Unwrap.unwrap(t, 54),
        )
    }

    @Test fun glueslongPathBrokenMidWord() {
        val t = "● Read(app/src/main/java/app/kabuk/ui/TerminalScreen\n.kt)"
        assertEquals("● Read(app/src/main/java/app/kabuk/ui/TerminalScreen.kt)", Unwrap.unwrap(t, 52))
    }

    @Test fun keepsCodeBlockIndentation() {
        val t = "Run this:\n\n    ./gradlew assembleDebug\n    adb install app.apk"
        assertEquals(t, Unwrap.unwrap(t, 40))
    }

    @Test fun keepsBoxesAlone() {
        val t = "╭──────────╮\n│ hello    │\n╰──────────╯"
        assertEquals(t, Unwrap.unwrap(t))
    }

    @Test fun shortCommandsStaySeparate() {
        // Codex review: two short commands must not be joined into one
        assertEquals("echo one\necho two", Unwrap.unwrap("echo one\necho two", available = 54))
    }

    @Test fun singleLineIsJustTrimmed() = assertEquals("hello", Unwrap.unwrap("   hello   "))
}
