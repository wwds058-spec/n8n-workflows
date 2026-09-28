package com.personalai.assistant.tools

import android.Manifest
import android.content.Context
import android.provider.CallLog
import com.personalai.assistant.core.AssistantTool
import com.personalai.assistant.core.PermissionLevel
import com.personalai.assistant.core.ToolInput
import com.personalai.assistant.core.ToolOutcome
import com.personalai.assistant.core.ToolParam
import com.personalai.assistant.core.ToolPlan
import com.personalai.assistant.core.ToolSpec

/** Answers "who called me", "call the last person who called" and similar (spec §7). */
class ReadCallLogTool(private val context: Context) : AssistantTool {
    override val spec = ToolSpec(
        name = "read_call_log",
        description = "Read recent calls from the phone's call history, newest first.",
        params = listOf(
            ToolParam("type", "string", "Which calls to include", required = false,
                enumValues = listOf("all", "missed", "incoming", "outgoing", "rejected")),
            ToolParam("since", "string", "Only calls at or after this time. ${TimeInput.FORMAT_HINT}", required = false),
            ToolParam("until", "string", "Only calls before this time. ${TimeInput.FORMAT_HINT}", required = false),
            ToolParam("limit", "integer", "Maximum number of calls to return (default 10, max 50)", required = false),
        ),
        level = PermissionLevel.AUTOMATIC,
    )

    override suspend fun prepare(input: ToolInput): ToolPlan {
        val type = input.string("type") ?: "all"
        val typeCode = when (type) {
            "all" -> null
            "missed" -> CallLog.Calls.MISSED_TYPE
            "incoming" -> CallLog.Calls.INCOMING_TYPE
            "outgoing" -> CallLog.Calls.OUTGOING_TYPE
            "rejected" -> CallLog.Calls.REJECTED_TYPE
            else -> return reject("Unknown call type \"$type\".")
        }
        val since = input.string("since")?.let { TimeInput.parse(it).toInstant().toEpochMilli() }
        val until = input.string("until")?.let { TimeInput.parse(it).toInstant().toEpochMilli() }
        val limit = (input.int("limit") ?: 10).coerceIn(1, 50)

        return ToolPlan.Ready("Read $type calls") {
            if (!context.hasPermission(Manifest.permission.READ_CALL_LOG)) return@Ready missingPermission("read the call log")

            val where = mutableListOf<String>()
            val args = mutableListOf<String>()
            typeCode?.let { where += "${CallLog.Calls.TYPE} = ?"; args += it.toString() }
            since?.let { where += "${CallLog.Calls.DATE} >= ?"; args += it.toString() }
            until?.let { where += "${CallLog.Calls.DATE} < ?"; args += it.toString() }

            val lines = mutableListOf<String>()
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                where.joinToString(" AND ").ifEmpty { null },
                args.toTypedArray().ifEmpty { null },
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && lines.size < limit) {
                    val number = c.getString(0).orEmpty().ifEmpty { "private number" }
                    val name = c.getString(1)?.takeIf { it.isNotBlank() }
                    val kind = when (c.getInt(2)) {
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                        CallLog.Calls.BLOCKED_TYPE -> "blocked"
                        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
                        else -> "other"
                    }
                    val who = if (name != null) "$name ($number)" else "$number (not in contacts)"
                    lines += "${TimeInput.describe(c.getLong(3))} - $kind - $who - ${c.getLong(4)}s"
                }
            }
            if (lines.isEmpty()) ToolOutcome.Success("No matching calls.")
            else ToolOutcome.Success(lines.joinToString("\n"))
        }
    }
}
