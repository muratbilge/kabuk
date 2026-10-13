package app.kabuk.agents

import app.kabuk.data.shellQuote
import app.kabuk.term.SshTab
import net.schmizz.sshj.SSHClient
import org.json.JSONObject

/** An AI agent running in a herdr pane on the server. */
data class Agent(
    val terminalId: String,
    val paneId: String,
    val name: String,          // "claude", "codex", ...
    val status: String,        // idle | working | blocked | unknown
    val cwd: String,
    val workspace: String,     // the workspace label ("notes-app", "~")
    val workspaceNumber: Int,
    val focused: Boolean,
) {
    val needsYou get() = status == "blocked"
    val working get() = status == "working"
    /** finished and not looked at yet (herdr's "done"), or idle */
    val resting get() = status == "done" || status == "idle"
    /** "notes-app" or the folder name when the workspace has no real label */
    val place get() = workspace.takeIf { it.isNotBlank() && it != "~" } ?: cwd.substringAfterLast('/').ifBlank { cwd }
}

/** Talks to herdr's CLI over SSH. Nothing has to be installed on the server besides herdr itself. */
object Herdr {
    private const val FIND = "H=\$(command -v herdr || echo \"\$HOME/.local/bin/herdr\"); [ -x \"\$H\" ] || exit 3; "

    /** null when herdr isn't installed or not running */
    fun agents(c: SSHClient): List<Agent>? {
        val (code, out) = SshTab.exec(c, FIND + "\"\$H\" agent list && echo && \"\$H\" workspace list", timeoutS = 8)
        if (code != 0) return null
        val lines = out.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return null
        return parse(lines[0], lines[1])
    }

    fun parse(agentJson: String, workspaceJson: String): List<Agent> {
        val ws = JSONObject(workspaceJson).getJSONObject("result").getJSONArray("workspaces")
        val labels = HashMap<String, Pair<String, Int>>()
        for (i in 0 until ws.length()) {
            val w = ws.getJSONObject(i)
            labels[w.getString("workspace_id")] = w.optString("label") to w.optInt("number")
        }
        val arr = JSONObject(agentJson).getJSONObject("result").getJSONArray("agents")
        return List(arr.length()) { i ->
            val a = arr.getJSONObject(i)
            val w = labels[a.optString("workspace_id")]
            Agent(
                terminalId = a.optString("terminal_id"), paneId = a.optString("pane_id"),
                name = a.optString("agent", "agent"), status = a.optString("agent_status", "unknown"),
                cwd = a.optString("cwd"), workspace = w?.first ?: "", workspaceNumber = w?.second ?: 0,
                focused = a.optBoolean("focused"),
            )
        }.sortedWith(compareBy({ order(it.status) }, { it.workspaceNumber }))
    }

    private fun order(s: String) = when (s) { "blocked" -> 0; "working" -> 1; "done" -> 2; "idle" -> 3; else -> 4 }

    // ---------------------------------------------------------------- live updates

    /** one update from the watcher: the agents and the open question of each waiting one (by pane id) */
    class Update(val agents: List<Agent>, val questions: Map<String, app.kabuk.text.Pending>)

    enum class StreamEnd { UNAVAILABLE, ENDED }

    private val watchScript: String by lazy {
        app.kabuk.term.TabManager.app.assets.open("herdr_watch.py").bufferedReader().use { it.readText() }
    }

    /** a watcher line → update; null for an error line (herdr not running right now) */
    fun parseUpdate(line: String): Update? {
        val o = JSONObject(line)
        if (o.has("error")) return null
        val agents = parse(o.getJSONObject("agents").toString(), o.getJSONObject("workspaces").toString())
        val q = o.optJSONObject("questions")
        val questions = HashMap<String, app.kabuk.text.Pending>()
        q?.keys()?.forEach { pane -> app.kabuk.text.QuickReplies.pending(q.getString(pane))?.let { questions[pane] = it } }
        return Update(agents, questions)
    }

