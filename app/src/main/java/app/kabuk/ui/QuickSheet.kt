package app.kabuk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kabuk.data.Settings
import app.kabuk.term.TabManager

/** Quick messages (⚡ snippets) go straight to the terminal, without Enter; you press ⏎ (or add more) yourself. */
fun TerminalUi.typeSnippet(s: String) {
    view.scrollToBottom()
    if (!view.sendBlock(s, false)) TabManager.toast("Not connected")
}

/** An earlier prompt goes into the message box, to change it before sending again. */
fun TerminalUi.reuse(s: String) {
    draft = s
    showPrompt = true
    focusPrompt++
}

/** The ⚡ key: quick messages and earlier prompts, reachable even with the message box hidden. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickSheet(ui: TerminalUi, onDismiss: () -> Unit, onEdit: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Quick messages", style = MaterialTheme.typography.titleSmall, color = Accent, modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { onDismiss(); onEdit() }) {
                        Icon(Icons.Default.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Edit")
                    }
                }
                Text(
                    "Typed into the terminal. Press ⏎ to run.", style = MaterialTheme.typography.bodySmall, color = Muted,
                    modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                )
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Settings.snippets().forEach { s ->
                        SuggestionChip(
                            onClick = { onDismiss(); ui.typeSnippet(s) },
                            label = { Text(s, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            shape = RoundedCornerShape(8.dp),
                            colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Surface2),
                            modifier = Modifier.heightIn(min = 40.dp),
                        )
                    }
                }
            }
            val hist = Settings.history()
            if (hist.isNotEmpty()) {
                item {
                    Text("Earlier prompts", style = MaterialTheme.typography.titleSmall, color = Accent,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp))
                    Text("Opens it in the message box to change and send.", style = MaterialTheme.typography.bodySmall, color = Muted,
                        modifier = Modifier.padding(start = 20.dp, bottom = 4.dp))
                }
                items(hist.take(15)) { h ->
                    Text(
                        h, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clickable { onDismiss(); ui.reuse(h) }
                            .heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
