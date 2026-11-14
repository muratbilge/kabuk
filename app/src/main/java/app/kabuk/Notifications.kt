package app.kabuk

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import app.kabuk.agents.AgentNotifier
import app.kabuk.log.KLog
import app.kabuk.service.SessionService

/**
 * Kabuk's notification channels and the notification permission at targetSdk 28.
 *
 * Android 13+ asks a pre-33 app for POST_NOTIFICATIONS by itself, but only when the app makes its first channel
 * while one of its activities is in front. Channels made at process start (before any activity) never bring the
 * prompt, and on a fresh install notifications then stay off silently. So the channels are made from
 * [MainActivity] once it is on screen; the services make them lazily too (idempotent), for a start without the UI.
 */
object Notifications {
    @Volatile private var created = false

    /** Makes every channel once per process; true when this install had none yet (the system prompt may come now). */
    fun ensureChannels(c: Context): Boolean {
        if (created) return false
        synchronized(this) {
            if (created) return false
            val nm = c.getSystemService(NotificationManager::class.java)
            val fresh = nm.notificationChannels.isEmpty()
            AgentNotifier.createChannels(nm)
            SessionService.createChannel(nm)
            created = true
            KLog.i("NOTIFY", "channels ${if (fresh) "created" else "present"}, notifications ${if (enabled(c)) "on" else "off"}")
            return fresh
        }
    }

    fun enabled(c: Context) = NotificationManagerCompat.from(c).areNotificationsEnabled()

    enum class Step { NONE, WAIT_FOR_SYSTEM_PROMPT, OFFER_SETTINGS }

    /**
     * What the activity does after [ensureChannels] when it comes to the front.
     * [sdk]/[target]: device API level and the app's targetSdk; [fresh]: the channels were just made for the first
     * time; [offered]: the one-time "Turn on" note was shown before.
     */
    fun nextStep(sdk: Int, target: Int, enabled: Boolean, fresh: Boolean, offered: Boolean): Step = when {
        enabled || sdk < 33 -> Step.NONE              // below 13 notifications are on unless you turned them off
        target >= 33 -> Step.NONE                      // MainActivity.onCreate asks for the runtime permission
        fresh -> Step.WAIT_FOR_SYSTEM_PROMPT           // the system asks by itself now
        offered -> Step.NONE
        else -> Step.OFFER_SETTINGS
    }
}
