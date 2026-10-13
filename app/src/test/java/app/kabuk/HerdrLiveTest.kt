package app.kabuk

import app.kabuk.agents.Herdr
import app.kabuk.text.QuickReplies
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrLiveTest {
    private val claudeAsks = """
● Bash(./gradlew assembleRelease)
  ⎿  Running…

╭──────────────────────────────────────╮
│ Bash command                         │
│                                      │
│   ./gradlew assembleRelease          │
│   Build the release APK              │
│                                      │
│ Do you want to proceed?              │
│ ❯ 1. Yes                             │
│   2. Yes, and don't ask again for    │
│      ./gradlew commands in this      │
│   3. No, and tell Claude what to do  │
│      differently (esc)               │
╰──────────────────────────────────────╯
""".trimIndent()

    @Test fun questionAboveTheMenu() {
        val p = QuickReplies.pending(claudeAsks)!!
        assertEquals(listOf("1", "2", "3"), p.choices.map { it.key })
        assertTrue(p.prompt, p.prompt.endsWith("Do you want to proceed?"))
        assertTrue(p.prompt, p.prompt.contains("./gradlew assembleRelease"))
        assertTrue(p.prompt, !p.prompt.contains("│"))
    }

    @Test fun noMenuNoQuestion() {
        assertNull(QuickReplies.pending("❯ \n  ⏵⏵ auto mode on"))
    }

    /** the watcher's line format, built from real herdr socket replies (agent.list / workspace.list) */
    @Test fun watcherLine() {
        val line = JSONObject()
            .put("agents", JSONObject("""{"id":"k","result":{"type":"agent_list","agents":[
                {"terminal_id":"t1","agent":"claude","agent_status":"blocked","workspace_id":"wE","pane_id":"wE:p1","focused":false,"cwd":"/projects/kabuk"},
                {"terminal_id":"t2","name":"kabuk-test","agent_status":"done","workspace_id":"wE","pane_id":"wE:p4","focused":true,"cwd":"/tmp"}]}}"""))
            .put("workspaces", JSONObject("""{"id":"k","result":{"type":"workspace_list","workspaces":[
                {"workspace_id":"wE","number":1,"label":"kabuk","focused":true}]}}"""))
            .put("questions", JSONObject().put("wE:p1", claudeAsks))
            .toString()
        val u = Herdr.parseUpdate(line)!!
        assertEquals(listOf("blocked", "done"), u.agents.map { it.status })
        assertTrue(u.agents[1].resting)
        assertEquals(3, u.questions["wE:p1"]!!.choices.size)
        assertNull(Herdr.parseUpdate("""{"error": "no socket"}"""))
    }

    /** two approvals with identical buttons are still different questions: a late Yes must not approve the next one */
    @Test fun sameButtonsDifferentQuestion() {
        val first = QuickReplies.pending(claudeAsks)!!
        val second = QuickReplies.pending(claudeAsks.replace("./gradlew assembleRelease ", "rm -rf build             "))!!
        assertEquals(first.choices, second.choices)
        assertTrue(first.fingerprint != second.fingerprint)
        assertEquals(first.fingerprint, QuickReplies.pending(claudeAsks)!!.fingerprint)
    }
}
