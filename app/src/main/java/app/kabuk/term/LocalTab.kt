package app.kabuk.term

import android.content.Context
import app.kabuk.data.Settings
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/** A shell on the phone itself (Android's /system/bin/sh, no extra packages). */
class LocalTab(id: Long, ctx: Context) : TermTab(id, "Phone", 0xFF9ECE6A) {
    private val client = object : TerminalSessionClient by QuietSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = screenChanged()
        override fun onTitleChanged(changedSession: TerminalSession) {
            title.value = changedSession.title?.takeIf { it.isNotBlank() } ?: "Phone"
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

    init {
        val home = File(ctx.filesDir, "home").apply { mkdirs() }
        val env = arrayOf(
            "TERM=xterm-256color", "COLORTERM=truecolor", "HOME=${home.absolutePath}",
            "TMPDIR=${ctx.cacheDir.absolutePath}", "PATH=/system/bin:/system/xbin", "LANG=en_US.UTF-8",
            "ANDROID_DATA=/data", "ANDROID_ROOT=/system",
        )
        session = TerminalSession("/system/bin/sh", home.absolutePath, arrayOf("-sh"), env, Settings.current.scrollbackLines, client)
        session.updateSize(80, 24)
        status.value = Status.Connected
    }

    override val emulator: TerminalEmulator? get() = session.emulator

    override fun write(data: ByteArray, offset: Int, count: Int) = session.write(data, offset, count)

    override fun resize(columns: Int, rows: Int) = session.updateSize(columns, rows)

    override fun close() = session.finishIfRunning()
}
