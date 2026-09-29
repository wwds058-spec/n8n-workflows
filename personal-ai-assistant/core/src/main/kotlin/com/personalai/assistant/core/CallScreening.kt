package com.personalai.assistant.core

/** Phone-number helpers shared by call screening and the calling tools. */
object PhoneNumbers {

    /** Keeps digits and a leading '+'; converts a leading international "00" to '+'. */
    fun clean(raw: String): String {
        val trimmed = raw.trim()
        val plus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        return when {
            plus -> "+$digits"
            digits.startsWith("00") -> "+${digits.drop(2)}"
            else -> digits
        }
    }

    /**
     * Key used to decide whether two numbers are the same line: the last 10 digits
     * (or all of them for shorter numbers). "+91 98765 43210", "098765 43210" and
     * "9876543210" share a key. Returns null when there are fewer than 3 digits.
     */
    fun matchKey(raw: String?): String? {
        val digits = raw?.filter { it.isDigit() } ?: return null
        if (digits.length < 3) return null
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    fun sameNumber(a: String?, b: String?): Boolean {
        val ka = matchKey(a) ?: return false
        return ka == matchKey(b)
    }
}

/** How the user wants calls from a number handled. Set per number by the user. */
enum class CallerCategory(val label: String) {
    IMPORTANT("Important"),
    FAMILY("Family"),
    PERSONAL("Personal"),
    BUSINESS("Business"),
    SPAM("Spam"),
    BLOCKED("Blocked");

    companion object {
        fun parse(value: String?): CallerCategory? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) || it.label.equals(value?.trim(), ignoreCase = true) }

        /** Category implied by a remembered relationship ("father" → family, "boss" → business). */
        fun fromRelationship(relationship: String?): CallerCategory? {
            val r = relationship?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (RelationshipWords.canonical(r) != null) return FAMILY
            val work = listOf("business", "work", "colleague", "office", "boss", "client", "manager", "partner", "customer")
            if (work.any { r.contains(it) }) return BUSINESS
            return PERSONAL
        }
    }
}

/** Everything known about a caller while the phone is ringing. */
data class IncomingCaller(
    /** The number as the network sent it; null when the caller hid it. */
    val number: String?,
    /** Name from the phone's contacts, if the number is saved. */
    val contactName: String? = null,
    /** Relationship the user taught the assistant for that contact ("father"). */
    val relationship: String? = null,
    /** Category the user assigned to this number. */
    val assignedCategory: CallerCategory? = null,
) {
    val isHidden: Boolean get() = PhoneNumbers.matchKey(number) == null
    val inContacts: Boolean get() = contactName != null

    /** The user's explicit category wins; otherwise one implied by the relationship. */
    val category: CallerCategory? get() = assignedCategory ?: CallerCategory.fromRelationship(relationship)

    val displayName: String get() = contactName ?: number?.takeIf { !isHidden } ?: "Hidden number"
}

enum class ConditionField(val label: String) {
    IN_CONTACTS("Caller is in contacts"),
    CATEGORY("Caller category"),
    NUMBER_STARTS_WITH("Number starts with"),
    HIDDEN_NUMBER("Number is hidden"),
    HAS_RELATIONSHIP("Caller has a remembered relationship"),
}

enum class ConditionOperator(val label: String) { IS("is"), IS_NOT("is not") }

/**
 * One test on the caller. [value] is "true"/"false" for yes/no fields, a [CallerCategory]
 * name for CATEGORY (or "NONE" for no category), and a digit prefix for NUMBER_STARTS_WITH.
 */
data class Condition(
    val field: ConditionField,
    val operator: ConditionOperator = ConditionOperator.IS,
    val value: String = "true",
) {
    fun matches(caller: IncomingCaller): Boolean {
        val result = when (field) {
            ConditionField.IN_CONTACTS -> caller.inContacts == value.toBooleanStrictOrNull()
            ConditionField.HIDDEN_NUMBER -> caller.isHidden == value.toBooleanStrictOrNull()
            ConditionField.HAS_RELATIONSHIP -> (!caller.relationship.isNullOrBlank()) == value.toBooleanStrictOrNull()
            ConditionField.CATEGORY ->
                if (value.equals("NONE", ignoreCase = true)) caller.category == null
                else caller.category != null && caller.category == CallerCategory.parse(value)
            ConditionField.NUMBER_STARTS_WITH -> {
                val prefix = value.filter { it.isDigit() }
                val digits = caller.number?.filter { it.isDigit() }.orEmpty()
                // Match the national number ("140…") as well as the full international form ("91140…").
                prefix.isNotEmpty() && (digits.startsWith(prefix) || PhoneNumbers.matchKey(digits).orEmpty().startsWith(prefix))
            }
        }
        return if (operator == ConditionOperator.IS) result else !result
    }

    fun describe(): String = when (field) {
        ConditionField.CATEGORY -> "category ${operator.label} ${CallerCategory.parse(value)?.label ?: "none"}"
        ConditionField.NUMBER_STARTS_WITH -> "number ${if (operator == ConditionOperator.IS) "starts" else "doesn't start"} with $value"
        else -> {
            val yes = value.toBooleanStrictOrNull() == true
            val positive = (operator == ConditionOperator.IS) == yes
            when (field) {
                ConditionField.IN_CONTACTS -> if (positive) "caller is in contacts" else "caller is not in contacts"
                ConditionField.HIDDEN_NUMBER -> if (positive) "number is hidden" else "number is shown"
                else -> if (positive) "caller has a relationship" else "caller has no relationship"
            }
        }
    }
}

