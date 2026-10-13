package app.kabuk.ui

import androidx.compose.material3.SegmentedButton
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kabuk.data.Host
import app.kabuk.data.Hosts
import app.kabuk.data.SecretBox
import app.kabuk.term.TabManager
import java.util.UUID

@Composable
fun HostsScreen(onConnected: () -> Unit, onEdit: (String?) -> Unit, onSettings: () -> Unit, onBack: (() -> Unit)?) {
    val hosts by Hosts.all.collectAsState()
    val tabs by TabManager.tabs.collectAsState()
    Box(Modifier.fillMaxSize().background(Bg)) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            else Spacer(Modifier.width(4.dp))
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(app.kabuk.R.drawable.ic_launcher_fg), null, Modifier.size(52.dp),
            )
            Text("Kabuk", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            // room at the bottom for the Add server button
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (tabs.isNotEmpty() && onBack != null) item {
                Card(onClick = onBack) {
                    Icon(Icons.Default.Terminal, null, tint = Accent)
                    Spacer(Modifier.width(14.dp))
                    Text("Back to ${tabs.size} open tab${if (tabs.size == 1) "" else "s"}", fontWeight = FontWeight.SemiBold)
                }
            }
            val reopen = TabManager.reopenable()
            if (reopen.isNotEmpty() && tabs.isEmpty()) item {
                Card(onClick = { TabManager.reopen(); onConnected() }) {
                    Icon(Icons.Default.Terminal, null, tint = Accent)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Reopen last session", fontWeight = FontWeight.SemiBold)
                        Text(reopen.joinToString(" · ") { it.name }, fontSize = 13.sp, color = Muted)
                    }
                }
            }
            item { Section("Servers") }
            items(hosts, key = { it.id }) { h ->
                val open = tabs.filterIsInstance<app.kabuk.term.SshTab>().filter { it.hostId == h.id }
                Card(onClick = { TabManager.openSsh(h); onConnected() }) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(h.color).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Dns, null, tint = Color(h.color), modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            h.label + when (h.session) {
                                Host.TMUX -> " · tmux ${h.tmuxSession}"
                                Host.AUTO -> " · herdr/tmux"
                                else -> ""
                            } + (if (h.keyInstalled) " · key" else ""),
                            style = MaterialTheme.typography.bodySmall, color = Muted, fontFamily = Mono,
                        )
                        if (open.isNotEmpty()) Text(
                            "${open.size} open tab${if (open.size == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium, color = Good,
                        )
                    }
                    IconButton(onClick = { onEdit(h.id) }) { Icon(Icons.Default.Edit, "Edit", tint = Muted) }
                }
            }
            item {
                Card(onClick = { TabManager.openLocal(); onConnected() }) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Good.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PhoneAndroid, null, tint = Good, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Phone shell", style = MaterialTheme.typography.titleMedium)
                        Text("Android's own shell, no packages", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
            }
        }
    }
        // thumb-reachable, bottom right
        androidx.compose.material3.ExtendedFloatingActionButton(
            onClick = { onEdit(null) },
            icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add server") },
            containerColor = Color(0xFF6B4019), contentColor = Color(0xFFFFDCBE),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = Accent, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp))
}

@Composable
private fun Card(onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).clickable(onClick = onClick)
            .heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

private val hostColors = listOf(0xFF7AA2F7, 0xFF9ECE6A, 0xFFE0AF68, 0xFFF7768E, 0xFFBB9AF7, 0xFF7DCFFF)

