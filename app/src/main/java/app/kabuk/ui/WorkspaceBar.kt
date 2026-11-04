package app.kabuk.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kabuk.agents.Agent
import app.kabuk.agents.Orch
import app.kabuk.agents.Workspace
import app.kabuk.agents.WsTab
import app.kabuk.term.SshTab
import app.kabuk.term.TabManager
import net.schmizz.sshj.SSHClient

/** What a tap or a menu choice in the bar does; wired to an SshTab in [WorkspaceBar], inert in screenshots. */
class WsActions(
    val focusWorkspace: (Workspace) -> Unit = {},
    val focusTab: (Workspace, WsTab) -> Unit = { _, _ -> },
    /** folder, label ("" = the folder's name), tool ("" = a shell) */
    val newWorkspace: (String, String, String) -> Unit = { _, _, _ -> },
    val newTab: (Workspace, String) -> Unit = { _, _ -> },
    val renameWorkspace: (Workspace, String) -> Unit = { _, _ -> },
    val closeWorkspace: (Workspace) -> Unit = {},
    val renameTab: (Workspace, WsTab, String) -> Unit = { _, _, _ -> },
    val closeTab: (Workspace, WsTab) -> Unit = { _, _ -> },
    /** whether orchd goes on with that agent after a usage limit (`orch auto-continue`) */
    val autoContinue: (Agent, Boolean) -> Unit = { _, _ -> },
)

/**
 * The native workspace bar of an orch server: a chip per orchd workspace (its agents' most urgent status as a dot,
 * the one on screen in the TUI lit), that one's tabs after it when it has more than one, and + for a new
 * workspace. Tap = show it in the TUI (`orch ws focus`, `orch tab focus`); long-press = new tab, rename, close,
 * and on a tab with an agent the auto-continue switch for its usage limits.
 * Nothing is shown while the stream reports no workspaces (herdr servers, orchd down, an old orch).
 */
@Composable
fun WorkspaceBar(tab: SshTab) {
    val workspaces by tab.workspaces.collectAsState()
    if (workspaces.isEmpty()) return
    val agents by tab.agents.collectAsState()
    fun run(action: String, what: String, cmd: (SSHClient) -> Int?, ok: String? = null) =
        tab.act(action, what, cmd) { err -> (err ?: ok)?.let(TabManager::toast) }
    WorkspaceBarContent(
        workspaces, agents,
        WsActions(
            focusWorkspace = { w -> run("ws focus ${w.id}", "workspace", { Orch.focusWorkspace(it, w.id) }) },
            focusTab = { w, t -> run("tab focus ${w.id} ${t.id}", "tab", { Orch.focusTab(it, w.id, t.id) }) },
            newWorkspace = { dir, label, tool ->
                run("ws new", "folder", { Orch.newWorkspace(it, dir, label, tool) }, "Started ${tool.ifBlank { "a shell" }} in $dir")
            },
            newTab = { w, tool -> run("tab new ${w.id}", "workspace", { Orch.newTab(it, w.id, tool) }, "New tab in ${w.place}") },
            renameWorkspace = { w, l -> run("ws rename ${w.id}", "workspace", { Orch.renameWorkspace(it, w.id, l) }) },
            closeWorkspace = { w -> run("ws close ${w.id}", "workspace", { Orch.closeWorkspace(it, w.id) }, "Closed ${w.place}") },
            renameTab = { w, t, l -> run("tab rename ${w.id} ${t.id}", "tab", { Orch.renameTab(it, w.id, t.id, l) }) },
            closeTab = { w, t -> run("tab close ${w.id} ${t.id}", "tab", { Orch.closeTab(it, w.id, t.id) }, "Closed tab ${t.label}") },
            autoContinue = { a, on -> tab.autoContinue(a, on) { err -> TabManager.toast(err ?: "Auto-continue ${if (on) "on" else "off"} for ${a.place}") } },
        ),
    )
}

