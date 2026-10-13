package app.kabuk

import app.kabuk.text.QuickReplies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickRepliesTest {
    @Test fun claudePermissionPrompt() {
        val screen = """
● Bash(rm -rf build)
  ⎿  Running…

╭──────────────────────────────────────────────╮
│ Bash command                                 │
│                                              │
│   rm -rf build                               │
│                                              │
│ Do you want to proceed?                      │
│ ❯ 1. Yes                                     │
│   2. Yes, and don't ask again for rm         │
│      commands in /projects/kabuk             │
│   3. No, and tell Claude what to do          │
│      differently (esc)                       │
╰──────────────────────────────────────────────╯
""".trimIndent().lines()
        val c = QuickReplies.find(screen)
        assertEquals(listOf("1", "2", "3"), c.map { it.key })
        assertEquals("Yes", c[0].label)
        assertTrue(c[0].selected)
        assertEquals("Yes, and don't ask again f…", c[1].label)
        assertEquals("No, and tell Claude what t…", c[2].label)
    }

    @Test fun codexApproval() {
        val screen = """
  Would you like to run the following command?
  $ ./gradlew test

› 1. Yes, proceed (y)
  2. Yes, and don't ask again for this command (a)
  3. No, and tell Codex what to do differently (esc)
""".trimIndent().lines()
        assertEquals(listOf("1", "2", "3"), QuickReplies.find(screen).map { it.key })
    }

    @Test fun ordinaryNumberedListInOldOutputIsIgnoredWhenTextFollows() {
        val screen = (listOf("Steps:", "1. build", "2. test", "That's all.", "") + List(30) { "line $it" })
        assertTrue(QuickReplies.find(screen).isEmpty())
    }

    @Test fun numberedListInAnswerAboveTheInputBoxIsNotAMenu() {
        val screen = listOf("● Next steps:", "  1. build", "  2. test", "", "─────────", "❯ ", "─────────")
        assertTrue(QuickReplies.find(screen).isEmpty())
    }

    @Test fun menuHigherUpWithEmptyRowsBelow() {
        val screen = listOf("Do you want to proceed?", "❯ 1. Yes", "  2. No") + List(40) { "" }
        assertEquals(2, QuickReplies.find(screen).size)
    }

    @Test fun listAboveInputBoxInHerdrWideLayoutIsNotAMenu() {
        val screen = listOf("w9 home     │ ● Plan:", "wA webshop  │   1. build", "wE /home    │   2. test",
            "            │ ──────────", "            │ ❯ ", "            │ ──────────")
        assertTrue(QuickReplies.find(screen).isEmpty())
    }

    @Test fun listInShellOutputIsNotAMenu() {
        assertTrue(QuickReplies.find(listOf("1. alpha", "2. beta", "dev@DevBox:~$ ")).isEmpty())
    }

    @Test fun singleItemIsNotAMenu() {
        assertTrue(QuickReplies.find(listOf("1. only one", "")).isEmpty())
    }

    @Test fun takesTheLastMenu() {
        val screen = listOf("1. a", "2. b", "text", "1. x", "2. y", "3. z")
        assertEquals(listOf("x", "y", "z"), QuickReplies.find(screen).map { it.label })
    }

    /** real herdr screens (46 columns): Claude's options wrap, herdr's scrollbar sits in the last column */
    @Test fun wrappedOptionsWithHerdrScrollbar() {
        for (f in listOf("herdr-claude-menu-40.txt", "herdr-claude-menu-20.txt")) {
            val rows = javaClass.classLoader!!.getResource(f)!!.readText().lines()
            val c = QuickReplies.find(rows)
            assertEquals(f, listOf("1", "2", "3"), c.map { it.key })
            assertEquals(f, true, c[0].selected)
        }
    }
}
