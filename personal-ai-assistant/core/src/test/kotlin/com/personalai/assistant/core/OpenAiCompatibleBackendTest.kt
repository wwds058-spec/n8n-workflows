package com.personalai.assistant.core

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiCompatibleBackendTest {

    private val json = ObjectMapper()

    private class ScriptedTransport(vararg responses: HttpResponse) : HttpTransport {
        private val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<Pair<String, String?>>()
        val headers = mutableListOf<Map<String, String>>()
        override fun send(url: String, headers: Map<String, String>, body: String?): HttpResponse {
            requests += url to body
            this.headers += headers
            return queue.removeFirstOrNull() ?: error("No scripted response left")
        }
    }

    private class RecordingTool : AssistantTool {
        val inputs = mutableListOf<String?>()
        override val spec = ToolSpec(
            "call_contact", "Start a call.",
            listOf(ToolParam("contact", "string", "Who"), ToolParam("note", "string", "Optional", required = false)),
            PermissionLevel.AUTOMATIC,
        )
        override suspend fun prepare(input: ToolInput) = ToolPlan.Ready("Call ${input.string("contact")}") {
            inputs += input.string("contact")
            ToolOutcome.Success("Dialing ${input.string("contact")}.")
        }
    }

    private fun ok(body: String) = HttpResponse(200, body)

    private fun backend(transport: HttpTransport, key: String = "test-key") =
        OpenAiCompatibleBackend("Groq", transport) { Endpoint("https://api.example.com/v1", key, "some-model") }

    private fun agent(b: ChatBackend, tool: AssistantTool, dispatcher: TestDispatcher) = AssistantAgent(
        backend = { b },
        tools = ToolRegistry(listOf(tool)),
        confirmations = { true },
        logger = { },
        policy = { PermissionPolicy() },
        config = { AgentConfig() },
        context = { PromptContext("Yasin", "Personal AI", emptyList(), voiceMode = false) },
        clock = { ZonedDateTime.of(2026, 9, 29, 9, 0, 0, 0, ZoneId.of("Asia/Kolkata")) },
        ioContext = dispatcher,
    )

    @Test
    fun `tool call round trip uses the OpenAI format`() = runTest {
        val transport = ScriptedTransport(
            ok(
                """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                   "reasoning":"thinking out loud",
                   "tool_calls":[{"id":"","type":"function","function":{"name":"call_contact","arguments":"{\"contact\":\"Ahmed\"}"},
                                  "extra_content":{"google":{"thought_signature":"sig123"}}}]}}]}""",
            ),
            ok("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Dialing Ahmed now."}}]}"""),
        )
        val tool = RecordingTool()
        val reply = agent(backend(transport), tool, StandardTestDispatcher(testScheduler)).send("Call Ahmed")

        assertEquals("Dialing Ahmed now.", reply.text)
        assertEquals(listOf<String?>("Ahmed"), tool.inputs)
        assertEquals("https://api.example.com/v1/chat/completions", transport.requests[0].first)
        assertEquals("Bearer test-key", transport.headers[0]["Authorization"])

        // First request: system prompt, user message, function tool schema.
        val first = json.readTree(transport.requests[0].second)
        assertEquals("some-model", first["model"].asText())
        assertEquals("system", first["messages"][0]["role"].asText())
        assertTrue(first["messages"][1]["content"].asText().endsWith("Call Ahmed"))
        val fn = first["tools"][0]["function"]
        assertEquals("call_contact", fn["name"].asText())
        assertEquals(listOf("contact"), fn["parameters"]["required"].map { it.asText() })
        assertFalse(first["messages"][0]["content"].asText().contains("Use web search"))

        // Second request: the assistant tool call (with a generated id and Gemini's signature,
        // without Groq's reasoning text) and the matching tool result.
        val second = json.readTree(transport.requests[1].second)["messages"]
        val assistant = second[2]
        assertFalse(assistant.has("reasoning"))
        val call = assistant["tool_calls"][0]
        val id = call["id"].asText()
        assertTrue(id.startsWith("call_"))
        assertEquals("sig123", call["extra_content"]["google"]["thought_signature"].asText())
        val result = second[3]
        assertEquals("tool", result["role"].asText())
        assertEquals(id, result["tool_call_id"].asText())
        assertEquals("Dialing Ahmed.", result["content"].asText())
    }

    @Test
    fun `malformed tool call from Groq is retried once`() = runTest {
        val transport = ScriptedTransport(
            HttpResponse(400, """{"error":{"message":"Failed to call a function","code":"tool_use_failed"}}"""),
            ok("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Hi!"}}]}"""),
        )
        val reply = agent(backend(transport), RecordingTool(), StandardTestDispatcher(testScheduler)).send("Hello")
        assertEquals("Hi!", reply.text)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `errors are classified`() = runTest {
        val cases = mapOf(
            401 to ProviderException.Kind.AUTH,
            429 to ProviderException.Kind.RATE_LIMIT,
            503 to ProviderException.Kind.SERVER,
            404 to ProviderException.Kind.BAD_REQUEST,
        )
        cases.forEach { (code, kind) ->
            val transport = ScriptedTransport(HttpResponse(code, """[{"error":{"message":"nope"}}]"""))
            val e = assertFailsWith<ProviderException> {
                agent(backend(transport), RecordingTool(), StandardTestDispatcher(testScheduler)).send("Hi")
            }
            assertEquals(kind, e.kind, "HTTP $code")
        }
    }

    @Test
    fun `failed turn leaves history valid for the next one`() = runTest {
        val transport = ScriptedTransport(
            HttpResponse(429, """{"error":{"message":"slow down"}}"""),
            ok("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Hi!"}}]}"""),
        )
        val a = agent(backend(transport), RecordingTool(), StandardTestDispatcher(testScheduler))
        assertFailsWith<ProviderException> { a.send("first") }
        a.send("second")
        val messages = json.readTree(transport.requests[1].second)["messages"]
        assertEquals(2, messages.size()) // system + the second user message only
    }

    @Test
    fun `missing key is reported without a network call`() = runTest {
        val transport = ScriptedTransport()
        val e = assertFailsWith<ProviderException> {
            agent(backend(transport, key = ""), RecordingTool(), StandardTestDispatcher(testScheduler)).send("Hi")
        }
        assertEquals(ProviderException.Kind.AUTH, e.kind)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `model list drops prefixes and non-chat models`() {
        val transport = ScriptedTransport(
            ok(
                """{"data":[{"id":"models/gemini-2.5-flash"},{"id":"models/text-embedding-004"},
                   {"id":"models/gemini-2.5-pro"},{"id":"whisper-large-v3"}]}""",
            ),
        )
        assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-pro"), backend(transport).listModels())
        assertEquals(null, transport.requests.single().second) // GET
    }
}
