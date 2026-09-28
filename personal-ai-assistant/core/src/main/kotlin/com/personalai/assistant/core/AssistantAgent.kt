package com.personalai.assistant.core

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.CoroutineContext

/** Sends one Messages API request. Swappable so the agent loop can be tested offline. */
fun interface MessageGateway {
    fun create(params: MessageCreateParams): BetaMessage
}

data class AgentConfig(
    val model: String = DEFAULT_MODEL,
    val effort: BetaOutputConfig.Effort = BetaOutputConfig.Effort.MEDIUM,
    val maxTokens: Long = 16_000L,
    val webSearch: Boolean = true,
    val maxSteps: Int = 10,
) {
    /** Server-side refusal fallbacks are only offered on some models. */
    val supportsFallbacks: Boolean get() = model in FALLBACK_MODELS

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"

        /** Models the settings screen offers. Both support adaptive thinking and effort. */
        val MODELS = listOf("claude-opus-5" to "Claude Opus 5 (best)", "claude-sonnet-5" to "Claude Sonnet 5 (faster, cheaper)")

        private val FALLBACK_MODELS = setOf("claude-opus-5", "claude-opus-5-5", "claude-fable-5-1")
    }
}

/** Something the agent did (or tried to do) while handling a request. */
data class PerformedAction(val toolName: String, val summary: String, val status: ActionStatus)

data class AgentReply(val text: String, val actions: List<PerformedAction>)

/**
 * The assistant's reasoning loop (spec §5, §15, §29):
 * UNDERSTAND -> PLAN -> ASK PERMISSION -> ACT -> VERIFY -> REPORT.
 *
 * The model can only request tools from [tools]. Every request passes through
 * [policy] and, when needed, [confirmations] before anything runs on the phone.
 */
