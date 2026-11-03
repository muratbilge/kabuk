package app.kabuk.agents

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.kabuk.term.TabManager
import app.kabuk.text.Choice

/**
 * An answer button in a "needs you" notification: sends the answer to that agent's pane over SSH.
 * The work runs on its own thread; the foreground service keeps the process, so the receiver returns at once
 * (a broadcast may take a few seconds at most, an answer up to three orch calls).
 */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // tab ids start at 1 in every process: the server must be the one the button was made for
        val tab = TabManager.sshTabs().firstOrNull { it.id == intent.getLongExtra("tab", -1) }
            ?.takeIf { it.hostId == intent.getStringExtra("host") }
        // a question on the orchtool bus: the option's text is the answer, the first answer wins
        val question = intent.getLongExtra("question", -1)
        if (question >= 0) {
            val label = intent.getStringExtra("label") ?: return
            val key = intent.getStringExtra("agent") ?: return
            if (tab == null) return AgentNotifier.answered(key, "q$question", label, AnswerOutcome.OFFLINE)
            // a button from before a reconnect or restart: that question must still be open on this server
            if (tab.busQuestions.value.none { it.id == question }) return AgentNotifier.answered(key, "q$question", label, AnswerOutcome.GONE)
            tab.answerQuestion(question, label) { outcome -> AgentNotifier.answered(key, "q$question", label, outcome) }
            return
        }
        val pane = intent.getStringExtra("pane") ?: return
        val key = intent.getStringExtra("key") ?: return
        val label = intent.getStringExtra("label") ?: ""
        val expect = intent.getStringExtra("expect") ?: return
        val agent = intent.getStringExtra("agent") ?: return
        if (tab == null) return AgentNotifier.answered(agent, expect, "$key · $label", AnswerOutcome.OFFLINE)
        tab.answerAgent(pane, Choice(key, label, false), expect) { outcome ->
            AgentNotifier.answered(agent, expect, "$key · $label", outcome)
        }
    }
}
