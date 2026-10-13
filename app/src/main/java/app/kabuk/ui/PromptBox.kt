package app.kabuk.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kabuk.data.Settings
import app.kabuk.term.SshTab
import app.kabuk.term.TabManager
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The prompt box: type with autocorrect, swipe or voice, attach a picture, then send it all at once.
 * The draft belongs to the tab and is kept when sending isn't possible (disconnected).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PromptBox(ui: TerminalUi) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf<String?>(null) }   // "history" | "snippets"
    // expanded: as tall as possible while the tab strip, key bar and some terminal stay visible
    val maxExpanded = (ui.available - 40.dp - (KeyRowHeight * 2 + 2.dp) - 96.dp).coerceAtLeast(120.dp)
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(ui.focusPrompt) { if (ui.focusPrompt > 0) runCatching { focus.requestFocus() } }

    fun send(enter: Boolean) {
        val text = ui.draft
        // an empty send would press Enter, which picks the highlighted answer of an agent's menu
        if (text.isBlank()) return
        if (!ui.view.sendBlock(text.trimEnd(), enter)) {
            TabManager.note("Not connected · your text is kept", "Reconnect") { ui.target?.reconnect() }
            return
        }
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        Settings.addHistory(text)
        ui.draft = ""
        ui.promptExpanded = false
        ui.promptHint = null
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !said.isNullOrBlank()) ui.draft = join(ui.draft, said)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) attachImage(ctx, ui, uri)
    }
    val canSend = ui.draft.isNotBlank()

    Row(
        Modifier.fillMaxWidth().background(Surface1).padding(start = 0.dp, end = 6.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // hide the whole box (the draft stays); the lit ✎ key brings it back
        IconButton(onClick = { ui.showPrompt = false; ui.promptExpanded = false }, modifier = Modifier.size(44.dp, 48.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, "Hide message box", tint = Muted)
        }
        TextField(
            value = ui.draft, onValueChange = { ui.draft = it },
            modifier = Modifier.weight(1f).focusRequester(focus).then(
                if (ui.promptExpanded) Modifier.heightIn(min = minOf(160.dp, maxExpanded), max = maxExpanded)
                else Modifier.heightIn(min = 48.dp)
            ),
            placeholder = { Text(ui.promptHint ?: "Message…", fontSize = 15.sp, color = Muted, maxLines = 1) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
            maxLines = if (ui.promptExpanded) Int.MAX_VALUE else 2,
            shape = RoundedCornerShape(26.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Surface2, unfocusedContainerColor = Surface2,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Accent,
            ),
            leadingIcon = {
                // ⚡ tools: snippets, earlier prompts, picture
                Box {
                    IconButton(onClick = { menu = "snippets" }) {
                        if (ui.uploading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Bolt, "Snippets, earlier prompts, picture", tint = Warn)
                    }
                    DropdownMenu(menu != null, onDismissRequest = { menu = null }) {
                        if (ui.target is SshTab) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, null, tint = Blue) },
                                text = { Text("Attach picture") },
                                onClick = { menu = null; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            )
                            HorizontalDivider()
                        }
                        Settings.snippets().forEach { sn ->
                            // quick messages go straight to the terminal, like from the ⚡ key
                            DropdownMenuItem(text = { Text(sn, fontFamily = Mono) }, onClick = { menu = null; ui.typeSnippet(sn) })
                        }
                        val hist = Settings.history()
                        if (hist.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Earlier", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
                            hist.take(12).forEach { h ->
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.History, null, tint = Muted) },
                                    text = { Text(h, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp)) },
                                    onClick = { menu = null; ui.reuse(h) },
                                )
                            }
                        }
                    }
                }
            },
            trailingIcon = {
                Row {
                    IconButton(onClick = {
                        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your prompt")
                        runCatching { voice.launch(i) }.onFailure { TabManager.toast("No speech recognizer on this phone") }
                    }) { Icon(Icons.Default.Mic, "Speak", tint = Muted) }
                    if (ui.draft.isNotEmpty() || ui.promptExpanded) IconButton(onClick = { ui.promptExpanded = !ui.promptExpanded }) {
                        Icon(
                            if (ui.promptExpanded) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                            if (ui.promptExpanded) "Make smaller" else "Expand", tint = if (ui.promptExpanded) Accent else Muted,
                        )
                    }
                }
            },
        )
        // tap = send + Enter, long-press = insert only (to keep editing in the terminal); off while empty
        Box(
            Modifier.padding(start = 6.dp, bottom = 2.dp).size(48.dp).clip(CircleShape)
                .background(if (canSend) Accent else Surface2)
                .combinedClickable(enabled = canSend, onClick = { send(true) }, onLongClick = { send(false) }),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, "Send (long-press: insert without Enter)", tint = if (canSend) OnAccent else Muted)
        }
    }
}

private fun join(a: String, b: String) = if (a.isBlank()) b else a.trimEnd() + " " + b

/** Shrinks the picture, uploads it to the server and puts its path into the draft (Claude Code reads image paths). */
fun attachImage(ctx: Context, ui: TerminalUi, uri: Uri) {
    // the selected tab, even when the terminal view isn't bound yet (share right after launch)
    val tab = TabManager.currentTab() as? SshTab ?: return TabManager.toast("Pictures go to SSH tabs only")
    val tabId = tab.id
    ui.uploading = true
    thread {
        val bytes = runCatching { shrink(ctx, uri) }.getOrNull()
        if (bytes == null) {
            TabManager.main.post { ui.uploading = false; TabManager.toast("Couldn't read that picture") }
            return@thread
        }
        val name = "img-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jpg"
        tab.upload(bytes, name) { path ->
            ui.uploading = false
            if (path == null) TabManager.toast("Upload failed (not connected?)")
            else ui.appendTo(tabId, path)
        }
    }
}

private fun shrink(ctx: Context, uri: Uri): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 1600 || bounds.outHeight / (sample * 2) >= 1600) sample *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)!!.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("not an image")
    return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}
