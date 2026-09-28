package com.personalai.assistant.tools

import android.content.Context
import android.content.Intent
import com.personalai.assistant.core.AssistantTool
import com.personalai.assistant.core.ContactMatcher
import com.personalai.assistant.core.PermissionLevel
import com.personalai.assistant.core.ToolInput
import com.personalai.assistant.core.ToolOutcome
import com.personalai.assistant.core.ToolParam
import com.personalai.assistant.core.ToolPlan
import com.personalai.assistant.core.ToolSpec

/** Opens an installed app. Apps handle their own sign-in; nothing is bypassed (spec §22). */
class OpenAppTool(private val context: Context) : AssistantTool {
    override val spec = ToolSpec(
        name = "open_app",
        description = "Open an app installed on the phone, by its name as shown on the home screen.",
        params = listOf(ToolParam("app_name", "string", "App name, e.g. \"WhatsApp\" or \"Google Maps\"")),
        level = PermissionLevel.USER_CONFIGURABLE,
    )

    private data class App(val label: String, val packageName: String)

    override suspend fun prepare(input: ToolInput): ToolPlan {
        val query = ContactMatcher.normalize(input.requireString("app_name"))
        val apps = installedApps()
        val hits = apps.filter { ContactMatcher.normalize(it.label) == query }
            .ifEmpty { apps.filter { ContactMatcher.normalize(it.label).startsWith(query) } }
            .ifEmpty { apps.filter { ContactMatcher.normalize(it.label).contains(query) } }
            .distinctBy { it.packageName }

        val app = when (hits.size) {
            0 -> return reject("No installed app is called \"${input.string("app_name")}\".")
            1 -> hits.single()
            else -> return reject("Several apps match: ${hits.take(8).joinToString { it.label }}. Ask the user which one.")
        }
        return ToolPlan.Ready("Open ${app.label}") {
            val launch = context.packageManager.getLaunchIntentForPackage(app.packageName)
                ?: return@Ready ToolOutcome.Failure("${app.label} can't be opened from here.")
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolOutcome.Success("Opened ${app.label}.")
        }
    }

    private fun installedApps(): List<App> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(launcher, 0).map {
            App(it.loadLabel(pm).toString(), it.activityInfo.packageName)
        }
    }
}
