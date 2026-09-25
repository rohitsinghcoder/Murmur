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

    private val word = Regex("[A-Za-z']+")
    private val joiner = Regex("[ \\t-]+")
    private val percent = Regex("^[ -]?(?:percent|per cent)\\b", RegexOption.IGNORE_CASE)
    private val currency = Regex("^ (dollars?|rupees?|euros?)\\b", RegexOption.IGNORE_CASE)
    private val ampm = Regex("^ ?([ap])\\.? ?m(?![a-z])(\\.)?", RegexOption.IGNORE_CASE)
    private val keepsDigits = Regex("^ (?:lakhs?|crores?|o'clock)\\b", RegexOption.IGNORE_CASE)
    private val decadeNext = Regex("^ (?:\\w+ties|hundreds|thousands|millions)\\b", RegexOption.IGNORE_CASE)
    private val timeCues = setOf("at", "by", "around", "till", "until", "from", "after", "before", "about")

    private enum class Kind { None, Unit, Teen, Tens, Hundred, Scale }

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

    fun format(text: String): String {
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
                joiner.matches(text.substring(words[j].range.last + 1, words[j + 1].range.first)) &&
                isNumberWord(words[j + 1].value.lowercase())
            ) j++
            while (j > i && words[j].value.lowercase() in connectors) j--

            val run = words.subList(i, j + 1).map { it.value.lowercase() }
            val (items, used) = parse(run)
            val end = words[i + used - 1].range.last + 1
            val prev = words.getOrNull(i - 1)?.takeIf {
                text.substring(it.range.last + 1, first.range.first).isBlank()
            }?.value?.lowercase()
            val written = write(items, run.first(), prev, text.substring(end))
            if (written != null) {
                out.append(text, pos, first.range.first).append(written.first)
                pos = end + written.second
            }
            i += used
        }
        return out.append(text, pos, text.length).toString()
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
                    while (j < w.size && (small(w[j]) ?: 10) < 10) {
                        digits.append(small(w[j]))
                        j++
                    }
                    if (count == 0 || digits.isEmpty()) break@loop
                    items += Num(total + cur, count + 1 + digits.length, decimals = digits.toString())
                    reset()
                    i = j
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
                Kind.Unit -> if (v == 0) last == Kind.None else last in setOf(Kind.None, Kind.Tens, Kind.Hundred, Kind.Scale)
                Kind.Teen, Kind.Tens -> last in setOf(Kind.None, Kind.Hundred, Kind.Scale)
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

    /** The written form of a run and how many characters after it were absorbed, or null to keep the words. */
    private fun write(items: List<Num>, firstWord: String, prev: String?, rest: String): Pair<String, Int>? {
        if (items.isEmpty()) return null
        val a = items[0]

        // Years: "nineteen ninety nine", "twenty twenty five", "twenty oh five".
        if (firstWord == "nineteen" || firstWord == "twenty") {
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
        val meridiem = ampm.find(rest)
        if (a.words == 1 && a.value in 1..12 && (meridiem != null || (prev != null && prev in timeCues))) {
            val minutes = when {
                items.size == 2 && items[1].decimals.isEmpty() && !items[1].oh && items[1].value in 10..59 -> items[1].value
                items.size == 3 && items[1].oh && items[2].digit && items[2].value > 0 -> items[2].value
                else -> null
            }
            val suffix = meridiem?.let {
                val m = " " + it.groupValues[1].uppercase() + "M"
                // Keep a sentence-ending period that was part of "p.m.".
                val endsSentence = it.groupValues[2].isNotEmpty() &&
                    rest.substring(it.range.last + 1).let { r -> r.isBlank() || r.trimStart().firstOrNull()?.isUpperCase() == true }
                (if (endsSentence) "$m." else m) to it.value.length
            }
            if (minutes != null) {
                return "${a.value}:${"%02d".format(minutes)}${suffix?.first.orEmpty()}" to (suffix?.second ?: 0)
            }
            if (items.size == 1 && suffix != null) return "${a.value}${suffix.first}" to suffix.second
        }

        // Spelled-out digits, like a phone number: "nine eight seven six".
        if (items.size >= 3 && items.all { it.digit || it.oh }) {
            return items.joinToString("") { it.value.toString() } to 0
        }

        // Anything else made of several numbers is ambiguous ("one two", "five ten"): keep it.
        if (items.size != 1) return null
        if (decadeNext.containsMatchIn(rest)) return null // "the nineteen sixties"
        percent.find(rest)?.let { return "${digits(a)}%" to it.value.length }
        currency.find(rest)?.let {
            val symbol = when (it.groupValues[1].lowercase().first()) {
                'd' -> "$"
                'r' -> "₹"
                else -> "€"
            }
            return "$symbol${digits(a)}" to it.value.length
        }
        if (a.value >= 10 || a.decimals.isNotEmpty() || keepsDigits.containsMatchIn(rest)) return digits(a) to 0
        return null
    }

    private fun digits(n: Num): String {
        n.scale?.let { (word, size) -> return "${grouped(n.value / size)} $word" }
        return grouped(n.value) + if (n.decimals.isNotEmpty()) "." + n.decimals else ""
    }

    // No separators under 10,000, so years and "2500" stay as typed.
    private fun grouped(v: Long) = if (v >= 10_000) String.format(Locale.US, "%,d", v) else v.toString()
}
