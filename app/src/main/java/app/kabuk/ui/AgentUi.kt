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
import app.kabuk.agents.AnswerOutcome
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
    val asks = remember(v) { TabManager.sshTabs().sumOf { it.busQuestions.value.size } }
    if (all.isEmpty() && asks == 0) return
    val needs = all.count { it.needsYou } + asks
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
    val asks = remember(v) { TabManager.sshTabs().flatMap { t -> t.busQuestions.value.map { t to it } } }
    val comms = remember(v) { TabManager.sshTabs().filter { it.usesOrch } }
    val note = remember(v) { TabManager.sshTabs().firstNotNullOfOrNull { it.orchNote.value } }
    // free-text answer to a bus question / a message to an agent
    var replyTo by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<SshTab, app.kabuk.agents.BusQuestion>?>(null) }
    var messageOn by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<SshTab?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        TabManager.agentsOpen = true
        onDispose { TabManager.agentsOpen = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Agents", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    // a message to an agent, reachable without scrolling past the agent list (the timeline is below)
                    if (comms.isNotEmpty()) androidx.compose.material3.TextButton(onClick = {
                        messageOn = (TabManager.currentTab() as? SshTab)?.takeIf { it.usesOrch } ?: comms.first()
                    }) { Text("Message…") }
                    if (TabManager.sshTabs().any { it.canSend }) androidx.compose.material3.FilledTonalButton(onClick = { newAgent = true }) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New agent")
                    }
                }
            }
            if (note != null) item {
                Text(note, style = MaterialTheme.typography.bodySmall, color = Warn,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            if (asks.isNotEmpty()) {
                item {
                    Text("Asks you", style = MaterialTheme.typography.titleSmall, color = Bad,
                        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 2.dp))
                }
                items(asks, key = { (t, q) -> "${t.id}/q${q.id}" }) { (t, q) ->
                    BusQuestionRow(q, onOption = { opt ->
                        t.answerQuestion(q.id, opt) { r ->
                            TabManager.toast(if (r == AnswerOutcome.SENT) "Answered ${q.from}: $opt" else r.text)
                        }
                    }, onReply = { replyTo = t to q })
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
                        AgentRow(t, a, rows.map { it.first }.distinct().size > 1, q, q?.let { t.answeredWith(a.paneId, it.fingerprint) },
                            onClick = {
                                TabManager.select(t.id)
                                t.focusAgent(a) { ok -> if (!ok) TabManager.toast("Couldn't switch to that agent") }
                                onDismiss()
                            },
                            onAnswer = { c ->
                                val tell = c.label.startsWith("No", ignoreCase = true) && c.label.contains("tell", ignoreCase = true)
                                if (tell) onDismiss()
                                t.answerAgent(a.paneId, c, q!!.fingerprint) { r ->
                                    TabManager.toast(if (r == AnswerOutcome.SENT) "Sent ${c.key} · ${c.label} to ${a.place}" else r.text)
                                    // the reason must reach this agent: open the message box only once it's on screen
                                    if (r == AnswerOutcome.SENT && tell) {
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
            if (rows.isEmpty() && asks.isEmpty()) item {
                Text("No agents found. Kabuk lists agents from herdr, or from orchtool when the server starts with orch.",
                    color = Muted, modifier = Modifier.padding(20.dp))
            }
            comms.forEach { t ->
                item(key = "comms/${t.id}") {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Comms" + if (comms.size > 1) " · ${t.hostName}" else "", style = MaterialTheme.typography.titleSmall,
                            color = Muted, modifier = Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = { messageOn = t }) { Text("Message…") }
                    }
                }
                val tl = t.timeline.value.takeLast(25).reversed()
                if (tl.isEmpty()) item(key = "comms-empty/${t.id}") {
                    Text("Nothing on the bus yet.", color = Muted, modifier = Modifier.padding(horizontal = 20.dp))
                }
                items(tl, key = { m -> "${t.id}/m${m.id}" }) { m -> TimelineRow(m) }
            }
        }
    }
    replyTo?.let { (t, q) ->
        TextDialog("Answer ${q.from}", q.text, "Your answer", onDismiss = { replyTo = null }) { text ->
            replyTo = null
            t.answerQuestion(q.id, text) { r -> TabManager.toast(if (r == AnswerOutcome.SENT) "Answered ${q.from}" else r.text) }
        }
    }
    messageOn?.let { t ->
        // only agents the bus knows can receive a message; a pane orchd merely detected (no hooks) can't
        val names = (t.agents.value.filter { it.registered && it.bus.isNotEmpty() }.map { it.bus } + "all").distinct()
        MessageDialog(names, onDismiss = { messageOn = null }) { to, text ->
            messageOn = null
            t.sendBus(to, text) { ok -> TabManager.toast(if (ok) "Sent to $to" else "Couldn't send to $to") }
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
private fun AgentRow(
    t: SshTab, a: Agent, showHost: Boolean, q: app.kabuk.text.Pending?,
    /** the choice this phone already sent for [q] (the agent hasn't taken it yet), or null */
    answered: String?,
    onClick: () -> Unit, onAnswer: (app.kabuk.text.Choice) -> Unit,
) {
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
            // answered from this phone, but the dialog is still on screen: no buttons, say so instead
            if (answered != null) Text(
                "Answered ${if (answered == app.kabuk.agents.Orch.ESC) "Esc" else answered}" +
                    "${q.choices.firstOrNull { it.key == answered }?.let { " · ${it.label}" } ?: ""} from this phone · waiting for the agent",
                style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2,
                modifier = Modifier.padding(start = 24.dp, top = 8.dp),
            )
            else androidx.compose.foundation.layout.FlowRow(
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
                Text("Starts $tool in that folder's workspace (herdr or orch, as the server starts with).", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = dir.isNotBlank(), onClick = { onStart(dir.trim(), tool) }) { Text("Start") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** a question from the orchtool bus: its text, the agent's options as buttons, and "Reply…" for your own words */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BusQuestionRow(q: app.kabuk.agents.BusQuestion, onOption: (String) -> Unit, onReply: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Bad))
            Spacer(Modifier.width(14.dp))
            Text(q.from, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("#${q.id}", style = MaterialTheme.typography.labelMedium, color = Muted)
        }
        Text(
            q.text, style = MaterialTheme.typography.bodyMedium, maxLines = 8,
            modifier = Modifier.padding(start = 24.dp, top = 6.dp).fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)).background(Surface2).padding(10.dp),
        )
        androidx.compose.foundation.layout.FlowRow(
            Modifier.padding(start = 24.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            q.options.forEachIndexed { i, opt ->
                Row(
                    Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (i == 0) Accent else Color(0xFF2A3B66))
                        .clickable { onOption(opt) }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(opt, color = if (i == 0) OnAccent else Color(0xFFD9E2FF), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
            Row(
                Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).background(Surface2)
                    .clickable(onClick = onReply).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text("Reply…", color = Text1, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

private val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT)

/** "14:02  codex → murat  done, tests green" */
@Composable
private fun TimelineRow(m: app.kabuk.agents.BusMessage) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(clock.format(java.util.Date(m.tsMs)), style = MaterialTheme.typography.bodySmall, color = Muted, fontFamily = Mono)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${m.from} → ${m.to}" + if (m.kind != "msg") " · ${m.kind}" else "",
                style = MaterialTheme.typography.labelMedium, color = if (m.kind == "ask") Bad else Accent, maxLines = 1,
            )
            Text(m.text, style = MaterialTheme.typography.bodySmall, maxLines = 4)
        }
    }
}

/** one text field: answer a question in your own words */
@Composable
private fun TextDialog(title: String, context: String, label: String, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(context, style = MaterialTheme.typography.bodyMedium, color = Muted, maxLines = 6)
                androidx.compose.material3.OutlinedTextField(text, { text = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = text.isNotBlank(), onClick = { onSend(text.trim()) }) { Text("Send") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** a message to an agent on the bus (or "all") */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MessageDialog(names: List<String>, onDismiss: () -> Unit, onSend: (String, String) -> Unit) {
    var to by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(names.first()) }
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    names.forEach { n ->
                        androidx.compose.material3.FilterChip(selected = to == n, onClick = { to = n }, label = { Text(n, maxLines = 1) })
                    }
                }
                androidx.compose.material3.OutlinedTextField(text, { text = it }, label = { Text("To $to") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = text.isNotBlank(), onClick = { onSend(to, text.trim()) }) { Text("Send") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
