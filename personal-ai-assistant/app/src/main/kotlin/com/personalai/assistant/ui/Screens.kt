package com.personalai.assistant.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalai.assistant.AppContainer
import com.personalai.assistant.core.AgentConfig
import java.text.DateFormat
import java.util.Date

private val QUICK_ACTIONS = listOf(
    "Who called me today?",
    "What's on my calendar today?",
    "Call the last person who called me",
    "Remind me in one hour to drink water",
)

@Composable
fun AssistantScreen(vm: AssistantViewModel, modifier: Modifier = Modifier) {
    val chat by vm.chat.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val listening by vm.listening.collectAsStateWithLifecycle()
    val partial by vm.partialSpeech.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startListening()
    }
    val context = LocalContext.current

    LaunchedEffect(chat.size) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1)
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                settings.assistantName.ifBlank { "Personal AI" },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = vm::newConversation, enabled = !busy) {
                Icon(Icons.Filled.Add, contentDescription = "New conversation")
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (chat.isEmpty()) {
                item {
                    Column(Modifier.padding(vertical = 24.dp)) {
                        Text("How can I help?", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(12.dp))
                        QUICK_ACTIONS.forEach { q ->
                            AssistChip(onClick = { vm.send(q) }, label = { Text(q) }, enabled = !busy)
                        }
                    }
                }
            }
            items(chat) { ChatBubble(it) }
            if (busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("Working…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (listening || partial.isNotEmpty()) {
            Text(
                partial.ifEmpty { "Listening…" },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a command") },
                maxLines = 4,
            )
            IconButton(
                onClick = { vm.send(input); input = "" },
                enabled = input.isNotBlank() && !busy,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
            Spacer(Modifier.size(4.dp))
            LargeFloatingActionButton(
                onClick = {
                    when {
                        listening -> vm.stopListening()
                        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED -> vm.startListening()
                        else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (listening) "Stop listening" else "Speak",
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(item: ChatItem) {
    val colors = MaterialTheme.colorScheme
    val (bg, fg, align) = when (item.role) {
        ChatRole.USER -> Triple(colors.primaryContainer, colors.onPrimaryContainer, Alignment.CenterEnd)
        ChatRole.ASSISTANT -> Triple(colors.surfaceVariant, colors.onSurfaceVariant, Alignment.CenterStart)
        ChatRole.ACTION -> Triple(colors.secondaryContainer, colors.onSecondaryContainer, Alignment.CenterStart)
        ChatRole.ERROR -> Triple(colors.errorContainer, colors.onErrorContainer, Alignment.CenterStart)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Text(
            item.text,
            color = fg,
            style = if (item.role == ChatRole.ACTION) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(bg, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
fun MemoryScreen(vm: AssistantViewModel, modifier: Modifier = Modifier) {
    val memories by vm.memories.collectAsStateWithLifecycle(initialValue = emptyList())
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Memory", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (memories.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Delete all") }
        }
        Text(
            "Things you asked the assistant to remember. Say \"Remember that Ahmed is my business partner\" to add one.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(memories, key = { it.id }) { m ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.text, style = MaterialTheme.typography.bodyLarge)
                            val tag = listOfNotNull(m.person, m.relationship).joinToString(" · ")
                            if (tag.isNotEmpty()) Text(tag, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { vm.deleteMemory(m.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete memory")
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all memories?") },
            text = { Text("The assistant will forget everything you've told it to remember.") },
            confirmButton = { TextButton(onClick = { vm.deleteAllMemories(); confirmClear = false }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ActivityScreen(vm: AssistantViewModel, modifier: Modifier = Modifier) {
    val entries by vm.activity.collectAsStateWithLifecycle(initialValue = emptyList())
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Activity", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (entries.isNotEmpty()) TextButton(onClick = vm::clearActivity) { Text("Clear") }
        }
        Text(
            "Every action the assistant took or tried to take, including ones you declined.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${e.toolName} · ${e.status.lowercase().replace('_', ' ')}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(format.format(Date(e.timestamp)), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Text(e.summary, style = MaterialTheme.typography.bodyMedium)
                        if (e.result != e.summary) Text(e.result, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: AssistantViewModel, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var apiKey by rememberSaveable { mutableStateOf(s.apiKey) }
    var userName by rememberSaveable { mutableStateOf(s.userName) }
    var assistantName by rememberSaveable { mutableStateOf(s.assistantName) }
    var language by rememberSaveable { mutableStateOf(s.speechLanguage) }
    var saved by remember { mutableStateOf(false) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Section("AI service")
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; saved = false },
            label = { Text("Anthropic API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Create a key at console.anthropic.com. It's stored encrypted on this phone and sent only to Anthropic.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Model", style = MaterialTheme.typography.labelLarge)
        AgentConfig.MODELS.forEach { (id, label) ->
            FilterChip(selected = s.model == id, onClick = { vm.updateSettings { it.copy(model = id) } }, label = { Text(label) })
        }
        Text("Thinking effort (higher is smarter but slower)", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("low", "medium", "high").forEach { level ->
                FilterChip(
                    selected = s.effort == level,
                    onClick = { vm.updateSettings { it.copy(effort = level) } },
                    label = { Text(level.replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        ToggleRow("Web search", "Look things up online, like weather or phone numbers of offices.", s.webSearch) { on ->
            vm.updateSettings { it.copy(webSearch = on) }
        }

        Section("You")
        OutlinedTextField(
            value = userName,
            onValueChange = { userName = it; saved = false },
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = assistantName,
            onValueChange = { assistantName = it; saved = false },
            label = { Text("Assistant's name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Section("Voice")
        ToggleRow("Speak replies", "Read answers aloud when you ask by voice.", s.voiceReplies) { on ->
            vm.updateSettings { it.copy(voiceReplies = on) }
        }
        OutlinedTextField(
            value = language,
            onValueChange = { language = it; saved = false },
            label = { Text("Speech language (e.g. en-IN, te-IN, hi-IN)") },
            placeholder = { Text("Phone default") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                vm.updateSettings {
                    it.copy(
                        apiKey = apiKey.trim(),
                        userName = userName.trim(),
                        assistantName = assistantName.trim(),
                        speechLanguage = language.trim(),
                    )
                }
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saved) "Saved" else "Save") }

        Section("Ask before")
        Text(
            "Messages, calendar changes and deleting memories always need your approval. You can let these run without asking:",
            style = MaterialTheme.typography.bodySmall,
        )
        vm.configurableTools.forEach { spec ->
            val asks = spec.name !in s.autoApproved
            ToggleRow(spec.name.replace('_', ' ').replaceFirstChar { it.uppercase() }, spec.description, asks) { on ->
                vm.updateSettings {
                    it.copy(autoApproved = if (on) it.autoApproved - spec.name else it.autoApproved + spec.name)
                }
            }
        }

        Section("Phone permissions")
        Text(
            "Contacts, calls, call history, SMS, calendar, microphone and notifications. Each is used only when you ask for something that needs it.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { permissions.launch(AppContainer.PHONE_PERMISSIONS) }, modifier = Modifier.fillMaxWidth()) {
            Text("Grant phone permissions")
        }
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open Android app settings") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
