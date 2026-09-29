package com.personalai.assistant.calls

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.personalai.assistant.R
import com.personalai.assistant.core.CallRule
import com.personalai.assistant.core.CallerCategory
import com.personalai.assistant.core.ConditionCodec
import com.personalai.assistant.core.IncomingCaller
import com.personalai.assistant.core.MatchMode
import com.personalai.assistant.core.PhoneNumbers
import com.personalai.assistant.core.RuleEngine
import com.personalai.assistant.core.ScreeningAction
import com.personalai.assistant.core.ScreeningDecision
import com.personalai.assistant.data.AppDatabase
import com.personalai.assistant.data.CallRuleEntity
import com.personalai.assistant.data.CallerCategoryEntity
import com.personalai.assistant.data.ScreenedCallEntity
import com.personalai.assistant.tools.ContactsRepository
import com.personalai.assistant.tools.hasPermission
import com.personalai.assistant.ui.MainActivity

/**
 * Everything call screening needs: who is calling, which rule applies, and the history.
 * The Android side (answering Telecom within its time limit) is in [CallScreener].
 */
class CallScreeningRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val contacts: ContactsRepository,
    /** When the app lock is on, notifications hide caller details on the lock screen. */
    private val hideDetailsOnLockScreen: () -> Boolean = { false },
) {
    val rules = db.callRules()
    val categories = db.callerCategories()
    val history = db.screenedCalls()

    /** Call screening needs Android 10+, where the "caller ID & spam" role exists. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun isEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roles = context.getSystemService(RoleManager::class.java) ?: return false
        return roles.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && roles.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    /** The system dialog that makes this app the phone's call-screening app, or null if unsupported. */
    fun roleRequestIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val roles = context.getSystemService(RoleManager::class.java) ?: return null
        if (!roles.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return null
        return roles.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
    }

    /** Puts the built-in rules back, e.g. after the user deleted all their data. */
    suspend fun restoreDefaultRules() {
        context.getSharedPreferences("call_screening", Context.MODE_PRIVATE).edit().putBoolean("rulesSeeded", false).apply()
        ensureDefaultRules()
    }

    /** Creates the built-in rules once, on first launch. Rules the user deletes stay deleted. */
    suspend fun ensureDefaultRules() {
        val prefs = context.getSharedPreferences("call_screening", Context.MODE_PRIVATE)
        if (prefs.getBoolean("rulesSeeded", false)) return
        if (rules.count() == 0) rules.insertAll(RuleEngine.defaultRules().map { it.toEntity() })
        prefs.edit().putBoolean("rulesSeeded", true).apply()
    }

    /** Looks up the caller in contacts, remembered relationships and user categories. */
    suspend fun identify(rawNumber: String?): IncomingCaller {
        val number = rawNumber?.let(PhoneNumbers::clean)?.takeIf { PhoneNumbers.matchKey(it) != null }
            ?: return IncomingCaller(null)
        val name = contacts.lookupName(number)
        val relationship = name?.let { contactName ->
            db.memories().recent(500).firstOrNull { m ->
                !m.relationship.isNullOrBlank() && m.person?.trim().equals(contactName.trim(), ignoreCase = true)
            }?.relationship
        }
        val assigned = PhoneNumbers.matchKey(number)?.let { categories.byKey(it) }?.let { CallerCategory.parse(it.category) }
        return IncomingCaller(number, name, relationship, assigned)
    }

    suspend fun decide(caller: IncomingCaller): ScreeningDecision =
        RuleEngine.evaluate(caller, rules.all().map { it.toRule() })

    /** Saves the call to the screening history and, if the rule says so, notifies the user. */
    suspend fun record(caller: IncomingCaller, decision: ScreeningDecision) {
        history.insert(
            ScreenedCallEntity(
                timestamp = System.currentTimeMillis(),
                number = caller.number,
                contactName = caller.contactName,
                category = caller.category?.name,
                decision = decision.action.name,
                notified = decision.notify,
                ruleName = decision.ruleName,
                reason = decision.reason,
            ),
        )
        if (decision.notify) notify(caller, decision)
    }

    suspend fun setCategory(number: String, category: CallerCategory, note: String?) {
        val key = PhoneNumbers.matchKey(number) ?: throw IllegalArgumentException("\"$number\" isn't a valid phone number.")
        categories.upsert(
            CallerCategoryEntity(
                numberKey = key,
                displayNumber = PhoneNumbers.clean(number),
                category = category.name,
                note = note?.takeIf { it.isNotBlank() },
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun removeCategory(number: String): Boolean {
        val key = PhoneNumbers.matchKey(number) ?: return false
        return categories.deleteByKey(key) > 0
    }

    private fun notify(caller: IncomingCaller, decision: ScreeningDecision) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) return
        ensureChannel(context)
        val who = buildString {
            append(caller.displayName)
            if (caller.contactName != null && caller.number != null) append(" (${caller.number})")
        }
        val title = when (decision.action) {
            ScreeningAction.REJECT -> "Rejected call: $who"
            ScreeningAction.SILENCE -> "Silenced call: $who"
            ScreeningAction.ALLOW ->
                if (caller.category == CallerCategory.IMPORTANT) "Important call: $who" else "Incoming call: $who"
        }
        val detail = listOfNotNull(
            caller.category?.label,
            caller.relationship,
            if (!caller.inContacts && !caller.isHidden) "Not in contacts" else null,
            decision.ruleName?.let { "Rule: $it" },
        ).joinToString(" · ")
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(detail)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .apply {
                if (hideDetailsOnLockScreen()) {
                    setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    setPublicVersion(
                        NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_launcher_foreground)
                            .setContentTitle("Call screened")
                            .setCategory(NotificationCompat.CATEGORY_CALL)
                            .build(),
                    )
                }
            }
            .build()
        NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    companion object {
        const val CHANNEL_ID = "call_screening"

        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Call screening", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Who is calling and what call screening did"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

fun CallRule.toEntity(): CallRuleEntity = CallRuleEntity(
    id = id,
    name = name,
    enabled = enabled,
    priority = priority,
    matchMode = matchMode.name,
    conditions = ConditionCodec.encode(conditions),
    action = action.name,
    notify = notify,
    builtIn = builtIn,
    createdAt = System.currentTimeMillis(),
)

fun CallRuleEntity.toRule(): CallRule = CallRule(
    id = id,
    name = name,
    enabled = enabled,
    priority = priority,
    matchMode = MatchMode.entries.firstOrNull { it.name == matchMode } ?: MatchMode.ALL,
    conditions = ConditionCodec.decode(conditions),
    action = ScreeningAction.entries.firstOrNull { it.name == action } ?: ScreeningAction.ALLOW,
    notify = notify,
    builtIn = builtIn,
)
