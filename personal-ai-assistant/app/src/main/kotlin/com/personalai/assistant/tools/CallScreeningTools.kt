package com.personalai.assistant.tools

import com.personalai.assistant.calls.CallScreeningRepository
import com.personalai.assistant.core.AssistantTool
import com.personalai.assistant.core.CallerCategory
import com.personalai.assistant.core.ContactMatch
import com.personalai.assistant.core.PermissionLevel
import com.personalai.assistant.core.PhoneNumbers
import com.personalai.assistant.core.ScreeningAction
import com.personalai.assistant.core.ToolInput
import com.personalai.assistant.core.ToolOutcome
import com.personalai.assistant.core.ToolParam
import com.personalai.assistant.core.ToolPlan
import com.personalai.assistant.core.ToolSpec

/** "Who called while I was busy?" answered from the call-screening history. */
class ReadScreenedCallsTool(private val screening: CallScreeningRepository) : AssistantTool {
    override val spec = ToolSpec(
        name = "read_screened_calls",
        description = "Read incoming calls handled by call screening: caller, category, whether the call rang, " +
            "was silenced or was rejected, and why. Newest first.",
        params = listOf(
            ToolParam("since", "string", "Only calls at or after this time. ${TimeInput.FORMAT_HINT}", required = false),
            ToolParam("limit", "integer", "Maximum number of calls (default 20, max 100)", required = false),
        ),
        level = PermissionLevel.AUTOMATIC,
    )

    override suspend fun prepare(input: ToolInput): ToolPlan {
        val since = input.string("since")?.let { TimeInput.parse(it).toInstant().toEpochMilli() } ?: 0L
        val limit = (input.int("limit") ?: 20).coerceIn(1, 100)
        return ToolPlan.Ready("Read screened calls") {
            val calls = screening.history.since(since, limit)
            if (calls.isEmpty()) {
                val note = if (screening.isEnabled()) "" else " Call screening is turned off; the user can turn it on in the Calls tab."
                return@Ready ToolOutcome.Success("No screened calls.$note")
            }
            ToolOutcome.Success(
                calls.joinToString("\n") { c ->
                    val who = listOfNotNull(c.contactName, c.number ?: "hidden number").joinToString(" ")
                    val action = ScreeningAction.entries.firstOrNull { it.name == c.decision }?.label ?: c.decision
                    val category = CallerCategory.parse(c.category)?.label?.let { " [$it]" }.orEmpty()
                    "${TimeInput.describe(c.timestamp)} - $who$category - $action - ${c.reason}"
                },
            )
        }
    }
}

class ListCallerCategoriesTool(private val screening: CallScreeningRepository) : AssistantTool {
    override val spec = ToolSpec(
        name = "list_caller_categories",
        description = "List phone numbers the user has marked as important, spam, blocked, family, personal or business.",
        params = emptyList(),
        level = PermissionLevel.AUTOMATIC,
    )

    override suspend fun prepare(input: ToolInput): ToolPlan = ToolPlan.Ready("List caller categories") {
        val all = screening.categories.all()
        if (all.isEmpty()) ToolOutcome.Success("No numbers have a category yet.")
        else ToolOutcome.Success(
            all.joinToString("\n") { e ->
                val label = CallerCategory.parse(e.category)?.label ?: e.category
                "${e.displayNumber} - $label${e.note?.let { " - $it" }.orEmpty()}"
            },
        )
    }
}

/** Changes how future calls from a number are handled, so the user always confirms it. */
class SetCallerCategoryTool(
    private val screening: CallScreeningRepository,
    private val contacts: ContactsRepository,
) : AssistantTool {
    override val spec = ToolSpec(
        name = "set_caller_category",
        description = "Mark a phone number as important, spam, blocked, family, personal or business, or remove its " +
            "category with \"none\". Call screening uses this: spam is silenced, blocked is rejected, important notifies.",
        params = listOf(
            ToolParam("category", "string", "The category to set",
                enumValues = CallerCategory.entries.map { it.name.lowercase() } + "none"),
            ToolParam("phone_number", "string", "The number, if the user gave one", required = false),
            ToolParam("contact", "string", "Contact name, if the user named a saved contact", required = false),
            ToolParam("note", "string", "Short note, e.g. \"loan offers\"", required = false),
        ),
        level = PermissionLevel.CONFIRMATION_REQUIRED,
    )

    override suspend fun prepare(input: ToolInput): ToolPlan {
        val categoryText = input.requireString("category")
        val remove = categoryText.equals("none", ignoreCase = true)
        val category = if (remove) null else CallerCategory.parse(categoryText)
            ?: return reject("Unknown category \"$categoryText\".")

        val (number, name) = input.string("phone_number")?.let { raw ->
            if (PhoneNumbers.matchKey(raw) == null) return reject("\"$raw\" isn't a valid phone number.")
            PhoneNumbers.clean(raw) to contacts.lookupName(raw)
        } ?: run {
            val query = input.string("contact") ?: return reject("Give a phone number or a contact name.")
            if (!contacts.canRead()) return ToolPlan.Rejected(missingPermission("read contacts"))
            when (val match = contacts.resolve(query)) {
                is ContactMatch.Found -> {
                    val n = ContactsRepository.preferredNumber(match.contact)
                        ?: return reject("${match.contact.name} has no phone number saved.")
                    PhoneNumbers.clean(n.number) to match.contact.name
                }
                is ContactMatch.Ambiguous -> return reject(
                    "Several contacts match \"$query\": ${ContactsRepository.describeCandidates(match.candidates)}. Ask which one.",
                )
                ContactMatch.NotFound -> return reject("No contact matches \"$query\".")
            }
        }
        val who = name?.let { "$it ($number)" } ?: number
        val note = input.string("note")

        if (remove) {
            return ToolPlan.Ready("Remove the call category for $who") {
                if (screening.removeCategory(number)) ToolOutcome.Success("Removed the category for $who.")
                else ToolOutcome.Failure("$who had no category.")
            }
        }
        val effect = when (category!!) {
            CallerCategory.SPAM -> "Their calls will be silenced."
            CallerCategory.BLOCKED -> "Their calls will be rejected."
            CallerCategory.IMPORTANT -> "You'll get a notification when they call."
            else -> "Used by your call-screening rules."
        }
        return ToolPlan.Ready("Mark $who as ${category.label}${note?.let { " ($it)" }.orEmpty()}.\n$effect") {
            screening.setCategory(number, category, note)
            val saved = PhoneNumbers.matchKey(number)?.let { screening.categories.byKey(it) }
            if (saved?.category == category.name) ToolOutcome.Success("Marked $who as ${category.label}. $effect")
            else ToolOutcome.Failure("The category couldn't be saved.")
        }
    }
}
