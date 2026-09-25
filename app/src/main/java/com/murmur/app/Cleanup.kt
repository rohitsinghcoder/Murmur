package com.murmur.app

/** Tidies a transcript before it is shown or typed: drops hesitation sounds like "um" and "uh". */
object Cleanup {
    // um, umm, uhm, uh, uhh, ah, ahh, aah, er, erm, hm, hmm, mm. "er" and "ah" are only filler
    // on their own; the word boundaries keep "err", "ahead" and similar words intact.
    private val filler = Regex(
        """,?\s*\b(?:u+h*m+|u+h+|a+h+|e+rm*|h+m+|m{2,})\b,?""",
        RegexOption.IGNORE_CASE,
    )
    private val spaceBeforePunct = Regex("""\s+([,.?!])""")
    private val doubleComma = Regex(""",+([,.?!])""")
    private val sentenceStart = Regex("""([.?!]\s+)(\p{Ll})""")

    /** Everything applied to a transcript: fillers out, numbers as digits. */
    fun tidy(text: String) = Numbers.format(removeFillers(text))

    fun removeFillers(text: String): String {
        if (!filler.containsMatchIn(text)) return text
        val cleaned = filler.replace(text, " ")
            .replace(spaceBeforePunct, "$1")
            .replace(doubleComma, "$1")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trimStart(',', '.', '?', '!', ' ')
            // Removing a sentence-opening "Um," leaves the next word lowercase.
            .replace(sentenceStart) { it.groupValues[1] + it.groupValues[2].uppercase() }
        return cleaned.replaceFirstChar { it.uppercaseChar() }
    }
}
