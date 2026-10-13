package app.kabuk.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.kabuk.data.Hosts
import app.kabuk.data.Settings
import app.kabuk.term.LocalTab
import app.kabuk.term.SshTab
import app.kabuk.term.Status
import app.kabuk.term.TabManager
import app.kabuk.term.TermTab
import app.kabuk.term.TerminalCanvasView
import kotlinx.coroutines.delay

/** What the terminal screen shares with the activity. */
class TerminalUi(val view: TerminalCanvasView, val mods: ModifierState) {
    var selecting by mutableStateOf(false)
    var atBottom by mutableStateOf(true)
    var showPrompt by mutableStateOf(Settings.current.showPromptBox)
    /** the prompt box normally takes two lines; expanded it gets a big editor for long prompts */
    var promptExpanded by mutableStateOf(false)
    var link by mutableStateOf<String?>(null)
    var showAgents by mutableStateOf(false)
    var showQuick by mutableStateOf(false)
    var choices by mutableStateOf<List<app.kabuk.text.Choice>>(emptyList())
    var uploading by mutableStateOf(false)
    /** when a reply chip was tapped: the old menu is still on screen for a moment, don't bring it back */
    var repliedAt = 0L
    fun replied() { repliedAt = android.os.SystemClock.uptimeMillis(); choices = emptyList() }
    /** off in screenshot tests, which set [choices] themselves */
    var autoDetect = true
    /** bump to focus the message box; [promptHint] is its placeholder until the next send */
    var focusPrompt by mutableStateOf(0)
    var promptHint by mutableStateOf<String?>(null)
    /** height the terminal screen really has (the keyboard already taken off) */
    var available by mutableStateOf(800.dp)

    /** a reply chip (null = Esc) was tapped */
    fun answer(c: app.kabuk.text.Choice?) {
        val t = target ?: return
        view.scrollToBottom()
        t.write(c?.key ?: "\u001b")
        replied()
        TabManager.toast(if (c == null) "Sent Esc" else "Sent ${c.key} · ${c.label}")
        // "No, and tell Claude what to do differently": straight into writing that
        if (c != null && c.label.startsWith("No", ignoreCase = true) && c.label.contains("tell", ignoreCase = true)) {
            promptHint = "Tell it what to do instead…"
            showPrompt = true
            focusPrompt++
        }
    }

    /** one draft per tab, so text typed for one agent can't be sent to another */
    private val drafts = androidx.compose.runtime.mutableStateMapOf<Long, String>()
    var draft: String
        get() = drafts[TabManager.current.value ?: -1] ?: ""
        set(v) { drafts[TabManager.current.value ?: -1] = v }

    /** add to a specific tab's draft (an upload finishing after you switched tabs) */
    fun appendTo(tabId: Long, text: String) {
        val cur = drafts[tabId] ?: ""
        drafts[tabId] = if (cur.isBlank()) text else cur.trimEnd() + " " + text
    }

    /** the tab input goes to: always the one on screen right now */
    val target: TermTab? get() = view.tab
}

