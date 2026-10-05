package com.murmur.app

/**
 * Fits a transcript into the text around the cursor. The model writes every dictation as a
 * sentence of its own ("See you tomorrow."), which reads wrong when it continues one:
 * "Great, See you tomorrow. and more".
 */
object Insertion {
    /** Dictation starting with these attaches to the previous word. */
    private const val NO_SPACE_BEFORE = ".,!?;:)]}'’”"

    /** Text right after these needs no space: brackets, opening quotes, mentions, tags. */
    private const val NO_SPACE_AFTER = "([{“‘@#/"

    /** A sentence ends at these, so the dictation keeps its capital. */
    private const val SENTENCE_END = ".!?…"

    /**
     * Words lowercased when they continue a sentence. Only common words: any other capital
     * may be a name ("John", "London"), which must keep it.
     */
    private val common = setOf(
        "a", "about", "actually", "after", "again", "all", "also", "an", "and", "any", "are",
        "as", "at", "be", "because", "been", "before", "but", "by", "can", "come", "could",
        "did", "do", "does", "don't", "each", "even", "every", "for", "from", "get", "give",
        "go", "going", "got", "had", "has", "have", "he", "her", "here", "him", "his", "how",
        "if", "in", "into", "is", "it", "it's", "its", "just", "know", "let", "let's", "like",
        "make", "maybe", "me", "more", "most", "my", "need", "no", "not", "now", "of", "okay",
        "on", "once", "only", "or", "our", "out", "please", "probably", "really", "said",
        "say", "see", "she", "should", "so", "some", "still", "such", "sure", "take", "tell",
        "than", "thank", "thanks", "that", "that's", "the", "their", "them", "then", "there",
        "there's", "these", "they", "think", "this", "those", "though", "to", "too", "until",
        "up", "us", "very", "want", "was", "we", "we're", "well", "were", "what", "when",
        "where", "which", "while", "who", "why", "will", "with", "would", "yes", "yet", "you",
        "you're", "your",
    )

    /** [text] as it should be inserted between [before] and [after], spaces included. */
    fun fit(before: String, text: String, after: String): String {
        if (text.isEmpty()) return text
        var t = text
        val prev = before.trimEnd(' ', '\t').lastOrNull()
        val continues = prev != null && prev != '\n' && prev !in SENTENCE_END
        if (continues) t = lowercaseFirstWord(t)
        val next = after.trimStart(' ', '\t').firstOrNull()
        // The sentence goes on after the cursor: the model's closing period doesn't belong.
        if (next != null && next.isLowerCase() && t.endsWith('.') && !t.endsWith("..")) {
            t = t.dropLast(1)
        }
        val last = before.lastOrNull()
        val lead = last != null && !last.isWhitespace() && last !in NO_SPACE_AFTER && t[0] !in NO_SPACE_BEFORE
        val trail = after.firstOrNull()?.isLetterOrDigit() == true
        return (if (lead) " " else "") + t + (if (trail) " " else "")
    }

    private fun lowercaseFirstWord(text: String): String {
        val word = text.takeWhile { it.isLetter() || it == '\'' || it == '’' }
        if (word.isEmpty() || !word[0].isUpperCase()) return text
        if (word.lowercase().replace('’', '\'') !in common) return text
        return text.replaceFirstChar { it.lowercaseChar() }
    }
}
