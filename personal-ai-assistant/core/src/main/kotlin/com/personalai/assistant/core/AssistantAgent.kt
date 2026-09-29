package com.personalai.assistant.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.CoroutineContext

data class AgentConfig(
    /** Let the model search the web, when the selected service supports it. */
    val webSearch: Boolean = true,
    val maxSteps: Int = 10,
    /** How many recent exchanges the model sees; older ones are dropped to bound the context. */
    val maxHistoryTurns: Int = 20,
)

/** Something the agent did (or tried to do) while handling a request. */
data class PerformedAction(val toolName: String, val summary: String, val status: ActionStatus)

data class AgentReply(val text: String, val actions: List<PerformedAction>)

/**
 * The assistant's reasoning loop (spec §5, §15, §29):
 * UNDERSTAND -> PLAN -> ASK PERMISSION -> ACT -> VERIFY -> REPORT.
 *
 * The model can only request tools from [tools]. Every request passes through
 * [policy] and, when needed, [confirmations] before anything runs on the phone.
 * [backend] supplies the AI service; switching to another one starts a new conversation.
 */
class AssistantAgent(
    private val backend: () -> ChatBackend,
    private val tools: ToolRegistry,
    private val confirmations: ConfirmationGate,
    private val logger: ActionLogger,
    private val policy: () -> PermissionPolicy,
    private val config: () -> AgentConfig,
    private val context: suspend () -> PromptContext,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    private val lock = Mutex()
    private var active: ChatBackend? = null

    /** Saved turns to load into the next backend used, e.g. after the app restarts. */
    private var pendingRestore: List<ChatTurn> = emptyList()

    fun reset() {
        active?.reset()
        pendingRestore = emptyList()
    }

    /** Rebuilds the model's view of an earlier conversation from saved text messages. */
    fun restore(turns: List<ChatTurn>) {
        // A conversation must start with the user.
        pendingRestore = turns.filter { it.text.isNotBlank() }.dropWhile { !it.fromUser }
        active = null
    }

    suspend fun send(userText: String): AgentReply = lock.withLock {
        val b = backend()
        if (b !== active) {
            b.reset()
            pendingRestore.forEach { if (it.fromUser) b.addUserText(it.text) else b.addAssistantText(it.text) }
            pendingRestore = emptyList()
            active = b
        }
        // Keep room for the turn about to start.
        b.keepLastTurns((config().maxHistoryTurns - 1).coerceAtLeast(0))
        val checkpoint = b.checkpoint()
        try {
            runTurn(b, userText)
        } catch (e: Throwable) {
            // Leave the history as it was before this turn so that a half-finished tool
            // exchange can't make every later request invalid.
            b.rollback(checkpoint)
            throw e
        }
    }

    private suspend fun runTurn(b: ChatBackend, userText: String): AgentReply {
        val cfg = config()
        val webSearch = cfg.webSearch && b.supportsWebSearch
        val request = ModelRequest(
            system = SystemPrompt.build(context().copy(webSearch = webSearch)),
            tools = tools.all.map { it.spec },
            webSearch = webSearch,
        )
        val actions = mutableListOf<PerformedAction>()
        val now = clock().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm (VV)"))

        val turnStart = b.checkpoint()
        b.addUserText("[Current time: $now]\n$userText")

        repeat(cfg.maxSteps) {
            val step = withContext(ioContext) { b.next(request) }
            when (step.stop) {
                StopKind.REFUSAL -> {
                    // Drop the whole turn so no unanswered tool call is left in the history.
                    b.rollback(turnStart)
                    return AgentReply(
                        listOfNotNull("I can't help with that request.", step.refusalReason).joinToString(" "),
                        actions,
                    )
                }
                StopKind.TOOL_USE -> {
                    if (step.toolCalls.isEmpty()) return AgentReply(step.text, actions)
                    b.addToolResults(step.toolCalls.map { runTool(it, actions) })
                }
                StopKind.CONTINUE -> Unit
                StopKind.MAX_TOKENS ->
                    return AgentReply(step.text.ifEmpty { "My answer was cut off. Please try again." }, actions)
                StopKind.END -> return AgentReply(step.text, actions)
            }
        }
        return AgentReply(
            "I stopped because this took too many steps. Here's what I did so far.",
            actions,
        )
    }

    private suspend fun runTool(call: ToolCall, actions: MutableList<PerformedAction>): ToolResult {
        fun result(content: String, isError: Boolean) = ToolResult(call.id, call.name, content, isError)

        val tool = tools[call.name] ?: return result("Unknown tool \"${call.name}\".", isError = true)
        val input = call.input?.let(::ToolInput)
            ?: return result("Tool input was not a JSON object.", isError = true)

        // Tools query content providers and the database; keep that off the main thread.
        val plan = try {
            withContext(ioContext) { tool.prepare(input) }
        } catch (e: Exception) {
            ToolPlan.Rejected(ToolOutcome.Failure(e.message ?: "Could not prepare this action."))
        }

        when (plan) {
            is ToolPlan.Rejected -> {
                record(tool.spec, plan.outcome.message, ActionStatus.REJECTED, plan.outcome.message, actions)
                return result(plan.outcome.message, isError = true)
            }
            is ToolPlan.Ready -> {
                if (plan.alwaysConfirm || policy().requiresConfirmation(tool.spec)) {
                    val approved = confirmations.confirm(
                        ActionRequest(tool.spec.name, tool.spec.description.substringBefore('.'), plan.preview, tool.spec.level),
                    )
                    if (!approved) {
                        val msg = "The user declined this action. Do not retry it unless they ask again."
                        record(tool.spec, plan.preview, ActionStatus.DECLINED_BY_USER, msg, actions)
                        return result(msg, isError = true)
                    }
                }
                val outcome = try {
                    withContext(ioContext) { plan.run() }
                } catch (e: Exception) {
                    ToolOutcome.Failure(e.message ?: e.javaClass.simpleName)
                }
                val status = if (outcome is ToolOutcome.Success) ActionStatus.SUCCEEDED else ActionStatus.FAILED
                record(tool.spec, plan.preview, status, outcome.message, actions)
                return result(outcome.message, isError = outcome !is ToolOutcome.Success)
            }
        }
    }

    private suspend fun record(
        spec: ToolSpec,
        summary: String,
        status: ActionStatus,
        result: String,
        actions: MutableList<PerformedAction>,
    ) {
        actions += PerformedAction(spec.name, summary, status)
        // Reads are frequent and harmless; the activity log keeps what changed something.
        if (spec.level != PermissionLevel.AUTOMATIC || status != ActionStatus.SUCCEEDED) {
            logger.record(ActionLogEntry(clock().toInstant().toEpochMilli(), spec.name, summary, status, result))
        }
    }
}
