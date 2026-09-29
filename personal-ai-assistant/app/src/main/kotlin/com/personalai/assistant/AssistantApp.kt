package com.personalai.assistant

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import com.personalai.assistant.agent.AnthropicGateway
import com.personalai.assistant.core.ActionLogger
import com.personalai.assistant.core.AgentConfig
import com.personalai.assistant.core.AnthropicBackend
import com.personalai.assistant.core.AnthropicOptions
import com.personalai.assistant.core.AssistantAgent
import com.personalai.assistant.core.AssistantTool
import com.personalai.assistant.core.ChatBackend
import com.personalai.assistant.core.ConfirmationGate
import com.personalai.assistant.core.Endpoint
import com.personalai.assistant.core.OkHttpTransport
import com.personalai.assistant.core.OpenAiCompatibleBackend
import com.personalai.assistant.core.PermissionPolicy
import com.personalai.assistant.core.PromptContext
import com.personalai.assistant.core.Provider
import com.personalai.assistant.core.ToolRegistry
import com.personalai.assistant.data.ActionLogEntity
import com.personalai.assistant.data.AppDatabase
import com.personalai.assistant.data.SettingsStore
import com.personalai.assistant.tools.CallContactTool
import com.personalai.assistant.tools.ContactsRepository
import com.personalai.assistant.tools.CreateCalendarEventTool
import com.personalai.assistant.tools.CreateReminderTool
import com.personalai.assistant.tools.FindContactTool
import com.personalai.assistant.tools.ForgetMemoryTool
import com.personalai.assistant.tools.OpenAppTool
import com.personalai.assistant.tools.ReadCalendarTool
import com.personalai.assistant.tools.ReadCallLogTool
import com.personalai.assistant.tools.ReminderWorker
import com.personalai.assistant.tools.SaveMemoryTool
import com.personalai.assistant.tools.SendSmsTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AssistantApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ReminderWorker.ensureChannel(this)
    }
}

/** Creates and holds the app's long-lived objects. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val settings = SettingsStore(app)
    val database = AppDatabase.create(app)

    private val contacts = ContactsRepository(app, database.memories())

    val tools: List<AssistantTool> = listOf(
        FindContactTool(contacts),
        CallContactTool(app, contacts),
        ReadCallLogTool(app),
        SendSmsTool(app, contacts),
        OpenAppTool(app),
        CreateReminderTool(app),
        ReadCalendarTool(app),
        CreateCalendarEventTool(app),
        SaveMemoryTool(database.memories()),
        ForgetMemoryTool(database.memories()),
    )

    private val anthropic = AnthropicBackend(AnthropicGateway { settings.current.keyFor(Provider.ANTHROPIC) }) {
        val s = settings.current
        AnthropicOptions(model = s.modelFor(Provider.ANTHROPIC), effort = s.effortLevel)
    }

    private val transport = OkHttpTransport()

    private val compatible: Map<Provider, OpenAiCompatibleBackend> =
        Provider.entries.filter { it.baseUrl != null }.associateWith { p ->
            OpenAiCompatibleBackend(p.label.substringBefore(" ("), transport) {
                val s = settings.current
                Endpoint(p.baseUrl!!, s.keyFor(p).trim(), s.modelFor(p))
            }
        }

    private fun backendFor(p: Provider): ChatBackend = compatible[p] ?: anthropic

    /** Checks the saved key for [p] by listing the models it can use. */
    suspend fun listModels(p: Provider): List<String> = withContext(Dispatchers.IO) {
        val backend = compatible[p] ?: return@withContext AnthropicOptions.MODELS.map { it.first }
        backend.listModels()
    }

    fun createAgent(confirmations: ConfirmationGate, voiceMode: () -> Boolean): AssistantAgent =
        AssistantAgent(
            backend = { backendFor(settings.current.provider) },
            tools = ToolRegistry(tools),
            confirmations = confirmations,
            logger = ActionLogger { e ->
                database.actionLog().insert(
                    ActionLogEntity(
                        timestamp = e.timestampMillis,
                        toolName = e.toolName,
                        summary = e.summary,
                        status = e.status.name,
                        result = e.result,
                    ),
                )
            },
            policy = { PermissionPolicy(settings.current.autoApproved) },
            config = { AgentConfig(webSearch = settings.current.webSearch) },
            context = {
                val s = settings.current
                PromptContext(
                    userName = s.userName,
                    assistantName = s.assistantName.ifBlank { "Personal AI" },
                    memories = database.memories().recent(100).map { it.toFact() },
                    voiceMode = voiceMode(),
                )
            },
        )

    companion object {
        /** Runtime permissions the phone tools use, requested together from Settings. */
        val PHONE_PERMISSIONS: Array<String> = buildList {
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.WRITE_CALENDAR)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
}
