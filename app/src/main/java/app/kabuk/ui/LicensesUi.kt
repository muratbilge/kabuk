package app.kabuk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings -> "Licences": the texts in assets/licenses. BusyBox (GPL-2.0) in the Phone tab needs its licence and a
 * source offer shipped with the app; rclone (MIT) and the notices of the Go modules it is built with, and the font
 * licence live there too. Texts are shown paragraph by paragraph in a lazy list (rclone's notices are ~500 KB).
 */
@Composable
fun LicensesButton() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { open = true }) { Text("Licences") }
    if (!open) return
    val files = remember { ctx.assets.list("licenses").orEmpty().sorted() }
    val text = shown?.let { name -> remember(name) { runCatching { ctx.assets.open("licenses/$name").bufferedReader().readText() }.getOrDefault("") } }
    AlertDialog(
        onDismissRequest = { open = false; shown = null },
        title = { Text(shown ?: "Licences") },
        text = {
            if (text != null) {
                val paragraphs = remember(text) { text.split("\n\n") }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(paragraphs) { Text(it, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                }
            } else Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                files.forEach { f -> TextButton(onClick = { shown = f }) { Text(f) } }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (shown != null) shown = null else open = false }) { Text(if (shown != null) "Back" else "Close") }
        },
    )
}
