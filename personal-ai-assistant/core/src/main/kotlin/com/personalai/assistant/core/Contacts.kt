package com.personalai.assistant.core

import java.text.Normalizer

data class PhoneNumber(val number: String, val label: String)

data class Contact(val id: String, val name: String, val numbers: List<PhoneNumber>)

sealed interface ContactMatch {
    data class Found(val contact: Contact) : ContactMatch
    data class Ambiguous(val candidates: List<Contact>) : ContactMatch
    data object NotFound : ContactMatch
}

/**
 * Resolves spoken references like "Ahmed", "my father" or "nanna" to a contact.
 *
 * @param relationships relationship word (as the user saved it, e.g. "father") to the
 *   contact name it refers to. Comes from the personal memory.
 */
class ContactMatcher(private val relationships: Map<String, String> = emptyMap()) {

    fun match(query: String, contacts: List<Contact>): ContactMatch {
        val q = normalize(query).removePrefix("my ").trim()
        if (q.isEmpty()) return ContactMatch.NotFound

        // 1. A relationship the user taught the assistant ("Ahmed is my business partner").
        val relation = RelationshipWords.canonical(q)
        val remembered = relationships.entries.firstOrNull { (word, _) ->
            val w = normalize(word)
            w == q || (relation != null && RelationshipWords.canonical(w) == relation)
        }
        if (remembered != null) {
            val result = matchByName(normalize(remembered.value), contacts)
            if (result != ContactMatch.NotFound) return result
        }

        // 2. The contact is saved under the relationship word itself ("Dad", "Amma").
        if (relation != null) {
            val synonyms = RelationshipWords.synonymsOf(relation)
            val hits = contacts.filter { c -> nameTokens(c.name).any { it in synonyms } }
            when (hits.size) {
                1 -> return ContactMatch.Found(hits.single())
                in 2..Int.MAX_VALUE -> return ContactMatch.Ambiguous(hits)
            }
        }

        return matchByName(q, contacts)
    }

    private fun matchByName(q: String, contacts: List<Contact>): ContactMatch {
        val exact = contacts.filter { normalize(it.name) == q }
        pick(exact)?.let { return it }

        val queryTokens = q.split(' ').filter { it.isNotEmpty() }
        val allTokens = contacts.filter { c ->
            val tokens = nameTokens(c.name)
            queryTokens.all { qt -> tokens.any { it == qt } }
        }
        pick(allTokens)?.let { return it }

        val prefix = contacts.filter { c ->
            val tokens = nameTokens(c.name)
            queryTokens.all { qt -> tokens.any { it.startsWith(qt) } }
        }
        pick(prefix)?.let { return it }

        return ContactMatch.NotFound
    }

    private fun pick(hits: List<Contact>): ContactMatch? = when {
        hits.isEmpty() -> null
        hits.size == 1 -> ContactMatch.Found(hits.single())
        else -> ContactMatch.Ambiguous(hits.distinctBy { it.id })
    }

    private fun nameTokens(name: String): List<String> =
        normalize(name).split(' ').filter { it.isNotEmpty() }

    companion object {
        fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
                .replace(Regex("[^\\p{L}\\p{N}+ ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}

/** Common English, Hindi/Urdu and Telugu words for family relationships. */
object RelationshipWords {
    private val groups: Map<String, Set<String>> = mapOf(
        "father" to setOf("father", "dad", "daddy", "papa", "pappa", "appa", "abba", "abbu", "nanna", "pitaji", "baba"),
        "mother" to setOf("mother", "mom", "mum", "mummy", "mommy", "amma", "ammi", "maa", "mata", "mataji"),
        "brother" to setOf("brother", "bro", "bhai", "bhaiya", "anna", "annayya", "tammudu"),
        "sister" to setOf("sister", "sis", "didi", "behen", "akka", "chelli", "chellelu"),
        "wife" to setOf("wife", "biwi", "bharya", "bhaarya", "wifey"),
        "husband" to setOf("husband", "hubby", "pati", "bharta", "mogudu"),
        "son" to setOf("son", "beta", "koduku"),
        "daughter" to setOf("daughter", "beti", "kuthuru"),
        "grandfather" to setOf("grandfather", "grandpa", "dada", "nana", "thatha", "tata"),
        "grandmother" to setOf("grandmother", "grandma", "dadi", "nani", "ammamma", "nanamma", "avva"),
    )

    /** The canonical relationship for [word], or null if it isn't a relationship word. */
    fun canonical(word: String): String? {
        val w = ContactMatcher.normalize(word)
        return groups.entries.firstOrNull { (_, words) -> w in words }?.key
    }

    fun synonymsOf(canonical: String): Set<String> = groups[canonical].orEmpty()
}
