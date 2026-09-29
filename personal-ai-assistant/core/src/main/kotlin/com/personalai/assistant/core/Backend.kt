package com.personalai.assistant.core

/** AI services the assistant can use. */
enum class Provider(
    val label: String,
    val defaultModel: String,
    /** Where the user creates an API key. */
    val keyUrl: String,
    /** OpenAI-compatible endpoint, or null for the Claude API. */
    val baseUrl: String?,
) {
    ANTHROPIC("Claude (Anthropic)", "claude-opus-5", "console.anthropic.com", null),
    GEMINI("Gemini (Google)", "gemini-3.6-flash", "aistudio.google.com/apikey", "https://generativelanguage.googleapis.com/v1beta/openai"),
    GROQ("Groq", "openai/gpt-oss-120b", "console.groq.com/keys", "https://api.groq.com/openai/v1"),
}

/** A tool the model asked to run. [input] is null when the model sent something that isn't a JSON object. */
data class ToolCall(val id: String, val name: String, val input: Map<String, Any?>?)

data class ToolResult(val callId: String, val toolName: String, val content: String, val isError: Boolean)

enum class StopKind {
    /** The model finished its answer. */
    END,

    /** The model wants tools run; see [ModelStep.toolCalls]. */
    TOOL_USE,

    /** The service paused a long turn; call [ChatBackend.next] again to continue. */
    CONTINUE,

    /** The answer hit the length limit. */
    MAX_TOKENS,

    /** The model or a safety filter declined. Nothing was added to the history. */
    REFUSAL,
}

data class ModelStep(
    val text: String,
    val toolCalls: List<ToolCall>,
    val stop: StopKind,
    val refusalReason: String? = null,
)

/** What the agent asks the model for on each step. */
data class ModelRequest(
    val system: String,
    val tools: List<ToolSpec>,
    val webSearch: Boolean,
)

/**
 * One AI service and the conversation history in that service's own format.
 * Methods are called from one coroutine at a time; [next] blocks on the network.
 */
interface ChatBackend {
    /** Whether this service can search the web by itself. */
    val supportsWebSearch: Boolean

    fun reset()

    /** An opaque marker of the current history length, for [rollback]. */
    fun checkpoint(): Int

    fun rollback(to: Int)

    fun addUserText(text: String)

    fun addToolResults(results: List<ToolResult>)

    /** Calls the model with the history. Appends its reply to the history unless it's a refusal. */
    fun next(request: ModelRequest): ModelStep
}

/** A failure talking to an AI service, classified so the app can explain it. */
class ProviderException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { AUTH, RATE_LIMIT, BAD_REQUEST, SERVER, NETWORK }
}
