package com.personalai.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.personalai.assistant.core.AgentConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val apiKey: String = "",
    val userName: String = "",
    val assistantName: String = "Personal AI",
    val model: String = AgentConfig.DEFAULT_MODEL,
    val effort: String = "medium",
    val voiceReplies: Boolean = true,
    val webSearch: Boolean = true,
    /** BCP-47 tag such as "en-IN" or "te-IN"; empty means the phone's language. */
    val speechLanguage: String = "",
    /** User-configurable tools the user lets run without asking. */
    val autoApproved: Set<String> = emptySet(),
) {
    val effortLevel: BetaOutputConfig.Effort get() = BetaOutputConfig.Effort.of(effort)
}

/**
 * App settings. The API key is kept in encrypted storage backed by the Android Keystore;
 * everything else is in ordinary preferences.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secure: SharedPreferences = openEncrypted(context)

    private val state = MutableStateFlow(load())
    val settings: StateFlow<Settings> = state.asStateFlow()

    val current: Settings get() = state.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(state.value)
        secure.edit { putString(KEY_API, next.apiKey) }
        prefs.edit {
            putString("userName", next.userName)
            putString("assistantName", next.assistantName)
            putString("model", next.model)
            putString("effort", next.effort)
            putBoolean("voiceReplies", next.voiceReplies)
            putBoolean("webSearch", next.webSearch)
            putString("speechLanguage", next.speechLanguage)
            putStringSet("autoApproved", next.autoApproved)
        }
        state.value = next
    }

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            apiKey = secure.getString(KEY_API, "").orEmpty(),
            userName = prefs.getString("userName", d.userName).orEmpty(),
            assistantName = prefs.getString("assistantName", d.assistantName).orEmpty(),
            model = prefs.getString("model", d.model)
                ?.takeIf { m -> AgentConfig.MODELS.any { it.first == m } } ?: d.model,
            effort = prefs.getString("effort", d.effort) ?: d.effort,
            voiceReplies = prefs.getBoolean("voiceReplies", d.voiceReplies),
            webSearch = prefs.getBoolean("webSearch", d.webSearch),
            speechLanguage = prefs.getString("speechLanguage", d.speechLanguage).orEmpty(),
            autoApproved = prefs.getStringSet("autoApproved", d.autoApproved)?.toSet().orEmpty(),
        )
    }

    private companion object {
        const val KEY_API = "anthropicApiKey"
        const val SECURE_FILE = "secure_settings"

        fun openEncrypted(context: Context): SharedPreferences {
            fun open(): SharedPreferences = EncryptedSharedPreferences.create(
                context,
                SECURE_FILE,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return try {
                open()
            } catch (e: Exception) {
                // The keystore entry can be lost (e.g. after a restore). Start over; the user
                // re-enters the key rather than the app crashing on every launch.
                context.deleteSharedPreferences(SECURE_FILE)
                open()
            }
        }
    }
}
