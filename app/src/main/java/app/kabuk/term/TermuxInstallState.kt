package app.kabuk.term

/** What the Termux packages installer shows (pure, unit-tested; [TermuxInstaller] drives it). */
sealed class InstallState {
    /** nothing on screen */
    data object Idle : InstallState()
    /** the consent sheet: Install / Not now */
    data object Offer : InstallState()
    /** usr is there but broken (login or bash missing): Repair / Not now */
    data object OfferRepair : InstallState()
    /** [total] < 0 while unknown */
    data class Downloading(val bytes: Long, val total: Long) : InstallState()
    data object Verifying : InstallState()
    data class Extracting(val done: Int, val total: Int) : InstallState()
    data class Failed(val message: String) : InstallState()
    data object Done : InstallState()

    val running get() = this is Downloading || this is Verifying || this is Extracting
}

/**
 * The installer's state machine. [offered]: the consent sheet was shown once already (it then comes back only from
 * the menu); [installed]: Termux's prefix is in place. Progress that arrives after a cancel is ignored.
 */
class InstallMachine(offered: Boolean, installed: Boolean) {
    var offered = offered; private set
    var installed = installed; private set
    var state: InstallState = InstallState.Idle; private set
    /** the running (or last finished) install is a repair of an installed prefix */
    var repairing = false; private set

    /** [OfferRepair] was shown in this process (it comes once per process, then only the menus offer Repair) */
    var repairOffered = false; private set

    /**
     * A Phone tab was opened: the first time ever (not installed) the consent sheet comes; with a [broken] prefix
     * (usr there, login or bash missing) the Repair sheet instead, once per process. True when a sheet came.
     */
    fun phoneTabOpened(broken: Boolean = false): Boolean {
        if (state != InstallState.Idle) return false
        if (broken) {
            if (repairOffered) return false
            repairOffered = true
            state = InstallState.OfferRepair
            return true
        }
        if (installed || offered) return false
        offered = true
        state = InstallState.Offer
        return true
    }

    /** "Install Termux packages…" from a menu: the sheet again (also to reinstall); no-op while installing. */
    fun menu(): Boolean {
        if (state.running) return false
        offered = true
        state = InstallState.Offer
        return true
    }

    /** Not now / sheet dismissed: busybox stays, asked again only from the menu. */
    fun notNow() { if (state == InstallState.Offer || state == InstallState.OfferRepair) state = InstallState.Idle }

    /** Install (from the sheet) or Retry (after a failure). True when the installer should start. */
    fun install(): Boolean {
        if (state != InstallState.Offer && state !is InstallState.Failed) return false
        if (state == InstallState.Offer) repairing = false
        state = InstallState.Downloading(0, -1)
        return true
    }

    /**
     * "Repair Termux packages…", after the user confirmed: a fresh bootstrap over a new staging dir that replaces
     * the prefix (home stays). True when the installer should start; no-op while installing.
     */
    fun repair(): Boolean {
        if (state.running) return false
        offered = true
        repairing = true
        state = InstallState.Downloading(0, -1)
        return true
    }

    fun downloaded(bytes: Long, total: Long) { if (state is InstallState.Downloading) state = InstallState.Downloading(bytes, total) }
    fun verifying() { if (state is InstallState.Downloading) state = InstallState.Verifying }
    fun extracted(done: Int, total: Int) {
        if (state == InstallState.Verifying || state is InstallState.Extracting) state = InstallState.Extracting(done, total)
    }

    fun failed(message: String) { if (state.running) state = InstallState.Failed(message) }

    /** Cancel while running: back to nothing (busybox stays); the installer cleans up. */
    fun cancel(): Boolean {
        if (!state.running) return false
        state = InstallState.Idle
        return true
    }

    fun finished() {
        if (!state.running) return
        installed = true
        state = InstallState.Done
    }

    /** Closing the Done / Failed sheet. */
    fun dismiss() { if (state == InstallState.Done || state is InstallState.Failed) state = InstallState.Idle }

    /** 0..1 for a determinate bar: download 0..0.7, verify 0.7, extract 0.7..1; null while the size is unknown. */
    fun fraction(): Float? = when (val s = state) {
        is InstallState.Downloading -> if (s.total > 0) 0.7f * (s.bytes.toFloat() / s.total).coerceIn(0f, 1f) else null
        InstallState.Verifying -> 0.7f
        is InstallState.Extracting -> 0.7f + 0.3f * (if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f)
        else -> null
    }
}
