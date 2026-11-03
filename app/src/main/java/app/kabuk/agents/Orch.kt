package app.kabuk.agents

import app.kabuk.data.shellQuote
import app.kabuk.log.KLog
import app.kabuk.term.SshTab
import app.kabuk.text.Pending
import app.kabuk.text.QuickReplies
import net.schmizz.sshj.SSHClient
import org.json.JSONArray
import org.json.JSONObject

/** A question an agent asked you on the orchtool bus (`ask_human`); the first answer wins. */
data class BusQuestion(val id: Long, val from: String, val text: String, val options: List<String>, val tsMs: Long)

/** One row of the orchtool timeline: messages between agents and you, questions, answers. */
data class BusMessage(val id: Long, val tsMs: Long, val from: String, val to: String, val kind: String, val text: String)

/**
 * Talks to orchtool (`orch`, the herdr replacement) over SSH. Agents, their dialogs, your questions and the
 * timeline come live from `orch watch --json`; actions are single `orch` commands.
 */
object Orch {
    /** a non-login SSH shell often lacks ~/.local/bin; agents started from here need it too */
    private const val HEARTBEAT_DEADLINE_MS = 150_000L
    private const val FIND = "export PATH=\"\$HOME/.local/bin:\$PATH\"; O=\$(command -v orch) || exit 3; "

    class Update(
        val agents: List<Agent>,
        val screens: Map<String, Pending>,
        val questions: List<BusQuestion>,
        val timeline: List<BusMessage>,
        val human: String,
    )

    /** a watch line → update; null for an error line (orchd not running right now) */
    fun parseUpdate(line: String): Update? {
        val o = JSONObject(line)
        if (o.has("error")) return null
        val arr = o.getJSONArray("agents")
        val agents = List(arr.length()) { i ->
            val a = arr.getJSONObject(i)
            val pane = a.optString("pane").takeIf { !a.isNull("pane") && it.isNotEmpty() } ?: ""
            val bus = a.optString("name")
            Agent(
                terminalId = pane.ifEmpty { "bus:$bus" }, paneId = pane,
                name = a.optString("kind", "agent"), status = a.optString("status", "unknown"),
                cwd = a.optString("cwd"), workspace = a.optString("workspace"),
                workspaceNumber = a.optInt("workspace_number"), focused = a.optBoolean("focused"),
                bus = bus, statusText = if (a.isNull("status_text")) "" else a.optString("status_text"),
                registered = a.optBoolean("registered", true),
            )
        }
        val screens = HashMap<String, Pending>()
        o.optJSONObject("screens")?.let { s ->
            s.keys().forEach { pane -> QuickReplies.pending(s.getString(pane))?.let { screens[pane] = it } }
        }
        val qs = o.optJSONArray("questions") ?: JSONArray()
        val questions = List(qs.length()) { i ->
            val q = qs.getJSONObject(i)
            val opts = q.optJSONArray("options") ?: JSONArray()
            BusQuestion(q.getLong("id"), q.optString("from"), q.optString("text"),
                List(opts.length()) { opts.getString(it) }, q.optLong("ts_ms"))
        }
        val tl = o.optJSONArray("timeline") ?: JSONArray()
        val timeline = List(tl.length()) { i ->
            val m = tl.getJSONObject(i)
            BusMessage(m.getLong("id"), m.optLong("ts_ms"), m.optString("from"), m.optString("to"),
                m.optString("kind"), m.optString("text"))
        }
        return Update(agents, screens, questions, timeline, o.optString("human", "murat"))
    }

    /** runs `orch watch` until the connection ends or [active] turns false; UNAVAILABLE when orch isn't installed */
    fun stream(c: SSHClient, active: () -> Boolean, onUpdate: (Update?) -> Unit): Herdr.StreamEnd {
        val session = c.startSession()
        try {
            val cmd = session.exec(FIND + "exec \"\$O\" watch --json")
            val reader = cmd.inputStream.bufferedReader()
            var any = false
            // orch watch prints at least every 60 s: silence for longer means a stuck stream, so close it
            // (the caller then clears what it showed and starts again)
            val lastLine = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val watchdog = kotlin.concurrent.thread(name = "orch-watchdog", isDaemon = true) {
                try {
                    while (true) {
                        Thread.sleep(5_000)
                        if (System.currentTimeMillis() - lastLine.get() > HEARTBEAT_DEADLINE_MS || !active()) {
                            if (active()) KLog.w("AGENT", "orch watch silent for ${HEARTBEAT_DEADLINE_MS / 1000} s: reconnecting")
                            runCatching { session.close() }
                            break
                        }
                    }
                } catch (_: InterruptedException) {}
            }
            try {
            while (active()) {
                val line = reader.readLine() ?: break
                lastLine.set(System.currentTimeMillis())
                if (line.isBlank()) continue
                any = true
                val u = runCatching { parseUpdate(line) }
                    .onFailure { KLog.w("AGENT", "bad orch watch line (${line.length} chars)", it) }.getOrNull()
                onUpdate(u)
            }
            } finally { watchdog.interrupt() }
            if (!any) {
                cmd.join(3, java.util.concurrent.TimeUnit.SECONDS)
                if (cmd.exitStatus == 3) return Herdr.StreamEnd.UNAVAILABLE
            }
            return Herdr.StreamEnd.ENDED
        } finally {
            runCatching { session.close() }
        }
    }

