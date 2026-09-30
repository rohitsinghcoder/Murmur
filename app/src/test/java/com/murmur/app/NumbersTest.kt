package com.murmur.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NumbersTest {
    private fun check(input: String, expected: String) =
        assertEquals("format(\"$input\")", expected, Numbers.format(input))

    private fun kept(vararg inputs: String) {
        for (input in inputs) check(input, input)
    }

    @Test fun smallNumbersStayWords() {
        kept(
            "I have one thing and two of them.",
            "I met no one there. Someone called. The one I want. One of them.",
            "I'll be there at five.", "Around five.", "From nine to five.",
            "Seven eleven.", "Five ten.", "One, two, three.",
        )
    }

    @Test fun tensAndCompounds() {
        check("She is twenty one.", "She is 21.")
        check("Twenty one pilots.", "21 pilots.")
        check("Ninety nine problems.", "99 problems.")
        check("I scored a ten out of ten.", "I scored a 10 out of 10.")
        check("Ten to five.", "10 to five.")
        check("twenty parties", "20 parties")
        check("Twenty-five years", "25 years")
        check("a ten-year-old", "a 10-year-old")
    }

    @Test fun hundredsAndScales() {
        check("I have one hundred and twenty three apples.", "I have 123 apples.")
        check("One hundred.", "100.")
        check("One thousand.", "1000.")
        check("Two hundred thousand.", "200,000.")
        check("ten thousand", "10,000")
        check("one million two hundred thousand", "1,200,000")
        check("Five million people.", "5 million people.")
        check("two thousand and five", "2005")
        check("nineteen hundred", "1900")
    }

    @Test fun articleNumbersStayWords() {
        kept(
            "I want a hundred dollars.", "A hundred people came.", "a million",
            "a hundred and fifty", "a thousand five hundred", "a thousand and one nights",
        )
    }

    @Test fun years() {
        check("It was in nineteen ninety nine.", "It was in 1999.")
        check("twenty twenty four", "2024")
        check("twenty twenty", "2020")
        check("twenty ten", "2010")
        check("Twenty oh five.", "2005.")
        check("nineteen oh five", "1905")
        kept("The nineteen sixties were fun.", "the twenty twenties", "eighteen hundreds")
    }

    @Test fun decimals() {
        check("It is three point one four.", "It is 3.14.")
        check("Zero point five.", "0.5.")
        check("three point oh five", "3.05")
        check("two point five million", "2.5 million")
        kept("point five", "Point being, we need", "The one point I want to make.", "three point")
    }

    @Test fun percentAndMoney() {
        check("We got fifty percent.", "We got 50%.")
        check("I'm at four percent.", "I'm at 4%.")
        check("thirty two point five percent", "32.5%")
        check("one hundred per cent", "100%")
        check("It costs twenty five dollars.", "It costs $25.")
        check("Twenty-five dollars.", "$25.")
        check("five dollars", "$5")
        check("Twenty five thousand dollars.", "$25,000.")
        check("two million dollars", "$2 million")
        check("fifty rupees", "₹50")
        check("ten euros", "€10")
        kept("a five-dollar bill")
    }

    @Test fun times() {
        check("Meet at three thirty.", "Meet at 3:30.")
        check("Meet me at three thirty pm.", "Meet me at 3:30 PM.")
        check("I wake up at seven am.", "I wake up at 7 AM.")
        check("at twelve oh five", "at 12:05")
        check("at eleven thirty", "at 11:30")
        check("around ten fifteen", "around 10:15")
        check("It ends at nine p.m. Then we go.", "It ends at 9 PM. Then we go.")
        check("five o'clock", "5 o'clock")
        kept("Eleven thirty.", "three thirty")
    }

    @Test fun timesAvoidFalsePositives() {
        kept(
            "Which one am I?", "So two am I.",
            "it's about three fifty dollars", "at five fifty percent",
        )
    }

    @Test fun spelledDigits() {
        check("Call me at nine eight seven six five four three two one zero.", "Call me at 9876543210.")
        check("nine one one", "911")
        check("two oh two", "202")
        check("zero zero seven", "007")
    }

    @Test fun ordinals() {
        check("Twenty-first century.", "21st century.")
        check("the twenty first of May", "the 21st of May")
        check("twenty-third", "23rd")
        check("the thirty fourth time", "the 34th time")
        kept(
            "Wait a second. The second one.", "a twenty second clip", "twenty-second",
            "one hundred and first", "the twentieth time", "one hundredth", "the first one",
        )
        check("Two seconds.", "Two seconds.")
        check("twenty seconds", "20 seconds")
    }

    @Test fun specialForms() {
        check("It's twenty four seven.", "It's 24/7.")
        kept("Fifty-fifty.", "The oh no moment.", "Oh, one more thing.")
    }

    @Test fun textWithoutNumbersIsReturnedAsIs() {
        kept("", "Hello there.", "Wait a second.", "No digits 123 here or 4.5 either.")
    }

    @Test fun alreadyFormattedTextIsStable() {
        for (s in listOf("It costs $25.", "Meet at 3:30 PM.", "21st century", "5 million people", "2024", "50%")) {
            check(s, s)
        }
    }

    @Test fun keepsSurroundingPunctuationAndCase() {
        check("Twenty people came, then thirty.", "20 people came, then 30.")
        check("(twenty five)", "(25)")
        check("\"Ninety nine\" she said", "\"99\" she said")
    }
}
