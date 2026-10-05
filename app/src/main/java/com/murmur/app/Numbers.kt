package com.murmur.app

import java.util.Locale

/**
 * Writes spoken numbers the way people type them: "twenty twenty five" → 2025, "fifty
 * percent" → 50%, "three thirty pm" → 3:30 PM, "twenty five dollars" → $25. Small numbers on
 * their own stay words ("one thing", "two of them"), and anything ambiguous is left as spoken.
 */
object Numbers {
    private val units = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val teens = listOf(
        "ten", "eleven", "twelve", "thirteen", "fourteen",
        "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val scales = mapOf("thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L)
    private val connectors = setOf("and", "point", "oh")
    private val signs = setOf("minus", "negative")

    private val word = Regex("[A-Za-z']+")
    private val joiner = Regex("[ \\t-]+")

    // The patterns below are matched right after a number with [Regex.matchAt], so a long
    // transcript is never copied or scanned once per number.
    private val percent = Regex("[ -]?(?:percent|per cent)\\b", RegexOption.IGNORE_CASE)
    private val currency = Regex(" (dollars?|rupees?|euros?)\\b", RegexOption.IGNORE_CASE)
    /** Cents after the currency: "and fifty cents", "and five cents", or a bare "fifty" at the end. */
    private val cents = Regex(
        "( and)? (${(units.drop(1) + teens + tens).joinToString("|")})" +
            "(?:[ -](${units.drop(1).joinToString("|")}))?( cents?)?\\b",
        RegexOption.IGNORE_CASE,
    )
    private val ampm = Regex(" ?([ap])\\.? ?m(?![a-z])(\\.)?", RegexOption.IGNORE_CASE)
    /** "Which one am I?": a bare "am" followed by "I" is the verb. */
    private val amVerb = Regex(" ?am [Ii]\\b")
    /**
     * Without "am" or "pm", "at three thirty" is a time only where the clause ends or a time
     * word follows: "about two thirty people" and "after two twenty runs" are counts.
     */
    private val afterTime = Regex(
        "$|[,.;:?!)]| (?:tomorrow|today|tonight|then|on|in|and|or|sharp|so|I|we|you|he|she|they)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val keepsDigits = Regex(" (?:lakhs?|crores?|o'clock)\\b", RegexOption.IGNORE_CASE)
    private val decadeNext = Regex(
        " (?:twenties|thirties|forties|fifties|sixties|seventies|eighties|nineties|hundreds|thousands|millions)\\b",
        RegexOption.IGNORE_CASE,
    )
    /** An ordinal right after the number: "twenty-first", "one hundred and first", "twenty second". */
    private val ordinalNext = Regex(
        "(?:[ -]+and)?[ -]+(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|" +
            "tenth|eleventh|twelfth|\\w+teenth|\\w+ieth|hundredth|thousandth|millionth|billionth)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val ordinalUnits = mapOf(
        "first" to "1st", "third" to "3rd", "fourth" to "4th", "fifth" to "5th",
        "sixth" to "6th", "seventh" to "7th", "eighth" to "8th", "ninth" to "9th",
    )
    /** The run continues a number that was left as words: "a hundred and fifty". */
    private val scaleBefore = Regex("(?:hundred|thousand|million|billion)(?:[ -]+and)?[ -]+$", RegexOption.IGNORE_CASE)
    private val timeCues = setOf("at", "by", "around", "till", "until", "from", "after", "before", "about")

    private enum class Kind { None, Unit, Teen, Tens, Hundred, Scale }
    private val beforeUnit = setOf(Kind.None, Kind.Tens, Kind.Hundred, Kind.Scale)
    private val beforeTens = setOf(Kind.None, Kind.Hundred, Kind.Scale)

    /** One number within a run of number words. */
    private class Num(
        val value: Long,
        val words: Int,
        val decimals: String = "",
        val oh: Boolean = false,
        /** The number ended in "million" or "billion": written "5 million", not 5,000,000. */
        val scale: Pair<String, Long>? = null,
    ) {
        val digit get() = words == 1 && decimals.isEmpty() && value < 10
    }

    private fun small(w: String) = units.indexOf(w).takeIf { it >= 0 }
        ?: teens.indexOf(w).takeIf { it >= 0 }?.plus(10)
        ?: tens.indexOf(w).takeIf { it >= 0 }?.let { 20 + it * 10 }

    private fun isStart(w: String) = small(w) != null
    private fun isNumberWord(w: String) = isStart(w) || w == "hundred" || w in scales || w in connectors

    /** A word joined to the previous one by a hyphen, as in "twenty-dollar" or "first-time". */
    private fun hyphenWordAt(text: String, i: Int) = text.getOrNull(i) == '-' && text.getOrNull(i + 1)?.isLetter() == true

    /**
     * [live] text is still being spoken: a number at its very end may still grow ("one
     * hundred" → "one hundred twenty five"), so it stays words until something follows it.
     * Otherwise the live text would flick through 100, 120, 125 on the way.
     */
    fun format(text: String, live: Boolean = false): String {
        val words = word.findAll(text).toList()
        if (words.none { isStart(it.value.lowercase()) }) return text
        val out = StringBuilder()
        var pos = 0
        var i = 0
        while (i < words.size) {
            val first = words[i]
            if (first.range.first < pos || !isStart(first.value.lowercase())) {
                i++
                continue
            }
            // The run of number words joined only by spaces or hyphens.
            var j = i
            while (j + 1 < words.size &&
                joiner.matches(text.subSequence(words[j].range.last + 1, words[j + 1].range.first)) &&
                isNumberWord(words[j + 1].value.lowercase())
            ) j++
            if (live && (words[j].range.last + 1 until text.length).all { text[it].isWhitespace() }) break
            while (j > i && words[j].value.lowercase() in connectors) j--

            val run = words.subList(i, j + 1).map { it.value.lowercase() }
            val (items, used) = parse(run)
            val end = words[i + used - 1].range.last + 1
            val prevWord = words.getOrNull(i - 1)?.takeIf {
                (it.range.last + 1 until first.range.first).all { k -> text[k].isWhitespace() }
            }
            val prev = prevWord?.value?.lowercase()
            val continues = scaleBefore.containsMatchIn(text.substring(maxOf(0, first.range.first - 20), first.range.first))
            val written = if (continues) null else write(items, run.first(), prev, text, end)
            if (written != null) {
                // "minus twenty" → -20, but not in sums like "fifty minus twenty" or "x minus ten".
                val negative = prevWord != null && prev in signs && written.first[0].isDigit() &&
                    plainWordBefore(text, prevWord.range.first)
                val from = if (negative) prevWord!!.range.first else first.range.first
                out.append(text, pos, from).append(if (negative) "-" else "").append(written.first)
                pos = end + written.second
            }
            i += used
        }
        return out.append(text, pos, text.length).toString()
    }

    /** The token before [at] is absent or an ordinal word, not a number or a one-letter variable. */
    private fun plainWordBefore(text: String, at: Int): Boolean {
        var e = at
        while (e > 0 && text[e - 1].isWhitespace()) e--
        var s = e
        while (s > 0 && !text[s - 1].isWhitespace()) s--
        val token = text.substring(s, e).trimEnd(',', '.', ';', ':').lowercase()
        if (token.isEmpty()) return true
        return token.length > 1 && token.none { it.isDigit() } && !isNumberWord(token)
    }

    /**
     * Splits a run like "twenty twenty five" into numbers (20, 25). Stops early at an "and"
     * or "point" that doesn't belong to a number; returns the numbers and words used.
     */
    private fun parse(w: List<String>): Pair<List<Num>, Int> {
        val items = mutableListOf<Num>()
        var total = 0L
        var cur = 0L
        var last = Kind.None
        var count = 0
        var lastScale = Long.MAX_VALUE
        var scaleWord: String? = null
        fun reset() {
            total = 0; cur = 0; last = Kind.None; count = 0; lastScale = Long.MAX_VALUE; scaleWord = null
        }
        fun close() {
            if (count > 0) {
                val v = total + cur
                val s = scaleWord?.let { it to scales.getValue(it) }?.takeIf { v % it.second == 0L && it.second >= 1_000_000 }
                items += Num(v, count, scale = s)
            }
            reset()
        }

        var i = 0
        loop@ while (i < w.size) {
            val t = w[i]
            when (t) {
                "and" -> {
                    val next = w.getOrNull(i + 1)
                    if ((last == Kind.Hundred || last == Kind.Scale) && next != null && small(next) != null) {
                        count++
                        i++
                        continue@loop
                    }
                    break@loop
                }
                "point" -> {
                    var j = i + 1
                    val digits = StringBuilder()
                    while (j < w.size && (w[j] == "oh" || (small(w[j]) ?: 10) < 10)) {
                        digits.append(small(w[j]) ?: 0)
                        j++
                    }
                    if (count == 0 || digits.isEmpty()) break@loop
                    // "one point five million": kept as 1.5 million, like "5 million".
                    val scale = w.getOrNull(j)?.takeIf { (it == "million" || it == "billion") && total == 0L }
                    items += if (scale != null) {
                        val size = scales.getValue(scale)
                        Num(cur * size, count + 2 + digits.length, decimals = digits.toString(), scale = scale to size)
                    } else {
                        Num(total + cur, count + 1 + digits.length, decimals = digits.toString())
                    }
                    reset()
                    i = if (scale != null) j + 1 else j
                    continue@loop
                }
                "oh" -> {
                    close()
                    items += Num(0, 1, oh = true)
                    i++
                    continue@loop
                }
            }
            val v = small(t)
            val kind = when {
                v == null && t == "hundred" -> Kind.Hundred
                v == null -> Kind.Scale
                v < 10 -> Kind.Unit
                v < 20 -> Kind.Teen
                else -> Kind.Tens
            }
            val fits = when (kind) {
                Kind.Unit -> if (v == 0) last == Kind.None else last in beforeUnit
                Kind.Teen, Kind.Tens -> last in beforeTens
                Kind.Hundred -> (last == Kind.Unit || last == Kind.Teen) && cur in 1..99
                Kind.Scale -> last != Kind.None && cur > 0 && scales.getValue(t) < lastScale
                Kind.None -> false
            }
            if (!fits) {
                // "hundred" or "thousand" can't start a number: the run ends here.
                if (kind == Kind.Hundred || kind == Kind.Scale) break@loop
                close()
            }
            when (kind) {
                Kind.Hundred -> cur *= 100
                Kind.Scale -> {
                    val s = scales.getValue(t)
                    total += cur * s
                    cur = 0
                    lastScale = s
                    scaleWord = t
                }
                else -> cur += v!!
            }
            if (kind != Kind.Scale) scaleWord = null
            last = kind
            count++
            i++
        }
        close()
        return items to i
    }

    /**
     * The written form of a run ending at [end] in [text] and how many characters after it
     * were absorbed, or null to keep the words.
     */
    private fun write(items: List<Num>, firstWord: String, prev: String?, text: String, end: Int): Pair<String, Int>? {
        if (items.isEmpty()) return null
        // "point five percent": the "point" was left as a word, so the number can't be read alone.
        if (prev == "point") return null
        val a = items[0]

        // Ordinals stay words ("one hundred and first", "the twenty second"), except a tens
        // word and an ordinal unit written together: "twenty-first" → 21st.
        ordinalNext.matchAt(text, end)?.let {
            val unit = ordinalUnits[it.groupValues[1].lowercase()]
            val tens = a.value % 100 in 20..90 && a.value % 10 == 0L && a.decimals.isEmpty() && a.scale == null
            // "twenty first-time buyers" are twenty buyers.
            val compound = hyphenWordAt(text, it.range.last + 1)
            if (unit != null && items.size == 1 && tens && !compound && !it.value.contains("and", ignoreCase = true)) {
                return "${a.value / 10}${unit}" to it.value.length
            }
            return null
        }

        // Years: "nineteen ninety nine", "twenty twenty five", "twenty oh five". Not "twenty
        // twenty-dollar bills".
        if ((firstWord == "nineteen" || firstWord == "twenty") && !hyphenWordAt(text, end)) {
            if (items.size == 2 && a.words == 1 && items[1].decimals.isEmpty() && !items[1].oh && items[1].value in 10..99) {
                return "${a.value * 100 + items[1].value}" to 0
            }
            if (items.size == 3 && a.words == 1 && items[1].oh && items[2].digit && items[2].value > 0) {
                return "${a.value * 100 + items[2].value}" to 0
            }
        }
        if (items.size == 2 && a.value == 24L && a.words == 2 && items[1].digit && items[1].value == 7L) {
            return "24/7" to 0
        }

        // Times: "three thirty pm", "at ten fifteen", "seven am".
        val meridiem = ampm.matchAt(text, end)?.takeIf { amVerb.matchAt(text, end) == null }
        val unitNext = percent.matchAt(text, end) != null || currency.matchAt(text, end) != null // "about three fifty dollars"
        val cued = prev != null && prev in timeCues && afterTime.matchAt(text, end) != null
        if (a.words == 1 && a.value in 1..12 && !unitNext && (meridiem != null || cued)) {
            val minutes = when {
                items.size == 2 && items[1].decimals.isEmpty() && !items[1].oh && items[1].value in 10..59 -> items[1].value
                items.size == 3 && items[1].oh && items[2].digit && items[2].value > 0 -> items[2].value
                else -> null
            }
            val suffix = meridiem?.let {
                val m = " " + it.groupValues[1].uppercase() + "M"
                // Keep a sentence-ending period that was part of "p.m.".
                val next = (it.range.last + 1 until text.length).firstOrNull { k -> !text[k].isWhitespace() }
                val endsSentence = it.groupValues[2].isNotEmpty() && (next == null || text[next].isUpperCase())
                (if (endsSentence) "$m." else m) to it.value.length
            }
            if (minutes != null) {
                return "${a.value}:${minutes.toString().padStart(2, '0')}${suffix?.first.orEmpty()}" to (suffix?.second ?: 0)
            }
            if (items.size == 1 && suffix != null) return "${a.value}${suffix.first}" to suffix.second
        }

        // Spelled-out digits, like a phone number: "nine eight seven six". Not counting
        // ("one two three go").
        if (items.size >= 3 && items.all { it.digit || it.oh } &&
            items.withIndex().any { (k, n) -> n.oh || n.value != k + 1L }
        ) {
            return items.joinToString("") { it.value.toString() } to 0
        }

        // Anything else made of several numbers is ambiguous ("one two", "five ten"): keep it.
        if (items.size != 1) return null
        if (decadeNext.matchAt(text, end) != null) return null // "the nineteen sixties"
        percent.matchAt(text, end)?.let { return "${digits(a)}%" to it.value.length }
        currency.matchAt(text, end)?.let {
            val symbol = when (it.groupValues[1].lowercase().first()) {
                'd' -> "$"
                'r' -> "₹"
                else -> "€"
            }
            val c = if (a.scale == null && a.decimals.isEmpty()) cents(text, it.range.last + 1) else null
            val amount = digits(a) + (c?.let { (value, _) -> "." + value.toString().padStart(2, '0') } ?: "")
            return "$symbol$amount" to it.value.length + (c?.second ?: 0)
        }
        if (a.value >= 10 || a.decimals.isNotEmpty() || keepsDigits.matchAt(text, end) != null) return digits(a) to 0
        return null
    }

    /**
     * Cents after "dollars" at [at]: "and fifty cents", "fifty cents", or "fifty" ending the
     * clause ("twenty five dollars fifty"). Returns the cents and the characters they span.
     */
    private fun cents(text: String, at: Int): Pair<Int, Int>? {
        val m = cents.matchAt(text, at) ?: return null
        val (and, head, unit, word) = m.destructured
        val h = small(head.lowercase()) ?: return null
        if (unit.isNotEmpty() && (h < 20 || h % 10 != 0)) return null
        val value = h + (if (unit.isEmpty()) 0 else small(unit.lowercase()) ?: return null)
        if (value !in 1..99) return null
        // Without "cents", "five dollars and ten minutes" or "ten dollars fifty times" isn't money.
        val endsClause = (m.range.last + 1).let { k -> k == text.length || text[k] in ",.;:?!)" }
        if (word.isEmpty() && (and.isNotEmpty() || !endsClause)) return null
        return value to m.value.length
    }

    private fun digits(n: Num): String {
        val decimals = if (n.decimals.isNotEmpty()) "." + n.decimals else ""
        n.scale?.let { (word, size) -> return "${grouped(n.value / size)}$decimals $word" }
        return grouped(n.value) + decimals
    }

    // No separators under 10,000, so years and "2500" stay as typed.
    private fun grouped(v: Long) = if (v >= 10_000) String.format(Locale.US, "%,d", v) else v.toString()
}