@Composable
fun WorkspaceBarContent(workspaces: List<Workspace>, agents: List<Agent>, act: WsActions) {
    var newWs by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val shown = workspaces.indexOfFirst { it.shown }
    // keep the workspace on screen in view when it changes (a switch from the phone or in the TUI)
    LaunchedEffect(shown) { if (shown >= 0) listState.animateScrollToItem(shown) }
    Column {
        HorizontalDivider(color = Color(0xFF2E3440), thickness = 1.dp)
        // slim, like the tab strip above: every dp here is terminal space
        Row(Modifier.fillMaxWidth().background(Surface1).height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                state = listState, modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                workspaces.forEach { w ->
                    item(key = "ws/${w.id}") { WorkspaceChip(w, act) }
                    if (w.shown && w.tabs.size > 1) items(w.tabs, key = { "tab/${w.id}/${it.id}" }) { t -> TabChip(w, t, agents, act) }
                }
            }
            IconButton(onClick = { newWs = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Add, "New workspace", tint = Accent)
            }
        }
    }
    if (newWs) NewWorkspaceDialog(workspaces.map { it.cwd } + agents.map { it.cwd }, onDismiss = { newWs = false }) { dir, label, tool ->
        newWs = false
        act.newWorkspace(dir, label, tool)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceChip(w: Workspace, act: WsActions) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (w.shown) Surface2 else Color.Transparent)
                .combinedClickable(onClick = { if (!w.shown) act.focusWorkspace(w) }, onLongClick = { menu = true })
                .padding(horizontal = 10.dp)
                .height(30.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentDot(w.status)
            Spacer(Modifier.width(7.dp))
            Text(
                w.place, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                modifier = Modifier.widthIn(max = 120.dp),
                fontWeight = if (w.shown) FontWeight.SemiBold else FontWeight.Normal,
                color = if (w.shown) Color.White else Color(0xFFB4BCD0),
            )
            // how many tabs wait behind a workspace that isn't on screen (its tabs show once it is)
            if (w.tabs.size > 1 && !w.shown) { Spacer(Modifier.width(5.dp)); Text("${w.tabs.size}", fontSize = 11.sp, color = Muted) }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            if (!w.shown) DropdownMenuItem(text = { Text("Show") }, onClick = { menu = false; act.focusWorkspace(w) })
            DropdownMenuItem(text = { Text("New tab: shell") }, onClick = { menu = false; act.newTab(w, "") })
            DropdownMenuItem(text = { Text("New tab: Claude") }, onClick = { menu = false; act.newTab(w, "claude") })
            DropdownMenuItem(text = { Text("New tab: Codex") }, onClick = { menu = false; act.newTab(w, "codex") })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; rename = true })
            DropdownMenuItem(text = { Text("Close workspace…", color = Bad) }, onClick = { menu = false; confirmClose = true })
        }
    }
    if (rename) RenameDialog(w.place, onDone = { l ->
        rename = false
        if (!l.isNullOrBlank() && l != w.place) act.renameWorkspace(w, l)
    }, title = "Rename workspace", hint = "name")
    if (confirmClose) ConfirmClose(
        "Close ${w.place}?",
        "This kills every pane in it on the server (${w.tabs.size} tab${if (w.tabs.size == 1) "" else "s"}), including any agent running there.",
        onDismiss = { confirmClose = false },
    ) { act.closeWorkspace(w) }
}

