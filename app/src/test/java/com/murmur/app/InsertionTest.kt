package com.murmur.app

import org.junit.Assert.assertEquals
import org.junit.Test

class InsertionTest {
    private fun fit(before: String, text: String, after: String = "") = Insertion.fit(before, text, after)

    @Test
    fun emptyFieldKeepsTextAsIs() {
        assertEquals("See you tomorrow.", fit("", "See you tomorrow."))
    }

    @Test
    fun newSentenceKeepsCapitalAndGetsSpace() {
        assertEquals(" See you tomorrow.", fit("Thanks.", "See you tomorrow."))
        assertEquals("See you.", fit("Thanks. ", "See you."))
        assertEquals("See you.", fit("Hi\n", "See you."))
    }

    @Test
    fun continuingASentenceLowercasesCommonWords() {
        assertEquals("see you tomorrow.", fit("Great, ", "See you tomorrow."))
        assertEquals(" and then we left.", fit("We ate", "And then we left."))
        assertEquals(" it's fine.", fit("I think", "It's fine."))
    }

    @Test
    fun namesAndIKeepTheirCapital() {
        assertEquals("John is here.", fit("Great, ", "John is here."))
        assertEquals("I think so.", fit("Well, ", "I think so."))
        assertEquals("I'm in.", fit("Okay, ", "I'm in."))
        assertEquals("NASA called.", fit("Guess what, ", "NASA called."))
    }

    @Test
    fun periodDroppedWhenTheSentenceGoesOn() {
        assertEquals("the big ", fit("I saw ", "The big.", "dog"))
        assertEquals("the big", fit("I saw ", "The big.", " dog"))
        assertEquals("Hello.", fit("", "Hello.", " World"))
        assertEquals("Wait...", fit("", "Wait...", " and see"))
    }

    @Test
    fun spacing() {
        assertEquals(", thanks", fit("Hi", ", thanks"))
        assertEquals("there", fit("(", "There"))
        assertEquals("john", fit("@", "john"))
        assertEquals(" Hello ", fit("Say", "Hello", "world"))
        assertEquals(" so ", fit("Say", "So", "what"))
        assertEquals("", fit("Say", ""))
    }
}
