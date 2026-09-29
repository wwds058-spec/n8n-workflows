package com.personalai.assistant.calls

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import com.personalai.assistant.AssistantApp
import com.personalai.assistant.core.IncomingCaller
import com.personalai.assistant.core.ScreeningAction
import com.personalai.assistant.core.ScreeningDecision
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android calls this while an incoming call is ringing, once the user has made this app
 * the phone's "caller ID & spam" app. It can let the call ring, silence it or reject it.
 * It cannot answer the call or hear or speak on it.
 *
 * Android usually doesn't send calls from saved contacts to a screening app that isn't
 * the default phone app; those ring normally without reaching this service.
 */
class CallScreener : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            callDetails.callDirection != Call.Details.DIRECTION_INCOMING
        ) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        val container = (application as AssistantApp).container
        val repo = container.callScreening
        val raw = callDetails.handle?.schemeSpecificPart

        // Telecom waits only a few seconds for an answer, so decide quickly and fall back
        // to letting the call ring. The history write and notification happen afterwards.
        container.appScope.launch {
            val result = withTimeoutOrNull(DECISION_TIMEOUT_MS) {
                val caller = repo.identify(raw)
                caller to repo.decide(caller)
            }
            val caller = result?.first ?: IncomingCaller(raw)
            val decision = result?.second ?: ScreeningDecision(
                ScreeningAction.ALLOW,
                notify = true,
                ruleName = null,
                reason = "Screening took too long, so the call rang normally.",
            )
            val applied = applicable(decision)
            respondToCall(callDetails, response(applied.action))
            repo.record(caller, applied)
        }
    }

    /** Silencing needs Android 10; on older versions the call rings and the user is notified. */
    private fun applicable(decision: ScreeningDecision): ScreeningDecision =
        if (decision.action == ScreeningAction.SILENCE && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            decision.copy(action = ScreeningAction.ALLOW, notify = true, reason = decision.reason + " Silencing needs Android 10, so it rang.")
        } else {
            decision
        }

    private fun response(action: ScreeningAction): CallResponse {
        val builder = CallResponse.Builder()
        when (action) {
            ScreeningAction.ALLOW -> Unit
            ScreeningAction.SILENCE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setSilenceCall(true)
            ScreeningAction.REJECT -> builder.setDisallowCall(true).setRejectCall(true)
        }
        return builder.build()
    }

    private companion object {
        const val DECISION_TIMEOUT_MS = 3_000L
    }
}
