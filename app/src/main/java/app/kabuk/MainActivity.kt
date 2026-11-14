package app.kabuk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import app.kabuk.data.Hosts
import app.kabuk.data.Settings
import app.kabuk.log.KLog
import app.kabuk.term.TabManager
import app.kabuk.term.TermTab
import app.kabuk.term.TerminalCanvasView
import app.kabuk.term.Themes
import app.kabuk.ui.Bg
import app.kabuk.ui.HostEditScreen
import app.kabuk.ui.HostsScreen
import app.kabuk.ui.KabukTheme
import app.kabuk.ui.LinkDialog
import app.kabuk.ui.ModifierState
import app.kabuk.ui.PromptDialog
import app.kabuk.ui.SettingsScreen
import app.kabuk.ui.Snack
import app.kabuk.ui.Surface1
import app.kabuk.ui.TerminalScreen
import app.kabuk.ui.TerminalUi
import app.kabuk.ui.TextModeScreen
import kotlinx.coroutines.launch

sealed class Screen {
    data object Terminal : Screen()
    data object Hosts : Screen()
    data class EditHost(val id: String?) : Screen()
    data object Settings : Screen()
    data class TextMode(val tab: TermTab) : Screen()
}

class MainActivity : ComponentActivity() {
    private lateinit var ui: TerminalUi
    private var screen by mutableStateOf<Screen>(Screen.Hosts)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Kabuk is always dark. "auto" follows the system's light/dark mode: below targetSdk 35 that gave dark status
        // icons and a light navigation bar scrim over Kabuk's dark UI on a light-mode phone (B0 audit).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val mods = ModifierState()
        val view = TerminalCanvasView(this, mods)
        ui = TerminalUi(view, mods)
        view.listener = object : TerminalCanvasView.Listener {
            override fun onSelectionChanged(active: Boolean) { ui.selecting = active }
            override fun onLinkTapped(url: String) { ui.link = url }
            override fun onScrolledChanged(atBottom: Boolean) { ui.atBottom = atBottom }
            override fun onFontSizeChanged(sp: Float) { Settings.update { it.copy(fontSizeSp = sp) } }
            override fun onKeyTyped() {}
        }
        if (TabManager.tabs.value.isNotEmpty()) screen = Screen.Terminal

