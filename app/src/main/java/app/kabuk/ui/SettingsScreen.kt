package app.kabuk.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kabuk.BuildConfig
import app.kabuk.data.Settings
import app.kabuk.ssh.KeyPairStore
import app.kabuk.term.TabManager
import app.kabuk.term.Themes

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val p by Settings.prefs.collectAsState()
    val ctx = LocalContext.current
    var pub by remember { mutableStateOf(runCatching { KeyPairStore.publicKey(ctx) }.getOrElse { "(key not available: ${it.message})" }) }
    var confirmNewKey by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("Look")
            Label("Text size ${"%.0f".format(p.fontSizeSp)}", "Pinch the terminal to change it too.")
            Slider(p.fontSizeSp, { v -> Settings.update { it.copy(fontSizeSp = kotlin.math.round(v)) } }, valueRange = 8f..24f)
            Label("Terminal colors")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Themes.names.forEach { n ->
                    Box(
                        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color(Themes.background(n)))
                            .border(if (n == p.theme) 2.dp else 1.dp, if (n == p.theme) Accent else Color(0xFF2E3440), RoundedCornerShape(12.dp))
                            .selectable(n == p.theme, role = androidx.compose.ui.semantics.Role.RadioButton) { Settings.update { it.copy(theme = n) } },
                        contentAlignment = Alignment.Center,
                    ) { Text(n, fontSize = 10.sp, color = if (n == "Light") Color.Black else Color.White) }
                }
            }

            Section("Keyboard")
            SwitchRow("Message box open at start", p.showPromptBox, "Type with autocorrect and voice, send in one go. The ✎ key opens and hides it.") { v ->
                Settings.update { it.copy(showPromptBox = v) }
            }
            SwitchRow("Vibrate on key bar", p.vibrateKeys) { v -> Settings.update { it.copy(vibrateKeys = v) } }
            SwitchRow("Volume keys as Ctrl / Fn", p.volumeKeys,
                "Like Termux: hold Volume Down = Ctrl. Hold Volume Up + W/A/S/D = arrows, E = Esc, T = Tab, P/N = page up/down, 1-0 = F1-F10.") { v ->
                Settings.update { it.copy(volumeKeys = v) }
            }
            SwitchRow("Keyboard compatibility mode", p.compatKeyboard,
                "Turn on if letters double or Backspace misbehaves (some Samsung keyboards).") { v ->
                Settings.update { it.copy(compatKeyboard = v) }
            }

            Section("Agents")
            SwitchRow("Notify when an agent finishes", p.notifyFinished,
                "\"Needs you\" notifications are always on. Agents are read from herdr on the server.") { v ->
                Settings.update { it.copy(notifyFinished = v) }
            }
            SwitchRow("Remember sent prompts", p.rememberPrompts, "For the ⚡ menu. Stored encrypted on this phone.") { v ->
                Settings.update { it.copy(rememberPrompts = v) }
                if (!v) Settings.clearHistory()
            }
            TextButton(onClick = { Settings.clearHistory(); TabManager.toast("Prompt history cleared") }) { Text("Clear prompt history") }
            Label("⚡ snippets", "One per line.")
            var snippets by remember { mutableStateOf(Settings.snippets().joinToString("\n")) }
            androidx.compose.material3.OutlinedTextField(
                snippets, { snippets = it; Settings.saveSnippets(it.lines().map(String::trim).filter(String::isNotEmpty)) },
                modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 13.sp),
                minLines = 3, maxLines = 10,
            )

            Section("Behavior")
            SwitchRow("Keep screen on in the terminal", p.keepScreenOn) { v -> Settings.update { it.copy(keepScreenOn = v) } }
            SwitchRow("Vibrate on bell", p.bellVibrate) { v -> Settings.update { it.copy(bellVibrate = v) } }
            Label("Scroll speed")
            Segmented(listOf(1 to "Normal", 2 to "Fast", 3 to "Fastest"), p.scrollSpeed) { n -> Settings.update { it.copy(scrollSpeed = n) } }
            Label("Scrollback", "Lines kept in the app (tmux keeps its own). Applies to new tabs.")
            Segmented(listOf(2000 to "2000", 5000 to "5000", 20000 to "20000"), p.scrollbackLines) { n -> Settings.update { it.copy(scrollbackLines = n) } }

            Section("This phone's SSH key")
            Text(pub, fontFamily = Mono, fontSize = 11.sp, color = Color(0xFFB4BCD0),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface1).padding(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { TabManager.copyToClipboard(pub, "Public key copied") }) { Text("Copy") }
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pub), "Share key"))
                }) { Text("Share") }
                TextButton(onClick = { confirmNewKey = true }) { Text("New key…", color = Bad) }
            }
            Text("Only the public half leaves the phone. The private key is encrypted with the Android Keystore.",
                fontSize = 12.sp, color = Muted)

            Spacer(Modifier.height(16.dp))
            Text("Kabuk ${BuildConfig.VERSION_NAME} · terminal engine from Termux (Apache 2.0) · SSH by sshj · font JetBrains Mono (OFL)",
                fontSize = 12.sp, color = Muted)
        }
    }
    if (confirmNewKey) AlertDialog(
        onDismissRequest = { confirmNewKey = false },
        title = { Text("Make a new key?") },
        text = { Text("Servers that only know the old key will ask for the password again. You'll be offered to install the new key then.") },
        confirmButton = {
            TextButton(onClick = {
                KeyPairStore.regenerate(ctx); pub = KeyPairStore.publicKey(ctx); confirmNewKey = false
                app.kabuk.data.Hosts.all.value.forEach { app.kabuk.data.Hosts.save(it.copy(keyInstalled = false)) }
            }) { Text("New key", color = Bad) }
        },
        dismissButton = { TextButton(onClick = { confirmNewKey = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Label(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

/** one of a few exclusive choices (M3 segmented buttons) */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected, onClick = { onSelect(value) },
                shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label) }
        }
    }
}
