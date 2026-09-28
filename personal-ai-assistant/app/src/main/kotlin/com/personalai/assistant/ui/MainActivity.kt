package com.personalai.assistant.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalai.assistant.core.PermissionLevel

class MainActivity : ComponentActivity() {

    private val viewModel: AssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                AppRoot(viewModel)
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    ASSISTANT("Assistant", Icons.Filled.Mic),
    MEMORY("Memory", Icons.Filled.Psychology),
    ACTIVITY("Activity", Icons.Filled.History),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@Composable
private fun AppRoot(vm: AssistantViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(if (settings.apiKey.isBlank()) Tab.SETTINGS.ordinal else Tab.ASSISTANT.ordinal) }
    val confirmation by vm.confirmation.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.ordinal,
                        onClick = { tab = t.ordinal },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (Tab.entries[tab]) {
            Tab.ASSISTANT -> AssistantScreen(vm, modifier)
            Tab.MEMORY -> MemoryScreen(vm, modifier)
            Tab.ACTIVITY -> ActivityScreen(vm, modifier)
            Tab.SETTINGS -> SettingsScreen(vm, modifier)
        }
    }

    confirmation?.let { pending ->
        val request = pending.request
        AlertDialog(
            onDismissRequest = { vm.answerConfirmation(false) },
            title = { Text("Allow this action?") },
            text = {
                Text(
                    request.detail + when (request.level) {
                        PermissionLevel.ALWAYS_CONFIRM -> "\n\nThis can't be undone."
                        else -> ""
                    },
                )
            },
            confirmButton = { TextButton(onClick = { vm.answerConfirmation(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { vm.answerConfirmation(false) }) { Text("Don't allow") } },
        )
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
