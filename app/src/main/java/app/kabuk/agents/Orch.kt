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
    /** the `orch watch` line format this app understands (`"v"` in every line; orchtool/notes/kabuk-contract-v2.md) */
    const val V = 1
    private const val HEARTBEAT_DEADLINE_MS = 150_000L
    /** a non-login SSH shell often lacks ~/.local/bin; agents started from here need it too (exit 3 = no orch) */
    private const val FIND = "export PATH=\"\$HOME/.local/bin:\$PATH\"; O=\$(command -v orch) || exit 3; "

    class Update(
        val agents: List<Agent>,
        val screens: Map<String, Pending>,
        val questions: List<BusQuestion>,
        val timeline: List<BusMessage>,
        val human: String,
        /** the line's format version and the server's orch version ("" before orchtool 0.1.1) */
        val v: Int = V,
        val orch: String = "",
    )

    /** what came of an `orch` command, and why, in words safe to log (never the dialog's text) */
    class Answered(val outcome: AnswerOutcome, val why: String)

    /**
     * What an `orch` exit status means (orchtool's exit-code table, kabuk-contract-v2.md): 0 sent, 10 the screen
     * changed, 11 answered already, 12 the pane exited, 13 the program isn't reading input, 14 no such pane or
     * question, 20 orchd unreachable, 2 the CLI didn't understand us, 3 our own wrapper found no orch. An orch
     * older than the table exits 1 for everything, which is FAILED: honest, since it didn't say.
     */
    fun outcomeOf(exit: Int?): AnswerOutcome = when (exit) {
        0 -> AnswerOutcome.SENT
        10 -> AnswerOutcome.CHANGED
        11 -> AnswerOutcome.TAKEN
        12, 14 -> AnswerOutcome.GONE
        13 -> AnswerOutcome.STUCK
        20, 3 -> AnswerOutcome.DAEMON
        2 -> AnswerOutcome.MISMATCH
        null -> AnswerOutcome.UNSURE
        else -> AnswerOutcome.FAILED
    }

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
        return Update(agents, screens, questions, timeline, o.optString("human", "murat"), o.optInt("v", V), o.optString("orch"))
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
            var last: String? = null
            try {
            while (active()) {
                val line = reader.readLine() ?: break
                lastLine.set(System.currentTimeMillis())
                if (line.isBlank()) continue
                any = true
                // the heartbeat resends an unchanged line: nothing to parse, show or notify again
                if (line == last) continue
                last = line
                // only the exception's class: on Android a JSONException's message quotes the whole input
                // line, which holds dialog and question text that must never reach the log
                val u = runCatching { parseUpdate(line) }
                    .onFailure { KLog.w("AGENT", "bad orch watch line (${line.length} chars): ${it.javaClass.simpleName}") }.getOrNull()
                onUpdate(u)
            }
            } finally { watchdog.interrupt() }
            if (!any) {
                cmd.join(3, java.util.concurrent.TimeUnit.SECONDS)
                when (cmd.exitStatus) {
                    3 -> return Herdr.StreamEnd.UNAVAILABLE
                    // an orch from before `watch` (clap: unknown subcommand)
                    2 -> return Herdr.StreamEnd.MISMATCH
                }
            }
            return Herdr.StreamEnd.ENDED
        } finally {
            runCatching { session.close() }
        }
    }

    /** the Escape key as a choice (the reply chips' Esc): sent as a key press, not as text */
    const val ESC = "\u001b"

    /** the command that answers a dialog: orchd sends only if the screen still hashes to [hash] (testable as text) */
    internal fun sendCommand(pane: String, key: String, hash: String): String =
        FIND + "\"\$O\" send ${shellQuote(pane)} " + (if (key == ESC) "--key Escape" else shellQuote(key)) +
            " --expect ${shellQuote(hash)} --lines 60"

    /**
     * Runs an `orch` command. With [retry] (only when orchd answers it once: orch panes, bus questions) no reply
     * within the timeout → one retry: a first try that landed is then refused with 11 ("answered"), which counts
     * as sent; a second silence is UNSURE. A herdr pane has no such memory, so a retry could type the key twice:
     * never retry there. Returns the exit status (null = no reply) and whether it came from the retry.
     */
    private fun once(c: SSHClient, cmd: String, retry: Boolean): Pair<Int?, Boolean> {
        val tries = if (retry) 2 else 1
        for (attempt in 0 until tries) {
            try {
                return SshTab.exec(c, cmd, timeoutS = 8).first to (attempt == 1)
            } catch (e: java.io.IOException) {
                if (!e.message.orEmpty().contains("took longer")) throw e
                if (attempt < tries - 1) KLog.w("AGENT", "orch gave no reply in 8 s: trying once more")
            }
        }
        return null to (tries == 2)
    }

    /**
     * What a repeatable command's exit status means, given whether it came from the retry: after a silent
     * first try, "answered" means the first try landed, and "the screen changed" may mean the same (the agent
     * took the key and moved on), so that is UNSURE rather than a confident "not sent".
     */
    internal fun sendOutcome(exit: Int?, retried: Boolean, what: String = "sent"): Answered = when {
        exit == 0 -> Answered(AnswerOutcome.SENT, if (retried) "$what on the second try" else what)
        retried && exit == 11 -> Answered(AnswerOutcome.SENT, "the first try landed (refused as answered on the retry)")
        retried && exit == 10 -> Answered(AnswerOutcome.UNSURE, "the screen changed after a silent first try (it may have landed)")
        exit == 11 -> Answered(AnswerOutcome.TAKEN, "answered already")
        else -> Answered(outcomeOf(exit), "not $what (exit $exit)")
    }

    /**
     * Answers a dialog on an agent's screen (approval menu). Rereads the pane first and only sends when the same
     * question is still open ([expect] = its fingerprint), so a late tap can't answer a newer one; orchd then
     * sends only if the screen is still exactly what was read, and answers each screen once, so a second
     * phone/TUI answering the same dialog is refused instead of reaching the next one.
     */
    fun answerScreen(c: SSHClient, pane: String, key: String, expect: String): Answered {
        val (code, out) = SshTab.exec(c, FIND + "\"\$O\" read ${shellQuote(pane)} --lines 60 --hash", timeoutS = 8)
        if (code != 0) return Answered(if (code == null) AnswerOutcome.FAILED else outcomeOf(code), "read failed (exit $code)")
        val hash = out.lineSequence().firstOrNull()?.removePrefix("hash:")?.takeIf { it.isNotBlank() && it.length <= 32 }
            ?: return Answered(AnswerOutcome.FAILED, "no screen hash")
        val now = QuickReplies.pending(out.substringAfter('\n')) ?: return Answered(AnswerOutcome.CHANGED, "no dialog on screen")
        if (now.fingerprint != expect) return Answered(AnswerOutcome.CHANGED, "dialog changed")
        if (key != ESC && now.choices.none { it.key == key }) return Answered(AnswerOutcome.CHANGED, "choice $key not offered")
        val (sent, retried) = once(c, sendCommand(pane, key, hash), retry = !pane.startsWith("herdr:"))
        return sendOutcome(sent, retried)
    }

    /** answers a bus question (first answer wins): TAKEN when it was answered already (the TUI, the CLI, …) */
    fun answerQuestion(c: SSHClient, id: Long, text: String): Answered {
        val (exit, retried) = once(c, FIND + "\"\$O\" answer --by kabuk --literal $id -- ${shellQuote(text)}", retry = true)
        return sendOutcome(exit, retried, what = "won")
    }

    /** a message from you to an agent (by its bus name, or "all") */
    fun send(c: SSHClient, to: String, text: String): Boolean =
        SshTab.exec(c, FIND + "\"\$O\" msg -- ${shellQuote(to)} ${shellQuote(text)}", timeoutS = 8).first == 0

    /** [tool] (claude, codex) in a new orch pane in [dir] (its folder's workspace), brought on screen */
    fun startAgent(c: SSHClient, dir: String, tool: String): Boolean {
        val cwd = if (dir == "~" || dir.startsWith("~/")) "\"\$HOME\"" + shellQuote(dir.removePrefix("~")) else shellQuote(dir)
        // run through a login shell (bash, zsh or fish alike: it only execs sh with the real command), so the
        // agent gets the user's real environment (PATH, JAVA_HOME, …), not the bare one of an SSH exec channel
        val inner = "cd $cwd 2>/dev/null || exit 4; P=\$(\"\$O\" new --cwd \"\$PWD\" -- ${shellQuote(tool)}) || exit 1; \"\$O\" focus \"\$P\""
        val (code, _) = SshTab.exec(
            c, FIND + "export O; KABUK_CMD=${shellQuote(inner)}; export KABUK_CMD; " +
                "exec \"\${SHELL:-/bin/sh}\" -lc 'exec sh -c \"\$KABUK_CMD\"'",
            timeoutS = 15,
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
