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
        description = "List calendar events between two times, with event ids for update_calendar_event and delete_calendar_event.",
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
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.RRULE,
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
                    val repeats = if (c.getString(6).isNullOrBlank()) "" else " (repeating)"
                    lines += "id ${c.getLong(5)}: $title - $whenText$where$repeats"
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

/** The fields of one calendar event needed to edit or delete it safely. */
private data class EventInfo(val title: String, val start: Long, val end: Long, val location: String?, val recurring: Boolean)

private fun loadEvent(context: Context, eventId: Long): EventInfo? {
    return context.contentResolver.query(
        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
        arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.DELETED,
        ),
        null,
        null,
        null,
    )?.use { c ->
        if (!c.moveToFirst() || c.getInt(5) == 1) return null
        EventInfo(
            title = c.getString(0)?.ifBlank { null } ?: "(no title)",
            start = c.getLong(1),
            end = if (c.isNull(2)) c.getLong(1) else c.getLong(2),
            location = c.getString(3)?.takeIf { it.isNotBlank() },
            recurring = !c.getString(4).isNullOrBlank(),
        )
    }
}

class UpdateCalendarEventTool(private val context: Context) : AssistantTool {
    override val spec = ToolSpec(
        name = "update_calendar_event",
        description = "Change or move a calendar event by its id (from read_calendar). Moving the start keeps the " +
            "event's length unless a new end is given. Repeating events can't be changed.",
        params = listOf(
            ToolParam("event_id", "integer", "Id of the event"),
            ToolParam("title", "string", "New title", required = false),
            ToolParam("start", "string", "New start. ${TimeInput.FORMAT_HINT}", required = false),
            ToolParam("end", "string", "New end. ${TimeInput.FORMAT_HINT}", required = false),
            ToolParam("location", "string", "New location", required = false),
        ),
        level = PermissionLevel.CONFIRMATION_REQUIRED,
    )

    override suspend fun prepare(input: ToolInput): ToolPlan {
        if (!context.hasPermission(Manifest.permission.READ_CALENDAR)) return ToolPlan.Rejected(missingPermission("read the calendar"))
        val id = input.int("event_id")?.toLong() ?: return reject("event_id must be a number.")
        val event = loadEvent(context, id) ?: return reject("There's no event with id $id.")
        if (event.recurring) return reject("\"${event.title}\" is a repeating event; the user needs to change it in their calendar app.")

        val title = input.string("title")
        val location = input.string("location")
        val newStart = input.string("start")?.let { TimeInput.parse(it).toInstant().toEpochMilli() }
        val newEnd = input.string("end")?.let { TimeInput.parse(it).toInstant().toEpochMilli() }
            ?: newStart?.let { it + (event.end - event.start) }
        val start = newStart ?: event.start
        val end = newEnd ?: event.end
        if (title == null && location == null && newStart == null && newEnd == null) return reject("Nothing to change.")
        if (end < start) return reject("The end time must be after the start time.")

        val changes = buildList {
            title?.let { add("Title: ${event.title} → $it") }
            if (start != event.start || end != event.end) {
                add("Time: ${TimeInput.describe(event.start)} → ${TimeInput.describe(start)} to ${TimeInput.describe(end)}")
            }
            location?.let { add("Location: ${event.location ?: "none"} → $it") }
        }
        return ToolPlan.Ready("Change \"${event.title}\":\n${changes.joinToString("\n")}") {
            if (!context.hasPermission(Manifest.permission.WRITE_CALENDAR)) return@Ready missingPermission("edit the calendar")
            val values = ContentValues().apply {
                title?.let { put(CalendarContract.Events.TITLE, it) }
                location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                if (newStart != null || newEnd != null) {
                    put(CalendarContract.Events.DTSTART, start)
                    put(CalendarContract.Events.DTEND, end)
                }
            }
            val rows = context.contentResolver.update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), values, null, null,
            )
            val after = loadEvent(context, id)
            if (rows == 1 && after != null && after.start == start && (title == null || after.title == title)) {
                ToolOutcome.Success("Updated \"${after.title}\": ${TimeInput.describe(after.start)} to ${TimeInput.describe(after.end)}.")
            } else {
                ToolOutcome.Failure("The calendar didn't accept the change.")
            }
        }
    }
}

class DeleteCalendarEventTool(private val context: Context) : AssistantTool {
    override val spec = ToolSpec(
        name = "delete_calendar_event",
        description = "Delete a calendar event by its id (from read_calendar). Repeating events can't be deleted here.",
        params = listOf(ToolParam("event_id", "integer", "Id of the event")),
        level = PermissionLevel.ALWAYS_CONFIRM,
    )

    override suspend fun prepare(input: ToolInput): ToolPlan {
        if (!context.hasPermission(Manifest.permission.READ_CALENDAR)) return ToolPlan.Rejected(missingPermission("read the calendar"))
        val id = input.int("event_id")?.toLong() ?: return reject("event_id must be a number.")
        val event = loadEvent(context, id) ?: return reject("There's no event with id $id.")
        if (event.recurring) return reject("\"${event.title}\" is a repeating event; the user needs to delete it in their calendar app.")
        return ToolPlan.Ready("Delete \"${event.title}\" on ${TimeInput.describe(event.start)}") {
            if (!context.hasPermission(Manifest.permission.WRITE_CALENDAR)) return@Ready missingPermission("edit the calendar")
            val rows = context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
            if (rows == 1 && loadEvent(context, id) == null) ToolOutcome.Success("Deleted \"${event.title}\".")
            else ToolOutcome.Failure("The calendar didn't delete the event.")
        }
    }
}
