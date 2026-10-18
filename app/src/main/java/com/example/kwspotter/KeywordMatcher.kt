package com.example.kwspotter

/**
 * Ищет в распознанном тексте «ФСБ» и «полиция».
 *
 * Маленькая модель Vosk часто пишет аббревиатуру по звукам («эф эс бэ», «эфэсбэ»)
 * или чуть мимо («ефсб»). Такие варианты сравниваются окном из соседних слов.
 * «Полиция» и её формы ловятся по основе «полиц», чтобы не спутать со словом «политика».
 */
object KeywordMatcher {

    private val FSB_VARIANTS = listOf(
        "фсб",
        "эфэсбэ",
        "эфсб",
        "ефсб",
        "эфэсб",
        "фэсбэ",
        "эфесбе",
        "афсб"
    )

    private val POLICE_STEMS = listOf("полиц", "палиц")

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

    fun containsKeyword(text: String): Boolean = match(text) != null

    /**
     * Подпись для шторки: «ФСБ», «полиция» или обе, если они в одной фразе.
     * null — ни одного слова нет.
     */
    fun match(text: String): String? {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return null

        val words = normalized.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        val police = words.any { word -> POLICE_STEMS.any { stem -> word.contains(stem) } }
        val fsb = containsFsb(words)
        return when {
            police && fsb -> "ФСБ и полиция"
            police -> "полиция"
            fsb -> "ФСБ"
            else -> null
        }
    }

    private fun containsFsb(words: List<String>): Boolean {
        for (start in words.indices) {
            var candidate = ""
            for (windowSize in 1..MAX_WINDOW) {
                val end = start + windowSize - 1
                if (end >= words.size) break
                candidate += words[end]
                if (matchesFsb(candidate)) return true
            }
        }
        return false
    }

    private fun matchesFsb(candidate: String): Boolean {
        if (candidate.length < 3) return false
        for (variant in FSB_VARIANTS) {
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
