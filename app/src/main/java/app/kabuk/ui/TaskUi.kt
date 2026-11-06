package app.kabuk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kabuk.agents.OrchTask
import app.kabuk.agents.Workspace

/** the tasks of workspace [w] (any of its tabs too), oldest first */
internal fun tasksOf(w: Workspace, tasks: List<OrchTask>): List<OrchTask> =
    if (w.label.isBlank()) emptyList() else tasks.filter { it.workspace == w.label }

/** queued: muted, running: amber, done: green, failed: pink, cancelled: muted */
internal fun taskColor(state: String): Color = when (state) {
    "running" -> Warn
    "done" -> Good
    "failed" -> Bad
    else -> Muted
}

/** the small "2" pill on a workspace chip: its queued + running tasks (amber while one runs) */
@Composable
internal fun TaskCount(open: List<OrchTask>) {
    if (open.isEmpty()) return
    val running = open.any { it.state == "running" }
    Text(
        "${open.size}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        color = if (running) Color.Black else Text1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (running) Warn else KeyFill).padding(horizontal = 5.dp),
    )
}

/**
 * Workspace [w]'s task queue: each task with its state, first line, what it waits for and why it failed; an open one
 * can be cancelled, a running one marked done (both asked first). "Add task…" queues a new one.
 */
@Composable
internal fun TasksDialog(
    w: Workspace, tasks: List<OrchTask>, onDismiss: () -> Unit, onAdd: () -> Unit,
    onFinish: (OrchTask, Boolean) -> Unit,
) {
    var confirm by remember { mutableStateOf<Pair<OrchTask, Boolean>?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tasks · ${w.place}") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (tasks.isEmpty()) Text(
                    "No tasks. orchd types a task into this workspace's agent once it is idle, one after the other.",
                    color = Muted, style = MaterialTheme.typography.bodyMedium,
                )
                // open ones first (newest finished below them), each oldest first as orchd runs them
                val shown = tasks.filter { it.open } + tasks.filter { !it.open }.reversed()
                shown.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color(0xFF2E3440))
                    TaskRow(w, t, onCancel = { confirm = t to true }, onDone = { confirm = t to false })
                }
            }
        },
        confirmButton = { TextButton(enabled = w.label.isNotBlank(), onClick = onAdd) { Text("Add task…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    confirm?.let { (t, cancel) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (cancel) "Cancel task #${t.id}?" else "Mark task #${t.id} done?") },
            text = {
                Text(
                    if (cancel) "orchd won't send it" + (if (t.state == "running") " any more; the agent isn't interrupted in what it is doing." else ".") +
                        " Tasks that wait for it stay waiting."
                    else "Use this when the agent has finished but orchd didn't notice. Tasks waiting for #${t.id} start next.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = null; onFinish(t, cancel) }) {
                    Text(if (cancel) "Cancel task" else "Mark done", color = if (cancel) Bad else Good)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun TaskRow(w: Workspace, t: OrchTask, onCancel: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#${t.id}", fontFamily = Mono, fontSize = 12.sp, color = Muted)
            Spacer(Modifier.width(6.dp))
            Text(t.state, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = taskColor(t.state))
            val tab = t.target.substringAfter('@', "")
            val where = listOfNotNull(
                tab.takeIf { it.isNotEmpty() }?.let { id -> "tab " + (w.tabs.firstOrNull { it.id == id }?.label?.ifBlank { null } ?: id) },
                t.after.takeIf { it > 0 && t.state == "queued" }?.let { "after #$it" },
            ).joinToString(" · ")
            if (where.isNotEmpty()) { Spacer(Modifier.width(6.dp)); Text(where, fontSize = 12.sp, color = Muted) }
        }
        Text(t.firstLine, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (t.error.isNotEmpty()) Text(t.error, style = MaterialTheme.typography.bodySmall, color = Bad, maxLines = 3)
        if (t.open) Row {
            TextButton(onClick = onCancel) { Text("Cancel…", color = Bad) }
            if (t.state == "running") TextButton(onClick = onDone) { Text("Mark done…") }
        }
    }
}

/**
 * A new task for [w]: the text (several lines are fine), the whole workspace or one tab, and optionally a task it
 * waits for (one of the workspace's open tasks, or any id typed). onAdd(target, text, after or 0).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddTaskDialog(w: Workspace, open: List<OrchTask>, onDismiss: () -> Unit, onAdd: (String, String, Long) -> Unit) {
    var text by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("") }
    var after by remember { mutableStateOf("") }
    val afterId = after.trim().removePrefix("#").toLongOrNull()
    val afterOk = after.isBlank() || (afterId != null && afterId > 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New task · ${w.place}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    text, { text = it }, label = { Text("What the agent should do") }, minLines = 4, maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (w.tabs.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(tab.isEmpty(), onClick = { tab = "" }, label = { Text("Any tab") })
                    w.tabs.forEach { t ->
                        FilterChip(tab == t.id, onClick = { tab = t.id }, label = { Text("Tab " + t.label.ifBlank { "${t.index + 1}" }) })
                    }
                }
                OutlinedTextField(
                    after, { after = it.filter { c -> c.isDigit() || c == '#' } }, label = { Text("After task # (optional)") },
                    singleLine = true, isError = !afterOk, modifier = Modifier.fillMaxWidth(),
                )
                if (open.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    open.forEach { t ->
                        FilterChip(afterId == t.id, onClick = { after = if (afterId == t.id) "" else "${t.id}" },
                            label = { Text("#${t.id} ${t.firstLine}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(140.dp)) })
                    }
                }
                Text(
                    "orchd types it into the agent once the agent is idle" +
                        (if (afterId != null) ", after task #$afterId is done." else ", oldest task first."),
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && afterOk, onClick = {
                onAdd(if (tab.isEmpty()) w.label else "${w.label}@$tab", text.trim(), afterId ?: 0)
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
