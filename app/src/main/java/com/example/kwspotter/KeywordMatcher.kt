package com.example.kwspotter

/**
 * Fuzzy detector for the keyword "ФСБ" inside a stream of recognized Russian text.
 *
 * Vosk's small Russian model does not have a dedicated grammar entry for abbreviations,
 * so it tends to spell the word out phonetically ("эф эс бэ", "эфэсбэ") or mis-recognize
 * it slightly ("ефсб", "фэсбэ"). We normalize the recognizer output and compare sliding
 * windows of consecutive words against a set of reference spellings using Levenshtein
 * distance, so small recognition errors still count as a hit.
 */
object KeywordMatcher {

    // Reference spellings, already normalized (lowercase, no spaces/punctuation).
    private val REFERENCE_VARIANTS = listOf(
        "фсб",
        "эфэсбэ",
        "эфсб",
        "ефсб",
        "эфэсб",
        "фэсбэ",
        "эфесбе",
        "афсб"
    )

    // How many consecutive recognized words to glue together before comparing
    // (covers cases like "эф эс бэ" being emitted as three separate tokens).
    private const val MAX_WINDOW = 3

    /** Lowercase, strip everything but Cyrillic letters and spaces, collapse whitespace. */
    fun normalize(text: String): String {
        val lower = text.lowercase()
        val filtered = buildString {
            for (ch in lower) {
                if (ch in 'а'..'я' || ch == 'ё' || ch.isWhitespace()) {
                    append(ch)
                }
            }
        }
        return filtered.trim().replace(Regex("\\s+"), " ")
    }

    /** Returns true if any fuzzy variant of the keyword appears in [text]. */
    fun containsKeyword(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false

        val words = normalized.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return false

        for (start in words.indices) {
            var candidate = ""
            for (windowSize in 1..MAX_WINDOW) {
                val end = start + windowSize - 1
                if (end >= words.size) break
                candidate += words[end]
                if (matchesAnyVariant(candidate)) return true
            }
        }
        return false
    }

    private fun matchesAnyVariant(candidate: String): Boolean {
        if (candidate.length < 3) return false
        for (variant in REFERENCE_VARIANTS) {
            val tolerance = if (variant.length <= 4) 1 else 2
            // Skip obviously unrelated lengths to avoid cheap false positives on long words.
            if (kotlin.math.abs(candidate.length - variant.length) > tolerance + 1) continue
            if (levenshtein(candidate, variant) <= tolerance) return true
        }
        return false
    }

    fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m

        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,      // insertion
                    prev[j] + 1,          // deletion
                    prev[j - 1] + cost    // substitution
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[n]
    }
}
