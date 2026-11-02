package app.kabuk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.kabuk.BuildConfig
import app.kabuk.log.KLog
import app.kabuk.update.UpdateManifest
import app.kabuk.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings -> Update: check the DevBox update server, show what is offered, download + verify + install. */
@Composable
fun UpdateSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val updater = remember { Updater(ctx.applicationContext) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var offered by remember { mutableStateOf<UpdateManifest?>(null) }

    fun run(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true; status = label
        scope.launch {
            try { block() } catch (e: Exception) { status = "Failed: ${e.message ?: e.javaClass.simpleName}" } finally { busy = false }
        }
    }

    Section("Update")
    Text("Installed: Kabuk ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            run("Checking…") {
                val m = withContext(Dispatchers.IO) { updater.check() }
                offered = m
                status = if (m == null) "You have the newest version." else "Found Kabuk ${m.versionName} (${m.versionCode})."
            }
        }) { Text("Check for update") }
        offered?.let { m ->
            Button(enabled = !busy, onClick = {
                run("Downloading ${m.versionName}…") {
                    val apk = withContext(Dispatchers.IO) { updater.download(m) }
                    status = "Verified. Android will ask you to confirm the install."
                    if (!withContext(Dispatchers.IO) { updater.install(apk) }) {
                        status = "Allow installs from Kabuk in the settings page that just opened, then tap Install again."
                    }
                }
            }) { Text("Install ${m.versionName}") }
        }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Muted) }
    offered?.takeIf { it.notes.isNotBlank() }?.let { Text(it.notes, style = MaterialTheme.typography.bodyMedium, color = Muted) }
}

/** Settings -> Diagnostics: the live-log switch (on by default while the app is young). */
@Composable
fun DiagnosticsSection() {
    var on by remember { mutableStateOf(KLog.liveLog) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Diagnostics")
        SwitchRow("Live log", on,
            "Sends Kabuk's log lines (connections, agents, crashes; never passwords or prompts) to the developer's log server while on.") { v ->
            on = v; KLog.liveLog = v
        }
    }
}
