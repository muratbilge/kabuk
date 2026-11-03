package app.kabuk.agents

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.kabuk.MainActivity
import app.kabuk.R
import app.kabuk.data.Settings
import app.kabuk.term.SshTab
import app.kabuk.term.TabManager

/**
 * Turns agent status changes (herdr or orchtool) and orchtool questions into notifications:
 * an agent starts waiting for you → "needs you" (loud); working → idle → "finished" (quiet, optional).
 */
object AgentNotifier {
    private const val NEEDS = "agent_needs_you"
    private const val DONE = "agent_finished"
    private lateinit var ctx: Context
    private val last = HashMap<String, String>()   // "tab/terminal" -> status
    private val notified = HashMap<String, String>()  // "needs you" shown -> for which question
    /**
     * A "needs you" whose agent or question vanished from the stream is cancelled only after a grace period:
     * a gap (orchd restart, SSH reconnect) must not re-alert the very same question when it is back.
     */
    private val vanishing = HashMap<String, Runnable>()
    private const val GRACE_MS = 45_000L

    fun init(c: Context) {
        ctx = c
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(NEEDS, "Agent needs you", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "An agent is waiting for an answer or approval"
        })
        nm.createNotificationChannel(NotificationChannel(DONE, "Agent finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "An agent finished its work"
        })
    }

    @Synchronized
    fun update(tab: SshTab) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val now = tab.agents.value
        val seen = HashSet<String>()
        for (a in now) {
            val key = "${tab.id}/${a.terminalId}"
            seen += key
            vanishing.remove(key)?.let { TabManager.main.removeCallbacks(it) }
            val before = last.put(key, a.status)
            val watching = TabManager.foreground && TabManager.terminalVisible && TabManager.current.value == tab.id && a.focused
            val id = key.hashCode()
            val q = tab.questions.value[a.paneId]
            val qKey = q?.fingerprint ?: ""
            when {
                // still waiting and not notified (for this question) yet; also catches up after you leave the screen
                a.needsYou && notified[key] != qKey && !watching -> {
                    notified[key] = qKey
                    nm.notify(id, build(tab, a, NEEDS, "${a.name} needs you · ${a.place}",
                        q?.prompt?.ifBlank { null } ?: "Waiting for your answer on ${tab.hostName}", q))
                }
                !a.needsYou && key in notified -> { notified -= key; nm.cancel(id) }
                before == "working" && a.resting && Settings.current.notifyFinished && !watching ->
                    nm.notify(id, build(tab, a, DONE, "${a.name} finished · ${a.place}", "Ready for the next prompt on ${tab.hostName}"))
            }
        }
        // questions on the orchtool bus: one notification each, with the agent's options as buttons
        for (q in tab.busQuestions.value) {
            val key = "${tab.id}/q${q.id}"
            seen += key
            vanishing.remove(key)?.let { TabManager.main.removeCallbacks(it) }
            last[key] = "ask"
            if (notified[key] == null && !(TabManager.foreground && TabManager.agentsOpen)) {
                notified[key] = "q${q.id}"
                nm.notify(key.hashCode(), buildQuestion(tab, q, key))
            }
        }
        // agents and questions that disappeared; a "needs you" stays a little in case the stream only has a gap
        last.keys.filter { it.startsWith("${tab.id}/") && it !in seen }.forEach { key ->
            last.remove(key)
            if (key in notified && !tab.closed) {
                val r = Runnable { forget(key) }
                vanishing[key] = r
                TabManager.main.postDelayed(r, GRACE_MS)
            } else forget(key)
        }
    }

    @Synchronized
    private fun forget(key: String) {
        vanishing.remove(key)
        notified.remove(key)
        ctx.getSystemService(NotificationManager::class.java).cancel(key.hashCode())
    }

    private fun buildQuestion(tab: SshTab, q: BusQuestion, key: String): Notification {
        val open = Intent(ctx, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_AGENT)
            .putExtra("tab", tab.id).putExtra("agents", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, key.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(ctx, NEEDS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${q.from} asks you")
            .setContentText(q.text)
            .setStyle(Notification.BigTextStyle().bigText(q.text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
        // Android 8-11 can't require unlocking for a button: there only tapping (opens the app) answers
        if (android.os.Build.VERSION.SDK_INT >= 31) q.options.take(3).forEachIndexed { i, opt ->
            val identity = Uri.parse("kabuk://ask/${tab.id}/${q.id}/$i")
            val bi = PendingIntent.getBroadcast(ctx, identity.hashCode(),
                Intent(ctx, AnswerReceiver::class.java).setData(identity)
                    .putExtra("tab", tab.id).putExtra("question", q.id).putExtra("label", opt).putExtra("agent", key),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val label = if (opt.length > 20) opt.take(19).trimEnd() + "…" else opt
            b.addAction(Notification.Action.Builder(null, label, bi).setAuthenticationRequired(true).build())
        }
        return b.build()
    }

    private fun build(tab: SshTab, a: Agent, channel: String, title: String, text: String, q: app.kabuk.text.Pending? = null): Notification {
        val open = Intent(ctx, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_AGENT)
            .putExtra("tab", tab.id).putExtra("terminal", a.terminalId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, "${tab.id}/${a.terminalId}".hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(if (channel == NEEDS) Notification.CATEGORY_REMINDER else Notification.CATEGORY_STATUS)
        // answer buttons (Android shows up to 3); "No, and tell …" opens the app to write what instead
        q?.choices?.take(3)?.forEach { c -> b.addAction(action(tab, a, c, q)) }
        return b.build()
    }

    private fun action(tab: SshTab, a: Agent, c: app.kabuk.text.Choice, q: app.kabuk.text.Pending): Notification.Action {
        val label = "${c.key} " + c.label.let { if (it.length > 18) it.take(17).trimEnd() + "…" else it }
        // every question gets its own intents: an updated notification can't change what an older button sends
        val identity = android.net.Uri.parse("kabuk://answer/${tab.id}/${Uri.encode(a.paneId)}/${c.key}/${q.fingerprint.hashCode()}")
        val req = identity.hashCode()
        val tell = c.label.startsWith("No", ignoreCase = true) && c.label.contains("tell", ignoreCase = true)
        // Android 8-11 can't require unlocking for a button: there the button opens the app (which needs unlocking)
        val pi = if (tell || android.os.Build.VERSION.SDK_INT < 31) {
            PendingIntent.getActivity(ctx, req,
                Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_AGENT).setData(identity)
                    .putExtra("tab", tab.id).putExtra("terminal", a.terminalId)
                    .putExtra("pane", a.paneId).putExtra("reply", c.key).putExtra("label", c.label).putExtra("tell", tell)
                    .putExtra("expect", q.fingerprint).putExtra("token", Settings.shortcutToken())
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getBroadcast(ctx, req,
                Intent(ctx, AnswerReceiver::class.java).setData(identity)
                    .putExtra("tab", tab.id).putExtra("pane", a.paneId).putExtra("key", c.key).putExtra("label", c.label)
                    .putExtra("expect", q.fingerprint)
                    .putExtra("agent", "${tab.id}/${a.terminalId}"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val b = Notification.Action.Builder(null, label, pi)
        // not from a locked phone in a pocket
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setAuthenticationRequired(true)
        return b.build()
    }

    /**
     * After an answer from the notification: confirm briefly, then it goes away. Not if a new question
     * arrived meanwhile (its notification must stay).
     */
    @Synchronized
    fun answered(agentKey: String, fingerprint: String, text: String, outcome: AnswerOutcome) {
        if (notified[agentKey] != null && notified[agentKey] != fingerprint) return
        val notificationId = agentKey.hashCode()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val fine = outcome == AnswerOutcome.SENT || outcome == AnswerOutcome.ALREADY
        val n = Notification.Builder(ctx, NEEDS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (outcome == AnswerOutcome.SENT) "Sent $text" else outcome.text)
            .setContentText(when (outcome) {
                AnswerOutcome.SENT -> "The agent got your answer."
                AnswerOutcome.ALREADY -> "Waiting for the agent to take it."
                else -> "Open Kabuk to see what it asks now."
            })
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(ctx, notificationId, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE))
            .setTimeoutAfter(if (fine) 4_000 else 60_000)
            .build()
        nm.notify(notificationId, n)
    }
}
