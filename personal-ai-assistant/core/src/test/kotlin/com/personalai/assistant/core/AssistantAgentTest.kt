package com.personalai.assistant.core

import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.MessageCreateParams
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

class AssistantAgentTest {

    private class FakeTool(
        name: String,
        level: PermissionLevel,
        private val outcome: ToolOutcome = ToolOutcome.Success("done"),
    ) : AssistantTool {
        var runs = 0
        override val spec = ToolSpec(name, "Test tool. Does things.", listOf(ToolParam("who", "string", "Who")), level)
        override suspend fun prepare(input: ToolInput): ToolPlan =
            ToolPlan.Ready("Act on ${input.string("who")}") { runs++; outcome }
    }

    private class ScriptedGateway(vararg responses: String) : MessageGateway {
        private val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<MessageCreateParams>()
        override fun create(params: MessageCreateParams): BetaMessage {
            requests += params
            val next = queue.removeFirstOrNull() ?: error("No scripted response left")
            return jsonMapper().readValue(next, BetaMessage::class.java)
        }
    }

    private fun message(stopReason: String, vararg blocks: String) = """
        {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5",
         "content":[${blocks.joinToString(",")}],
         "stop_reason":"$stopReason","stop_sequence":null,
         "usage":{"input_tokens":10,"output_tokens":5}}
    """.trimIndent()

    private fun text(t: String) = """{"type":"text","text":"$t"}"""
    private fun toolUse(id: String, name: String, who: String) =
        """{"type":"tool_use","id":"$id","name":"$name","input":{"who":"$who"}}"""

    private val logged = mutableListOf<ActionLogEntry>()
    private val asked = mutableListOf<ActionRequest>()

    private fun agent(
        gateway: MessageGateway,
        tool: AssistantTool,
        approve: Boolean = true,
        dispatcher: TestDispatcher,
        model: String = AnthropicOptions.DEFAULT_MODEL,
    ): AssistantAgent {
        val backend = AnthropicBackend(gateway) { AnthropicOptions(model = model) }
        return agentWith(backend, tool, approve, dispatcher)
    }

