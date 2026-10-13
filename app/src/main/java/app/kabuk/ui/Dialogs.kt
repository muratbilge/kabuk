package app.kabuk.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kabuk.term.Prompt
import app.kabuk.term.TabManager

@Composable
fun PromptDialog(p: Prompt) {
    when (p) {
        is Prompt.Confirm -> AlertDialog(
            onDismissRequest = {},
            title = { Text(p.title, color = if (p.danger) Bad else androidx.compose.ui.graphics.Color.Unspecified) },
            text = { Text(p.message, fontFamily = Mono, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { p.answer.complete(true) }) {
                    Text(if (p.danger) "Accept new key" else "Yes", color = if (p.danger) Bad else Accent)
                }
            },
            dismissButton = { TextButton(onClick = { p.answer.complete(false) }) { Text(if (p.danger) "Disconnect" else "No") } },
        )
        is Prompt.Password -> {
            // keyed by the question: a second server's prompt never inherits the first one's input
            var pw by remember(p) { mutableStateOf("") }
            var remember by remember(p) { mutableStateOf(false) }
            val focus = remember(p) { FocusRequester() }
            LaunchedEffect(p) { focus.requestFocus() }
            AlertDialog(
                onDismissRequest = {},
                title = { Text(p.title, fontSize = 18.sp) },
                text = {
                    Column {
                        if (p.message.isNotEmpty()) { Text(p.message, color = Bad); Spacer(Modifier.height(8.dp)) }
                        OutlinedTextField(
                            pw, { pw = it }, singleLine = true, label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.focusRequester(focus),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(remember, { remember = it })
                            Text("Remember (stored encrypted)", fontSize = 14.sp)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { p.answer.complete(pw to remember) }, enabled = pw.isNotEmpty()) { Text("Log in") } },
                dismissButton = { TextButton(onClick = { p.answer.complete(null) }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
fun RenameDialog(current: String, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Rename tab") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("empty = automatic") }) },
        confirmButton = { TextButton(onClick = { onDone(name.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

@Composable
fun LinkDialog(url: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link") },
        text = { Text(url, fontFamily = Mono, fontSize = 13.sp) },
        confirmButton = {
            TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                onDismiss()
            }) { Text("Open") }
        },
        dismissButton = {
            TextButton(onClick = { TabManager.copyToClipboard(url, "Link copied"); onDismiss() }) { Text("Copy") }
        },
    )
}
