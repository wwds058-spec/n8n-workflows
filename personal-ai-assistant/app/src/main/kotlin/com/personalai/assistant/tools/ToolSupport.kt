package com.personalai.assistant.tools

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.personalai.assistant.core.ToolOutcome
import com.personalai.assistant.core.ToolPlan
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** A failure the model can relay: which permission is missing and where to grant it. */
internal fun missingPermission(what: String): ToolOutcome.Failure = ToolOutcome.Failure(
    "Permission to $what isn't granted. Tell the user to open Settings in this app and tap " +
        "\"Grant phone permissions\".",
)

internal fun reject(message: String): ToolPlan.Rejected = ToolPlan.Rejected(ToolOutcome.Failure(message))

internal object TimeInput {
    const val FORMAT_HINT = "Local date-time in ISO 8601 format, e.g. 2026-09-29T10:00"

    /** Parses "2026-09-29T10:00", with optional seconds or offset, in the phone's time zone. */
    fun parse(value: String, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime {
        val v = value.trim().replace(' ', 'T')
        return try {
            LocalDateTime.parse(v).atZone(zone)
        } catch (e: DateTimeParseException) {
            try {
                OffsetDateTime.parse(v).atZoneSameInstant(zone)
            } catch (e2: DateTimeParseException) {
                throw IllegalArgumentException("Couldn't read the time \"$value\". Use the format ${FORMAT_HINT.substringAfter("format, ")}.")
            }
        }
    }

    private val spoken = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.getDefault())

    fun describe(time: ZonedDateTime): String = time.format(spoken)

    fun describe(epochMillis: Long): String =
        describe(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()))
}