    private fun agentWith(
        backend: ChatBackend,
        tool: AssistantTool,
        approve: Boolean = true,
        dispatcher: TestDispatcher,
    ) = AssistantAgent(
        backend = { backend },
        tools = ToolRegistry(listOf(tool)),
        confirmations = { asked += it; approve },
        logger = { logged += it },
        policy = { PermissionPolicy() },
        config = { AgentConfig(webSearch = false) },
        context = { PromptContext("Yasin", "Personal AI", emptyList(), voiceMode = false) },
        clock = { ZonedDateTime.of(2026, 9, 28, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata")) },
        ioContext = dispatcher,
    )

    @Test
    fun `confirmed action runs and the tool result goes back to the model`() = runTest {
        val tool = FakeTool("call_contact", PermissionLevel.USER_CONFIGURABLE)
        val gateway = ScriptedGateway(
            message("tool_use", text("Calling."), toolUse("tu_1", "call_contact", "Ahmed")),
            message("end_turn", text("Dialing Ahmed now.")),
        )
        val reply = agent(gateway, tool, dispatcher = StandardTestDispatcher(testScheduler)).send("Call Ahmed")

        assertEquals("Dialing Ahmed now.", reply.text)
        assertEquals(1, tool.runs)
        assertEquals("Act on Ahmed", asked.single().detail)
        assertEquals(ActionStatus.SUCCEEDED, reply.actions.single().status)
        assertEquals(ActionStatus.SUCCEEDED, logged.single().status)

        val second = gateway.requests[1].messages()
        assertEquals(3, second.size) // user, assistant tool_use, user tool_result
        val result = second.last().content().asBetaContentBlockParams().single().asToolResult()
        assertEquals("tu_1", result.toolUseId())
        assertFalse(result.isError().orElse(false))
    }

    @Test
    fun `declined action does not run and is reported as an error`() = runTest {
        val tool = FakeTool("send_sms", PermissionLevel.CONFIRMATION_REQUIRED)
        val gateway = ScriptedGateway(
            message("tool_use", toolUse("tu_1", "send_sms", "Ravi")),
            message("end_turn", text("Okay, I won't send it.")),
        )
        val reply = agent(gateway, tool, approve = false, dispatcher = StandardTestDispatcher(testScheduler))
            .send("Text Ravi")

        assertEquals(0, tool.runs)
        assertEquals(ActionStatus.DECLINED_BY_USER, reply.actions.single().status)
        val result = gateway.requests[1].messages().last().content().asBetaContentBlockParams().single().asToolResult()
        assertTrue(result.isError().orElse(false))
    }

    @Test
    fun `automatic tools run without asking`() = runTest {
        val tool = FakeTool("read_calendar", PermissionLevel.AUTOMATIC)
        val gateway = ScriptedGateway(
            message("tool_use", toolUse("tu_1", "read_calendar", "today")),
            message("end_turn", text("You have two meetings.")),
        )
        agent(gateway, tool, dispatcher = StandardTestDispatcher(testScheduler)).send("What's on today?")

        assertEquals(1, tool.runs)
        assertTrue(asked.isEmpty())
        assertTrue(logged.isEmpty()) // successful reads are not logged
    }

    @Test
    fun `failed tool is never reported as success`() = runTest {
        val tool = FakeTool("call_contact", PermissionLevel.AUTOMATIC, ToolOutcome.Failure("No permission"))
        val gateway = ScriptedGateway(
            message("tool_use", toolUse("tu_1", "call_contact", "Ahmed")),
            message("end_turn", text("I couldn't call.")),
        )
        val reply = agent(gateway, tool, dispatcher = StandardTestDispatcher(testScheduler)).send("Call Ahmed")

        assertEquals(ActionStatus.FAILED, reply.actions.single().status)
        assertEquals(ActionStatus.FAILED, logged.single().status)
    }

    @Test
    fun `network error rolls the history back`() = runTest {
        val tool = FakeTool("call_contact", PermissionLevel.AUTOMATIC)
        // Only one response: the follow-up request after the tool call fails.
        val gateway = ScriptedGateway(message("tool_use", toolUse("tu_1", "call_contact", "Ahmed")))
        val agent = agent(gateway, tool, dispatcher = StandardTestDispatcher(testScheduler))

        assertFailsWith<IllegalStateException> { agent.send("Call Ahmed") }

        // The next turn starts from an empty, valid history.
        val retry = ScriptedGateway(message("end_turn", text("Hi")))
        val fresh = agent(retry, tool, dispatcher = StandardTestDispatcher(testScheduler))
        fresh.send("Hello")
        assertEquals(1, retry.requests.single().messages().size)
    }

    @Test
    fun `refusal leaves no partial turn in history`() = runTest {
        val tool = FakeTool("call_contact", PermissionLevel.AUTOMATIC)
        val gateway = ScriptedGateway(
            message("refusal"),
            message("end_turn", text("Hello!")),
        )
        val agent = agent(gateway, tool, dispatcher = StandardTestDispatcher(testScheduler))

        agent.send("something refused")
        agent.send("Hi")
        assertEquals(1, gateway.requests[1].messages().size)
    }

    @Test
    fun `request opts into server-side fallbacks`() = runTest {
        val gateway = ScriptedGateway(message("end_turn", text("Hi")))
        agent(gateway, FakeTool("x", PermissionLevel.AUTOMATIC), dispatcher = StandardTestDispatcher(testScheduler))
            .send("Hi")
        val params = gateway.requests.single()
        assertTrue(params.fallbacks().isPresent)
        assertTrue(params.betas().orElse(emptyList()).any { it.asString() == AnthropicBackend.FALLBACK_BETA })
    }

    @Test
    fun `models without fallback support don't send the parameter`() = runTest {
        val gateway = ScriptedGateway(message("end_turn", text("Hi")))
        agent(gateway, FakeTool("x", PermissionLevel.AUTOMATIC), dispatcher = StandardTestDispatcher(testScheduler), model = "claude-sonnet-5")
            .send("Hi")
        val params = gateway.requests.single()
        assertFalse(params.fallbacks().isPresent)
        assertTrue(params.betas().orElse(emptyList()).isEmpty())
    }

    @Test
    fun `alwaysConfirm asks even when the tool is auto-approved`() = runTest {
        val tool = object : AssistantTool {
            var runs = 0
            override val spec = ToolSpec("call_contact", "Call.", listOf(ToolParam("who", "string", "Who")), PermissionLevel.USER_CONFIGURABLE)
            override suspend fun prepare(input: ToolInput) =
                ToolPlan.Ready("Call +1900555 (not in contacts)", alwaysConfirm = true) { runs++; ToolOutcome.Success("ok") }
        }
        val gateway = ScriptedGateway(
            message("tool_use", toolUse("tu_1", "call_contact", "+1900555")),
            message("end_turn", text("Okay.")),
        )
        val backend = AnthropicBackend(gateway) { AnthropicOptions() }
        val agent = AssistantAgent(
            backend = { backend },
            tools = ToolRegistry(listOf(tool)),
            confirmations = { asked += it; false },
            logger = { logged += it },
            // The user switched call_contact to automatic.
            policy = { PermissionPolicy(setOf("call_contact")) },
            config = { AgentConfig(webSearch = false) },
            context = { PromptContext("Yasin", "Personal AI", emptyList(), voiceMode = false) },
            ioContext = StandardTestDispatcher(testScheduler),
        )
        agent.send("Call +1900555")

        assertEquals(1, asked.size)
        assertEquals(0, tool.runs)
    }
}