@Composable
fun TerminalScreen(
    ui: TerminalUi,
    onOpenHosts: () -> Unit,
    onOpenSettings: () -> Unit,
    onTextMode: (TermTab) -> Unit,
) {
    val tabs by TabManager.tabs.collectAsState()
    val currentId by TabManager.current.collectAsState()
    val tab = tabs.firstOrNull { it.id == currentId } ?: tabs.firstOrNull()
    LaunchedEffect(tab) { ui.view.tab = tab }
    // look for a choice menu (approval prompts) a few times a second
    LaunchedEffect(tab) {
        if (ui.autoDetect) ui.choices = emptyList()
        var lastSeen = 0L
        while (ui.autoDetect) {
            // nothing to look at while the app isn't on screen
            if (!TabManager.foreground) { kotlinx.coroutines.delay(1000); continue }
            val now = android.os.SystemClock.uptimeMillis()
            // the bar showing up shrinks the terminal and the agent redraws: half-drawn or reflowed screens
            // must not make it vanish again, so it stays until the question is really gone
            // herdr's own view of the agent on screen first (exact, from the event stream), else read the screen
            val herdrQ = (tab as? SshTab)?.let { t ->
                t.agents.value.firstOrNull { it.focused && it.needsYou }?.let { t.questions.value[it.paneId] }
            }
            val found = if (tab?.canSend == true && !tab.redrawing && now - ui.repliedAt > 1200)
                herdrQ?.choices ?: app.kabuk.text.QuickReplies.find(ui.view.focusedPaneLines()) else emptyList()
            if (found.isNotEmpty()) {
                ui.choices = found; lastSeen = now
            } else if (ui.choices.isNotEmpty()) {
                val waiting = (tab as? SshTab)?.agents?.value?.any { it.focused && it.needsYou } == true
                if (tab?.canSend != true || now - ui.repliedAt <= 1200 || (!waiting && now - lastSeen > 1500)) ui.choices = emptyList()
            }
            kotlinx.coroutines.delay(350)
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    ui.available = maxHeight
    Column(Modifier.fillMaxSize()) {
        TabStrip(tabs, tab, onOpenHosts, onOpenSettings, ui, onTextMode)
        Box(Modifier.weight(1f).fillMaxWidth().background(Bg)) {
            AndroidView(factory = { ui.view }, modifier = Modifier.fillMaxSize())
            androidx.compose.material3.SnackbarHost(Snack.host, Modifier.align(Alignment.BottomCenter))
            if (tab != null) StatusBanner(tab, Modifier.align(Alignment.TopCenter))
            if (ui.selecting) SelectionBar(ui, Modifier.align(Alignment.TopCenter))
            if (!ui.atBottom) {
                SmallFloatingActionButton(
                    onClick = { ui.view.scrollToBottom() },
                    containerColor = Color(0xFF2A3B66), contentColor = Color(0xFFD9E2FF),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) { Icon(Icons.Default.KeyboardDoubleArrowDown, "Jump to bottom") }
            }
        }
        KeyBar(
            ui.mods, tab?.id, onAction = { a -> keyAction(ui, a, onTextMode) }, promptOpen = ui.showPrompt,
            choices = ui.choices, onChoice = ui::answer,
        )
        if (ui.showPrompt) PromptBox(ui)
    }
    }
    if (ui.showAgents) AgentsSheet(ui, onDismiss = { ui.showAgents = false })
    if (ui.showQuick) QuickSheet(ui, onDismiss = { ui.showQuick = false }, onEdit = onOpenSettings)
}

fun keyAction(ui: TerminalUi, a: KeyAction, onTextMode: (TermTab) -> Unit) {
    val v = ui.view
    val tab = ui.target
    when (a) {
        is KeyAction.Code -> v.sendKeyCode(a.keyCode, 0)
        is KeyAction.Text -> a.s.codePoints().forEach { v.inputCodePoint(it, false, false) }
        is KeyAction.Raw -> { v.scrollToBottom(); tab?.write(a.s) }
        KeyAction.Ctrl -> ui.mods.tap(0)
        KeyAction.Alt -> ui.mods.tap(1)
        KeyAction.Shift -> ui.mods.tap(2)
        KeyAction.Paste -> v.paste()
        KeyAction.PromptBox -> ui.showPrompt = !ui.showPrompt
        KeyAction.Keyboard -> v.showKeyboard()
        KeyAction.TextMode -> tab?.let(onTextMode)
        KeyAction.Replies -> {}
        KeyAction.Snippets -> ui.showQuick = true
    }
}

// ---------------------------------------------------------------- tabs

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabStrip(
    tabs: List<TermTab>, current: TermTab?, onOpenHosts: () -> Unit, onOpenSettings: () -> Unit,
    ui: TerminalUi, onTextMode: (TermTab) -> Unit,
) {
    var newMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var confirmCloseAll by remember { mutableStateOf(false) }
    if (confirmCloseAll) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmCloseAll = false },
        title = { Text("Close all ${tabs.size} tabs?") },
        text = { Text("Sessions in tmux keep running on the server.") },
        confirmButton = { TextButton(onClick = { confirmCloseAll = false; TabManager.closeAll() }) { Text("Close all") } },
        dismissButton = { TextButton(onClick = { confirmCloseAll = false }) { Text("Cancel") } },
    )
    val listState = rememberLazyListState()
    LaunchedEffect(current?.id, tabs.size) {
        val i = tabs.indexOf(current)
        if (i >= 0) listState.animateScrollToItem(i)
    }
    // slim: every dp here is terminal space
    Row(Modifier.fillMaxWidth().background(Surface1).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            state = listState, modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
        ) {
            items(tabs, key = { it.id }) { t -> TabChip(t, t === current, ui, onTextMode) }
        }
        AgentsChip(onClick = { ui.showAgents = true })
        Box {
            IconButton(onClick = { newMenu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Add, "New tab", tint = Accent) }
            DropdownMenu(newMenu, onDismissRequest = { newMenu = false }) {
                Hosts.all.value.forEach { h ->
                    DropdownMenuItem(
                        text = { Column { Text(h.name); Text(h.label, fontSize = 12.sp, color = Muted) } },
                        onClick = { newMenu = false; TabManager.openSsh(h) },
                    )
                }
                DropdownMenuItem(text = { Text("Phone shell") }, onClick = { newMenu = false; TabManager.openLocal() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Servers…") }, onClick = { newMenu = false; onOpenHosts() })
            }
        }
        Box {
            IconButton(onClick = { moreMenu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(moreMenu, onDismissRequest = { moreMenu = false }) {
                DropdownMenuItem(text = { Text("Copy text… (whole history)") },
                    onClick = { moreMenu = false; current?.let(onTextMode) })
                DropdownMenuItem(text = { Text("Copy screen") }, onClick = {
                    moreMenu = false; TabManager.copyToClipboard(ui.view.visibleText(), "Screen copied")
                })
                DropdownMenuItem(text = { Text("Select…") }, onClick = { moreMenu = false; ui.view.selectAll() })
                DropdownMenuItem(text = { Text("Paste") }, onClick = { moreMenu = false; ui.view.paste() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Bigger text") }, onClick = {
                    Settings.update { it.copy(fontSizeSp = (it.fontSizeSp + 1).coerceAtMost(32f)) }
                })
                DropdownMenuItem(text = { Text("Smaller text") }, onClick = {
                    Settings.update { it.copy(fontSizeSp = (it.fontSizeSp - 1).coerceAtLeast(6f)) }
                })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Servers") }, onClick = { moreMenu = false; onOpenHosts() })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { moreMenu = false; onOpenSettings() })
                DropdownMenuItem(text = { Text("Close all tabs…", color = Bad) },
                    onClick = { moreMenu = false; confirmCloseAll = true })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabChip(t: TermTab, selected: Boolean, ui: TerminalUi, onTextMode: (TermTab) -> Unit) {
    val title by t.title.collectAsState()
    val status by t.status.collectAsState()
    val unread by t.unread.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmKill by remember { mutableStateOf(false) }
    val dot = when (status) {
        is Status.Connected -> Good
        is Status.Connecting -> Warn
        is Status.Disconnected -> Bad
        is Status.Ended -> Muted
    }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) Surface2 else Color.Transparent)
                .combinedClickable(onClick = { TabManager.select(t.id) }, onLongClick = { menu = true })
                .padding(start = 10.dp, end = 2.dp)
                .height(34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status, dot)
            Spacer(Modifier.width(8.dp))
            Text(
                t.userTitle ?: title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp, modifier = Modifier.widthIn(max = 140.dp),
                fontWeight = if (selected || unread) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color.White else Color(0xFFB4BCD0),
            )
            if (unread && !selected) {
                Spacer(Modifier.width(4.dp)); Box(Modifier.size(6.dp).clip(CircleShape).background(Accent))
            }
            // close only on the selected tab (no accidental closes while switching), with Undo
            if (selected) IconButton(onClick = { TabManager.closeWithUndo(t) }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Close, "Close tab", tint = Muted, modifier = Modifier.size(18.dp))
            } else Spacer(Modifier.width(10.dp))
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename = true })
            if (t is SshTab) DropdownMenuItem(text = { Text("Reconnect") }, onClick = { menu = false; t.reconnect() })
            DropdownMenuItem(text = { Text("Copy text…") }, onClick = { menu = false; onTextMode(t) })
            DropdownMenuItem(text = { Text("Move left") }, onClick = { menu = false; TabManager.move(t, -1) })
            DropdownMenuItem(text = { Text("Move right") }, onClick = { menu = false; TabManager.move(t, 1) })
            if (t is SshTab) DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                menu = false; Hosts.get(t.hostId)?.let { TabManager.openSsh(it) }
            })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Close tab") }, onClick = { menu = false; TabManager.closeWithUndo(t) })
            if (t is SshTab && t.usesTmux) DropdownMenuItem(
                text = { Text("End session on server…", color = Bad) },
                onClick = { menu = false; confirmKill = true },
            )
        }
        if (confirmKill && t is SshTab) androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmKill = false },
            title = { Text("End the session on ${t.hostName}?") },
            text = { Text("This stops everything running in it on the server, including Claude and herdr. Just closing the tab keeps it running.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmKill = false
                    t.killRemoteSession { ok ->
                        if (ok) { TabManager.toast("Session ended"); TabManager.close(t) }
                        else TabManager.toast("Could not end the session (not connected?)")
                    }
                }) { Text("End session", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmKill = false }) { Text("Cancel") } },
        )
        if (rename) RenameDialog(t.displayTitle(), onDone = { name ->
            rename = false
            if (name != null) { t.userTitle = name.ifBlank { null }; t.title.value = t.title.value }
        })
    }
}

