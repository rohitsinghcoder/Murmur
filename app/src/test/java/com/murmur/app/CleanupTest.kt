package com.murmur.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupTest {
    private fun fillers(input: String, expected: String) =
        assertEquals("removeFillers(\"$input\")", expected, Cleanup.removeFillers(input))

    private fun tidy(input: String, expected: String) =
        assertEquals("tidy(\"$input\")", expected, Cleanup.tidy(input))

    @Test fun leavesTextWithoutFillersUntouched() {
        val text = "Nothing to remove here, e.g. vim.  Two  spaces stay."
        assertSame(text, Cleanup.removeFillers(text))
        fillers("", "")
    }

    @Test fun removesFillerAtSentenceStart() {
        fillers("Um, I think so.", "I think so.")
        fillers("Uh, uh, okay.", "Okay.")
        fillers("Hmm, well.", "Well.")
        fillers("Um so what now", "So what now")
        fillers("Um. Uh. Okay.", "Okay.")
    }

    @Test fun removesFillerMidSentenceWithItsCommas() {
        fillers("I think, um, we should go.", "I think we should go.")
        fillers("So, uh, what's up?", "So what's up?")
        fillers("I was like, um, really tired.", "I was like really tired.")
        fillers("I think um we should", "I think we should")
        fillers("I had an erm moment.", "I had an moment.")
    }

    @Test fun keepsSentencePunctuationAfterFiller() {
        fillers("Well, um. Next sentence.", "Well. Next sentence.")
        fillers("Yes, um. Uh, okay.", "Yes. Okay.")
        fillers("Yes, um.", "Yes.")
        fillers("What? Um, yes.", "What? Yes.")
    }

    @Test fun capitalizesNewSentenceStartOnly() {
        fillers("Hello. um, so", "Hello. So")
        // Nothing outside the removed filler is re-cased.
        fillers("I use e.g. vim, um, daily.", "I use e.g. vim daily.")
        fillers("iPhone, um, is good.", "iPhone is good.")
        fillers("I said, um, no.", "I said no.")
    }

    @Test fun onlyFillersLeavesNothing() {
        fillers("Hmm.", "")
        fillers("Um", "")
        fillers("um um uh", "")
        fillers("Mm, Hmm? What?", "What?")
    }

    @Test fun trailingFillerInPartialText() {
        fillers("I think, um", "I think")
        fillers("I think um", "I think")
    }

    @Test fun handlesQuotesAndParentheses() {
        fillers("He said, \"Um, no.\"", "He said, \"No.\"")
        fillers("(um, maybe)", "(maybe)")
        fillers("He said \"hi\" um, what", "He said \"hi\" what")
        fillers("He said \"hi, um\" twice", "He said \"hi\" twice")
    }

    @Test fun keepsRealWords() {
        val kept = listOf(
            "Ahead of the curve. Aha. Err on the side.",
            "The ohm is a unit. Umbrella. Uhuru. Hummus. Emma. Mmmbop's.",
            "Mm-hmm, that's right.",
            "Uh-huh, sure. Uh-oh. Um-hum.",
            "Like, so, well, you know, I mean it.",
            "Her name is Erma.",
        )
        for (text in kept) fillers(text, text)
    }

    @Test fun capitalizesLoneI() {
        assertEquals(
            "I think I'm right, i.e. I know. So do I. And I'll go, I've I'd",
            Cleanup.capitalizeI("i think i'm right, i.e. i know. So do i. And i'll go, i've i'd"),
        )
        val kept = "iPhone, iOS, pi, Wi-Fi, i.e. this, Hawaii, e-i"
        assertEquals(kept, Cleanup.capitalizeI(kept))
    }

    @Test fun tidyCombinesAllSteps() {
        tidy("Um, i paid twenty five dollars.", "I paid $25.")
        tidy("So, uh, meet at three thirty pm.", "So meet at 3:30 PM.")
        tidy("Uh, one thing.", "One thing.")
    }

    @Test fun tidyIsIdempotent() {
        val samples = listOf(
            "Um, I think so.", "He said, \"Um, no.\"", "Yes, um. Uh, okay.", "i think, um, i'm right",
            "Meet me at three thirty pm.", "It costs twenty five dollars.", "Call nine eight seven six.",
            "Twenty-first century.", "The nineteen sixties.", "fifty percent", "two million dollars",
            "a hundred and fifty", "Which one am I?", "at twelve oh five", "three point one four",
            "Well — um — fine.", "He said \"um\" a lot.", "Uh huh.", "I I think", "the the the end",
            "twenty five dollars and fifty cents", "one point five million dollars", "minus twenty degrees",
            "Um — so yeah", "I have twenty twenty-dollar bills.", "one two three go",
        )
        for (s in samples) {
            val once = Cleanup.tidy(s)
            assertEquals("tidy twice: \"$s\"", once, Cleanup.tidy(once))
        }
    }

    @Test fun growingPartialsAgreeWithFinal() {
        // The live text is re-tidied from the recognizer's running transcript each time, so
        // each prefix must be tidied the same way as the final text.
        val final = "Um, so I think, uh, we should meet at three thirty pm."
        val words = final.split(" ")
        for (n in 1..words.size) {
            val partial = words.take(n).joinToString(" ")
            val tidied = Cleanup.tidy(partial)
            assertEquals(tidied, Cleanup.tidy(tidied))
        }
        tidy(final, "So I think we should meet at 3:30 PM.")
        tidy("Um, so I think, uh, we", "So I think we")
    }

    @Test fun keepsCapitalizedWordsAndMillimetres() {
        val kept = listOf(
            "We went to the ER last night.", "I went to UM for college.", "Cut it to 5 mm.",
            "Cut it to 5mm.", "HMM is a model.", "AH and UH are airports.",
        )
        for (text in kept) fillers(text, text)
        fillers("Er, I went to UM.", "I went to UM.")
        fillers("Mm, okay.", "Okay.")
    }

    @Test fun removesDashOrEllipsisLeftByFiller() {
        fillers("Well — um — fine.", "Well — fine.")
        fillers("Yes — um, okay.", "Yes — okay.")
        fillers("Um — so yeah", "So yeah")
        fillers("Um – so yeah", "So yeah")
        fillers("Um - so yeah", "So yeah")
        fillers("Um… so yeah", "So yeah")
        fillers("I think, um… yes.", "I think… yes.")
    }

    @Test fun keepsQuotedOrBracketedFiller() {
        val kept = listOf("He said \"um\" a lot.", "\"Um,\" he said.", "I think (um) yes.", "The word “uh” is filler.")
        for (text in kept) fillers(text, text)
        fillers("(um, maybe)", "(maybe)")
    }

    @Test fun keepsSpacedBackchannels() {
        fillers("Uh huh.", "Uh-huh.")
        fillers("uh oh", "uh-oh")
        fillers("mm hmm, sure", "mm-hmm, sure")
        fillers("Um hum.", "Um-hum.")
        // Two fillers in a row are still fillers.
        fillers("Uh, uh, okay.", "Okay.")
        fillers("um um uh", "")
    }

    @Test fun collapsesStutters() {
        tidy("I I think so.", "I think so.")
        tidy("And, um, the the problem is", "And the problem is")
        tidy("I, um, I think so.", "I think so.")
        tidy("The the end.", "The end.")
        tidy("i i know", "I know")
        // Words that can be meant twice stay.
        val kept = listOf("It was so so.", "That that is true.", "He had had enough.", "Bye bye.", "Theme the them.")
        for (text in kept) tidy(text, text)
    }

    @Test fun leavesOtherLanguagesAsSpoken() {
        for (text in listOf("Er sagt, um zehn Uhr kommt er.", "Eu tenho um carro.", "Ho visto i ragazzi.", "twenty five")) {
            assertSame(text, Cleanup.tidy(text, english = false))
        }
    }

    @Test fun liveTextLeavesTrailingNumberAsWords() {
        fun live(input: String, expected: String) =
            assertEquals("tidy(\"$input\", live)", expected, Cleanup.tidy(input, live = true))
        live("I paid one hundred", "I paid one hundred")
        live("I paid one hundred and", "I paid one hundred and")
        live("I paid one hundred twenty five dollars", "I paid $125")
        live("In twenty twenty", "In twenty twenty")
        live("In twenty twenty five I", "In 2025 I")
        live("Um, at three thirty", "At three thirty")
        live("We got fifty percent", "We got 50%")
        live("It's twenty.", "It's 20.")
        // Each live step shows either the words or the final digits, never a wrong number.
        val final = "I paid one hundred twenty five dollars at three thirty pm"
        val words = final.split(" ")
        val steps = (1..words.size).map { Cleanup.tidy(words.take(it).joinToString(" "), live = true) }
        assertEquals(listOf("I", "I paid", "I paid one", "I paid one hundred", "I paid one hundred twenty",
            "I paid one hundred twenty five", "I paid $125", "I paid $125 at", "I paid $125 at three",
            "I paid $125 at three thirty", "I paid $125 at 3:30 PM"), steps)
        assertEquals(Cleanup.tidy(final), steps.last())
        for (s in steps) assertEquals(s, Cleanup.tidy(s, live = true))
    }

    @Test fun handlesLongTextQuickly() {
        val text = List(500) { "Um, I think, uh, we need twenty five dollars at three thirty pm." }.joinToString(" ")
        Cleanup.tidy(text) // warm up
        val start = System.nanoTime()
        val out = Cleanup.tidy(text)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(List(500) { "I think we need $25 at 3:30 PM." }.joinToString(" "), out)
        // Generous bound so slow CI machines don't flake; a regression to quadratic work would blow it.
        assertTrue("tidy took $ms ms", ms < 2_000)
    }
}