        lifecycleScope.launch {
            Settings.prefs.collect { p ->
                view.setFontSize(p.fontSizeSp)
                Themes.apply(p.theme, TabManager.tabs.value)
                view.invalidate()
                updateKeepScreenOn()
            }
        }
        lifecycleScope.launch {
            TabManager.toasts.collect { n ->
                // the newest message wins; an action keeps it up a little longer
                Snack.host.currentSnackbarData?.dismiss()
                launch {
                    val r = Snack.host.showSnackbar(
                        n.text, n.action,
                        duration = if (n.action != null) androidx.compose.material3.SnackbarDuration.Long
                                   else androidx.compose.material3.SnackbarDuration.Short,
                    )
                    if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                        n.onAction?.invoke()
                        if (TabManager.tabs.value.isNotEmpty() && screen == Screen.Hosts) screen = Screen.Terminal
                    }
                }
            }
        }
        lifecycleScope.launch {
            TabManager.tabs.collect { tabs ->
                // the last tab closed: back to the server list
                if (tabs.isEmpty() && screen == Screen.Terminal) screen = Screen.Hosts
                Themes.apply(Settings.current.theme, tabs)
            }
        }
        lifecycleScope.launch { Hosts.all.collect { updateShortcuts() } }

        setContent {
            KabukTheme {
                val prompts by TabManager.prompts.collectAsState()
                // behind the status and navigation bars: the color of the bars next to them (no seam)
                Box(Modifier.fillMaxSize().background(if (screen == Screen.Terminal) Surface1 else Bg)
                    .systemBarsPadding().imePadding()) {
                    when (val s = screen) {
                        Screen.Terminal -> TerminalScreen(
                            ui,
                            onOpenHosts = { screen = Screen.Hosts },
                            onOpenSettings = { screen = Screen.Settings },
                            onTextMode = { screen = Screen.TextMode(it) },
                        )
                        Screen.Hosts -> HostsScreen(
                            onConnected = { screen = Screen.Terminal },
                            onEdit = { screen = Screen.EditHost(it) },
                            onSettings = { screen = Screen.Settings },
                            onBack = if (TabManager.tabs.value.isNotEmpty()) ({ screen = Screen.Terminal }) else null,
                        )
                        is Screen.EditHost -> HostEditScreen(s.id, onDone = { screen = Screen.Hosts })
                        Screen.Settings -> SettingsScreen(onBack = { screen = home() })
                        is Screen.TextMode -> TextModeScreen(s.tab, onBack = { screen = Screen.Terminal })
                    }
                    // the terminal shows snackbars above its key bar itself
                    if (screen != Screen.Terminal) androidx.compose.material3.SnackbarHost(
                        Snack.host, Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                    )
                    prompts.firstOrNull()?.let { PromptDialog(it) }
                    ui.link?.let { LinkDialog(it) { ui.link = null } }
                }
                BackHandler {
                    when {
                        ui.selecting -> ui.view.clearSelection()
                        ui.showAgents -> ui.showAgents = false
                        ui.showQuick -> ui.showQuick = false
                        screen is Screen.EditHost -> screen = Screen.Hosts
                        screen == Screen.Terminal || (screen == Screen.Hosts && TabManager.tabs.value.isEmpty()) ->
                            // keep everything running, like the home button
                            moveTaskToBack(true)
                        else -> screen = home()
                    }
                }
                LaunchedEffect(screen) {
                    updateKeepScreenOn()
                    TabManager.terminalVisible = screen == Screen.Terminal
                }
            }
        }

        // targetSdk <= 32 (Kabuk is 28): no runtime request, see onResume and [Notifications]
        if (Build.VERSION.SDK_INT >= 33 && applicationInfo.targetSdkVersion >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        handleIntent(intent)
    }

    private fun home(): Screen = if (TabManager.tabs.value.isNotEmpty()) Screen.Terminal else Screen.Hosts

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        TabManager.foreground = true
    }

    override fun onResume() {
        super.onResume()
        // after the first frame: the activity is really in front when the channels are made
        window.decorView.post { checkNotifications() }
    }

    /** Fresh install at targetSdk 28 on Android 13+: bring the system's prompt, else offer the settings page once. */
    private fun checkNotifications() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val fresh = Notifications.ensureChannels(this)
        val prefs = getSharedPreferences("notifications", MODE_PRIVATE)
        fun step() = Notifications.nextStep(Build.VERSION.SDK_INT, applicationInfo.targetSdkVersion,
            Notifications.enabled(this), fresh, prefs.getBoolean("offered", false))
        when (step()) {
            Notifications.Step.NONE -> {}
            // the system's dialog pauses this activity; when it is answered onResume comes back here (not fresh then).
            // Still resumed after a while = no dialog came: offer the settings page instead.
            Notifications.Step.WAIT_FOR_SYSTEM_PROMPT -> {
                KLog.i("NOTIFY", "waiting for the system's notification prompt")
                window.decorView.postDelayed({
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !Notifications.enabled(this)) {
                        KLog.w("NOTIFY", "no system prompt came")
                        offerNotificationSettings(prefs)
                    }
                }, 2500)
            }
            Notifications.Step.OFFER_SETTINGS -> offerNotificationSettings(prefs)
        }
    }

    private fun offerNotificationSettings(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean("offered", false)) return
        prefs.edit().putBoolean("offered", true).apply()
        KLog.i("NOTIFY", "notifications off: offering the settings page once")
        TabManager.note("Notifications are off for Kabuk", "Turn on") {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            }.onFailure { KLog.w("NOTIFY", "no notification settings page", it) }
        }
    }

    override fun onStop() {
        TabManager.foreground = false
        super.onStop()
    }

    private fun handleIntent(i: Intent?) {
        when (i?.action) {
            Intent.ACTION_SEND -> {
                // shared text lands in the prompt box, not straight into the terminal
                i.getStringExtra(Intent.EXTRA_TEXT)?.let { text -> ui.draft = text; ui.showPrompt = true }
                @Suppress("DEPRECATION")
                (i.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))?.let { uri ->
                    if (i.type?.startsWith("image/") == true) { ui.showPrompt = true; app.kabuk.ui.attachImage(this, ui, uri) }
                }
                if (TabManager.tabs.value.isNotEmpty()) screen = Screen.Terminal
            }
            ACTION_OPEN_AGENT -> {
                val tab = TabManager.sshTabs().firstOrNull { it.id == i.getLongExtra("tab", -1) }
                val agent = tab?.agents?.value?.firstOrNull { it.terminalId == i.getStringExtra("terminal") }
                if (tab != null) {
                    TabManager.select(tab.id)
                    screen = Screen.Terminal
                    // a question from the orchtool bus: its answers are in the agents sheet
                    if (i.getBooleanExtra("agents", false)) ui.showAgents = true
                    // an answer button from our own notification (other apps don't know the token)
                    val reply = i.getStringExtra("reply")?.takeIf {
                        it.length == 1 && it[0].isDigit() && i.getStringExtra("token") == Settings.shortcutToken()
                    }
                    val pane = i.getStringExtra("pane")
                    val expect = i.getStringExtra("expect")
                    if (reply == null || pane == null || expect == null || agent == null) {
                        if (agent != null) tab.focusAgent(agent) {}
                    } else {
                        val label = i.getStringExtra("label") ?: ""
                        tab.answerAgent(pane, app.kabuk.text.Choice(reply, label, false), expect) { outcome ->
                            if (outcome != app.kabuk.agents.AnswerOutcome.SENT) return@answerAgent TabManager.toast(outcome.text)
                            TabManager.toast("Sent $reply · $label to ${agent.place}")
                            // "No, and tell it what to do": the reason must reach this agent, so only after switching to it
                            if (i.getBooleanExtra("tell", false)) tab.focusAgent(agent) { focused ->
                                if (!focused) return@focusAgent TabManager.toast("Couldn't switch to ${agent.place}")
                                ui.promptHint = "Tell it what to do instead…"
                                ui.showPrompt = true
                                ui.focusPrompt++
                            }
                        }
                    }
                } else TabManager.toast("That connection is closed")
            }
            ACTION_CONNECT -> if (i.getStringExtra("token") == Settings.shortcutToken()) Hosts.get(i.getStringExtra("host") ?: "")?.let { h ->
                // reuse an open tab to that server, else open one
                val open = TabManager.tabs.value.filterIsInstance<app.kabuk.term.SshTab>().firstOrNull { it.hostId == h.id }
                if (open != null) TabManager.select(open.id) else TabManager.openSsh(h)
                screen = Screen.Terminal
            }
        }
        i?.action = null
    }

    private fun updateKeepScreenOn() {
        if (Settings.current.keepScreenOn && screen == Screen.Terminal) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** long-press the app icon: one shortcut per server */
    private fun updateShortcuts() {
        val list = Hosts.all.value.take(4).map { h ->
            ShortcutInfoCompat.Builder(this, "host-${h.id}")
                .setShortLabel(h.name)
                .setLongLabel("Connect to ${h.name}")
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(Intent(this, MainActivity::class.java).setAction(ACTION_CONNECT).putExtra("host", h.id)
                    .putExtra("token", Settings.shortcutToken()))
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(this, list) }
    }

    /** Termux-style volume keys: Volume Down held = Ctrl, Volume Up held = Fn */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Settings.current.volumeKeys && screen == Screen.Terminal) {
            val down = event.action == KeyEvent.ACTION_DOWN
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> { ui.mods.volumeCtrl = down; return true }
                KeyEvent.KEYCODE_VOLUME_UP -> { ui.mods.volumeFn = down; return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val ACTION_CONNECT = "app.kabuk.CONNECT"
        const val ACTION_OPEN_AGENT = "app.kabuk.OPEN_AGENT"
    }
}
