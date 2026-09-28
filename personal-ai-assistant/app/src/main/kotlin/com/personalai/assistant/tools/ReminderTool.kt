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
import com.personalai.assistant.R
import com.personalai.assistant.core.AssistantTool
import com.personalai.assistant.core.PermissionLevel
import com.personalai.assistant.core.ToolInput
import com.personalai.assistant.core.ToolOutcome
import com.personalai.assistant.core.ToolParam
import com.personalai.assistant.core.ToolPlan
import com.personalai.assistant.core.ToolSpec
import com.personalai.assistant.ui.MainActivity
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class CreateReminderTool(private val context: Context) : AssistantTool {
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
            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(ReminderWorker.KEY_TEXT to text))
                .addTag(ReminderWorker.TAG)
                .build()
            // Wait for WorkManager to persist the job before reporting success.
            WorkManager.getInstance(context).enqueue(request).await()

            val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            val note = if (canNotify) "" else " Notifications are turned off for this app, so tell the user to grant them in Settings or the reminder won't show."
            ToolOutcome.Success("Reminder set for ${TimeInput.describe(time)}: \"$text\".$note")
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
            .build()
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        return Result.success()
    }

    companion object {
        const val KEY_TEXT = "text"
        const val TAG = "reminder"
        const val CHANNEL_ID = "reminders"

        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
