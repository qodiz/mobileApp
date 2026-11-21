package com.example.kwspotter

/**
 * Решает, показывать ли шторку и сбрасывать ли звонок.
 * ML и шторка используют одни и те же правила — без «только предупреждения».
 * Модель смотрит на весь накопленный текст и молчит, пока в нём меньше [MIN_ML_WORDS] слов.
 */
object ScamHangupGate {

    private const val MIN_ML_WORDS = 8

    private val PATRONYMIC = Regex("""(ович|овна|евич|евна|ична|ич)\b""")

    private val GREETING_MARKERS = listOf(
        "добрый день",
        "добрый вечер",
        "доброе утро",
        "здравствуйте",
        "алло",
        "слушаю",
        "кто это",
    )

    private val SMALLTALK_PHRASES = listOf(
        "как дела",
        "как у вас дела",
        "как у тебя дела",
        "как ваши дела",
        "как твои дела",
        "как поживаете",
        "как поживаешь",
        "что нового",
        "как настроение",
        "как жизнь",
        "как оно",
        "рад вас слышать",
        "рад тебя слышать",
        "как прошел день",
        "как прошёл день",
        "как вы",
        "как ты",
        "все хорошо",
        "всё хорошо",
        "ничего особенного",
    )

    fun shouldTreatAsScam(keywordReason: String?, ml: ScamClassifier.Result?, sessionText: String): Boolean {
        val normalized = KeywordMatcher.normalize(sessionText)
        if (normalized.isEmpty()) return false
        if (isBenignSmallTalk(normalized)) return false
        if (keywordReason != null && isStrongKeyword(keywordReason, normalized)) return true
        return shouldEndCallMlOnly(ml, normalized)
    }

    fun isBenignSmallTalk(normalized: String): Boolean {
        if (ScamSignalRules.hasExplicitScamPattern(normalized)) return false
        if (KeywordMatcher.matchStrong(normalized) != null) return false

        val onlySmallTalk = SMALLTALK_PHRASES.any { normalized.contains(it) } &&
            !normalized.contains("перевед") &&
            !normalized.contains("код") &&
            !normalized.contains("ссылк")

        if (onlySmallTalk && normalized.length <= 120) {
            val withoutSmall = SMALLTALK_PHRASES.fold(normalized) { acc, p -> acc.replace(p, " ") }
            val extra = withoutSmall.split(" ").filter { it.isNotBlank() && it.length > 2 }
            if (extra.size <= 8) return true
        }

        if (isBenignGreetingOnly(normalized)) return true
        return false
    }

    private fun shouldEndCallMlOnly(ml: ScamClassifier.Result?, normalized: String): Boolean {
        val result = ml ?: return false
        if (!result.alert) return false
        return wordCount(normalized) >= MIN_ML_WORDS
    }

    private fun wordCount(normalized: String): Int =
        normalized.split(" ").count { it.isNotBlank() }

    private fun isStrongKeyword(keywordReason: String, normalized: String): Boolean {
        val parts = keywordReason.split(", ").map { it.trim() }
        if (parts.contains(ScamKeywordConfig.LABEL_CODE) &&
            !ScamSignalRules.isExplicitCodeRequest(normalized)
        ) {
            return false
        }
        val weakOnly = parts.all { it == ScamKeywordConfig.LABEL_SCAM_META }
        if (weakOnly) {
            val education = normalized.contains("не просим") ||
                normalized.contains("мы не") ||
                normalized.contains("если спросят") ||
                normalized.contains("предупрежда")
            if (education || isBenignSmallTalk(normalized)) return false
        }
        if (ScamSignalRules.isLegitimateBankDisclaimer(normalized)) return false
        if (!ScamSignalRules.hasExplicitScamPattern(normalized) &&
            parts.all {
                it == ScamKeywordConfig.LABEL_CODE || it == ScamKeywordConfig.LABEL_SCAM_META
            }
        ) {
            return false
        }
        return true
    }

    private fun isBenignGreetingOnly(normalized: String): Boolean {
        if (normalized.length > 100) return false
        val hasGreeting = GREETING_MARKERS.any { normalized.contains(it) }
        if (!hasGreeting) return false
        if (ScamSignalRules.hasExplicitScamPattern(normalized)) return false
        if (KeywordMatcher.matchStrong(normalized) != null) return false

        val wordCount = normalized.split(" ").count { it.isNotBlank() }
        val hasPatronymic = PATRONYMIC.containsMatchIn(normalized)
        if (hasPatronymic && wordCount <= 12) return true
        if (wordCount <= 8) return true
        return false
    }
}
