package com.personalai.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.personalai.assistant.core.AnthropicOptions
import com.personalai.assistant.core.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val provider: Provider = Provider.ANTHROPIC,
    /** API key per AI service; each is kept even while another service is selected. */
    val apiKeys: Map<Provider, String> = emptyMap(),
    /** Chosen model per AI service; blank means the service's default. */
    val models: Map<Provider, String> = emptyMap(),
    val userName: String = "",
    val assistantName: String = "Personal AI",
    /** Claude only. */
    val effort: String = "medium",
    val voiceReplies: Boolean = true,
    /** Claude only; the other services don't search the web in this app. */
    val webSearch: Boolean = true,
    /** BCP-47 tag such as "en-IN" or "te-IN"; empty means the phone's language. */
    val speechLanguage: String = "",
    /** User-configurable tools the user lets run without asking. */
    val autoApproved: Set<String> = emptySet(),
    /** Require fingerprint, face or the phone's PIN to open the app. */
    val appLock: Boolean = false,
) {
    val apiKey: String get() = keyFor(provider)

    fun keyFor(p: Provider): String = apiKeys[p].orEmpty()

    fun modelFor(p: Provider): String {
        val chosen = models[p]?.trim().orEmpty()
        return when {
            chosen.isEmpty() -> p.defaultModel
            p == Provider.ANTHROPIC && AnthropicOptions.MODELS.none { it.first == chosen } -> p.defaultModel
            else -> chosen
        }
    }

    val effortLevel: BetaOutputConfig.Effort get() = BetaOutputConfig.Effort.of(effort)
}

/**
 * App settings. API keys are kept in encrypted storage backed by the Android Keystore;
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
        secure.edit {
            Provider.entries.forEach { p -> putString(keyName(p), next.keyFor(p)) }
        }
        prefs.edit {
            putString("provider", next.provider.name)
            Provider.entries.forEach { p -> putString("model_${p.name}", next.models[p].orEmpty()) }
            putString("userName", next.userName)
            putString("assistantName", next.assistantName)
            putString("effort", next.effort)
            putBoolean("voiceReplies", next.voiceReplies)
            putBoolean("webSearch", next.webSearch)
            putString("speechLanguage", next.speechLanguage)
            putStringSet("autoApproved", next.autoApproved)
            putBoolean("appLock", next.appLock)
        }
        state.value = next
    }

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            provider = prefs.getString("provider", null)
                ?.let { name -> Provider.entries.firstOrNull { it.name == name } } ?: d.provider,
            apiKeys = Provider.entries.associateWith { secure.getString(keyName(it), "").orEmpty() },
            models = Provider.entries.associateWith { prefs.getString("model_${it.name}", "").orEmpty() },
            userName = prefs.getString("userName", d.userName).orEmpty(),
            assistantName = prefs.getString("assistantName", d.assistantName).orEmpty(),
            effort = prefs.getString("effort", d.effort) ?: d.effort,
            voiceReplies = prefs.getBoolean("voiceReplies", d.voiceReplies),
            webSearch = prefs.getBoolean("webSearch", d.webSearch),
            speechLanguage = prefs.getString("speechLanguage", d.speechLanguage).orEmpty(),
            autoApproved = prefs.getStringSet("autoApproved", d.autoApproved)?.toSet().orEmpty(),
            appLock = prefs.getBoolean("appLock", d.appLock),
        )
    }

    private companion object {
        const val SECURE_FILE = "secure_settings"

        fun keyName(p: Provider) = "apiKey_${p.name}"

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
