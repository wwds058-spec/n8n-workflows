package com.personalai.assistant.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.personalai.assistant.core.AssistantTool
import com.personalai.assistant.core.PermissionLevel
import com.personalai.assistant.core.ToolInput
import com.personalai.assistant.core.ToolOutcome
import com.personalai.assistant.core.ToolParam
import com.personalai.assistant.core.ToolPlan
import com.personalai.assistant.core.ToolSpec
import java.time.Duration

class ReadCalendarTool(private val context: Context) : AssistantTool {
    override val spec = ToolSpec(
        name = "read_calendar",
        description = "List calendar events between two times.",
        params = listOf(
            ToolParam("start", "string", "Start of the range. ${TimeInput.FORMAT_HINT}"),
            ToolParam("end", "string", "End of the range. ${TimeInput.FORMAT_HINT}"),
        ),
        level = PermissionLevel.AUTOMATIC,
    )

    override suspend fun prepare(input: ToolInput): ToolPlan {
        val start = TimeInput.parse(input.requireString("start"))
        val end = TimeInput.parse(input.requireString("end"))
        if (!end.isAfter(start)) return reject("The end time must be after the start time.")

        return ToolPlan.Ready("Read calendar ${TimeInput.describe(start)} to ${TimeInput.describe(end)}") {
            if (!context.hasPermission(Manifest.permission.READ_CALENDAR)) return@Ready missingPermission("read the calendar")
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, start.toInstant().toEpochMilli())
                ContentUris.appendId(it, end.toInstant().toEpochMilli())
            }.build()
            val lines = mutableListOf<String>()
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.EVENT_LOCATION,
                ),
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && lines.size < 50) {
                    val title = c.getString(0)?.ifBlank { null } ?: "(no title)"
                    val whenText = if (c.getInt(3) == 1) "all day" else
                        "${TimeInput.describe(c.getLong(1))} to ${TimeInput.describe(c.getLong(2))}"
                    val where = c.getString(4)?.takeIf { it.isNotBlank() }?.let { " at $it" }.orEmpty()
                    lines += "$title - $whenText$where"
                }
            }
            ToolOutcome.Success(if (lines.isEmpty()) "No events in that range." else lines.joinToString("\n"))
        }
    }
}

class CreateCalendarEventTool(private val context: Context) : AssistantTool {
    override val spec = ToolSpec(
        name = "create_calendar_event",
        description = "Add an event to the phone's calendar.",
        params = listOf(
            ToolParam("title", "string", "Event title"),
            ToolParam("start", "string", "Start time. ${TimeInput.FORMAT_HINT}"),
            ToolParam("end", "string", "End time; defaults to one hour after start. ${TimeInput.FORMAT_HINT}", required = false),
            ToolParam("location", "string", "Where the event is", required = false),
            ToolParam("reminder_minutes", "integer", "Alert this many minutes before the start", required = false),
        ),
        level = PermissionLevel.CONFIRMATION_REQUIRED,
    )

    override suspend fun prepare(input: ToolInput): ToolPlan {
        val title = input.requireString("title")
        val start = TimeInput.parse(input.requireString("start"))
        val end = input.string("end")?.let { TimeInput.parse(it) } ?: start.plus(Duration.ofHours(1))
        if (!end.isAfter(start)) return reject("The end time must be after the start time.")
        val location = input.string("location")
        val reminder = input.int("reminder_minutes")?.takeIf { it >= 0 }

        val preview = buildString {
            append("$title\n${TimeInput.describe(start)} to ${TimeInput.describe(end)}")
            location?.let { append("\nAt $it") }
            reminder?.let { append("\nAlert $it min before") }
        }
        return ToolPlan.Ready(preview) {
            if (!context.hasPermission(Manifest.permission.WRITE_CALENDAR) ||
                !context.hasPermission(Manifest.permission.READ_CALENDAR)
            ) return@Ready missingPermission("edit the calendar")

            val calendarId = writableCalendarId()
                ?: return@Ready ToolOutcome.Failure("No calendar on this phone can be edited. Add a Google account calendar first.")
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, end.toInstant().toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, start.zone.id)
                location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return@Ready ToolOutcome.Failure("The calendar didn't accept the event.")
            val eventId = ContentUris.parseId(uri)

            var note = ""
            if (reminder != null) {
                val r = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, reminder)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                if (context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, r) == null) {
                    note = " The event was saved but its alert couldn't be added."
                }
            }
            ToolOutcome.Success("Added \"$title\" on ${TimeInput.describe(start)}.$note")
        }
    }

    private fun writableCalendarId(): Long? {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { c ->
            var first: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (c.getInt(1) == 1) return id
                if (first == null) first = id
            }
            return first
        }
        return null
    }
}