    /**
     * Answers a dialog on an agent's screen (approval menu). Rereads the pane first and only sends when the same
     * question is still open ([expect] = its fingerprint), so a late tap can't answer a newer one.
     * Returns null when sent, else a short reason (safe to log: no screen text).
     */
    fun answerScreen(c: SSHClient, pane: String, key: String, expect: String): String? {
        val (code, out) = SshTab.exec(c, FIND + "\"\$O\" read ${shellQuote(pane)} --lines 60 --hash", timeoutS = 8)
        if (code != 0) return "read failed (exit $code)"
        val hash = out.lineSequence().firstOrNull()?.removePrefix("hash:")?.takeIf { it.isNotBlank() && it.length <= 32 }
            ?: return "no screen hash"
        val now = QuickReplies.pending(out.substringAfter('\n')) ?: return "no dialog on screen"
        if (now.fingerprint != expect) return "dialog changed"
        if (now.choices.none { it.key == key }) return "choice $key not offered"
        // orchd sends only if the screen is still exactly what we read, and answers each screen once:
        // a second phone/TUI answering the same dialog is refused instead of reaching the next one
        val sent = SshTab.exec(
            c, FIND + "\"\$O\" send ${shellQuote(pane)} ${shellQuote(key)} --expect ${shellQuote(hash)} --lines 60", timeoutS = 8,
        ).first
        return if (sent == 0) null else "refused by orchd (exit $sent: screen changed or answered already)"
    }

    /** answers a bus question; false when it was already answered (by Telegram, the TUI, …) or on failure */
    fun answerQuestion(c: SSHClient, id: Long, text: String): Boolean =
        SshTab.exec(c, FIND + "\"\$O\" answer --by kabuk --literal $id -- ${shellQuote(text)}", timeoutS = 8).first == 0

    /** a message from you to an agent (by its bus name, or "all") */
    fun send(c: SSHClient, to: String, text: String): Boolean =
        SshTab.exec(c, FIND + "\"\$O\" msg -- ${shellQuote(to)} ${shellQuote(text)}", timeoutS = 8).first == 0

    /** [tool] (claude, codex) in a new orch pane in [dir] (its folder's workspace), brought on screen */
    fun startAgent(c: SSHClient, dir: String, tool: String): Boolean {
        val cwd = if (dir == "~" || dir.startsWith("~/")) "\"\$HOME\"" + shellQuote(dir.removePrefix("~")) else shellQuote(dir)
        val (code, _) = SshTab.exec(
            c, FIND + "cd $cwd 2>/dev/null || exit 4; P=\$(\"\$O\" new --cwd \"\$PWD\" -- ${shellQuote(tool)}) || exit 1; " +
                "\"\$O\" focus \"\$P\"",
            timeoutS = 10,
        )
        if (code == 4) throw java.io.FileNotFoundException(dir)
        return code == 0
    }

    /** brings an agent's orch pane on screen in the TUI (agents in herdr panes can't be switched to from here) */
    fun focus(c: SSHClient, a: Agent): Boolean =
        a.paneId.startsWith("orch:") &&
            SshTab.exec(c, FIND + "\"\$O\" focus ${shellQuote(a.paneId)}", timeoutS = 8).first == 0

    /** the whole recent text of a pane (orch or herdr), for "Copy text…" */
    fun read(c: SSHClient, pane: String): String? {
        val (code, out) = SshTab.exec(c, FIND + "\"\$O\" read ${shellQuote(pane)} --lines 4000", timeoutS = 15)
        return if (code == 0) out else null
    }
}
