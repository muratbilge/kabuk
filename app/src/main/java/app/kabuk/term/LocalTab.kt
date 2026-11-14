package app.kabuk.term

import android.content.Context
import app.kabuk.data.Settings
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * A shell on the phone itself: Termux's login shell (bash) once its packages are installed ([TermuxEnv]), else
 * busybox ash with Kabuk's tools on PATH ([LocalTools]), or Android's /system/bin/sh when the APK has no busybox.
 * [busybox] forces the busybox shell (recovery when the Termux prefix is broken). "-sh"/"-login" as argv[0] makes a
 * login shell.
 */
class LocalTab(id: Long, ctx: Context, val busybox: Boolean = false) : TermTab(id, if (busybox) "Phone (busybox)" else "Phone", 0xFF9ECE6A) {
    private val client = object : TerminalSessionClient by QuietSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = screenChanged()
        override fun onTitleChanged(changedSession: TerminalSession) {
            title.value = changedSession.title?.takeIf { it.isNotBlank() } ?: defaultTitle
        }
        override fun onSessionFinished(finishedSession: TerminalSession) {
            status.value = Status.Ended("exited (${finishedSession.exitStatus})")
            screenChanged()
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            text?.let { TabManager.remoteClipboard(this@LocalTab, it) }
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            TabManager.clipboardText()?.let { session?.emulator?.paste(it) }
        }
        override fun onBell(session: TerminalSession) = TabManager.bell(this@LocalTab)
        override fun onColorsChanged(session: TerminalSession) = screenChanged()
    }

    private val session: TerminalSession
    private val defaultTitle = if (busybox) "Phone (busybox)" else "Phone"
    /** runs Termux's login shell (not busybox) */
    val termux: Boolean

    init {
        val setup = LocalTools.prepare(ctx, termux = !busybox)
        termux = setup.termux
        session = TerminalSession(setup.shell, setup.home.absolutePath, setup.args, setup.env, Settings.current.scrollbackLines, client)
        session.updateSize(80, 24)
        status.value = Status.Connected
    }

    override val emulator: TerminalEmulator? get() = session.emulator

    override fun write(data: ByteArray, offset: Int, count: Int) = session.write(data, offset, count)

    override fun resize(columns: Int, rows: Int) = session.updateSize(columns, rows)

    override fun close() = session.finishIfRunning()
}
