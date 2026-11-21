package com.example.kwspotter

/**
 * Ключевые слова и фразы, характерные для телефонных мошенников.
 * Аббревиатуры (ФСБ, МВД, ФНС, ЦБ) сравниваются с учётом типичных ошибок Vosk.
 */
object KeywordMatcher {

    private const val MAX_WINDOW = 4

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
     * Краткая подпись сработавших триггеров для лога и шторки.
     */
    fun match(text: String): String? {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return null

        val words = normalized.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        val hits = linkedSetOf<String>()

        if (containsFsb(words)) hits.add(ScamKeywordConfig.LABEL_FSB)
        if (words.any { w -> ScamKeywordConfig.POLICE_STEMS.any { w.contains(it) } }) {
            hits.add(ScamKeywordConfig.LABEL_POLICE)
        }
        if (containsAbbrev(words, ScamKeywordConfig.MVD_VARIANTS)) {
            hits.add(ScamKeywordConfig.LABEL_MVD)
        }
        if (words.any { w -> ScamKeywordConfig.PROSECUTOR_STEMS.any { w.contains(it) } }) {
            hits.add(ScamKeywordConfig.LABEL_PROSECUTOR)
        }
        if (words.any { w -> ScamKeywordConfig.INVESTIGATOR_STEMS.any { w.contains(it) } }) {
            hits.add(ScamKeywordConfig.LABEL_INVESTIGATOR)
        }
        if (containsAbbrev(words, ScamKeywordConfig.CB_VARIANTS) ||
            words.any { w -> ScamKeywordConfig.CB_STEMS.any { w.contains(it) } }
        ) {
            hits.add(ScamKeywordConfig.LABEL_CB)
        }
        if (ScamKeywordConfig.GOSUSLUGI_SCAM_PHRASES.any { normalized.contains(it) } ||
            (
                ScamKeywordConfig.GOSUSLUGI_STEMS.any { normalized.contains(it) } &&
                    ScamKeywordConfig.GOSUSLUGI_ACTION_STEMS.any { normalized.contains(it) }
                )
        ) {
            hits.add(ScamKeywordConfig.LABEL_GOSUSLUGI)
        }
        if (ScamKeywordConfig.LINK_PHRASES.any { normalized.contains(it) }) {
            hits.add(ScamKeywordConfig.LABEL_LINK)
        }
        if (
            ScamKeywordConfig.SAFE_ACCOUNT_SMS_PHRASES.any { normalized.contains(it) } &&
                (normalized.contains("код") || normalized.contains("смс") || normalized.contains("ссылк"))
        ) {
            hits.add(ScamKeywordConfig.LABEL_SAFE_ACCOUNT)
            hits.add(ScamKeywordConfig.LABEL_CODE)
        }
        if (containsAbbrev(words, ScamKeywordConfig.TAX_VARIANTS) ||
            words.any { w -> ScamKeywordConfig.TAX_STEMS.any { w.contains(it) } }
        ) {
            hits.add(ScamKeywordConfig.LABEL_TAX)
        }
        if (ScamKeywordConfig.ACCOUNT_PHRASES.any { normalized.contains(it) } &&
            (normalized.contains("код") || normalized.contains("смс") || normalized.contains("ссылк") ||
                normalized.contains("перевед"))
        ) {
            hits.add(ScamKeywordConfig.LABEL_SAFE_ACCOUNT)
        }
        if (ScamSignalRules.isExplicitCodeRequest(normalized)) {
            hits.add(ScamKeywordConfig.LABEL_CODE)
        }
        if (ScamKeywordConfig.SECRECY_PHRASES.any { normalized.contains(it) }) {
            hits.add(ScamKeywordConfig.LABEL_SECRECY)
        }
        if (ScamKeywordConfig.AUTHORITY_PHRASES.any { normalized.contains(it) }) {
            hits.add(ScamKeywordConfig.LABEL_AUTHORITY)
        }
        if (ScamSignalRules.isBankSecurityImpersonation(normalized)) {
            hits.add(ScamKeywordConfig.LABEL_BANK)
            hits.add(ScamKeywordConfig.LABEL_AUTHORITY)
        }
        if (ScamSignalRules.isGovStructureImpersonation(normalized)) {
            hits.add(ScamKeywordConfig.LABEL_GOV)
        }
        if (normalized.contains("мошенник") || normalized.contains("мошеннич")) {
            hits.add(ScamKeywordConfig.LABEL_SCAM_META)
        }
        if (normalized.contains("anydesk") || normalized.contains("анидеск") ||
            (normalized.contains("удал") && normalized.contains("доступ"))
        ) {
            hits.add(ScamKeywordConfig.LABEL_REMOTE)
        }

        return hits.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /** Без слабого триггера «мошенники» (часто в антифрод-предупреждениях). */
    fun matchStrong(text: String): String? {
        val all = match(text) ?: return null
        val hits = all.split(", ").map { it.trim() }.toSet()
        if (hits.size == 1 && hits.contains(ScamKeywordConfig.LABEL_SCAM_META)) return null
        return all
    }

    private fun containsFsb(words: List<String>): Boolean =
        containsAbbrev(words, ScamKeywordConfig.FSB_VARIANTS)

    private fun containsAbbrev(words: List<String>, variants: List<String>): Boolean {
        for (start in words.indices) {
            var candidate = ""
            for (windowSize in 1..MAX_WINDOW) {
                val end = start + windowSize - 1
                if (end >= words.size) break
                candidate += words[end]
                if (matchesAbbrev(candidate, variants)) return true
            }
        }
        return false
    }

    private fun matchesAbbrev(candidate: String, variants: List<String>): Boolean {
        if (candidate.length < 2) return false
        for (variant in variants) {
            val tolerance = if (variant.length <= 4) 1 else 2
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
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost,
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[n]
    }
}
