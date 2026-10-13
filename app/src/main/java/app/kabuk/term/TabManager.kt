package app.kabuk.term

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import app.kabuk.agents.AgentNotifier
import app.kabuk.data.Host
import app.kabuk.data.Hosts
import app.kabuk.data.Settings
import app.kabuk.service.SessionService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking

/** A question the UI must answer for a background thread (host key, password, ...). */
sealed class Prompt {
    abstract val tabId: Long
    abstract val title: String
    abstract val message: String
    abstract fun cancel()
    class Confirm(override val tabId: Long, override val title: String, override val message: String, val danger: Boolean,
                  val answer: CompletableDeferred<Boolean>) : Prompt() {
        override fun cancel() { answer.complete(false) }
    }
    class Password(override val tabId: Long, override val title: String, override val message: String,
                   val answer: CompletableDeferred<Pair<String, Boolean>?>) : Prompt() {
        override fun cancel() { answer.complete(null) }
    }
}

/** All open tabs. Lives as long as the process; the foreground service keeps the process alive. */
object TabManager {
    lateinit var app: Context
        private set
    val main = Handler(Looper.getMainLooper())

    private val _tabs = MutableStateFlow<List<TermTab>>(emptyList())
    val tabs: StateFlow<List<TermTab>> = _tabs
    val current = MutableStateFlow<Long?>(null)
    val prompts = MutableStateFlow<List<Prompt>>(emptyList())
    /** short messages for the snackbar, optionally with one action (Undo, Reconnect) */
    class Note(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)
    val toasts = MutableSharedFlow<Note>(extraBufferCapacity = 8)
    /** bumps whenever any tab's agent list changes (the UI re-reads [SshTab.agents]) */
    val agentsVersion = MutableStateFlow(0)
    /** the app is on screen */
    @Volatile var foreground = false
    /** the terminal itself is on screen (not settings, server list, copy text) */
    @Volatile var terminalVisible = false
    private var nextId = 1L
    private var lastNetwork: Network? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        AgentNotifier.init(app)
        val cm = app.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val changed = lastNetwork != null && lastNetwork != network
                lastNetwork = network
                main.post {
                    _tabs.value.filterIsInstance<SshTab>().forEach {
                        if (changed || it.status.value is Status.Disconnected) it.networkChanged()
                    }
                }
            }
        })
    }

    fun currentTab(): TermTab? = _tabs.value.firstOrNull { it.id == current.value }

    fun openSsh(host: Host): TermTab = add(SshTab(nextId++, app, host))
    fun openLocal(): TermTab = add(LocalTab(nextId++, app))

    internal fun add(t: TermTab): TermTab {
        _tabs.update { it + t }
        select(t.id)
        tabsChanged()
        return t
    }

    fun select(id: Long) {
        current.value = id
        _tabs.value.firstOrNull { it.id == id }?.unread?.value = false
    }

    fun close(t: TermTab) {
        val list = _tabs.value
        val i = list.indexOf(t)
        if (i < 0) return
        t.close()
        val rest = list - t
        _tabs.value = rest
        if (current.value == t.id) current.value = rest.getOrNull(i.coerceAtMost(rest.size - 1))?.id
        tabsChanged()
    }

    fun closeAll() = _tabs.value.forEach { close(it) }

    fun move(t: TermTab, by: Int) {
        val l = _tabs.value.toMutableList()
        val i = l.indexOf(t); val j = (i + by).coerceIn(0, l.size - 1)
        if (i < 0 || i == j) return
        l.removeAt(i); l.add(j, t); _tabs.value = l
        tabsChanged()
    }

    private fun tabsChanged() {
        val i = Intent(app, SessionService::class.java)
        if (_tabs.value.isEmpty()) app.stopService(i)
        else runCatching {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        }.onFailure { toast("Couldn't start the background service: sessions may drop when the app is closed") }
        SessionService.refresh()
        // remember the open servers, so they can be reopened after Android killed the app
        if (_tabs.value.isNotEmpty()) Settings.saveLastTabs(_tabs.value.filterIsInstance<SshTab>().map { it.hostId })
    }

    /** servers that were open when the app was last killed, to offer "Reopen" */
    fun reopenable(): List<Host> =
        if (_tabs.value.isNotEmpty()) emptyList() else Settings.lastTabs().mapNotNull { Hosts.get(it) }

    fun reopen() = reopenable().forEach { openSsh(it) }

    // ---- questions from background threads (they block until the user answers or the tab closes) ----

    fun confirm(tab: TermTab, title: String, message: String, danger: Boolean): Boolean {
        val d = CompletableDeferred<Boolean>()
        val p = Prompt.Confirm(tab.id, title, message, danger, d)
        prompts.update { it + p }
        // the tab may have closed between the caller's check and now: then nobody would answer
        if (tab.closed) p.cancel()
        try {
            return runBlocking { d.await() }
        } finally {
            prompts.update { it - p }
        }
    }

    /** (password, remember) or null when cancelled */
    fun askPassword(tab: TermTab, title: String, message: String): Pair<String, Boolean>? {
        val d = CompletableDeferred<Pair<String, Boolean>?>()
        val p = Prompt.Password(tab.id, title, message, d)
        prompts.update { it + p }
        if (tab.closed) p.cancel()
        try {
            return runBlocking { d.await() }
        } finally {
            prompts.update { it - p }
        }
    }

    fun cancelPrompts(tab: TermTab) = prompts.value.filter { it.tabId == tab.id }.forEach { it.cancel() }

    // ---- agents ----

    fun agentsChanged(t: SshTab) {
        agentsVersion.update { it + 1 }
        AgentNotifier.update(t)
        SessionService.refresh()
    }

    fun sshTabs() = _tabs.value.filterIsInstance<SshTab>()

    // ---- small shared services ----

    fun toast(msg: String) { toasts.tryEmit(Note(msg)) }
    fun note(msg: String, action: String, onAction: () -> Unit) { toasts.tryEmit(Note(msg, action, onAction)) }

    /** closing from the tab's ×: an SSH tab can be brought back (tmux reattaches), so offer Undo */
    fun closeWithUndo(t: TermTab) {
        val host = (t as? SshTab)?.let { Hosts.get(it.hostId) }
        val name = t.displayTitle()
        close(t)
        if (host != null) note("Closed $name", "Undo") { openSsh(host) } else toast("Closed $name")
    }

    fun copyToClipboard(text: String, msg: String = "Copied") {
        main.post {
            val cm = app.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
            // Android 13+ shows its own confirmation
            if (Build.VERSION.SDK_INT < 33) toast(msg)
        }
    }

    /** a program asked to set the clipboard (OSC 52): only the tab you're looking at may do that */
    fun remoteClipboard(t: TermTab, text: String) {
        main.post {
            if (foreground && current.value == t.id) copyToClipboard(text, "Copied by ${t.displayTitle()}")
        }
    }

    fun clipboardText(): String? =
        app.getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()

    fun bell(t: TermTab) {
        if (current.value != t.id) t.unread.value = true
        if (Settings.current.bellVibrate) vibrate(40)
    }

    fun vibrate(ms: Long) {
        val v = app.getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
