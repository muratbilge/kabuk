package app.kabuk.ui

import androidx.compose.material3.SegmentedButton
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kabuk.agents.Agent
import app.kabuk.term.SshTab
import app.kabuk.term.TabManager

private fun statusColor(a: Agent) = when (a.status) {
    "blocked" -> Bad
    "working" -> Warn
    "idle", "done" -> Good
    else -> Muted
}

/** "1 needs you · 2 working" in the tab bar; hidden when no herdr agents are known */
@Composable
fun AgentsChip(onClick: () -> Unit) {
    val v by TabManager.agentsVersion.collectAsState()
    val all = remember(v) { TabManager.sshTabs().flatMap { it.agents.value } }
    if (all.isEmpty()) return
    val needs = all.count { it.needsYou }
    val working = all.count { it.working }
    // logo pink (tertiary container) when someone waits for you; 48dp touch target
    Row(
        Modifier.padding(end = 2.dp).height(40.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .background(if (needs > 0) Color(0xFF6E2335) else Surface2)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (needs > 0) Bad else if (working > 0) Warn else Good))
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                needs > 0 -> "$needs need${if (needs == 1) "s" else ""} you"
                working > 0 -> "$working working"
                else -> "${all.size} idle"
            },
            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (needs > 0) Color(0xFFFFD9DF) else Text1,
        )
    }
}

@Composable
private fun <T> remember(key: Any?, calc: () -> T): T = androidx.compose.runtime.remember(key) { calc() }

/**
 * All herdr agents, grouped Needs you / Working / Done / Idle. A waiting agent shows its question with answer
 * buttons right here (an inbox); tap a row to bring that agent on screen. "New agent" starts one in a folder.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AgentsSheet(ui: TerminalUi, onDismiss: () -> Unit) {
    val v by TabManager.agentsVersion.collectAsState()
    val rows = remember(v) { TabManager.sshTabs().flatMap { t -> t.agents.value.map { t to it } } }
    var newAgent by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Agents", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (TabManager.sshTabs().any { it.canSend }) androidx.compose.material3.FilledTonalButton(onClick = { newAgent = true }) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New agent")
                    }
                }
            }
            listOf("blocked" to "Needs you", "working" to "Working", "done" to "Done", "idle" to "Idle", "unknown" to "Other").forEach { (st, title) ->
                val group = rows.filter { (_, a) -> if (st == "unknown") a.status !in setOf("blocked", "working", "done", "idle") else a.status == st }
                if (group.isNotEmpty()) {
                    item {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = if (st == "blocked") Bad else Muted,
                            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 2.dp))
                    }
                    items(group, key = { (t, a) -> "${t.id}/${a.terminalId}" }) { (t, a) ->
                        val q = t.questions.value[a.paneId]
                        AgentRow(t, a, rows.map { it.first }.distinct().size > 1, q,
                            onClick = {
                                TabManager.select(t.id)
                                t.focusAgent(a) { ok -> if (!ok) TabManager.toast("Couldn't switch to that agent") }
                                onDismiss()
                            },
                            onAnswer = { c ->
                                val tell = c.label.startsWith("No", ignoreCase = true) && c.label.contains("tell", ignoreCase = true)
                                if (tell) onDismiss()
                                t.answerAgent(a.paneId, c, q!!.fingerprint) { ok ->
                                    TabManager.toast(if (ok) "Sent ${c.key} · ${c.label} to ${a.place}" else "Not sent: the question changed")
                                    // the reason must reach this agent: open the message box only once it's on screen
                                    if (ok && tell) {
                                        TabManager.select(t.id)
                                        t.focusAgent(a) { focused ->
                                            if (!focused) return@focusAgent TabManager.toast("Couldn't switch to ${a.place}")
                                            ui.promptHint = "Tell it what to do instead…"; ui.showPrompt = true; ui.focusPrompt++
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
            if (rows.isEmpty()) item {
                Text("No agents found. Kabuk lists agents running in herdr.", color = Muted, modifier = Modifier.padding(20.dp))
            }
        }
    }
    if (newAgent) NewAgentDialog(rows.map { it.second.cwd }, onDismiss = { newAgent = false }) { dir, tool ->
        newAgent = false
        val t = (TabManager.currentTab() as? SshTab)?.takeIf { it.canSend } ?: TabManager.sshTabs().firstOrNull { it.canSend }
        if (t == null) TabManager.toast("Not connected") else {
            TabManager.select(t.id)
            onDismiss()
            t.startAgent(dir, tool) { err -> TabManager.toast(err ?: "Started $tool in $dir") }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AgentRow(t: SshTab, a: Agent, showHost: Boolean, q: app.kabuk.text.Pending?, onClick: () -> Unit, onAnswer: (app.kabuk.text.Choice) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor(a)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(a.place, style = MaterialTheme.typography.titleMedium)
                Text(
                    a.name + " · " + a.cwd.replace(Regex("^/home/[^/]+"), "~") + if (showHost) " · ${t.hostName}" else "",
                    style = MaterialTheme.typography.bodySmall, color = Muted, fontFamily = Mono, maxLines = 1,
                )
            }
            if (a.focused) Text("on screen", style = MaterialTheme.typography.labelMedium, color = Accent)
        }
        // the inbox part: what it asks, and the answers
        if (q != null) {
            if (q.prompt.isNotBlank()) Text(
                q.prompt, style = MaterialTheme.typography.bodyMedium, fontFamily = Mono, maxLines = 4,
                modifier = Modifier.padding(start = 24.dp, top = 6.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)).background(Surface2).padding(10.dp),
            )
            androidx.compose.foundation.layout.FlowRow(
                Modifier.padding(start = 24.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                q.choices.forEachIndexed { i, c ->
                    val no = c.label.startsWith("No", ignoreCase = true)
                    Row(
                        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (i == 0) Accent else if (no) Color(0xFF6E2335) else Color(0xFF2A3B66))
                            .clickable { onAnswer(c) }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val fg = if (i == 0) OnAccent else if (no) Color(0xFFFFD9DF) else Color(0xFFD9E2FF)
                        Text(c.key, color = fg, fontFamily = Mono, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text(c.label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** folder + Claude or Codex; recent folders of your agents as one-tap suggestions */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NewAgentDialog(known: List<String>, onDismiss: () -> Unit, onStart: (String, String) -> Unit) {
    var dir by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var tool by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("claude") }
    val suggestions = known.map { it.replace(Regex("^/home/[^/]+"), "~") }.filter { it != "~" && it != "/" }.distinct().take(6)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New agent") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("claude" to "Claude", "codex" to "Codex").forEachIndexed { i, (v, label) ->
                        SegmentedButton(
                            selected = tool == v, onClick = { tool = v },
                            shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label) }
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    dir, { dir = it }, label = { Text("Folder on the server") }, singleLine = true,
                    placeholder = { Text("~/projects/…") }, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono),
                )
                if (suggestions.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    suggestions.forEach { s ->
                        androidx.compose.material3.SuggestionChip(onClick = { dir = s }, label = { Text(s, fontFamily = Mono, maxLines = 1) })
                    }
                }
                Text("Opens a new herdr workspace there and starts $tool in it.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = dir.isNotBlank(), onClick = { onStart(dir.trim(), tool) }) { Text("Start") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
