package com.personalai.assistant.tools

import com.personalai.assistant.core.AssistantTool
import com.personalai.assistant.core.PermissionLevel
import com.personalai.assistant.core.ToolInput
import com.personalai.assistant.core.ToolOutcome
import com.personalai.assistant.core.ToolParam
import com.personalai.assistant.core.ToolPlan
import com.personalai.assistant.core.ToolSpec
import com.personalai.assistant.data.MemoryDao
import com.personalai.assistant.data.MemoryEntity

class SaveMemoryTool(private val memories: MemoryDao) : AssistantTool {
    override val spec = ToolSpec(
        name = "save_memory",
        description = "Remember a fact the user asked you to remember. For a person, also give their " +
            "contact name and relationship so later requests like \"call my business partner\" work.",
        params = listOf(
            ToolParam("fact", "string", "The fact in one sentence, e.g. \"Ahmed Khan is my business partner\""),
            ToolParam("person", "string", "Contact name this is about, if any", required = false),
            ToolParam("relationship", "string", "Their relationship to the user, e.g. \"father\", \"business partner\"", required = false),
        ),
        level = PermissionLevel.AUTOMATIC,
    )

    override suspend fun prepare(input: ToolInput): ToolPlan {
        val fact = input.requireString("fact")
        val person = input.string("person")
        val relationship = input.string("relationship")?.lowercase()
        return ToolPlan.Ready("Remember: $fact") {
            val id = memories.insert(MemoryEntity(text = fact, person = person, relationship = relationship, createdAt = System.currentTimeMillis()))
            if (memories.get(id) != null) ToolOutcome.Success("Saved as memory $id.")
            else ToolOutcome.Failure("The memory couldn't be saved.")
        }
    }
}

class ForgetMemoryTool(private val memories: MemoryDao) : AssistantTool {
    override val spec = ToolSpec(
        name = "forget_memory",
        description = "Delete one saved memory by its id.",
        params = listOf(ToolParam("memory_id", "integer", "Id of the memory to delete")),
        level = PermissionLevel.ALWAYS_CONFIRM,
    )

    override suspend fun prepare(input: ToolInput): ToolPlan {
        val id = input.int("memory_id")?.toLong() ?: return reject("memory_id must be a number.")
        val memory = memories.get(id) ?: return reject("There's no memory with id $id.")
        return ToolPlan.Ready("Forget: ${memory.text}") {
            if (memories.delete(id) == 1) ToolOutcome.Success("Deleted memory $id.")
            else ToolOutcome.Failure("Memory $id couldn't be deleted.")
        }
    }
}