class AssistantAgent(
    private val gateway: MessageGateway,
    private val tools: ToolRegistry,
    private val confirmations: ConfirmationGate,
    private val logger: ActionLogger,
    private val policy: () -> PermissionPolicy,
    private val config: () -> AgentConfig,
    private val context: suspend () -> PromptContext,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    private val history = mutableListOf<BetaMessageParam>()
    private val lock = Mutex()

    fun reset() {
        history.clear()
    }

    suspend fun send(userText: String): AgentReply = lock.withLock {
        val checkpoint = history.size
        try {
            runTurn(userText)
        } catch (e: Throwable) {
            // Leave the history as it was before this turn so that a half-finished tool
            // exchange can't make every later request invalid.
            while (history.size > checkpoint) history.removeAt(history.size - 1)
            throw e
        }
    }

    private suspend fun runTurn(userText: String): AgentReply {
        val cfg = config()
        val ctx = context()
        val actions = mutableListOf<PerformedAction>()
        val now = clock().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm (VV)"))

        val turnStart = history.size
        history += userMessage("[Current time: $now]\n$userText")

        repeat(cfg.maxSteps) {
            val message = withContext(ioContext) { gateway.create(buildParams(cfg, ctx)) }
            val stopReason = message.stopReason().orElse(null)

            if (stopReason == BetaStopReason.REFUSAL) {
                // Drop the whole turn so no unanswered tool call is left in the history.
                while (history.size > turnStart) history.removeAt(history.size - 1)
                val why = message.stopDetails().flatMap { it.explanation() }.orElse(null)
                return AgentReply(
                    listOfNotNull("I can't help with that request.", why).joinToString(" "),
                    actions,
                )
            }

            history += message.toParam()
            val text = message.content().mapNotNull { it.text().orElse(null)?.text() }
                .joinToString("\n").trim()

            when (stopReason) {
                BetaStopReason.TOOL_USE -> {
                    val toolUses = message.content().mapNotNull { it.toolUse().orElse(null) }
                    val results = toolUses.map { runTool(it, actions) }
                    history += BetaMessageParam.builder()
                        .role(BetaMessageParam.Role.USER)
                        .contentOfBetaContentBlockParams(results)
                        .build()
                }
                // A server tool (web search) paused a long turn; send it back to continue.
                BetaStopReason.PAUSE_TURN -> Unit
                BetaStopReason.MAX_TOKENS ->
                    return AgentReply(text.ifEmpty { "My answer was cut off. Please try again." }, actions)
                else -> return AgentReply(text, actions)
            }
        }
        return AgentReply(
            "I stopped because this took too many steps. Here's what I did so far.",
            actions,
        )
    }

    private suspend fun runTool(
        use: BetaToolUseBlock,
        actions: MutableList<PerformedAction>,
    ): BetaContentBlockParam {
        val tool = tools[use.name()]
            ?: return toolResult(use.id(), "Unknown tool \"${use.name()}\".", isError = true)

        val input = try {
            @Suppress("UNCHECKED_CAST")
            ToolInput(use._input().convert(Map::class.java) as Map<String, Any?>? ?: emptyMap())
        } catch (e: Exception) {
            return toolResult(use.id(), "Tool input was not a JSON object.", isError = true)
        }

        // Tools query content providers and the database; keep that off the main thread.
        val plan = try {
            withContext(ioContext) { tool.prepare(input) }
        } catch (e: Exception) {
            ToolPlan.Rejected(ToolOutcome.Failure(e.message ?: "Could not prepare this action."))
        }

        when (plan) {
            is ToolPlan.Rejected -> {
                record(tool.spec, plan.outcome.message, ActionStatus.REJECTED, plan.outcome.message, actions)
                return toolResult(use.id(), plan.outcome.message, isError = true)
            }
            is ToolPlan.Ready -> {
                if (policy().requiresConfirmation(tool.spec)) {
                    val approved = confirmations.confirm(
                        ActionRequest(tool.spec.name, tool.spec.description.substringBefore('.'), plan.preview, tool.spec.level),
                    )
                    if (!approved) {
                        val msg = "The user declined this action. Do not retry it unless they ask again."
                        record(tool.spec, plan.preview, ActionStatus.DECLINED_BY_USER, msg, actions)
                        return toolResult(use.id(), msg, isError = true)
                    }
                }
                val outcome = try {
                    withContext(ioContext) { plan.run() }
                } catch (e: Exception) {
                    ToolOutcome.Failure(e.message ?: e.javaClass.simpleName)
                }
                val status = if (outcome is ToolOutcome.Success) ActionStatus.SUCCEEDED else ActionStatus.FAILED
                record(tool.spec, plan.preview, status, outcome.message, actions)
                return toolResult(use.id(), outcome.message, isError = outcome !is ToolOutcome.Success)
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

    private fun buildParams(cfg: AgentConfig, ctx: PromptContext): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(cfg.model)
            .maxTokens(cfg.maxTokens)
            .system(SystemPrompt.build(ctx))
            .thinking(BetaThinkingConfigAdaptive.builder().build())
            .outputConfig(BetaOutputConfig.builder().effort(cfg.effort).build())
            .messages(history.toList())

        if (cfg.supportsFallbacks) {
            // If a safety classifier declines, the API retries on the recommended model.
            builder.addBeta(FALLBACK_BETA).fallbacksDefault()
        }

        tools.all.forEach { builder.addTool(it.spec.toBetaTool()) }
        if (cfg.webSearch) {
            builder.addTool(BetaWebSearchTool20260209.builder().maxUses(5L).build())
        }
        return builder.build()
    }

    companion object {
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        private fun userMessage(text: String): BetaMessageParam =
            BetaMessageParam.builder().role(BetaMessageParam.Role.USER).content(text).build()

        private fun toolResult(id: String, content: String, isError: Boolean): BetaContentBlockParam =
            BetaContentBlockParam.ofToolResult(
                BetaToolResultBlockParam.builder()
                    .toolUseId(id)
                    .content(content)
                    .isError(isError)
                    .build(),
            )
    }
}

internal fun ToolSpec.toBetaTool(): BetaTool {
    val properties = BetaTool.InputSchema.Properties.builder()
    params.forEach { p ->
        val schema = buildMap<String, Any> {
            put("type", p.type)
            put("description", p.description)
            p.enumValues?.let { put("enum", it) }
        }
        properties.putAdditionalProperty(p.name, JsonValue.from(schema))
    }
    return BetaTool.builder()
        .name(name)
        .description(description)
        .inputSchema(
            BetaTool.InputSchema.builder()
                .properties(properties.build())
                .required(params.filter { it.required }.map { it.name })
                .build(),
        )
        .build()
}
