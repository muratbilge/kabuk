package app.kabuk.agents

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.kabuk.term.TabManager
import app.kabuk.text.Choice

/** An answer button in a "needs you" notification: sends the answer to that agent's pane over SSH. */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tab = TabManager.sshTabs().firstOrNull { it.id == intent.getLongExtra("tab", -1) }
        // a question on the orchtool bus: the option's text is the answer, the first answer wins
        val question = intent.getLongExtra("question", -1)
        if (question >= 0) {
            val label = intent.getStringExtra("label") ?: return
            val key = intent.getStringExtra("agent") ?: return
            if (tab == null) return AgentNotifier.answered(key, "q$question", label, false)
            val pending = goAsync()
            tab.answerQuestion(question, label) { ok ->
                AgentNotifier.answered(key, "q$question", label, ok)
                pending.finish()
            }
            return
        }
        val pane = intent.getStringExtra("pane") ?: return
        val key = intent.getStringExtra("key") ?: return
        val label = intent.getStringExtra("label") ?: ""
        val expect = intent.getStringExtra("expect") ?: return
        val agent = intent.getStringExtra("agent") ?: return
        if (tab == null) return AgentNotifier.answered(agent, expect, "$key · $label", false)
        val pending = goAsync()
        tab.answerAgent(pane, Choice(key, label, false), expect) { ok ->
            AgentNotifier.answered(agent, expect, "$key · $label", ok)
            pending.finish()
        }
    }
}
