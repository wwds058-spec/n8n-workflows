package com.personalai.assistant.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ContactMatcherTest {

    private val ahmedKhan = Contact("1", "Ahmed Khan", listOf(PhoneNumber("+911111111111", "Mobile")))
    private val ahmedAli = Contact("2", "Ahmed Ali", listOf(PhoneNumber("+912222222222", "Work")))
    private val ravi = Contact("3", "Ravi Kumar", listOf(PhoneNumber("+913333333333", "Mobile")))
    private val nanna = Contact("4", "Nanna", listOf(PhoneNumber("+914444444444", "Mobile")))
    private val contacts = listOf(ahmedKhan, ahmedAli, ravi, nanna)

    @Test
    fun `first name that matches one contact is found`() {
        val match = ContactMatcher().match("Ravi", contacts)
        assertEquals(ContactMatch.Found(ravi), match)
    }

    @Test
    fun `shared first name is ambiguous`() {
        val match = assertIs<ContactMatch.Ambiguous>(ContactMatcher().match("ahmed", contacts))
        assertEquals(setOf("1", "2"), match.candidates.map { it.id }.toSet())
    }

    @Test
    fun `full name resolves ambiguity`() {
        assertEquals(ContactMatch.Found(ahmedAli), ContactMatcher().match("Ahmed Ali", contacts))
    }

    @Test
    fun `relationship synonym finds contact saved under another word`() {
        // Saved as "Nanna" (Telugu for father); the user says "my father".
        assertEquals(ContactMatch.Found(nanna), ContactMatcher().match("my father", contacts))
        assertEquals(ContactMatch.Found(nanna), ContactMatcher().match("Dad", contacts))
    }

    @Test
    fun `remembered relationship wins`() {
        val matcher = ContactMatcher(mapOf("business partner" to "Ahmed Khan"))
        assertEquals(ContactMatch.Found(ahmedKhan), matcher.match("my business partner", contacts))
    }

    @Test
    fun `remembered relationship matches synonyms`() {
        val matcher = ContactMatcher(mapOf("brother" to "Ravi Kumar"))
        assertEquals(ContactMatch.Found(ravi), matcher.match("bhai", contacts))
    }

    @Test
    fun `unknown name is not found`() {
        assertEquals(ContactMatch.NotFound, ContactMatcher().match("Suresh", contacts))
    }

    @Test
    fun `accents and punctuation are ignored`() {
        val jose = Contact("5", "José O'Neil", emptyList())
        assertEquals(ContactMatch.Found(jose), ContactMatcher().match("jose o neil", listOf(jose)))
    }
}
