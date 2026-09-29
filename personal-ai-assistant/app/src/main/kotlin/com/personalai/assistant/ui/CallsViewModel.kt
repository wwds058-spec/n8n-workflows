package com.personalai.assistant.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personalai.assistant.AssistantApp
import com.personalai.assistant.calls.toEntity
import com.personalai.assistant.calls.toRule
import com.personalai.assistant.core.CallRule
import com.personalai.assistant.core.CallerCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class CallsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as AssistantApp).container.callScreening

    val history = repo.history.observeRecent()
    val categories = repo.categories.observeAll()
    val rules: Flow<List<CallRule>> = repo.rules.observeAll().map { list -> list.map { it.toRule() } }

    val isSupported: Boolean = repo.isSupported

    private val _enabled = MutableStateFlow(repo.isEnabled())
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refreshStatus() {
        _enabled.value = repo.isEnabled()
    }

    fun roleRequestIntent(): Intent? = repo.roleRequestIntent()

    fun setCategory(number: String, category: CallerCategory, note: String?) = launchWithMessage {
        repo.setCategory(number, category, note)
        "Saved ${category.label} for $number."
    }

    fun removeCategory(number: String) = launchWithMessage {
        repo.removeCategory(number)
        null
    }

    fun saveRule(rule: CallRule) = launchWithMessage {
        if (rule.id == 0L) repo.rules.insert(rule.toEntity()) else repo.rules.update(rule.toEntity())
        "Rule \"${rule.name}\" saved."
    }

    fun setRuleEnabled(rule: CallRule, enabled: Boolean) = launchWithMessage {
        repo.rules.update(rule.copy(enabled = enabled).toEntity())
        null
    }

    fun deleteRule(rule: CallRule) = launchWithMessage {
        repo.rules.delete(rule.id)
        "Rule \"${rule.name}\" deleted."
    }

    fun clearHistory() = launchWithMessage {
        repo.history.deleteAll()
        null
    }

    fun dismissMessage() {
        _message.value = null
    }

    private fun launchWithMessage(block: suspend () -> String?) {
        viewModelScope.launch {
            _message.value = try {
                block()
            } catch (e: Exception) {
                e.message ?: "Something went wrong."
            }
        }
    }
}