// ---------------------------------------------------------------- overlays

@Composable
private fun StatusBanner(tab: TermTab, modifier: Modifier) {
    val status by tab.status.collectAsState()
    when (val s = status) {
        is Status.Connected -> {}
        is Status.Connecting -> LinearProgressIndicator(modifier.fillMaxWidth().height(2.dp), color = Accent)
        is Status.Disconnected -> {
            val left by produceState(0L, s) {
                while (true) { value = ((s.retryAt ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0) / 1000; delay(500) }
            }
            Banner(
                modifier,
                if (s.retryAt != null) "Connection lost · retrying in ${left}s" else "Disconnected: ${s.reason}",
                error = true, tab,
            )
        }
        is Status.Ended -> Banner(modifier, "Session ended", error = false, tab)
    }
}

@Composable
private fun Banner(modifier: Modifier, text: String, error: Boolean, tab: TermTab) {
    val bg = if (error) Color(0xFF7A1F2B) else Surface2
    val fg = if (error) Color(0xFFFFDAD6) else Text1
    Row(
        modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .padding(start = 14.dp, end = 6.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (error) Icons.Default.CloudOff else Icons.Default.Info, null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f), fontSize = 14.sp, maxLines = 2, color = fg)
        TextButton(onClick = { TabManager.close(tab) }) { Text("Close", color = fg) }
        if (tab is SshTab) androidx.compose.material3.Button(
            onClick = { tab.reconnect() },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
        ) { Text("Reconnect") }
        if (tab is LocalTab) androidx.compose.material3.Button(onClick = { TabManager.close(tab); TabManager.openLocal() }) { Text("Restart") }
    }
}

