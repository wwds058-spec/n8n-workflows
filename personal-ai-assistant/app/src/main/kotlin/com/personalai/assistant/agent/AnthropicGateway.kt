package com.personalai.assistant.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.MessageCreateParams
import com.personalai.assistant.core.MessageGateway
import java.time.Duration

class MissingApiKeyException : IllegalStateException("Add your Anthropic API key in Settings.")

/** Calls the Claude Messages API with the key from Settings. Rebuilds the client when the key changes. */
class AnthropicGateway(private val apiKey: () -> String) : MessageGateway {

    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    override fun create(params: MessageCreateParams): BetaMessage {
        val key = apiKey().trim()
        if (key.isEmpty()) throw MissingApiKeyException()
        val c = synchronized(this) {
            if (client == null || clientKey != key) {
                client?.close()
                client = AnthropicOkHttpClient.builder()
                    .apiKey(key)
                    .timeout(Duration.ofMinutes(3))
                    .maxRetries(2)
                    .build()
                clientKey = key
            }
            client!!
        }
        return c.beta().messages().create(params)
    }

    companion object {
        /** A message the user can act on, for errors from the API or the network. */
        fun describe(e: Throwable): String = when (e) {
            is MissingApiKeyException -> e.message!!
            is UnauthorizedException -> "The API key was rejected. Check it in Settings."
            is PermissionDeniedException -> "This API key isn't allowed to use the selected model."
            is RateLimitException -> "Too many requests right now. Wait a moment and try again."
            is BadRequestException -> "The request was rejected: ${e.message}"
            is InternalServerException -> "The AI service had a problem. Try again shortly."
            is AnthropicServiceException -> "The AI service returned an error (${e.statusCode()})."
            is AnthropicIoException -> "Couldn't reach the AI service. Check the internet connection."
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
