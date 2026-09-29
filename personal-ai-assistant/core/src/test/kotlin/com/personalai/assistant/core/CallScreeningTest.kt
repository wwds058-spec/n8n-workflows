package com.personalai.assistant.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallScreeningTest {

    private val rules = RuleEngine.defaultRules().mapIndexed { i, r -> r.copy(id = i + 1L) }

    private fun decide(caller: IncomingCaller) = RuleEngine.evaluate(caller, rules)

    @Test
    fun `numbers in different formats match`() {
        assertTrue(PhoneNumbers.sameNumber("+91 98765 43210", "098765-43210"))
        assertTrue(PhoneNumbers.sameNumber("9876543210", "+919876543210"))
        assertFalse(PhoneNumbers.sameNumber("9876543210", "9876543211"))
        assertNull(PhoneNumbers.matchKey("12"))
        assertEquals("+919876543210", PhoneNumbers.clean("0091 98765 43210"))
    }

    @Test
    fun `contacts ring without a notification`() {
        val d = decide(IncomingCaller("+919876543210", contactName = "Ahmed Khan"))
        assertEquals(ScreeningAction.ALLOW, d.action)
        assertFalse(d.notify)
        assertEquals("Contacts", d.ruleName)
    }

    @Test
    fun `unknown callers ring and notify`() {
        val d = decide(IncomingCaller("+919000000000"))
        assertEquals(ScreeningAction.ALLOW, d.action)
        assertTrue(d.notify)
        assertEquals("Unknown callers", d.ruleName)
    }

    @Test
    fun `spam is silenced and blocked is rejected`() {
        assertEquals(ScreeningAction.SILENCE, decide(IncomingCaller("+911400000000", assignedCategory = CallerCategory.SPAM)).action)
        assertEquals(ScreeningAction.REJECT, decide(IncomingCaller("+911234567890", assignedCategory = CallerCategory.BLOCKED)).action)
    }

    @Test
    fun `important category beats the contacts rule`() {
        val d = decide(IncomingCaller("+919876543210", contactName = "Boss", assignedCategory = CallerCategory.IMPORTANT))
        assertEquals("Important callers", d.ruleName)
        assertTrue(d.notify)
    }

    @Test
    fun `hidden numbers notify`() {
        val d = decide(IncomingCaller(null))
        assertEquals("Hidden numbers", d.ruleName)
        assertEquals("Hidden number", IncomingCaller(null).displayName)
    }

    @Test
    fun `relationships imply a category`() {
        assertEquals(CallerCategory.FAMILY, IncomingCaller("1234567890", "Nanna", relationship = "father").category)
        assertEquals(CallerCategory.BUSINESS, IncomingCaller("1234567890", "Ahmed", relationship = "business partner").category)
        // An explicit category wins over the relationship.
        assertEquals(CallerCategory.SPAM, IncomingCaller("1234567890", "X", "father", CallerCategory.SPAM).category)
    }

    @Test
    fun `disabled rules are skipped and no match rings normally`() {
        val onlyUnknown = rules.map { if (it.name == "Unknown callers") it.copy(enabled = false) else it }
        val d = RuleEngine.evaluate(IncomingCaller("+919000000000"), onlyUnknown)
        assertEquals(ScreeningAction.ALLOW, d.action)
        assertFalse(d.notify)
        assertNull(d.ruleName)
    }

    @Test
    fun `custom prefix rule with OR matches either condition`() {
        val telemarketing = CallRule(
            id = 99, name = "Telemarketing", priority = 5, matchMode = MatchMode.ANY,
            conditions = listOf(
                Condition(ConditionField.NUMBER_STARTS_WITH, value = "140"),
                Condition(ConditionField.CATEGORY, value = "BUSINESS"),
            ),
            action = ScreeningAction.SILENCE, notify = false,
        )
        val all = rules + telemarketing
        assertEquals("Telemarketing", RuleEngine.evaluate(IncomingCaller("+911401234567"), all).ruleName)
        assertEquals("Telemarketing", RuleEngine.evaluate(IncomingCaller("1409876543"), all).ruleName)
        assertEquals("Unknown callers", RuleEngine.evaluate(IncomingCaller("+919876543210"), all).ruleName)
    }

    @Test
    fun `IS NOT and category NONE work`() {
        val c = Condition(ConditionField.CATEGORY, ConditionOperator.IS, "NONE")
        assertTrue(c.matches(IncomingCaller("1234567890")))
        assertFalse(c.matches(IncomingCaller("1234567890", assignedCategory = CallerCategory.SPAM)))
        val notContacts = Condition(ConditionField.IN_CONTACTS, ConditionOperator.IS_NOT, "true")
        assertTrue(notContacts.matches(IncomingCaller("1234567890")))
        assertEquals("caller is not in contacts", notContacts.describe())
    }

    @Test
    fun `conditions round-trip through the codec`() {
        val conditions = listOf(
            Condition(ConditionField.NUMBER_STARTS_WITH, ConditionOperator.IS_NOT, "140"),
            Condition(ConditionField.CATEGORY, value = "SPAM"),
        )
        assertEquals(conditions, ConditionCodec.decode(ConditionCodec.encode(conditions)))
        assertTrue(ConditionCodec.decode("GARBAGE|IS|x\n").isEmpty())
    }
}