@Composable
fun HostEditScreen(id: String?, onDone: () -> Unit) {
    val old = id?.let { Hosts.get(it) }
    var name by remember { mutableStateOf(old?.name ?: "") }
    var address by remember { mutableStateOf(old?.host ?: "") }
    var port by remember { mutableStateOf((old?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(old?.user ?: "") }
    var password by remember { mutableStateOf("") }
    var clearPassword by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(old?.session ?: Host.AUTO) }
    var session by remember { mutableStateOf(old?.tmuxSession ?: "main") }
    var startup by remember { mutableStateOf(old?.startupCommand ?: "") }
    var reconnect by remember { mutableStateOf(old?.autoReconnect ?: true) }
    var color by remember { mutableStateOf(old?.color ?: hostColors[0]) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showPw by remember { mutableStateOf(false) }
    val valid = address.isNotBlank() && user.isNotBlank() && port.toIntOrNull() in 1..65535

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(if (old == null) "Add server" else "Edit ${old.name}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            TextButton(enabled = valid, onClick = {
                val pwEnc = when {
                    password.isNotEmpty() -> SecretBox.encrypt(password)
                    clearPassword -> ""
                    else -> old?.passwordEnc ?: ""
                }
                Hosts.save(
                    Host(
                        id = old?.id ?: UUID.randomUUID().toString(), name = name.ifBlank { address.trim() },
                        host = address.trim(), port = port.toInt(), user = user.trim(), passwordEnc = pwEnc,
                        session = mode, tmuxSession = session.trim().ifBlank { "main" }, startupCommand = startup.trim(),
                        autoReconnect = reconnect, keyInstalled = old?.keyInstalled ?: false, color = color,
                    )
                )
                onDone()
            }) { Text("Save") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(address, { address = it }, label = { Text("Address") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                    modifier = Modifier.width(96.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            OutlinedTextField(user, { user = it }, label = { Text("User") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                password, { password = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (old?.passwordEnc?.isNotEmpty() == true) "Password (saved - type to replace)" else "Password (optional)") },
                visualTransformation = if (showPw) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPw = !showPw }) {
                        Icon(if (showPw) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showPw) "Hide password" else "Show password")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text("Empty = ask when connecting. After the first password login the app offers to install its key.") },
            )
            if (old?.passwordEnc?.isNotEmpty() == true) SwitchRow("Forget saved password", clearPassword) { clearPassword = it }

            Text("Start with", style = MaterialTheme.typography.bodyLarge)
            androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = listOf(Host.AUTO to "Auto", Host.TMUX to "tmux", Host.SHELL to "Plain shell")
                modes.forEachIndexed { i, (m, label) ->
                    SegmentedButton(
                        selected = mode == m, onClick = { mode = m },
                        shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(label) }
                }
            }
            Text(
                when (mode) {
                    Host.AUTO -> "herdr if the server has it, else tmux, else a plain shell. Agents keep running when you close the app or lose signal."
                    Host.TMUX -> "Always tmux (plain shell if it isn't installed). You come back to the same screen."
                    else -> "No session that survives: closing the connection ends what runs in it."
                },
                style = MaterialTheme.typography.bodyMedium, color = Muted,
            )
            if (mode != Host.SHELL) OutlinedTextField(session, { session = it }, label = { Text("tmux session name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            else OutlinedTextField(startup, { startup = it }, label = { Text("Run after login (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), placeholder = { Text("e.g. claude") })
            SwitchRow("Reconnect automatically", reconnect, "After network changes or drop-outs.") { reconnect = it }

            Text("Color", color = Muted, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                hostColors.forEach { c ->
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).clickable { color = c }.padding(4.dp).clip(CircleShape).background(Color(c))
                            .semantics { contentDescription = if (c == color) "Color, selected" else "Color" },
                        contentAlignment = Alignment.Center,
                    ) { if (c == color) Icon(Icons.Default.Check, null, tint = Bg, modifier = Modifier.size(22.dp)) }
                }
            }
            if (old != null) {
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Bad),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Bad.copy(alpha = 0.6f)),
                ) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("Delete server") }
            }
        }
    }
    if (confirmDelete && old != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${old.name}?") },
        text = { Text("Only removes it from this app. Nothing changes on the server.") },
        confirmButton = { TextButton(onClick = { Hosts.delete(old.id); onDone() }) { Text("Delete", color = Bad) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .toggleable(checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        Switch(checked, onCheckedChange = null)
    }
}