enum class MatchMode { ALL, ANY }

/** What Android can do with a ringing call. The app cannot answer or talk. */
enum class ScreeningAction(val label: String) {
    ALLOW("Ring normally"),
    SILENCE("Silence"),
    REJECT("Reject"),
}

data class CallRule(
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    /** Lower runs first; the first matching rule decides. */
    val priority: Int = 100,
    val matchMode: MatchMode = MatchMode.ALL,
    val conditions: List<Condition>,
    val action: ScreeningAction,
    val notify: Boolean,
    val builtIn: Boolean = false,
) {
    fun matches(caller: IncomingCaller): Boolean = when {
        conditions.isEmpty() -> true
        matchMode == MatchMode.ALL -> conditions.all { it.matches(caller) }
        else -> conditions.any { it.matches(caller) }
    }

    fun describe(): String {
        val joiner = if (matchMode == MatchMode.ALL) " AND " else " OR "
        val cond = conditions.joinToString(joiner) { it.describe() }.ifEmpty { "any call" }
        return "IF $cond THEN ${action.label.lowercase()}${if (notify) " and notify" else ""}"
    }
}

data class ScreeningDecision(
    val action: ScreeningAction,
    val notify: Boolean,
    val ruleName: String?,
    val reason: String,
)

object RuleEngine {

    /** The first enabled matching rule (by priority) decides. With no match the call rings normally. */
    fun evaluate(caller: IncomingCaller, rules: List<CallRule>): ScreeningDecision {
        val rule = rules.filter { it.enabled }
            .sortedWith(compareBy<CallRule> { it.priority }.thenBy { it.id })
            .firstOrNull { it.matches(caller) }
            ?: return ScreeningDecision(ScreeningAction.ALLOW, notify = false, ruleName = null, reason = "No rule matched, so the call rang normally.")
        return ScreeningDecision(rule.action, rule.notify, rule.name, "Rule \"${rule.name}\": ${rule.describe()}.")
    }

    /**
     * Rules created on first launch. Unknown callers ring and notify; spam is silenced;
     * blocked numbers are rejected; important callers notify. All can be edited or turned off.
     */
    fun defaultRules(): List<CallRule> = listOf(
        CallRule(
            name = "Blocked numbers", priority = 10, builtIn = true,
            conditions = listOf(Condition(ConditionField.CATEGORY, value = CallerCategory.BLOCKED.name)),
            action = ScreeningAction.REJECT, notify = true,
        ),
        CallRule(
            name = "Spam", priority = 20, builtIn = true,
            conditions = listOf(Condition(ConditionField.CATEGORY, value = CallerCategory.SPAM.name)),
            action = ScreeningAction.SILENCE, notify = true,
        ),
        CallRule(
            name = "Important callers", priority = 30, builtIn = true,
            conditions = listOf(Condition(ConditionField.CATEGORY, value = CallerCategory.IMPORTANT.name)),
            action = ScreeningAction.ALLOW, notify = true,
        ),
        CallRule(
            name = "Contacts", priority = 40, builtIn = true,
            conditions = listOf(Condition(ConditionField.IN_CONTACTS, value = "true")),
            action = ScreeningAction.ALLOW, notify = false,
        ),
        CallRule(
            name = "Hidden numbers", priority = 50, builtIn = true,
            conditions = listOf(Condition(ConditionField.HIDDEN_NUMBER, value = "true")),
            action = ScreeningAction.ALLOW, notify = true,
        ),
        CallRule(
            name = "Unknown callers", priority = 90, builtIn = true,
            conditions = listOf(Condition(ConditionField.IN_CONTACTS, value = "false")),
            action = ScreeningAction.ALLOW, notify = true,
        ),
    )
}

/** Stores rule conditions as text, one "FIELD|OPERATOR|VALUE" per line. */
object ConditionCodec {
    fun encode(conditions: List<Condition>): String =
        conditions.joinToString("\n") { "${it.field.name}|${it.operator.name}|${it.value.replace("|", "").replace("\n", "")}" }

    fun decode(text: String): List<Condition> = text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
        val parts = line.split("|", limit = 3)
        val field = ConditionField.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: return@mapNotNull null
        val op = ConditionOperator.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: ConditionOperator.IS
        Condition(field, op, parts.getOrNull(2).orEmpty())
    }
}
