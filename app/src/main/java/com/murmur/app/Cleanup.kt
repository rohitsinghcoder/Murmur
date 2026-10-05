package com.murmur.app

/**
 * Tidies a transcript before it is shown or typed: drops hesitation sounds like "um" and "uh".
 * Everything here is a pure function of the recognizer's text, so the live partial and the
 * final transcript are tidied the same way, and tidying twice changes nothing.
 */
object Cleanup {
    // um, umm, uhm, uh, uhh, ah, ahh, aah, er, erm, hm, hmm, mm, with a comma on either side,
    // or a dash after it. "er" and "ah" are only filler on their own: the lookarounds keep
    // "err", "ahead" and similar words, and hyphenated "uh-huh", "uh-oh" and "mm-hmm", which
    // mean something. Only the first letter may be a capital, so "ER", "UM" and "MM" (words
    // in their own right) stay, and "mm" after a number is millimetres.
    private val filler = Regex(
        """,?\s*(?<![\w'-])(?:[Uu]u*h*m+|[Uu]u*h+|[Aa]a*h+|[Ee]e*rm*|[Hh]h*m+|(?<!\d\s?)[Mm]m+)(?![\w'-])(?:,|\s*[—–])?""",
    )
    /** "uh huh", "uh oh", "mm hmm" and "um hum" mean yes / oops: hyphenate them so they stay. */
    private val backchannel = Regex("""(?<![\w'-])([Uu]h(?= (?:huh|oh)\b)|[Mm]m(?= hm+\b)|[Uu]m(?= hum\b)) """)
    /** A lone lowercase "i", or "i'm", "i'll", "i've", "i'd"; not the "i" in "i.e.". */
    private val loneI = Regex("""(?<![\w'.-])i(?=(?:'(?:m|ll|ve|d)\b)|[\s,;:?!)"]|\.(?!\w)|$)""")
    /**
     * A small word said twice, a stutter: "I I think", "the the". Only words that are never
     * meant twice; "that that", "had had" and "so so" can be.
     */
    private val repeated = Regex(
        """(?<![\w'-])(I|the|a|an|to|and|we|it|of)(?:\s+\1)+(?![\w'-])""",
        RegexOption.IGNORE_CASE,
    )
    private val leadingPunct = charArrayOf(',', '.', '?', '!', ';', ':', ' ', '—', '–', '-', '…')
    private val closers = ",.;:?!)]…"
    private val openers = "\"“("
    private val pairClosers = "\"”)"

    /**
     * Everything applied to a transcript: fillers and stutters out, "i" as "I", numbers as
     * digits. These rules are English: other languages ([english] false) are left as spoken
     * ("um" is "a" in Portuguese, "er" is "he" in German). [live] text is still being spoken,
     * so a number at its very end stays words until it is finished (see [Numbers.format]).
     */
    fun tidy(text: String, english: Boolean = true, live: Boolean = false): String =
        if (!english) text else Numbers.format(collapseRepeats(capitalizeI(removeFillers(text))), live)

    fun capitalizeI(text: String): String =
        if (text.indexOf('i') < 0) text else loneI.replace(text, "I")

    fun collapseRepeats(text: String): String =
        if (!repeated.containsMatchIn(text)) text else repeated.replace(text, "$1")

    fun removeFillers(input: String): String {
        val text = if (backchannel.containsMatchIn(input)) backchannel.replace(input, "$1-") else input
        if (!filler.containsMatchIn(text)) return text
        val out = StringBuilder()
        // Straight quotes in [out], to tell an opening quote from a closing one.
        var quotes = 0
        // A filler was just dropped: the next text decides the spacing and capitalization.
        var pending = false
        var fillerCapitalized = false

        fun put(s: String) {
            out.append(s)
            quotes += s.count { it == '"' }
        }

        fun append(segment: String) {
            if (!pending) {
                put(segment)
                return
            }
            val before = out.lastOrNull()
            val insideQuote = before == '"' && quotes % 2 == 1
            val opensClause = before == null || before == '(' || before == '“' || insideQuote
            val startsSentence = before == null || before in ".?!"
            // "Um." at the start of a sentence leaves only its punctuation behind: drop it too.
            var s = if (opensClause || startsSentence) segment.trimStart(*leadingPunct) else segment.trimStart()
            if (s.isEmpty()) return
            pending = false
            if (startsSentence || (opensClause && fillerCapitalized)) {
                s = s.replaceFirstChar { it.uppercaseChar() }
            }
            val closesQuote = s[0] == '"' && quotes % 2 == 1
            if (!opensClause && s[0] !in closers && s[0] != '”' && !closesQuote) out.append(' ')
            put(s)
        }

        var pos = 0
        for (m in filler.findAll(text)) {
            // A filler alone in quotes or brackets ("um", (uh)) is being talked about: keep it.
            val word = m.value.indexOfFirst { it.isLetter() } + m.range.first
            if (text.getOrNull(word - 1)?.let { it in openers } == true &&
                text.getOrNull(m.range.last + 1)?.let { it in pairClosers } == true
            ) continue
            append(text.substring(pos, m.range.first))
            if (!pending) {
                while (out.isNotEmpty() && out.last().isWhitespace()) out.setLength(out.length - 1)
                pending = true
                fillerCapitalized = m.value.firstOrNull { it.isLetter() }?.isUpperCase() == true
            }
            pos = m.range.last + 1
        }
        append(text.substring(pos))
        return out.toString()
    }
}