/** a tab of the workspace on screen: its label, the agent in it, its status; the active one filled */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabChip(w: Workspace, t: WsTab, agents: List<Agent>, act: WsActions) {
    val active = t.index == w.activeTab
    val agent = agents.firstOrNull { it.paneId.isNotEmpty() && it.paneId in t.panes }
    val label = t.label.ifBlank { "${t.index + 1}" } + (agent?.let { " ${it.name}" } ?: "")
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (active) KeyFill else Color.Transparent)
                .border(1.dp, if (active) Color.Transparent else Color(0xFF2E3440), RoundedCornerShape(8.dp))
                .combinedClickable(onClick = { if (!active) act.focusTab(w, t) }, onLongClick = { menu = true })
                .padding(horizontal = 8.dp)
                .height(26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (t.status.isNotEmpty()) { AgentDot(t.status, 6.dp); Spacer(Modifier.width(5.dp)) }
            Text(
                label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
                modifier = Modifier.widthIn(max = 110.dp), color = if (active) Text1 else Color(0xFFB4BCD0),
            )
            if (agent?.limited == true) { Spacer(Modifier.width(5.dp)); PauseSign(Blue, 9.dp) }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            if (!active) DropdownMenuItem(text = { Text("Show") }, onClick = { menu = false; act.focusTab(w, t) })
            // the pane's agent goes on by itself a minute after its usage limit resets (orchd types "continue")
            if (agent != null && agent.paneId.startsWith("orch:")) DropdownMenuItem(
                text = { Text("Auto-continue after a usage limit") },
                trailingIcon = { Checkbox(checked = agent.autoContinue, onCheckedChange = null) },
                onClick = { menu = false; act.autoContinue(agent, !agent.autoContinue) },
            )
            DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; rename = true })
            DropdownMenuItem(text = { Text("Close tab…", color = Bad) }, onClick = { menu = false; confirmClose = true })
        }
    }
    if (rename) RenameDialog(t.label, onDone = { l ->
        rename = false
        if (!l.isNullOrBlank() && l != t.label) act.renameTab(w, t, l)
    }, title = "Rename tab", hint = "name")
    if (confirmClose) ConfirmClose(
        "Close tab ${t.label} in ${w.place}?",
        "This kills ${if (t.panes.size == 1) "its pane" else "its ${t.panes.size} panes"} on the server" +
            (agent?.let { ", including ${it.name}" } ?: "") + ".",
        onDismiss = { confirmClose = false },
    ) { act.closeTab(w, t) }
}

/** the agents' most urgent status: ● pink needs you, ● amber working, ● blue paused on a usage limit, ● green idle, ○ no agent */
@Composable
private fun AgentDot(status: String, size: Dp = 7.dp) {
    when (status) {
        "blocked" -> Box(Modifier.size(size).clip(CircleShape).background(Bad))
        "working" -> Box(Modifier.size(size).clip(CircleShape).background(Warn))
        "limited" -> Box(Modifier.size(size).clip(CircleShape).background(Blue))
        "idle", "done" -> Box(Modifier.size(size).clip(CircleShape).background(Good))
        else -> Box(Modifier.size(size).clip(CircleShape).border(1.dp, Muted, CircleShape))
    }
}

@Composable
private fun ConfirmClose(title: String, text: String, onDismiss: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onClose() }) { Text("Close", color = Bad) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** folder + what runs in it (a shell, Claude or Codex) + an optional name; recent folders as one-tap suggestions */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NewWorkspaceDialog(known: List<String>, onDismiss: () -> Unit, onStart: (String, String, String) -> Unit) {
    var dir by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var tool by remember { mutableStateOf("claude") }
    val suggestions = known.map { it.replace(Regex("^/home/[^/]+"), "~") }.filter { it.isNotBlank() && it != "~" && it != "/" }.distinct().take(6)
    val tools = listOf("" to "Shell", "claude" to "Claude", "codex" to "Codex")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New workspace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    tools.forEachIndexed { i, (v, name) ->
                        SegmentedButton(selected = tool == v, onClick = { tool = v }, shape = SegmentedButtonDefaults.itemShape(i, tools.size)) { Text(name) }
                    }
                }
                OutlinedTextField(
                    dir, { dir = it }, label = { Text("Folder on the server") }, singleLine = true,
                    placeholder = { Text("~/projects/…") }, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono),
                )
                if (suggestions.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    suggestions.forEach { s -> SuggestionChip(onClick = { dir = s }, label = { Text(s, fontFamily = Mono, maxLines = 1) }) }
                }
                OutlinedTextField(
                    label, { label = it }, label = { Text("Name") }, singleLine = true,
                    placeholder = { Text("the folder's name") }, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "A new orch workspace with ${tools.first { it.first == tool }.second.lowercase().replace("shell", "a shell")} in that folder, shown in the TUI.",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
            }
        },
        confirmButton = { TextButton(enabled = dir.isNotBlank(), onClick = { onStart(dir.trim(), label.trim(), tool) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