/** connection state by shape too, not only color: ● connected, ◌ connecting, ⊘ lost, ○ ended */
@Composable
private fun StatusDot(status: Status, color: Color) {
    when (status) {
        is Status.Connected -> Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        is Status.Connecting -> Box(Modifier.size(9.dp).clip(CircleShape).border(2.dp, color, CircleShape))
        is Status.Disconnected -> Icon(Icons.Default.CloudOff, "Disconnected", tint = color, modifier = Modifier.size(14.dp))
        is Status.Ended -> Box(Modifier.size(8.dp).clip(CircleShape).border(1.5.dp, color, CircleShape))
    }
}

@Composable
private fun SelectionBar(ui: TerminalUi, modifier: Modifier) {
    Row(
        modifier.padding(8.dp).clip(RoundedCornerShape(14.dp)).background(Surface2).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = {
            TabManager.copyToClipboard(ui.view.selectedText()); ui.view.clearSelection()
        }) { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy") }
        TextButton(onClick = {
            TabManager.copyToClipboard(ui.view.selectedTextRaw(), "Copied as shown"); ui.view.clearSelection()
        }) { Text("As shown") }
        if (ui.view.inPane) TextButton(onClick = { ui.view.selectFullWidth() }) { Text("Wide") }
        TextButton(onClick = { ui.view.selectAll() }) {
            Text("All")
        }
        TextButton(onClick = { ui.view.clearSelection(); ui.view.paste() }) {
            Text("Paste")
        }
        IconButton(onClick = { ui.view.clearSelection() }) { Icon(Icons.Default.Close, "Cancel", tint = Muted) }
    }
}