    /**
     * Runs the watcher script on the server (herdr's event stream) and calls [onUpdate] for every change,
     * until the connection ends or [active] turns false. UNAVAILABLE when the server has no python3.
     */
    fun stream(c: SSHClient, active: () -> Boolean, onUpdate: (Update?) -> Unit): StreamEnd {
        val session = c.startSession()
        try {
            val cmd = session.exec("command -v python3 >/dev/null 2>&1 || exit 3; exec python3 -u -c ${shellQuote(watchScript)}")
            val reader = cmd.inputStream.bufferedReader()
            var any = false
            while (active()) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                any = true
                onUpdate(runCatching { parseUpdate(line) }.getOrNull())
            }
            if (!any) {
                cmd.join(3, java.util.concurrent.TimeUnit.SECONDS)
                if (cmd.exitStatus == 3) return StreamEnd.UNAVAILABLE
            }
            return StreamEnd.ENDED
        } finally {
            runCatching { session.close() }
        }
    }

    // ---------------------------------------------------------------- acting on agents

    /**
     * Answers an agent's question by pane, without bringing it on screen. Reads the pane first and only sends
     * when the same question is still open ([expect] = its fingerprint), so a late tap can't answer a newer one.
     */
    fun answer(c: SSHClient, paneId: String, key: String, expect: String): Boolean {
        val (code, text) = SshTab.exec(c, FIND + "\"\$H\" pane read ${shellQuote(paneId)} --source recent --lines 60", timeoutS = 8)
        if (code != 0) return false
        val now = app.kabuk.text.QuickReplies.pending(text) ?: return false
        if (now.fingerprint != expect || now.choices.none { it.key == key }) return false
        return SshTab.exec(c, FIND + "\"\$H\" pane send-text ${shellQuote(paneId)} ${shellQuote(key)}", timeoutS = 8).first == 0
    }

    /** a new workspace in [dir] with [tool] (claude, codex) started in it, brought on screen */
    fun startAgent(c: SSHClient, dir: String, tool: String): Boolean {
        val label = dir.trimEnd('/').substringAfterLast('/').ifBlank { "agent" }
        // ~ and $HOME work like in a shell; everything else is quoted
        val cwd = if (dir == "~" || dir.startsWith("~/")) "\"\$HOME\"" + shellQuote(dir.removePrefix("~")) else shellQuote(dir)
        // herdr quietly uses the home folder for a missing one: check first (exit 4)
        val (code, out) = SshTab.exec(
            c, FIND + "cd $cwd 2>/dev/null || exit 4; \"\$H\" workspace create --cwd \"\$PWD\" --label ${shellQuote(label)} --focus",
            timeoutS = 10,
        )
        if (code == 4) throw java.io.FileNotFoundException(dir)
        if (code != 0) return false
        val pane = runCatching {
            JSONObject(out.trim()).getJSONObject("result").getJSONObject("root_pane").getString("pane_id")
        }.getOrNull() ?: return false
        return SshTab.exec(c, FIND + "\"\$H\" pane run ${shellQuote(pane)} ${shellQuote(tool)}", timeoutS = 8).first == 0
    }

    fun focus(c: SSHClient, a: Agent): Boolean =
        SshTab.exec(c, FIND + "\"\$H\" agent focus ${shellQuote(a.terminalId)}", timeoutS = 8).first == 0

    /** (pane id, text) of the focused pane, wrapped lines of herdr's own layout already joined */
    fun readFocused(c: SSHClient): Pair<String, String>? {
        val (code, out) = SshTab.exec(c, FIND + "\"\$H\" pane list", timeoutS = 8)
        if (code != 0) return null
        val panes = JSONObject(out.trim()).getJSONObject("result").getJSONArray("panes")
        val id = (0 until panes.length()).map { panes.getJSONObject(it) }
            .firstOrNull { it.optBoolean("focused") }?.optString("pane_id") ?: return null
        val text = SshTab.exec(c, FIND + "\"\$H\" pane read ${shellQuote(id)} --source recent-unwrapped --lines 4000", timeoutS = 15)
        return if (text.first == 0) id to text.second else null
    }
}
