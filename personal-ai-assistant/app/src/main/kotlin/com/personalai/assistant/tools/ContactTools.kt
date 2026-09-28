package com.personalai.assistant.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.personalai.assistant.core.AssistantTool
import com.personalai.assistant.core.Contact
import com.personalai.assistant.core.ContactMatch
import com.personalai.assistant.core.ContactMatcher
import com.personalai.assistant.core.PermissionLevel
import com.personalai.assistant.core.PhoneNumber
import com.personalai.assistant.core.ToolInput
import com.personalai.assistant.core.ToolOutcome
import com.personalai.assistant.core.ToolParam
import com.personalai.assistant.core.ToolPlan
import com.personalai.assistant.core.ToolSpec
import com.personalai.assistant.data.MemoryDao

/** Reads the phone's contacts and resolves names using remembered relationships. */
class ContactsRepository(private val context: Context, private val memories: MemoryDao) {

    fun canRead(): Boolean = context.hasPermission(Manifest.permission.READ_CONTACTS)

    fun loadAll(): List<Contact> {
        val projection = arrayOf(
            Phone.CONTACT_ID,
            Phone.DISPLAY_NAME_PRIMARY,
            Phone.NUMBER,
            Phone.TYPE,
            Phone.LABEL,
        )
        val byId = LinkedHashMap<String, Pair<String, MutableList<PhoneNumber>>>()
        context.contentResolver.query(Phone.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val number = c.getString(2) ?: continue
                val label = Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4)).toString()
                val entry = byId.getOrPut(id) { name to mutableListOf() }
                if (entry.second.none { normalizeNumber(it.number) == normalizeNumber(number) }) {
                    entry.second += PhoneNumber(number, label)
                }
            }
        }
        return byId.map { (id, v) -> Contact(id, v.first, v.second) }
    }

    suspend fun resolve(query: String): ContactMatch {
        val relationships = memories.recent(500)
            .filter { !it.relationship.isNullOrBlank() && !it.person.isNullOrBlank() }
            .associate { it.relationship!! to it.person!! }
        return ContactMatcher(relationships).match(query, loadAll())
    }

    companion object {
        fun normalizeNumber(n: String): String = n.filter { it.isDigit() || it == '+' }

        /** Prefers a mobile number, which is what people usually mean by "call" or "text". */
        fun preferredNumber(contact: Contact): PhoneNumber? =
            contact.numbers.firstOrNull { it.label.contains("mobile", ignoreCase = true) }
                ?: contact.numbers.firstOrNull()

        fun describeCandidates(candidates: List<Contact>): String =
            candidates.take(8).joinToString("; ") { c ->
                "${c.name} (${c.numbers.joinToString { "${it.label} ${it.number}" }})"
            }
    }
}

/** Where a call or message goes, resolved from a contact name or a raw number. */
internal data class Recipient(val name: String?, val number: String) {
    val display: String get() = if (name != null) "$name ($number)" else number
}

internal suspend fun resolveRecipient(
    contacts: ContactsRepository,
    input: ToolInput,
): Pair<Recipient?, ToolPlan.Rejected?> {
    input.string("phone_number")?.let { raw ->
        val number = ContactsRepository.normalizeNumber(raw)
        if (number.count { it.isDigit() } < 3) return null to reject("\"$raw\" isn't a valid phone number.")
        return Recipient(input.string("contact"), number) to null
    }
    val query = input.string("contact")
        ?: return null to reject("Give either a contact name or a phone number.")
    if (!contacts.canRead()) return null to ToolPlan.Rejected(missingPermission("read contacts"))

    return when (val match = contacts.resolve(query)) {
        is ContactMatch.Found -> {
            val number = ContactsRepository.preferredNumber(match.contact)
                ?: return null to reject("${match.contact.name} has no phone number saved.")
            Recipient(match.contact.name, number.number) to null
        }
        is ContactMatch.Ambiguous -> null to reject(
            "Several contacts match \"$query\": ${ContactsRepository.describeCandidates(match.candidates)}. " +
                "Ask the user which one they mean, then call this tool again with the full name or number.",
        )
        ContactMatch.NotFound -> null to reject("No contact matches \"$query\". Ask the user for the number or the name as saved.")
    }
}

class FindContactTool(private val contacts: ContactsRepository) : AssistantTool {
    override val spec = ToolSpec(
        name = "find_contact",
        description = "Look up a contact by name or relationship (e.g. \"Ahmed\", \"my father\") and return their phone numbers.",
        params = listOf(ToolParam("query", "string", "Name or relationship to look up")),
        level = PermissionLevel.AUTOMATIC,
    )

    override suspend fun prepare(input: ToolInput): ToolPlan {
        val query = input.requireString("query")
        return ToolPlan.Ready("Look up \"$query\" in contacts") {
            if (!contacts.canRead()) return@Ready missingPermission("read contacts")
            when (val match = contacts.resolve(query)) {
                is ContactMatch.Found -> ToolOutcome.Success(ContactsRepository.describeCandidates(listOf(match.contact)))
                is ContactMatch.Ambiguous -> ToolOutcome.Success(
                    "Several matches: ${ContactsRepository.describeCandidates(match.candidates)}",
                )
                ContactMatch.NotFound -> ToolOutcome.Failure("No contact matches \"$query\".")
            }
        }
    }
}

class CallContactTool(
    private val context: Context,
    private val contacts: ContactsRepository,
) : AssistantTool {
    override val spec = ToolSpec(
        name = "call_contact",
        description = "Start a phone call to a contact or number. This only dials: the assistant cannot " +
            "hear or speak on the call.",
        params = listOf(
            ToolParam("contact", "string", "Contact name or relationship, e.g. \"Ahmed\" or \"my father\"", required = false),
            ToolParam("phone_number", "string", "Phone number to call, if the user gave one", required = false),
        ),
        level = PermissionLevel.USER_CONFIGURABLE,
    )

    override suspend fun prepare(input: ToolInput): ToolPlan {
        val (recipient, rejected) = resolveRecipient(contacts, input)
        if (rejected != null) return rejected
        val to = recipient!!
        return ToolPlan.Ready("Call ${to.display}") {
            if (!context.hasPermission(Manifest.permission.CALL_PHONE)) return@Ready missingPermission("make phone calls")
            val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", to.number, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                return@Ready ToolOutcome.Failure("This device can't make phone calls.")
            }
            ToolOutcome.Success(
                "Dialing ${to.display}; the phone's call screen is open. Whether the call connects isn't known yet.",
            )
        }
    }
}
