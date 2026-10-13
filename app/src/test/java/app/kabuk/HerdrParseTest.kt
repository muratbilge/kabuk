package app.kabuk

import app.kabuk.agents.Herdr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses real `herdr agent list` / `workspace list` output (herdr 0.7.1). */
class HerdrParseTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource(n).readText()

    @Test fun parsesAndSortsNeedsYouFirst() {
        val agents = Herdr.parse(res("herdr-agents.json"), res("herdr-workspaces.json"))
        assertEquals(8, agents.size)
        assertEquals("blocked", agents[0].status)
        assertEquals("webshop", agents[0].place)
        assertEquals("working", agents[1].status)
        assertTrue(agents.drop(2).all { it.status == "idle" })
        assertTrue(agents.any { it.name == "codex" })
        assertTrue(agents.all { it.terminalId.startsWith("term_") })
    }

    @Test fun folderNameWhenWorkspaceIsHome() {
        val agents = Herdr.parse(res("herdr-agents.json"), res("herdr-workspaces.json"))
        val home = agents.first { it.workspace == "~" }
        assertEquals(home.cwd.substringAfterLast('/'), home.place)
    }
}
