package com.personalai.assistant.core

/** A single input parameter of a tool, rendered into the tool's JSON schema. */
data class ToolParam(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String>? = null,
)

/** Everything the model and the permission layer need to know about a tool. */
data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam>,
    val level: PermissionLevel,
)

/**
 * Result of running a tool. The agent reports only what the tool returns here, so a
 * tool must return [Success] only after it has checked that the action took effect
 * (spec §24).
 */
sealed interface ToolOutcome {
    val message: String

    data class Success(override val message: String) : ToolOutcome
    data class Failure(override val message: String) : ToolOutcome
}

/**
 * The result of preparing a tool call. Preparing resolves inputs (for example a contact
 * name to a phone number) so the confirmation dialog can show exactly what will happen.
 */
sealed interface ToolPlan {
    /** Ready to run. [preview] is shown to the user when confirmation is required. */
    class Ready(
        val preview: String,
        val run: suspend () -> ToolOutcome,
    ) : ToolPlan

    /** Cannot run, for example an ambiguous contact. Sent back to the model as-is. */
    class Rejected(val outcome: ToolOutcome.Failure) : ToolPlan
}

/**
 * A capability the assistant may request. The model never gets direct access to the
 * phone; it can only ask for one of these, and the agent checks permissions first.
 */
interface AssistantTool {
    val spec: ToolSpec

    suspend fun prepare(input: ToolInput): ToolPlan
}

/** Typed access to the JSON object the model sent as tool input. */
class ToolInput(private val values: Map<String, Any?>) {

    fun string(name: String): String? =
        values[name]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    fun requireString(name: String): String =
        string(name) ?: throw IllegalArgumentException("Missing required input \"$name\".")

    fun int(name: String): Int? = when (val v = values[name]) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    fun boolean(name: String): Boolean? = when (val v = values[name]) {
        is Boolean -> v
        is String -> v.trim().lowercase().toBooleanStrictOrNull()
        else -> null
    }

    override fun toString(): String = values.toString()
}

class ToolRegistry(tools: List<AssistantTool>) {
    private val byName: Map<String, AssistantTool> = tools.associateBy { it.spec.name }

    init {
        require(byName.size == tools.size) { "Tool names must be unique." }
    }

    val all: Collection<AssistantTool> get() = byName.values

    operator fun get(name: String): AssistantTool? = byName[name]
}
