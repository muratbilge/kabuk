package app.kabuk

import app.kabuk.agents.Orch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses a real `orch watch --once` line (orchtool 0.1.0): a blocked Claude with its menu, a question, the timeline. */
class OrchParseTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource(n).readText()

    @Test fun agentsScreensQuestionsTimeline() {
        val u = Orch.parseUpdate(res("orch-watch.json").trim())!!
        val a = u.agents.single()
        assertEquals("webshop-claude", a.bus)
        assertEquals("claude", a.name)
        assertEquals("blocked", a.status)
        assertEquals("orch:p1", a.paneId)
        assertEquals(a.paneId, a.terminalId)
        assertEquals("webshop", a.place)
        assertEquals(1, a.workspaceNumber)
        // the approval menu on its screen, answerable like a herdr one
        val menu = u.screens[a.paneId]
        assertNotNull(menu)
        assertTrue(menu!!.choices.size >= 2)
        assertEquals("1", menu.choices[0].key)

        val q = u.questions.single()
        assertEquals("webshop-claude", q.from)
        assertEquals(listOf("yes, deploy", "not yet"), q.options)
        assertEquals(listOf("ask", "msg"), u.timeline.map { it.kind })
        assertEquals("murat", u.human)
    }

    @Test fun errorLineMeansUnavailable() {
        assertNull(Orch.parseUpdate("""{"error":"orchd is not running"}"""))
    }

    @Test fun agentWithoutPaneGetsABusId() {
        val line = """{"v":1,"human":"murat","agents":[{"name":"mcp-x","kind":"codex","status":"idle","status_text":null,""" +
            """"pane":null,"cwd":"/w","workspace":"","workspace_number":0,"focused":false}],"questions":[],"screens":{},"timeline":[]}"""
        val a = Orch.parseUpdate(line)!!.agents.single()
        assertEquals("bus:mcp-x", a.terminalId)
        assertEquals("", a.paneId)
        assertEquals("", a.statusText)
        assertEquals("w", a.place)
        assertTrue("no flag = a bus agent", a.registered)
    }

    /** a pane orchd only detected (a codex without hooks): shown, but not a message recipient */
    @Test fun unregisteredPaneAgent() {
        val line = """{"v":1,"human":"murat","agents":[{"name":"codex-p3","kind":"codex","status":"working","status_text":null,""" +
            """"pane":"orch:p3","cwd":"/w","workspace":"w","workspace_number":1,"focused":false,"seen_ms":null,"registered":false}],""" +
            """"questions":[],"screens":{},"timeline":[]}"""
        val a = Orch.parseUpdate(line)!!.agents.single()
        assertEquals("orch:p3", a.paneId)
        assertEquals(false, a.registered)
        assertTrue(Orch.parseUpdate(res("orch-watch.json").trim())!!.agents.single().registered)
    }
}
