package com.example.kwspotter

/**
 * Явные признаки мошенничества (комфортный режим).
 * Код из СМС, «пришлю ссылку», маска банка/госоргана — жёсткие сигналы.
 */
object ScamSignalRules {

    private val HARD_MARKERS = listOf(
        "перейдите по ссылк",
        "пришлю ссылк",
        "отправлю ссылк",
        "скину ссылк",
        "сброшу ссылк",
        "откройте ссылк",
        "зайдите на госуслуг",
        "зайдите в приложение госуслуг",
        "откройте приложение госуслуг",
        "центробанк",
        "следов",
        "прокур",
        "налогов",
        "фсб",
        "мвд",
        "фнс",
        "фссп",
        "пристав",
        "таможн",
        "военкомат",
        "anydesk",
        "анидеск",
        "никому не говор",
        "не говорите никому",
        "служба безопасности",
        "сотрудник безопасности",
        "отдел безопасности",
        "уголовн",
        "арест",
        "any desk",
        "следственный комитет",
        "киберпреступ",
    )

    private val MONEY_ACCOUNT_MARKERS = listOf(
        "безопасн",
        "защищ",
        "специальн",
        "счет",
        "счёт",
    )

    fun hasExplicitScamPattern(normalized: String): Boolean {
        if (isLegitimateBankDisclaimer(normalized)) return false
        if (HARD_MARKERS.any { normalized.contains(it) }) return true
        if (ScamKeywordConfig.GOSUSLUGI_SCAM_PHRASES.any { normalized.contains(it) }) return true
        if (ScamKeywordConfig.LINK_PHRASES.any { normalized.contains(it) }) return true
        if (ScamKeywordConfig.SAFE_ACCOUNT_SMS_PHRASES.any { normalized.contains(it) } &&
            (normalized.contains("код") || normalized.contains("смс") || normalized.contains("ссылк"))
        ) {
            return true
        }
        if (isExplicitCodeRequest(normalized)) return true
        if (isMoneyScamPressure(normalized)) return true
        if (isBankSecurityImpersonation(normalized)) return true
        if (isGovStructureImpersonation(normalized)) return true
        return false
    }

    /** Легитимное предупреждение банка «мы не просим код». */
    fun isLegitimateBankDisclaimer(normalized: String): Boolean {
        if (!ScamKeywordConfig.BANK_STEMS.any { normalized.contains(it) }) return false
        return normalized.contains("не просим") ||
            normalized.contains("не запрашиваем") ||
            normalized.contains("не сообщайте код") ||
            normalized.contains("если спросят") ||
            normalized.contains("это мошенники")
    }

    fun isBankSecurityImpersonation(normalized: String): Boolean {
        if (isLegitimateBankDisclaimer(normalized)) return false
        val bank = ScamKeywordConfig.BANK_STEMS.any { normalized.contains(it) }
        if (!bank) return false
        return ScamKeywordConfig.BANK_IMPERSONATION_STEMS.any { normalized.contains(it) } ||
            ScamKeywordConfig.AUTHORITY_PHRASES.any { normalized.contains(it) }
    }

    fun isGovStructureImpersonation(normalized: String): Boolean {
        if (!ScamKeywordConfig.GOV_STEMS.any { normalized.contains(it) }) return false
        val intro = normalized.contains("я ") ||
            normalized.contains("это ") ||
            normalized.contains("звоню") ||
            normalized.contains("сотрудник") ||
            normalized.contains("служб") ||
            normalized.contains("управлен")
        return intro || isExplicitCodeRequest(normalized) ||
            ScamKeywordConfig.LINK_PHRASES.any { normalized.contains(it) }
    }

    /** Запрос кода/пароля/цифр — только явный, не одно «продиктовать». */
    fun isExplicitCodeRequest(normalized: String): Boolean {
        if (ScamKeywordConfig.CODE_PHRASES.any { normalized.contains(it) }) return true
        val dictation = normalized.contains("продикту") ||
            normalized.contains("дикту") ||
            normalized.contains("назов") ||
            normalized.contains("сообщите")
        val secret = normalized.contains("код") ||
            normalized.contains("смс") ||
            normalized.contains("парол") ||
            normalized.contains("цифр") ||
            normalized.contains("пин")
        if (dictation && secret) return true
        if (normalized.contains("код") &&
            (normalized.contains("смс") || normalized.contains("сообщ") || normalized.contains("подтверж"))
        ) {
            return true
        }
        return false
    }

    /** Деньги + счёт/перевод (без одного только «срочно»). */
    private fun isMoneyScamPressure(normalized: String): Boolean {
        val money = normalized.contains("перевед") || normalized.contains("перевод")
        val account = MONEY_ACCOUNT_MARKERS.any { normalized.contains(it) }
        return money && account
    }
}
