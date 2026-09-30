package com.murmur.app

/**
 * Tidies a transcript before it is shown or typed: drops hesitation sounds like "um" and "uh".
 * Everything here is a pure function of the recognizer's text, so the live partial and the
 * final transcript are tidied the same way, and tidying twice changes nothing.
 */
object Cleanup {
    // um, umm, uhm, uh, uhh, ah, ahh, aah, er, erm, hm, hmm, mm, with a comma on either side.
    // "er" and "ah" are only filler on their own: the lookarounds keep "err", "ahead" and
    // similar words, and hyphenated "uh-huh", "uh-oh" and "mm-hmm", which mean something.
    private val filler = Regex(
        """,?\s*(?<![\w'-])(?:u+h*m+|u+h+|a+h+|e+rm*|h+m+|m{2,})(?![\w'-]),?""",
        RegexOption.IGNORE_CASE,
    )
    /** A lone lowercase "i", or "i'm", "i'll", "i've", "i'd"; not the "i" in "i.e.". */
    private val loneI = Regex("""(?<![\w'.-])i(?=(?:'(?:m|ll|ve|d)\b)|[\s,;:?!)"]|\.(?!\w)|$)""")
    private val leadingPunct = charArrayOf(',', '.', '?', '!', ';', ':', ' ')
    private val closers = ",.;:?!)]…"

    /** Everything applied to a transcript: fillers out, "i" as "I", numbers as digits. */
    fun tidy(text: String) = Numbers.format(capitalizeI(removeFillers(text)))

    fun capitalizeI(text: String): String =
        if (text.indexOf('i') < 0) text else loneI.replace(text, "I")

    fun removeFillers(text: String): String {
        if (!filler.containsMatchIn(text)) return text
        val out = StringBuilder()
        // A filler was just dropped: the next text decides the spacing and capitalization.
        var pending = false
        var fillerCapitalized = false

        fun append(segment: String) {
            if (!pending) {
                out.append(segment)
                return
            }
            val before = out.lastOrNull()
            val insideQuote = before == '"' && out.count { it == '"' } % 2 == 1
            val opensClause = before == null || before == '(' || before == '“' || insideQuote
            val startsSentence = before == null || before in ".?!"
            // "Um." at the start of a sentence leaves only its punctuation behind: drop it too.
            var s = if (opensClause || startsSentence) segment.trimStart(*leadingPunct) else segment.trimStart()
            if (s.isEmpty()) return
            pending = false
            if (startsSentence || (opensClause && fillerCapitalized)) {
                s = s.replaceFirstChar { it.uppercaseChar() }
            }
            val closesQuote = s[0] == '"' && out.count { it == '"' } % 2 == 1
            if (!opensClause && s[0] !in closers && s[0] != '”' && !closesQuote) out.append(' ')
            out.append(s)
        }

        var pos = 0
        for (m in filler.findAll(text)) {
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
