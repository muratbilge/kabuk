package app.kabuk.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kabuk.term.TabManager
import app.kabuk.term.TermTab

/**
 * The whole history as normal Android text: select with the system handles, copy, share.
 * For tmux tabs the text comes from the server (tmux capture-pane), so it includes everything
 * that scrolled by - not only what's on the screen.
 */
@Composable
fun TextModeScreen(tab: TermTab, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var wrap by remember { mutableStateOf(true) }
    var join by remember { mutableStateOf(true) }
    var raw by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf("") }
    var truncated by remember { mutableStateOf(false) }
    LaunchedEffect(tab) {
        tab.captureHistory { all, from ->
            source = from
            truncated = all.lines().size > 4000
            // keep it fast: the last 4000 lines are plenty to pick from
            raw = all.lines().takeLast(4000).joinToString("\n")
        }
    }
    // programs like Claude Code wrap their own text: join those lines back into paragraphs
    LaunchedEffect(raw, join) { raw?.let { text = if (join) app.kabuk.text.Unwrap.unwrap(it) else it } }
    val scroll = rememberScrollState()
    LaunchedEffect(text) { if (text != null) scroll.scrollTo(scroll.maxValue) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().background(Surface1).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Copy text", fontWeight = FontWeight.SemiBold)
                Text(
                    (if (source.isNotEmpty()) "$source · " else "") + (if (truncated) "last 4000 lines" else "long-press to select"),
                    fontSize = 12.sp, color = Muted, maxLines = 1,
                )
            }
            androidx.compose.material3.FilterChip(selected = join, onClick = { join = !join }, label = { Text("Join lines") })
            IconButton(onClick = { wrap = !wrap }) { Icon(Icons.Default.WrapText, "Wrap lines", tint = if (wrap) Accent else Muted) }
            IconButton(onClick = { text?.let { TabManager.copyToClipboard(it, "All copied") } }) {
                Icon(Icons.Default.ContentCopy, "Copy all")
            }
            IconButton(onClick = {
                text?.let {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it), "Share"))
                }
            }) { Icon(Icons.Default.Share, "Share") }
        }
        val t = text
        if (t == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            SelectionContainer {
                Text(
                    t, fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp, softWrap = wrap,
                    modifier = Modifier.fillMaxSize().verticalScroll(scroll)
                        .let { if (wrap) it else it.horizontalScroll(rememberScrollState()) }
                        .padding(10.dp),
                )
            }
        }
    }
}
