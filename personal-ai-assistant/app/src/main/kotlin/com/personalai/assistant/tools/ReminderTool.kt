package com.personalai.assistant.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.personalai.assistant.AssistantApp
import com.personalai.assistant.R
import com.personalai.assistant.core.AssistantTool
import com.personalai.assistant.core.PermissionLevel
import com.personalai.assistant.core.ToolInput
import com.personalai.assistant.core.ToolOutcome
import com.personalai.assistant.core.ToolParam
import com.personalai.assistant.core.ToolPlan
import com.personalai.assistant.core.ToolSpec
import com.personalai.assistant.data.ReminderDao
import com.personalai.assistant.data.ReminderEntity
import com.personalai.assistant.ui.MainActivity
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

class CreateReminderTool(private val context: Context, private val reminders: ReminderDao) : AssistantTool {
    override val spec = ToolSpec(
        name = "create_reminder",
        description = "Set a reminder that shows a notification on the phone at the given time.",
        params = listOf(
            ToolParam("text", "string", "What to remind the user about, e.g. \"Call Ahmed\""),
            ToolParam("time", "string", "When to remind. ${TimeInput.FORMAT_HINT}"),
        ),
        level = PermissionLevel.AUTOMATIC,
    )

    override suspend fun prepare(input: ToolInput): ToolPlan {
        val text = input.requireString("text")
        val time = TimeInput.parse(input.requireString("time"))
        val delay = Duration.between(ZonedDateTime.now(time.zone), time)
        if (delay.isNegative) return reject("${TimeInput.describe(time)} is in the past. Ask the user for a future time.")

        return ToolPlan.Ready("Remind \"$text\" at ${TimeInput.describe(time)}") {
            val triggerAt = time.toInstant().toEpochMilli()
            val requestBuilder = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .addTag(ReminderWorker.TAG)
            val workId = UUID.randomUUID()
            val id = reminders.insert(
                ReminderEntity(text = text, triggerAt = triggerAt, workId = workId.toString(), status = STATUS_SCHEDULED,
                    createdAt = System.currentTimeMillis()),
            )
            val request = requestBuilder
                .setId(workId)
                .setInputData(workDataOf(ReminderWorker.KEY_TEXT to text, ReminderWorker.KEY_REMINDER_ID to id))
                .build()
            // Wait for WorkManager to persist the job before reporting success.
            try {
                WorkManager.getInstance(context).enqueue(request).await()
            } catch (e: Exception) {
                reminders.setStatus(id, STATUS_CANCELLED)
                throw e
            }

            val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            val note = if (canNotify) "" else " Notifications are turned off for this app, so tell the user to grant them in Settings or the reminder won't show."
            ToolOutcome.Success("Reminder $id set for ${TimeInput.describe(time)}: \"$text\".$note")
        }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val text = inputData.getString(KEY_TEXT) ?: return Result.failure()
        val context = applicationContext
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            return Result.failure()
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .apply {
                if ((context as AssistantApp).container.settings.current.appLock) {
                    setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    setPublicVersion(
                        NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_launcher_foreground)
                            .setContentTitle("Reminder")
                            .build(),
                    )
                }
            }
            .build()
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        val reminderId = inputData.getLong(KEY_REMINDER_ID, -1L)
        if (reminderId > 0) {
            (context as AssistantApp).container.database.reminders().setStatus(reminderId, STATUS_DONE)
        }
        return Result.success()
    }

    companion object {
        const val KEY_TEXT = "text"
        const val KEY_REMINDER_ID = "reminderId"
        const val TAG = "reminder"
        const val CHANNEL_ID = "reminders"

        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

internal const val STATUS_SCHEDULED = "SCHEDULED"
internal const val STATUS_DONE = "DONE"
internal const val STATUS_CANCELLED = "CANCELLED"

class ListRemindersTool(private val reminders: ReminderDao) : AssistantTool {
    override val spec = ToolSpec(
        name = "list_reminders",
        description = "List reminders that are still scheduled, with their ids. Set include_past to also show recent done or cancelled ones.",
        params = listOf(ToolParam("include_past", "boolean", "Also list recent done and cancelled reminders", required = false)),
        level = PermissionLevel.AUTOMATIC,
    )

    override suspend fun prepare(input: ToolInput): ToolPlan {
        val includePast = input.boolean("include_past") == true
        return ToolPlan.Ready("List reminders") {
            val list = if (includePast) reminders.recent(30) else reminders.upcoming()
            if (list.isEmpty()) return@Ready ToolOutcome.Success(if (includePast) "No reminders." else "No upcoming reminders.")
            ToolOutcome.Success(
                list.joinToString("\n") { r ->
                    val state = if (r.status == STATUS_SCHEDULED) "" else " (${r.status.lowercase()})"
                    "id ${r.id}: ${TimeInput.describe(r.triggerAt)} - ${r.text}$state"
                },
            )
        }
    }
}

class CancelReminderTool(private val context: Context, private val reminders: ReminderDao) : AssistantTool {
    override val spec = ToolSpec(
        name = "cancel_reminder",
        description = "Cancel a scheduled reminder by its id (get ids with list_reminders).",
        params = listOf(ToolParam("reminder_id", "integer", "Id of the reminder")),
        level = PermissionLevel.USER_CONFIGURABLE,
    )

    override suspend fun prepare(input: ToolInput): ToolPlan {
        val id = input.int("reminder_id")?.toLong() ?: return reject("reminder_id must be a number.")
        val reminder = reminders.get(id) ?: return reject("There's no reminder with id $id.")
        if (reminder.status != STATUS_SCHEDULED) return reject("Reminder $id is already ${reminder.status.lowercase()}.")
        return ToolPlan.Ready("Cancel reminder: \"${reminder.text}\" at ${TimeInput.describe(reminder.triggerAt)}") {
            WorkManager.getInstance(context).cancelWorkById(UUID.fromString(reminder.workId)).await()
            if (reminders.setStatus(id, STATUS_CANCELLED) == 1) ToolOutcome.Success("Cancelled reminder $id: \"${reminder.text}\".")
            else ToolOutcome.Failure("Reminder $id couldn't be updated.")
        }
    }
}
