package app.kabuk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import app.kabuk.MainActivity
import app.kabuk.R
import app.kabuk.term.TabManager

/**
 * Keeps the process (and so every SSH connection) alive while the app is in the background,
 * like Termux's session service. Optional wake lock for long jobs with the screen off.
 */
class SessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Open sessions", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while terminal sessions are open"
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT -> { TabManager.closeAll(); return START_NOT_STICKY }
            ACTION_WAKE -> toggleWakeLock()
        }
        val n = build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun toggleWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release(); wifiLock?.release(); wakeLock = null; wifiLock = null
        } else {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kabuk:sessions").apply { acquire() }
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "kabuk:sessions").apply { acquire() }
        }
    }

    private fun build(): Notification {
        val n = TabManager.tabs.value.size
        val locked = wakeLock?.isHeld == true
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, SessionService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$n session${if (n == 1) "" else "s"} open")
            .setContentText(summary() + if (locked) " · wake lock on" else "")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (locked) "Release wake lock" else "Acquire wake lock", action(ACTION_WAKE, 1)).build())
            .addAction(Notification.Action.Builder(null, "Exit", action(ACTION_EXIT, 2)).build())
            .build()
    }

    /** "2 working · 1 needs you" when herdr agents are known, else the tab names */
    private fun summary(): String {
        val agents = TabManager.sshTabs().flatMap { it.agents.value }
        if (agents.isEmpty()) return TabManager.tabs.value.joinToString(" · ") { it.displayTitle() }
        val needs = agents.count { it.needsYou }; val working = agents.count { it.working }
        return listOfNotNull(
            if (needs > 0) "$needs need${if (needs == 1) "s" else ""} you" else null,
            if (working > 0) "$working working" else null,
            "${agents.size - needs - working} idle",
        ).joinToString(" · ")
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "sessions"
        private const val ID = 1
        const val ACTION_EXIT = "app.kabuk.EXIT"
        const val ACTION_WAKE = "app.kabuk.WAKE"
        private var instance: SessionService? = null

        @Volatile private var lastShown = ""

        /** re-posts the notification, but only when its text changed (agents are polled every 3 s) */
        fun refresh() {
            val s = instance ?: return
            val text = s.summary() + "|" + TabManager.tabs.value.size + "|" + (s.wakeLock?.isHeld == true)
            if (text == lastShown) return
            lastShown = text
            s.getSystemService(NotificationManager::class.java).notify(ID, s.build())
        }
    }
}
