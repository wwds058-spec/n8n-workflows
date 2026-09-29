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
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams

/** Sends one Messages API request. Swappable so the agent loop can be tested offline. */
fun interface MessageGateway {
    fun create(params: MessageCreateParams): BetaMessage
}

data class AnthropicOptions(
    val model: String = DEFAULT_MODEL,
    val effort: BetaOutputConfig.Effort = BetaOutputConfig.Effort.MEDIUM,
    val maxTokens: Long = 16_000L,
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

/** The Claude Messages API, with adaptive thinking, effort control and server-side web search. */
class AnthropicBackend(
    private val gateway: MessageGateway,
    private val options: () -> AnthropicOptions,
) : ChatBackend {

    private val history = mutableListOf<BetaMessageParam>()

    override val supportsWebSearch = true

    override fun reset() = history.clear()

    override fun checkpoint() = history.size

    override fun rollback(to: Int) {
        while (history.size > to) history.removeAt(history.size - 1)
    }

    override fun addUserText(text: String) {
        history += BetaMessageParam.builder().role(BetaMessageParam.Role.USER).content(text).build()
    }

    override fun addAssistantText(text: String) {
        history += BetaMessageParam.builder().role(BetaMessageParam.Role.ASSISTANT).content(text).build()
    }

    override fun keepLastTurns(turns: Int) {
        // Turns start with a user message whose content is plain text (tool results are blocks).
        val starts = history.indices.filter { i ->
            history[i].role() == BetaMessageParam.Role.USER && history[i].content().isString()
        }
        if (starts.size <= turns) return
        if (turns <= 0) {
            history.clear()
            return
        }
        repeat(starts[starts.size - turns]) { history.removeAt(0) }
    }

    override fun addToolResults(results: List<ToolResult>) {
        val blocks = results.map { r ->
            BetaContentBlockParam.ofToolResult(
                BetaToolResultBlockParam.builder()
                    .toolUseId(r.callId)
                    .content(r.content)
                    .isError(r.isError)
                    .build(),
            )
        }
        history += BetaMessageParam.builder()
            .role(BetaMessageParam.Role.USER)
            .contentOfBetaContentBlockParams(blocks)
            .build()
    }

    override fun next(request: ModelRequest): ModelStep {
        val message = gateway.create(buildParams(request, options()))
        val stopReason = message.stopReason().orElse(null)
        if (stopReason == BetaStopReason.REFUSAL) {
            val why = message.stopDetails().flatMap { it.explanation() }.orElse(null)
            return ModelStep("", emptyList(), StopKind.REFUSAL, why)
        }

        history += message.toParam()
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()
        val calls = message.content().mapNotNull { it.toolUse().orElse(null) }.map { use ->
            @Suppress("UNCHECKED_CAST")
            val input = runCatching { use._input().convert(Map::class.java) as Map<String, Any?>? }.getOrNull()
            ToolCall(use.id(), use.name(), input)
        }
        val stop = when (stopReason) {
            BetaStopReason.TOOL_USE -> StopKind.TOOL_USE
            BetaStopReason.PAUSE_TURN -> StopKind.CONTINUE
            BetaStopReason.MAX_TOKENS -> StopKind.MAX_TOKENS
            else -> StopKind.END
        }
        return ModelStep(text, calls, stop)
    }

    private fun buildParams(request: ModelRequest, opts: AnthropicOptions): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(opts.model)
            .maxTokens(opts.maxTokens)
            .system(request.system)
            .thinking(BetaThinkingConfigAdaptive.builder().build())
            .outputConfig(BetaOutputConfig.builder().effort(opts.effort).build())
            .messages(history.toList())

        if (opts.supportsFallbacks) {
            // If a safety classifier declines, the API retries on the recommended model.
            builder.addBeta(FALLBACK_BETA).fallbacksDefault()
        }

        request.tools.forEach { builder.addTool(it.toBetaTool()) }
        if (request.webSearch) {
            builder.addTool(BetaWebSearchTool20260209.builder().maxUses(5L).build())
        }
        return builder.build()
    }

    companion object {
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    }
}

internal fun ToolSpec.toBetaTool(): BetaTool {
    val properties = BetaTool.InputSchema.Properties.builder()
    params.forEach { p -> properties.putAdditionalProperty(p.name, JsonValue.from(p.jsonSchema())) }
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

internal fun ToolParam.jsonSchema(): Map<String, Any> = buildMap {
    put("type", type)
    put("description", description)
    enumValues?.let { put("enum", it) }
}
