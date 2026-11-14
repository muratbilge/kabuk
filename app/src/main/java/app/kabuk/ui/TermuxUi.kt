package app.kabuk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.kabuk.term.InstallState
import app.kabuk.term.LocalTab
import app.kabuk.term.TabManager
import app.kabuk.term.TermuxEnv
import app.kabuk.term.TermuxInstaller

/**
 * The Termux packages installer's sheet: consent (Install / Not now), progress (Cancel / Hide), result
 * (Restart shell / Open Phone tab, or Retry). Shown over every screen; [onOpenTerminal] switches to the terminal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxSheet(onOpenTerminal: () -> Unit) {
    val state by TermuxInstaller.state.collectAsState()
    val fraction by TermuxInstaller.fraction.collectAsState()
    val hidden by TermuxInstaller.hidden.collectAsState()
    val ctx = LocalContext.current
    val confirmRepair by TermuxInstaller.confirmRepair.collectAsState()
    if (confirmRepair) RepairDialog()
    if (state == InstallState.Idle || (state.running && hidden)) return
    val repair = TermuxInstaller.repairing
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = {
            when {
                state == InstallState.Offer || state == InstallState.OfferRepair -> TermuxInstaller.notNow()
                state.running -> TermuxInstaller.hidden.value = true
                else -> TermuxInstaller.dismiss()
            }
        },
        sheetState = sheet, containerColor = Surface1,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
            when (val s = state) {
                InstallState.Offer -> {
                    Title("Install Termux packages")
                    Body(
                        "~${TermuxEnv.DOWNLOAD_MB} MB download, ~${TermuxEnv.INSTALLED_MB} MB on the phone; then pkg install " +
                            "works like in Termux (python, git, openssh, nodejs, clang …), with bash as the Phone tab's shell."
                    )
                    Body(
                        "Packages come straight from Termux's repositories. Without them the Phone tab keeps its busybox shell.",
                        muted = true,
                    )
                    Buttons {
                        TextButton(onClick = { TermuxInstaller.notNow() }) { Text("Not now") }
                        Primary("Install") { TermuxInstaller.install(ctx) }
                    }
                }
                InstallState.OfferRepair -> {
                    Title("Termux packages are damaged")
                    Body(
                        "Termux's login or bash is missing, so this Phone tab runs busybox. Repair downloads the base " +
                            "packages again (~${TermuxEnv.DOWNLOAD_MB} MB) and replaces everything Termux installed: " +
                            "installed packages are removed, your home folder stays."
                    )
                    Body("Repair stays in the Phone tab's menu if you'd rather do it later.", muted = true)
                    Buttons {
                        TextButton(onClick = { TermuxInstaller.notNow() }) { Text("Not now") }
                        Primary("Repair") { TermuxInstaller.repair(ctx) }
                    }
                }
                is InstallState.Downloading, InstallState.Verifying, is InstallState.Extracting -> {
                    Title(if (repair) "Repairing Termux packages" else "Installing Termux packages")
                    val f = fraction
                    if (f == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp), color = Accent)
                    else LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), color = Accent)
                    Body(progressText(s), muted = true)
                    Buttons {
                        TextButton(onClick = { TermuxInstaller.cancel() }) { Text("Cancel") }
                        TextButton(onClick = { TermuxInstaller.hidden.value = true }) { Text("Hide") }
                    }
                }
                is InstallState.Failed -> {
                    Title(if (repair) "Couldn't repair Termux packages" else "Couldn't install Termux packages")
                    Body(s.message)
                    Body(if (repair) "Your Termux packages are as they were." else "The Phone tab keeps its busybox shell.", muted = true)
                    Buttons {
                        TextButton(onClick = { TermuxInstaller.dismiss() }) { Text("Close") }
                        Primary("Retry") { TermuxInstaller.install(ctx) }
                    }
                }
                InstallState.Done -> {
                    // after a repair the open Termux shells still run the old (deleted) files: restart them too
                    val open = TabManager.tabs.value.filterIsInstance<LocalTab>().filter { !it.busybox && (repair || !it.termux) }
                    Title(if (repair) "Termux packages repaired" else "Termux packages installed")
                    Body(
                        (if (repair) "A fresh set of base packages is in place; your home folder is unchanged. "
                            else "New Phone tabs start bash. ") +
                            "The first new tab fetches the package lists (pkg update); then try: pkg install python"
                    )
                    if (open.isNotEmpty()) Body(
                        if (repair) "Open Phone tabs still run the old programs." else "Your open Phone tab still runs busybox.",
                        muted = true,
                    )
                    Buttons {
                        TextButton(onClick = { TermuxInstaller.dismiss() }) { Text("Close") }
                        Primary(if (open.isNotEmpty()) "Restart shell" else "Open Phone tab") {
                            TermuxInstaller.dismiss()
                            open.forEach { TabManager.close(it) }
                            TabManager.openLocal()
                            onOpenTerminal()
                        }
                    }
                }
                InstallState.Idle -> {}
            }
        }
    }
}

/** "Repair Termux packages…": confirm first, it replaces everything under usr (installed packages go). */
@Composable
private fun RepairDialog() {
    val ctx = LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { TermuxInstaller.confirmRepair.value = false },
        containerColor = Surface1,
        title = { Text("Repair Termux packages?") },
        text = {
            Text(
                "Downloads the base packages again (~${TermuxEnv.DOWNLOAD_MB} MB) and replaces everything Termux " +
                    "installed: installed packages are removed, your home folder stays."
            )
        },
        confirmButton = {
            TextButton(onClick = { TermuxInstaller.confirmRepair.value = false; TermuxInstaller.repair(ctx) }) { Text("Repair") }
        },
        dismissButton = { TextButton(onClick = { TermuxInstaller.confirmRepair.value = false }) { Text("Cancel") } },
    )
}

internal fun progressText(s: InstallState): String = when (s) {
    is InstallState.Downloading -> "Downloading " + mb(s.bytes) + (if (s.total > 0) " of " + mb(s.total) else "") + " MB"
    InstallState.Verifying -> "Checking the download"
    is InstallState.Extracting -> "Unpacking ${s.done} of ${s.total} files"
    else -> ""
}

private fun mb(b: Long) = "%.1f".format(java.util.Locale.ROOT, b / 1048576.0)

@Composable private fun Title(t: String) =
    Text(t, style = MaterialTheme.typography.titleLarge, color = Text1, modifier = Modifier.padding(bottom = 10.dp))

@Composable private fun Body(t: String, muted: Boolean = false) =
    Text(t, style = MaterialTheme.typography.bodyMedium, color = if (muted) Muted else Text1, modifier = Modifier.padding(bottom = 8.dp))

@Composable private fun Buttons(content: @Composable () -> Unit) {
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) { content() }
}

@Composable private fun Primary(label: String, onClick: () -> Unit) = Button(
    onClick = onClick,
    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
) { Text(label) }
