package app.kabuk.term

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.flow.MutableStateFlow

sealed class Status {
    data class Connecting(val what: String) : Status()
    data object Connected : Status()
    /** [retryAt] = wall-clock ms of the next automatic attempt, null = waiting for the user */
    data class Disconnected(val reason: String, val retryAt: Long?) : Status()
    data class Ended(val reason: String) : Status()
}

/** One open terminal: an emulator (screen + scrollback) plus whatever feeds it (SSH or a local shell). */
abstract class TermTab(val id: Long, initialTitle: String, val color: Long) {
    val title = MutableStateFlow(initialTitle)
    /** set when the user renamed the tab; then the program's own title is ignored */
    var userTitle: String? = null
    val status = MutableStateFlow<Status>(Status.Connecting(""))
    /** new output arrived while another tab was shown */
    val unread = MutableStateFlow(false)

    abstract val emulator: TerminalEmulator?

    /** called on the main thread whenever the screen changed; the view hooks in here */
    var onScreenUpdate: (() -> Unit)? = null

    abstract fun write(data: ByteArray, offset: Int, count: Int)
    fun write(s: String) = s.toByteArray().let { write(it, 0, it.size) }

    /** keyboard input; [alt] sends ESC first, like xterm's meta-sends-escape */
    fun writeCodePoint(alt: Boolean, codePoint: Int) {
        val sb = StringBuilder()
        if (alt) sb.append('\u001b')
        sb.appendCodePoint(codePoint)
        write(sb.toString())
    }

    abstract fun resize(columns: Int, rows: Int)
    abstract fun close()
    open fun reconnect() {}

    /** changes when the tab gets a new connection */
    open val generation: Int get() = 0
    open val closed: Boolean get() = false

    /** false while input would be lost (not connected) */
    open val canSend: Boolean get() = true

    /** main thread: the program is halfway through redrawing the screen (don't read it now) */
    open val redrawing: Boolean get() = false

    /** whole history as plain text, plus where it came from (herdr pane, tmux, this app) */
    open fun captureHistory(done: (text: String, source: String) -> Unit) {
        done(emulator?.screen?.transcriptTextWithFullLinesJoined ?: "", "this app's scrollback")
    }

    protected fun screenChanged() {
        if (TabManager.current.value != id) unread.value = true
        onScreenUpdate?.invoke()
    }

    fun displayTitle() = userTitle ?: title.value
}

/** TerminalEmulator wants a session client for logging and cursor style; we don't need the rest. */
object QuietSessionClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
    override fun logError(tag: String?, message: String?) { Log.e(tag, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag, message ?: "") }
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }
}
