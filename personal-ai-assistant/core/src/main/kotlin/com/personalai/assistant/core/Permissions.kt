package com.personalai.assistant.core

/**
 * How much user involvement an action needs before it runs (spec §14).
 */
enum class PermissionLevel(val number: Int, val label: String) {
    /** Read-only or easily undone: read calendar, create a reminder, save a memory. */
    AUTOMATIC(1, "Automatic"),

    /** Confirmed by default; the user may switch individual tools to automatic. */
    USER_CONFIGURABLE(2, "User configurable"),

    /** Sends something to another person or changes shared data. */
    CONFIRMATION_REQUIRED(3, "Confirmation required"),

    /** Destructive or sensitive. Can never be switched to automatic. */
    ALWAYS_CONFIRM(4, "Always confirm"),
}

/**
 * Decides whether an action needs the user's confirmation before it runs.
 *
 * @param autoApproved names of [PermissionLevel.USER_CONFIGURABLE] tools the user has
 *   chosen to run without asking. Ignored for every other level.
 */
class PermissionPolicy(private val autoApproved: Set<String> = emptySet()) {

    fun requiresConfirmation(spec: ToolSpec): Boolean = when (spec.level) {
        PermissionLevel.AUTOMATIC -> false
        PermissionLevel.USER_CONFIGURABLE -> spec.name !in autoApproved
        PermissionLevel.CONFIRMATION_REQUIRED, PermissionLevel.ALWAYS_CONFIRM -> true
    }
}

/** What the user is shown when an action needs approval. */
data class ActionRequest(
    val toolName: String,
    val title: String,
    val detail: String,
    val level: PermissionLevel,
)

/** Asks the user to approve an action. Implemented by the UI. */
fun interface ConfirmationGate {
    /** Suspends until the user answers. Returns true only on explicit approval. */
    suspend fun confirm(request: ActionRequest): Boolean
}
