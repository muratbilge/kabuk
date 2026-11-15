package app.kabuk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kabuk.term.TermuxDevices
import app.kabuk.term.TermuxDevices.DialogResult
import java.util.Calendar
import java.util.TimeZone

/**
 * termux-dialog's widgets over the terminal (Termux:API's answers: -1 OK/Yes, -2 Cancel/No/dismissed, 0 a sheet item).
 * Keyed by the request, so a second dialog never inherits the first one's input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptDialog(d: TermuxDevices.PendingDialog) {
    val s = d.spec
    val cancel = { d.answer(DialogResult(-2, "")) }
    val title: (@Composable () -> Unit)? = s.title?.let { t -> { Text(t, fontSize = 18.sp) } }
    fun okCancel(body: @Composable () -> Unit, ok: () -> Unit, okText: String = "OK", cancelText: String = "Cancel",
                 onCancel: () -> Unit = cancel): @Composable () -> Unit = {
        AlertDialog(
            onDismissRequest = cancel, title = title, text = body,
            confirmButton = { TextButton(onClick = ok) { Text(okText) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(cancelText) } },
        )
    }
    when (s.widget) {
        "confirm" -> okCancel({ Text(s.hint ?: "Confirm") }, { d.answer(DialogResult(-1, "yes")) }, "Yes", "No",
            onCancel = { d.answer(DialogResult(-2, "no")) })()
        "checkbox" -> {
            val checked = remember(d) { mutableStateListOf<Int>() }
            okCancel({
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    s.values.forEachIndexed { i, v ->
                        Row(Modifier.fillMaxWidth().clickable { if (i in checked) checked.remove(i) else checked.add(i) },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(i in checked, { on -> if (on) checked.add(i) else checked.remove(i) })
                            Text(v)
                        }
                    }
                }
            }, {
                val picked = checked.sorted().map { it to s.values[it] }
                d.answer(DialogResult(-1, picked.joinToString(", ", "[", "]") { it.second }, values = picked))
            })()
        }
        "radio" -> {
            var sel by remember(d) { mutableIntStateOf(-1) }
            okCancel({
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    s.values.forEachIndexed { i, v ->
                        Row(Modifier.fillMaxWidth().clickable { sel = i }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(sel == i, { sel = i })
                            Text(v)
                        }
                    }
                }
            }, { d.answer(DialogResult(-1, s.values.getOrElse(sel) { "" }, index = sel)) })()
        }
        "spinner" -> {
            var sel by remember(d) { mutableIntStateOf(0) }
            var open by remember(d) { mutableStateOf(false) }
            okCancel({
                ExposedDropdownMenuBox(open, { open = it }) {
                    OutlinedTextField(s.values.getOrElse(sel) { "" }, {}, readOnly = true, singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                    ExposedDropdownMenu(open, { open = false }) {
                        s.values.forEachIndexed { i, v -> DropdownMenuItem({ Text(v) }, { sel = i; open = false }) }
                    }
                }
            }, { d.answer(DialogResult(-1, s.values.getOrElse(sel) { "" }, index = sel)) })()
        }
        "sheet" -> ModalBottomSheet(onDismissRequest = cancel) {
            s.title?.let { Text(it, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
            LazyColumn(Modifier.navigationBarsPadding()) {
                itemsIndexed(s.values) { i, v ->
                    Text(v, fontSize = 18.sp, modifier = Modifier.fillMaxWidth().clickable { d.answer(DialogResult(0, v, index = i)) }
                        .padding(horizontal = 24.dp, vertical = 16.dp))
                }
            }
        }
        "counter" -> {
            val r = s.range ?: listOf(0, 100, 50)
            val min = minOf(r[0], r[1]); val max = maxOf(r[0], r[1])
            var n by remember(d) { mutableIntStateOf(r[2]) }
            okCancel({
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { if (n - 1 >= min) n-- }) { Text("−", fontSize = 20.sp) }
                    Spacer(Modifier.width(24.dp))
                    Text("$n", fontSize = 28.sp, fontFamily = Mono)
                    Spacer(Modifier.width(24.dp))
                    OutlinedButton(onClick = { if (n + 1 <= max) n++ }) { Text("+", fontSize = 20.sp) }
                }
            }, { d.answer(DialogResult(-1, "$n")) })()
        }
        "date" -> {
            val state = rememberDatePickerState(initialSelectedDateMillis = remember(d) { todayUtcMillis() })
            DatePickerDialog(
                onDismissRequest = cancel,
                confirmButton = {
                    TextButton(onClick = {
                        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = state.selectedDateMillis ?: todayUtcMillis() }
                        val r = runCatching { TermuxDevices.dateText(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH), s.dateFormat) }
                        d.answer(r.fold({ DialogResult(-1, it) }, { DialogResult(-2, "", error = it.toString()) }))
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } },
            ) { DatePicker(state, title = s.title?.let { t -> { Text(t, Modifier.padding(start = 24.dp, top = 16.dp)) } }) }
        }
        "time" -> {
            val now = remember(d) { Calendar.getInstance() }
            val state = rememberTimePickerState(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
            okCancel({ TimePicker(state) }, { d.answer(DialogResult(-1, TermuxDevices.timeText(state.hour, state.minute))) })()
        }
        else -> { // text
            var text by remember(d) { mutableStateOf("") }
            val focus = remember(d) { FocusRequester() }
            LaunchedEffect(d) { runCatching { focus.requestFocus() } }
            okCancel({
                OutlinedTextField(
                    text, { text = it }, singleLine = !s.multiLine, minLines = if (s.multiLine) 4 else 1,
                    placeholder = s.hint?.let { h -> { Text(h) } },
                    visualTransformation = if (s.password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = when {
                        s.numeric && s.password -> KeyboardType.NumberPassword
                        s.numeric -> KeyboardType.Decimal
                        s.password -> KeyboardType.Password
                        else -> KeyboardType.Text
                    }),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(focus),
                )
            }, { d.answer(DialogResult(-1, text)) })()
        }
    }
}

private fun todayUtcMillis(): Long {
    val local = Calendar.getInstance()
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}
