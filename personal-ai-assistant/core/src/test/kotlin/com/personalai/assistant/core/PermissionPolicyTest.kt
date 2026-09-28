package com.personalai.assistant.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionPolicyTest {

    private fun spec(name: String, level: PermissionLevel) = ToolSpec(name, "", emptyList(), level)

    @Test
    fun `automatic never asks`() {
        assertFalse(PermissionPolicy().requiresConfirmation(spec("read_calendar", PermissionLevel.AUTOMATIC)))
    }

    @Test
    fun `configurable asks until the user auto-approves it`() {
        val call = spec("call_contact", PermissionLevel.USER_CONFIGURABLE)
        assertTrue(PermissionPolicy().requiresConfirmation(call))
        assertFalse(PermissionPolicy(setOf("call_contact")).requiresConfirmation(call))
    }

    @Test
    fun `higher levels cannot be auto-approved`() {
        val policy = PermissionPolicy(setOf("send_sms", "forget_memory"))
        assertTrue(policy.requiresConfirmation(spec("send_sms", PermissionLevel.CONFIRMATION_REQUIRED)))
        assertTrue(policy.requiresConfirmation(spec("forget_memory", PermissionLevel.ALWAYS_CONFIRM)))
    }
}
