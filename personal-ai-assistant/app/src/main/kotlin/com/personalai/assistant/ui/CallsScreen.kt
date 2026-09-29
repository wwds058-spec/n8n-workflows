package com.personalai.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personalai.assistant.core.CallRule
import com.personalai.assistant.core.CallerCategory
import com.personalai.assistant.core.Condition
import com.personalai.assistant.core.ConditionField
import com.personalai.assistant.core.ConditionOperator
import com.personalai.assistant.core.MatchMode
import com.personalai.assistant.core.ScreeningAction
import com.personalai.assistant.data.ScreenedCallEntity
import java.text.DateFormat
import java.util.Date

private enum class CallsSection(val label: String) { HISTORY("History"), CATEGORIES("Categories"), RULES("Rules") }

@Composable
fun CallsScreen(modifier: Modifier = Modifier, vm: CallsViewModel = viewModel()) {
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by vm.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val rules by vm.rules.collectAsStateWithLifecycle(initialValue = emptyList())
    val message by vm.message.collectAsStateWithLifecycle()

    var section by rememberSaveable { mutableStateOf(CallsSection.HISTORY) }
    var categoryDialog by remember { mutableStateOf<CategoryDraft?>(null) }
    var ruleDialog by remember { mutableStateOf<CallRule?>(null) }
    var confirmDelete by remember { mutableStateOf<CallRule?>(null) }

    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshStatus()
    }
    LaunchedEffect(Unit) { vm.refreshStatus() }

    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column {
                Spacer(Modifier.height(8.dp))
                Text("Calls", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            StatusCard(
                supported = vm.isSupported,
                enabled = enabled,
                onEnable = { vm.roleRequestIntent()?.let { roleLauncher.launch(it) } },
            )
        }
        message?.let { msg ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::dismissMessage) { Text("OK") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CallsSection.entries.forEach { s ->
                    FilterChip(selected = section == s, onClick = { section = s }, label = { Text(s.label) })
                }
            }
        }

        when (section) {
            CallsSection.HISTORY -> {
                if (history.isEmpty()) {
                    item { Hint("No screened calls yet. When screening is on, incoming calls from numbers not in your contacts appear here.") }
                } else {
                    item {
                        TextButton(onClick = vm::clearHistory) { Text("Clear history") }
                    }
                }
                items(history, key = { it.id }) { call ->
                    ScreenedCallCard(
                        call = call,
                        time = dateFormat.format(Date(call.timestamp)),
                        onMark = call.number?.let { n -> { categoryDialog = CategoryDraft(n, CallerCategory.parse(call.category)) } },
                    )
                }
            }
            CallsSection.CATEGORIES -> {
                item {
                    Column {
                        Hint("Numbers you've given a category. Spam is silenced, blocked is rejected and important notifies you, using the default rules.")
                        Button(onClick = { categoryDialog = CategoryDraft("", null) }) { Text("Add number") }
                    }
                }
                items(categories, key = { it.id }) { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.displayNumber, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(CallerCategory.parse(entry.category)?.label, entry.note).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(onClick = {
                                categoryDialog = CategoryDraft(entry.displayNumber, CallerCategory.parse(entry.category), entry.note.orEmpty())
                            }) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                            IconButton(onClick = { vm.removeCategory(entry.displayNumber) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }
            CallsSection.RULES -> {
                item {
                    Column {
                        Hint("The first matching rule (lowest priority number) decides. If none matches, the call rings normally.")
                        Button(onClick = { ruleDialog = newRuleTemplate(rules) }) { Text("Add rule") }
                    }
                }
                items(rules, key = { it.id }) { rule ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${rule.priority}. ${rule.name}", style = MaterialTheme.typography.titleSmall)
                                    Text(rule.describe(), style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = rule.enabled, onCheckedChange = { vm.setRuleEnabled(rule, it) })
                            }
                            Row {
                                TextButton(onClick = { ruleDialog = rule }) { Text("Edit") }
                                if (!rule.builtIn) TextButton(onClick = { confirmDelete = rule }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    categoryDialog?.let { draft ->
        CategoryDialog(
            draft = draft,
            onDismiss = { categoryDialog = null },
            onSave = { number, category, note ->
                vm.setCategory(number, category, note)
                categoryDialog = null
            },
        )
    }
    ruleDialog?.let { rule ->
        RuleDialog(
            initial = rule,
            onDismiss = { ruleDialog = null },
            onSave = {
                vm.saveRule(it)
                ruleDialog = null
            },
        )
    }
    confirmDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete rule?") },
            text = { Text(rule.describe()) },
            confirmButton = { TextButton(onClick = { vm.deleteRule(rule); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusCard(supported: Boolean, enabled: Boolean, onEnable: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (enabled) colors.primaryContainer else colors.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    !supported -> "Call screening needs Android 10 or newer"
                    enabled -> "Call screening is on"
                    else -> "Call screening is off"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Screening checks who is calling while the phone rings and applies your rules: let it ring, " +
                    "silence it or reject it, and notify you. It can't answer calls or talk to callers. " +
                    "Calls from saved contacts usually ring normally without being screened.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (supported && !enabled) {
                Button(onClick = onEnable) { Text("Turn on call screening") }
                Text(
                    "Android will ask you to choose Personal AI as your \"caller ID & spam\" app.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ScreenedCallCard(call: ScreenedCallEntity, time: String, onMark: (() -> Unit)?) {
    val action = ScreeningAction.entries.firstOrNull { it.name == call.decision }
    val decisionColor = when (action) {
        ScreeningAction.REJECT -> MaterialTheme.colorScheme.error
        ScreeningAction.SILENCE -> Color(0xFF9A6200)
        else -> MaterialTheme.colorScheme.primary
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(call.contactName ?: call.number ?: "Hidden number", style = MaterialTheme.typography.titleSmall)
            if (call.contactName != null && call.number != null) {
                Text(call.number, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(action?.label ?: call.decision, color = decisionColor, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge)
                Text(CallerCategory.parse(call.category)?.label ?: "No category", style = MaterialTheme.typography.labelLarge)
                if (call.notified) Text("Notified", style = MaterialTheme.typography.labelLarge)
            }
            Text(time, style = MaterialTheme.typography.bodySmall)
            Text(call.reason, style = MaterialTheme.typography.bodySmall)
            if (onMark != null) TextButton(onClick = onMark) { Text("Set category") }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
}

private data class CategoryDraft(val number: String, val category: CallerCategory?, val note: String = "")

@Composable
private fun CategoryDialog(
    draft: CategoryDraft,
    onDismiss: () -> Unit,
    onSave: (String, CallerCategory, String?) -> Unit,
) {
    var number by remember { mutableStateOf(draft.number) }
    var category by remember { mutableStateOf(draft.category) }
    var note by remember { mutableStateOf(draft.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Caller category") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = number, onValueChange = { number = it }, label = { Text("Phone number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                ChipRow {
                    CallerCategory.entries.forEach { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.label) })
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { category?.let { onSave(number.trim(), it, note.trim()) } },
                enabled = category != null && number.count { it.isDigit() } >= 3,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun newRuleTemplate(existing: List<CallRule>) = CallRule(
    name = "",
    priority = ((existing.maxOfOrNull { it.priority } ?: 0) + 10).coerceAtMost(999),
    conditions = listOf(Condition(ConditionField.NUMBER_STARTS_WITH, value = "")),
    action = ScreeningAction.SILENCE,
    notify = true,
)

@Composable
private fun RuleDialog(initial: CallRule, onDismiss: () -> Unit, onSave: (CallRule) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var priority by remember { mutableStateOf(initial.priority.toString()) }
    var first by remember { mutableStateOf(initial.conditions.getOrNull(0) ?: Condition(ConditionField.IN_CONTACTS, value = "false")) }
    var second by remember { mutableStateOf(initial.conditions.getOrNull(1)) }
    var mode by remember { mutableStateOf(initial.matchMode) }
    var action by remember { mutableStateOf(initial.action) }
    var notify by remember { mutableStateOf(initial.notify) }

    val conditions = listOfNotNull(first, second)
    val valid = name.isNotBlank() && priority.toIntOrNull() != null && conditions.all { conditionValid(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "New rule" else "Edit rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    value = priority, onValueChange = { priority = it.filter(Char::isDigit).take(3) },
                    label = { Text("Priority (lower runs first)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("IF", style = MaterialTheme.typography.labelLarge)
                ConditionEditor(first) { first = it }
                if (second == null) {
                    OutlinedButton(onClick = { second = Condition(ConditionField.CATEGORY, value = CallerCategory.SPAM.name) }) {
                        Text("Add a second condition")
                    }
                } else {
                    ChipRow {
                        FilterChip(selected = mode == MatchMode.ALL, onClick = { mode = MatchMode.ALL }, label = { Text("AND") })
                        FilterChip(selected = mode == MatchMode.ANY, onClick = { mode = MatchMode.ANY }, label = { Text("OR") })
                        TextButton(onClick = { second = null }) { Text("Remove") }
                    }
                    ConditionEditor(second!!) { second = it }
                }
                Text("THEN", style = MaterialTheme.typography.labelLarge)
                ChipRow {
                    ScreeningAction.entries.forEach { a ->
                        FilterChip(selected = action == a, onClick = { action = a }, label = { Text(a.label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Notify me", modifier = Modifier.weight(1f))
                    Switch(checked = notify, onCheckedChange = { notify = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            priority = priority.toInt(),
                            conditions = conditions,
                            matchMode = if (second == null) MatchMode.ALL else mode,
                            action = action,
                            notify = notify,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun conditionValid(c: Condition): Boolean = when (c.field) {
    ConditionField.NUMBER_STARTS_WITH -> c.value.any(Char::isDigit)
    ConditionField.CATEGORY -> c.value.equals("NONE", true) || CallerCategory.parse(c.value) != null
    else -> c.value == "true" || c.value == "false"
}

@Composable
private fun ConditionEditor(condition: Condition, onChange: (Condition) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChipRow {
            ConditionField.entries.forEach { f ->
                FilterChip(
                    selected = condition.field == f,
                    onClick = {
                        if (f != condition.field) {
                            val defaultValue = when (f) {
                                ConditionField.CATEGORY -> CallerCategory.SPAM.name
                                ConditionField.NUMBER_STARTS_WITH -> ""
                                else -> "true"
                            }
                            onChange(Condition(f, ConditionOperator.IS, defaultValue))
                        }
                    },
                    label = { Text(f.label) },
                )
            }
        }
        when (condition.field) {
            ConditionField.CATEGORY -> {
                ChipRow {
                    ConditionOperator.entries.forEach { op ->
                        FilterChip(selected = condition.operator == op, onClick = { onChange(condition.copy(operator = op)) }, label = { Text(op.label) })
                    }
                }
                ChipRow {
                    (CallerCategory.entries.map { it.name to it.label } + ("NONE" to "No category")).forEach { (value, label) ->
                        FilterChip(
                            selected = condition.value.equals(value, ignoreCase = true),
                            onClick = { onChange(condition.copy(value = value)) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            ConditionField.NUMBER_STARTS_WITH -> {
                ChipRow {
                    ConditionOperator.entries.forEach { op ->
                        FilterChip(selected = condition.operator == op, onClick = { onChange(condition.copy(operator = op)) }, label = { Text(op.label) })
                    }
                }
                OutlinedTextField(
                    value = condition.value,
                    onValueChange = { onChange(condition.copy(value = it.filter(Char::isDigit).take(12))) },
                    label = { Text("Digits, e.g. 140") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            else -> ChipRow {
                listOf("true" to "Yes", "false" to "No").forEach { (value, label) ->
                    FilterChip(
                        selected = condition.value == value && condition.operator == ConditionOperator.IS,
                        onClick = { onChange(condition.copy(operator = ConditionOperator.IS, value = value)) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
