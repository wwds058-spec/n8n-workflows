package com.personalai.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personalai.assistant.AssistantApp
import com.personalai.assistant.agent.AnthropicGateway
import com.personalai.assistant.core.ActionRequest
import com.personalai.assistant.core.ActionStatus
import com.personalai.assistant.core.PermissionLevel
import com.personalai.assistant.data.Settings
import com.personalai.assistant.voice.Speaker
import com.personalai.assistant.voice.SpeechInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ChatRole { USER, ASSISTANT, ACTION, ERROR }

data class ChatItem(val role: ChatRole, val text: String)

class PendingConfirmation(val request: ActionRequest, val answer: CompletableDeferred<Boolean>)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AssistantApp).container
    val settingsStore = container.settings
    val settings: StateFlow<Settings> = settingsStore.settings
    val memories = container.database.memories().observeAll()
    val activity = container.database.actionLog().observeRecent()
    val configurableTools = container.tools.map { it.spec }.filter { it.level == PermissionLevel.USER_CONFIGURABLE }

    private val _chat = MutableStateFlow<List<ChatItem>>(emptyList())
    val chat: StateFlow<List<ChatItem>> = _chat.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _partialSpeech = MutableStateFlow("")
    val partialSpeech: StateFlow<String> = _partialSpeech.asStateFlow()

    private val _confirmation = MutableStateFlow<PendingConfirmation?>(null)
    val confirmation: StateFlow<PendingConfirmation?> = _confirmation.asStateFlow()

    private val speech = SpeechInput(application)
    private val speaker = Speaker(application)

    /** True while handling a request that was spoken, so the reply is spoken too. */
    private var voiceTurn = false

    private val agent = container.createAgent(
        confirmations = { request ->
            val pending = PendingConfirmation(request, CompletableDeferred())
            _confirmation.value = pending
            if (voiceTurn && settings.value.voiceReplies) speaker.speak("Please confirm on screen.")
            try {
                pending.answer.await()
            } finally {
                _confirmation.value = null
            }
        },
        voiceMode = { voiceTurn && settings.value.voiceReplies },
    )

    fun send(text: String, spoken: Boolean = false) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return
        voiceTurn = spoken
        speaker.stop()
        append(ChatItem(ChatRole.USER, trimmed))
        _busy.value = true
        viewModelScope.launch {
            try {
                val reply = agent.send(trimmed)
                reply.actions.filter { it.status != ActionStatus.SUCCEEDED || it.toolName !in QUIET_TOOLS }
                    .forEach { append(ChatItem(ChatRole.ACTION, "${statusMark(it.status)} ${it.summary}")) }
                val answer = reply.text.ifBlank { "Done." }
                append(ChatItem(ChatRole.ASSISTANT, answer))
                if (spoken && settings.value.voiceReplies) speaker.speak(answer, settings.value.speechLanguage)
            } catch (e: Exception) {
                append(ChatItem(ChatRole.ERROR, AnthropicGateway.describe(e)))
            } finally {
                _busy.value = false
            }
        }
    }

    fun answerConfirmation(approved: Boolean) {
        _confirmation.value?.answer?.complete(approved)
    }

    fun startListening() {
        if (_busy.value) return
        speaker.stop()
        if (!speech.isAvailable) {
            append(ChatItem(ChatRole.ERROR, "Speech recognition isn't available on this phone. Install or enable Google speech services."))
            return
        }
        _listening.value = true
        _partialSpeech.value = ""
        speech.start(settings.value.speechLanguage, object : SpeechInput.Listener {
            override fun onPartial(text: String) {
                _partialSpeech.value = text
            }

            override fun onFinal(text: String) {
                _partialSpeech.value = ""
                send(text, spoken = true)
            }

            override fun onError(message: String) {
                _partialSpeech.value = ""
                append(ChatItem(ChatRole.ERROR, message))
            }

            override fun onFinished() {
                _listening.value = false
            }
        })
    }

    fun stopListening() {
        speech.finish()
    }

    fun newConversation() {
        agent.reset()
        speaker.stop()
        _chat.value = emptyList()
    }

    fun updateSettings(transform: (Settings) -> Settings) = settingsStore.update(transform)

    fun deleteMemory(id: Long) {
        viewModelScope.launch { container.database.memories().delete(id) }
    }

    fun deleteAllMemories() {
        viewModelScope.launch { container.database.memories().deleteAll() }
    }

    fun clearActivity() {
        viewModelScope.launch { container.database.actionLog().deleteAll() }
    }

    private fun append(item: ChatItem) {
        _chat.update { it + item }
    }

    override fun onCleared() {
        speech.stop()
        speaker.shutdown()
        _confirmation.value?.answer?.complete(false)
    }

    private companion object {
        /** Successful read-only lookups aren't shown as separate lines in the chat. */
        val QUIET_TOOLS = setOf("find_contact", "read_call_log", "read_calendar")

        fun statusMark(status: ActionStatus) = when (status) {
            ActionStatus.SUCCEEDED -> "✓"
            ActionStatus.FAILED -> "✗"
            ActionStatus.DECLINED_BY_USER -> "⊘"
            ActionStatus.REJECTED -> "?"
        }
    }
}
