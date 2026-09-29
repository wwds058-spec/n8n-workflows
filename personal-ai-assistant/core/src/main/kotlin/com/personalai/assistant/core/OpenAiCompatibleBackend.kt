package com.personalai.assistant.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Duration
import java.util.UUID

data class HttpResponse(val code: Int, val body: String)

/** Performs one HTTP request. Swappable so the backend can be tested offline. */
fun interface HttpTransport {
    /** [body] null means GET, otherwise a JSON POST. */
    fun send(url: String, headers: Map<String, String>, body: String?): HttpResponse
}

class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofMinutes(2))
        .build(),
) : HttpTransport {
    override fun send(url: String, headers: Map<String, String>, body: String?): HttpResponse {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (body != null) post(body.toRequestBody("application/json".toMediaType()))
        }.build()
        client.newCall(request).execute().use { response ->
            return HttpResponse(response.code, response.body?.string().orEmpty())
        }
    }
}

data class Endpoint(val baseUrl: String, val apiKey: String, val model: String)

/**
 * Any service with an OpenAI-style chat completions API and function calling. Used for
 * Google Gemini (its OpenAI compatibility endpoint) and Groq.
 */
class OpenAiCompatibleBackend(
    private val serviceName: String,
    private val transport: HttpTransport,
    private val endpoint: () -> Endpoint,
) : ChatBackend {

    private val json = ObjectMapper()
    private val history = mutableListOf<ObjectNode>()

    override val supportsWebSearch = false

    override fun reset() = history.clear()

    override fun checkpoint() = history.size

    override fun rollback(to: Int) {
        while (history.size > to) history.removeAt(history.size - 1)
    }

    override fun addUserText(text: String) {
        history += json.createObjectNode().put("role", "user").put("content", text)
    }

    override fun addAssistantText(text: String) {
        history += json.createObjectNode().put("role", "assistant").put("content", text)
    }

    override fun keepLastTurns(turns: Int) {
        val starts = history.indices.filter { history[it].path("role").asText() == "user" }
        if (starts.size <= turns) return
        if (turns <= 0) {
            history.clear()
            return
        }
        repeat(starts[starts.size - turns]) { history.removeAt(0) }
    }

    override fun addToolResults(results: List<ToolResult>) {
        results.forEach { r ->
            history += json.createObjectNode()
                .put("role", "tool")
                .put("tool_call_id", r.callId)
                .put("name", r.toolName)
                .put("content", if (r.isError) "ERROR: ${r.content}" else r.content)
        }
    }

    override fun next(request: ModelRequest): ModelStep {
        val ep = endpoint()
        requireKey(ep)
        val body = buildRequest(request, ep.model).toString()

        var response = call("${ep.baseUrl}/chat/completions", ep, body)
        // Some open models occasionally emit a malformed tool call, which Groq rejects with
        // "tool_use_failed". A second attempt usually succeeds.
        if (response.code == 400 && response.body.contains("tool_use_failed")) {
            response = call("${ep.baseUrl}/chat/completions", ep, body)
        }
        if (response.code !in 200..299) throw errorFor(response)

        val choice = parse(response.body).path("choices").path(0)
        val message = choice.path("message")
        if (message.isMissingNode) throw ProviderException(ProviderException.Kind.SERVER, "$serviceName returned an empty response.")

        val finish = choice.path("finish_reason").asText("")
        if (finish == "content_filter" || finish == "safety") {
            return ModelStep("", emptyList(), StopKind.REFUSAL, "$serviceName's safety filter blocked the reply.")
        }

        val stored = json.createObjectNode().put("role", "assistant")
        val text = message.path("content").takeIf { it.isTextual }?.asText().orEmpty()
        stored.put("content", text)

        val calls = mutableListOf<ToolCall>()
        val rawCalls = message.path("tool_calls")
        if (rawCalls.isArray && rawCalls.size() > 0) {
            val kept: ArrayNode = stored.putArray("tool_calls")
            rawCalls.forEach { raw ->
                val id = raw.path("id").asText("").ifBlank { "call_${UUID.randomUUID()}" }
                val name = raw.path("function").path("name").asText("")
                val args = raw.path("function").path("arguments")
                val argsText = if (args.isTextual) args.asText() else args.toString()
                // Keep only standard fields, plus Gemini's extra_content (it carries the
                // thought signature Gemini needs to see again on the next request).
                val call = kept.addObject().put("id", id).put("type", "function")
                call.putObject("function").put("name", name).put("arguments", argsText.ifBlank { "{}" })
                raw.get("extra_content")?.let { call.set<JsonNode>("extra_content", it) }
                calls += ToolCall(id, name, parseArguments(argsText))
            }
        }
        history += stored

        val stop = when {
            calls.isNotEmpty() -> StopKind.TOOL_USE
            finish == "length" -> StopKind.MAX_TOKENS
            else -> StopKind.END
        }
        return ModelStep(text.trim(), calls, stop)
    }

    /** Model ids available to this key, for the settings screen. */
    fun listModels(): List<String> {
        val ep = endpoint()
        requireKey(ep)
        val response = try {
            transport.send("${ep.baseUrl}/models", headers(ep), null)
        } catch (e: IOException) {
            throw ProviderException(ProviderException.Kind.NETWORK, "Couldn't reach $serviceName.", e)
        }
        if (response.code !in 200..299) throw errorFor(response)
        return parse(response.body).path("data")
            .mapNotNull { it.path("id").asText(null)?.removePrefix("models/") }
            .filterNot { id -> NOT_CHAT.any { id.contains(it, ignoreCase = true) } }
            .distinct()
            .sorted()
    }

    private fun buildRequest(request: ModelRequest, model: String): ObjectNode {
        val root = json.createObjectNode().put("model", model)
        val messages = root.putArray("messages")
        messages.addObject().put("role", "system").put("content", request.system)
        history.forEach { messages.add(it) }

        if (request.tools.isNotEmpty()) {
            val tools = root.putArray("tools")
            request.tools.forEach { spec ->
                val fn = tools.addObject().put("type", "function").putObject("function")
                fn.put("name", spec.name).put("description", spec.description)
                val params = fn.putObject("parameters").put("type", "object")
                val props = params.putObject("properties")
                spec.params.forEach { p -> props.set<JsonNode>(p.name, json.valueToTree(p.jsonSchema())) }
                val required = params.putArray("required")
                spec.params.filter { it.required }.forEach { required.add(it.name) }
            }
            root.put("tool_choice", "auto")
        }
        return root
    }

    private fun call(url: String, ep: Endpoint, body: String): HttpResponse = try {
        transport.send(url, headers(ep), body)
    } catch (e: IOException) {
        throw ProviderException(ProviderException.Kind.NETWORK, "Couldn't reach $serviceName. Check the internet connection.", e)
    }

    private fun headers(ep: Endpoint) = mapOf("Authorization" to "Bearer ${ep.apiKey}")

    private fun requireKey(ep: Endpoint) {
        if (ep.apiKey.isBlank()) {
            throw ProviderException(ProviderException.Kind.AUTH, "Add your $serviceName API key in Settings.")
        }
    }

    private fun parse(body: String): JsonNode = try {
        json.readTree(body)
    } catch (e: Exception) {
        throw ProviderException(ProviderException.Kind.SERVER, "$serviceName sent a response the app couldn't read.", e)
    }

    private fun parseArguments(text: String): Map<String, Any?>? = try {
        val node = json.readTree(text.ifBlank { "{}" })
        @Suppress("UNCHECKED_CAST")
        if (node.isObject) json.convertValue(node, Map::class.java) as Map<String, Any?> else null
    } catch (e: Exception) {
        null
    }

    private fun errorFor(response: HttpResponse): ProviderException {
        val detail = runCatching {
            val root = json.readTree(response.body)
            // Gemini sometimes wraps the error in a one-element array.
            val err = if (root.isArray) root.path(0).path("error") else root.path("error")
            err.path("message").asText(null)
        }.getOrNull()?.take(300)

        val kind = when (response.code) {
            401, 403 -> ProviderException.Kind.AUTH
            429 -> ProviderException.Kind.RATE_LIMIT
            in 500..599 -> ProviderException.Kind.SERVER
            else -> ProviderException.Kind.BAD_REQUEST
        }
        val message = when (kind) {
            ProviderException.Kind.AUTH -> "$serviceName rejected the API key. Check it in Settings."
            ProviderException.Kind.RATE_LIMIT -> "$serviceName's free-tier limit was reached. Wait a minute and try again."
            ProviderException.Kind.SERVER -> "$serviceName had a problem (${response.code}). Try again shortly."
            else -> if (response.code == 404) {
                "$serviceName doesn't have that model. Pick another in Settings with \"Check key & list models\"."
            } else {
                "$serviceName rejected the request (${response.code})${detail?.let { ": $it" } ?: "."}"
            }
        }
        return ProviderException(kind, message)
    }

    private companion object {
        /** Model ids that can't hold a chat with tools. */
        val NOT_CHAT = listOf("embed", "tts", "whisper", "guard", "imagen", "image", "veo", "aqa", "audio", "live", "transcribe", "antigravity", "deep-research", "robotics", "computer-use")
    }
}
