package com.personalai.assistant.tools

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.personalai.assistant.core.AssistantTool
import com.personalai.assistant.core.PermissionLevel
import com.personalai.assistant.core.ToolInput
import com.personalai.assistant.core.ToolOutcome
import com.personalai.assistant.core.ToolParam
import com.personalai.assistant.core.ToolPlan
import com.personalai.assistant.core.ToolSpec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Sends an SMS from the phone's own number and waits for the radio to report the result,
 * so the assistant only says "sent" when the network accepted it (spec §24).
 */
class SendSmsTool(
    private val context: Context,
    private val contacts: ContactsRepository,
) : AssistantTool {
    override val spec = ToolSpec(
        name = "send_sms",
        description = "Send an SMS text message from the user's phone number.",
        params = listOf(
            ToolParam("message", "string", "The exact text to send"),
            ToolParam("contact", "string", "Contact name or relationship", required = false),
            ToolParam("phone_number", "string", "Phone number, if the user gave one", required = false),
        ),
        level = PermissionLevel.CONFIRMATION_REQUIRED,
    )

    override suspend fun prepare(input: ToolInput): ToolPlan {
        val text = input.requireString("message")
        val (recipient, rejected) = resolveRecipient(contacts, input)
        if (rejected != null) return rejected
        val to = recipient!!

        return ToolPlan.Ready("Text ${to.display}:\n\"$text\"") {
            if (!context.hasPermission(Manifest.permission.SEND_SMS)) return@Ready missingPermission("send SMS")
            send(to, text)
        }
    }

    private suspend fun send(to: Recipient, text: String): ToolOutcome {
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: return ToolOutcome.Failure("This device can't send SMS.")

        val parts = sms.divideMessage(text)
        val action = "${context.packageName}.SMS_SENT.${UUID.randomUUID()}"
        val results = Channel<Int>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                results.trySend(resultCode)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sentIntents = ArrayList(parts.indices.map { i ->
                PendingIntent.getBroadcast(
                    context,
                    i,
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                )
            })
            if (parts.size == 1) {
                sms.sendTextMessage(to.number, null, text, sentIntents[0], null)
            } else {
                sms.sendMultipartTextMessage(to.number, null, parts, sentIntents, null)
            }

            val codes = withTimeoutOrNull(60_000) { List(parts.size) { results.receive() } }
                ?: return ToolOutcome.Failure(
                    "The phone didn't confirm the message to ${to.display} within a minute. It may still be sent; check the Messages app.",
                )
            val failed = codes.firstOrNull { it != Activity.RESULT_OK }
            return if (failed == null) {
                ToolOutcome.Success("SMS sent to ${to.display}.")
            } else {
                ToolOutcome.Failure("The SMS to ${to.display} failed (${describe(failed)}).")
            }
        } finally {
            context.unregisterReceiver(receiver)
            results.close()
        }
    }

    private fun describe(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no mobile service"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "mobile radio is off, maybe airplane mode"
        SmsManager.RESULT_ERROR_NULL_PDU -> "message could not be encoded"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "the network or SIM rejected it; check balance and signal"
        else -> "error code $code"
    }
}
