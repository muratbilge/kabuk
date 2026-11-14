package app.kabuk.term

import android.content.Context
import app.kabuk.log.KLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

/**
 * Installs Termux's packages after the user agreed: the consent sheet comes once, at the first Phone tab after the
 * update (later only from a menu). Drives [InstallMachine] from a background thread while [SessionService] keeps the
 * process alive; the UI (ui/TermuxUi.kt) only shows [state]. Logs (tag TERMUX): decisions, sizes, counts, durations,
 * errors; never file contents or terminal text.
 */
object TermuxInstaller {
    private const val TAG = "TERMUX"
    private const val PREFS = "termux"
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state
    /** 0..1 for the progress bar, null = indeterminate */
    val fraction = MutableStateFlow<Float?>(null)
    /** the user hid the progress sheet; the result still comes as a sheet */
    val hidden = MutableStateFlow(false)
    private var machine: InstallMachine? = null
    private var cancelFlag: AtomicBoolean? = null
    private var worker: Thread? = null
    private var unsupportedLogged = false

    val running get() = _state.value.running

    @Synchronized private fun machine(ctx: Context): InstallMachine = machine ?: run {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        InstallMachine(p.getBoolean("offered", false), TermuxEnv.installed(ctx)).also {
            machine = it
            // a process killed mid-install leaves the staging dir behind
            if (TermuxEnv.staging(ctx).exists()) Thread({ TermuxEnv.cleanup(ctx, download = false) }, "termux-cleanup").start()
        }
    }

    private fun publish(m: InstallMachine) {
        _state.value = m.state
        fraction.value = m.fraction()
    }

    private fun saveOffered(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("offered", true).apply()

    fun supported(ctx: Context) = TermuxEnv.supported(ctx)
    fun installed(ctx: Context) = TermuxEnv.installed(ctx)

    /** A Phone tab was opened: the consent sheet, the first time only. */
    @Synchronized fun phoneTabOpened(ctx: Context) {
        if (!TermuxEnv.supported(ctx)) {
            if (!unsupportedLogged) {
                unsupportedLogged = true
                KLog.i(TAG, "not offered: unsupported here (abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}, " +
                    "targetSdk=${ctx.applicationInfo.targetSdkVersion}, filesDir=${ctx.filesDir.path})")
            }
            return
        }
        val m = machine(ctx)
        if (m.phoneTabOpened()) {
            saveOffered(ctx)
            KLog.i(TAG, "offering the install (first Phone tab, arch=${TermuxEnv.arch()})")
            publish(m)
        }
    }

    /** "Install Termux packages…" from a menu. */
    @Synchronized fun menu(ctx: Context) {
        if (!TermuxEnv.supported(ctx)) { TabManager.toast("Termux packages need a 64-bit ARM or x86 phone"); return }
        val m = machine(ctx)
        if (m.menu()) { saveOffered(ctx); hidden.value = false; publish(m) }
    }

    @Synchronized fun notNow() {
        val m = machine ?: return
        if (m.state == InstallState.Offer) KLog.i(TAG, "not now")
        m.notNow(); publish(m)
    }

    @Synchronized fun dismiss() { machine?.let { it.dismiss(); publish(it) } }

    /** Install or Retry. */
    @Synchronized fun install(ctx: Context) {
        val m = machine(ctx)
        val retry = m.state is InstallState.Failed
        if (!m.install()) return
        hidden.value = false
        publish(m)
        KLog.i(TAG, "${if (retry) "retry" else "install"}: ${TermuxEnv.BOOTSTRAP_TAG} (${TermuxEnv.arch()})")
        val cancel = AtomicBoolean(false)
        cancelFlag = cancel
        val previous = worker
        val app = ctx.applicationContext
        worker = Thread({ run(app, m, cancel, previous) }, "termux-install").also { it.start() }
    }

    @Synchronized fun cancel() {
        val m = machine ?: return
        if (!m.cancel()) return
        cancelFlag?.set(true)
        KLog.i(TAG, "cancel requested")
        publish(m)
    }

    private fun update(m: InstallMachine, cancel: AtomicBoolean, f: InstallMachine.() -> Unit) = synchronized(this) {
        // a cancelled run must not move the machine of the next one
        if (!cancel.get()) { m.f(); publish(m) }
    }

    private fun run(ctx: Context, m: InstallMachine, cancel: AtomicBoolean, previous: Thread?) {
        previous?.join() // a cancelled run still cleaning up
        TabManager.main.post { TabManager.keepAlive(true) }
        try {
            val r = TermuxEnv.install(
                ctx, AndroidFs, cancelled = { cancel.get() },
                onDownload = { b, t -> update(m, cancel) { downloaded(b, t) } },
                onVerify = { update(m, cancel) { verifying() } },
                onExtract = { d, t -> update(m, cancel) { extracted(d, t) } },
            )
            val s = r.stats
            KLog.i(TAG, "installed: downloaded ${r.downloaded shr 10} KB in ${r.downloadMs} ms; ${s.files} files " +
                "(${s.bytes shr 20} MB, ${s.rewritten} relocated, ${s.binary} binary), ${s.links} links, " +
                "${r.checksums} dpkg checksums fixed, extract ${r.extractMs} ms")
            update(m, cancel) { finished() }
            if (cancel.get()) {
                KLog.i(TAG, "cancel came too late: installed anyway")
                synchronized(this) { machine = null } // re-read from disk next time
            }
        } catch (e: Bootstrap.Cancelled) {
            TermuxEnv.cleanup(ctx, download = true)
            KLog.i(TAG, "cancelled, partial files removed")
        } catch (e: Throwable) {
            val keepDownload = e !is TermuxEnv.ChecksumException && isNetwork(e)
            TermuxEnv.cleanup(ctx, download = !keepDownload)
            KLog.w(TAG, "install failed: ${e.javaClass.simpleName}: ${e.message}" + if (keepDownload) " (partial download kept for Retry)" else "")
            update(m, cancel) { failed(describe(e)) }
        } finally {
            TabManager.main.post { TabManager.keepAlive(false) }
        }
    }

    private fun isNetwork(e: Throwable) = e is UnknownHostException || e is ConnectException ||
        e is SocketTimeoutException || e is SSLException || (e is IOException && e.message?.startsWith("HTTP") != true &&
        e.message?.contains("space") != true)

    /** A short message for the sheet. */
    internal fun describe(e: Throwable): String = when {
        e is UnknownHostException || e is ConnectException -> "No internet connection"
        e is SocketTimeoutException -> "The connection timed out"
        e is java.net.SocketException || e is SSLException -> "The connection was lost"
        e is TermuxEnv.ChecksumException -> "The download was damaged (checksum mismatch)"
        e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true -> "Not enough free space on the phone"
        else -> e.message ?: e.javaClass.simpleName
    }
}
