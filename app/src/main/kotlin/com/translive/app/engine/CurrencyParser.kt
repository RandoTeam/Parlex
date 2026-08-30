package com.translive.app.engine

import com.translive.app.data.model.Currency
import com.translive.app.data.model.Language
import java.util.regex.Pattern

data class CurrencyMatch(
    val startIndex: Int,
    val endIndex: Int,
    val rawMatchedText: String,
    val detectedCurrency: Currency,
    val parsedAmount: Double,
    val multiplier: Double = 1.0
) {
    val totalAmount: Double get() = parsedAmount * multiplier
}

object CurrencyParser {

    private val REGEX_PATTERN = Pattern.compile(
        """(?iu)(?:(?<prefix>[$€£¥₩₫₽₺₸฿₹₪₴]|R\$|CA\$|AU\$|SG\$|RM|Rp|Kč|zł|Ft|AED|SAR)\s*(?<amountPre>[0-9]{1,3}(?:[.,\s\u00A0\u202F'][0-9]{3})*(?:[.,][0-9]{1,2})?|[0-9]+(?:[.,][0-9]+)?)\s*(?<multPre>k|m|tr|triệu|тыс|млн)?)|(?:(?<![\d.,])(?<amountPost>[0-9]{1,3}(?:[.,\s\u00A0\u202F'][0-9]{3})*(?:[.,][0-9]{1,2})?|[0-9]+(?:[.,][0-9]+)?)\s*(?<multPost>k|m|tr|triệu|тыс|млн)?\s*(?<suffix>[$€£¥₩₫₽₺₸฿₹₪₴]|USD|EUR|RUB|CNY|VND|GBP|JPY|KRW|TRY|KZT|AED|THB|IDR|INR|BRL|CAD|AUD|CHF|SGD|MYR|PHP|ILS|SAR|PLN|CZK|HUF|руб\.?|рублей|р\.?|долл\.?|долларов|юаней|dong|đồng|đ|TL|тг|тенге|baht|бат|bucks|евро|元|块|円|원)(?=$|[\s.,!?:;\"'()\\[\\]{}<>«»…—\-]))"""
    )

    fun parseAll(text: String, sourceLang: Language? = null): List<CurrencyMatch> {
        if (text.isBlank()) return emptyList()
        val matches = mutableListOf<CurrencyMatch>()
        val matcher = REGEX_PATTERN.matcher(text)

        while (matcher.find()) {
            val prefix = matcher.group("prefix")
            val suffix = matcher.group("suffix")
            val amountStr = matcher.group("amountPre") ?: matcher.group("amountPost") ?: continue
            val multStr = matcher.group("multPre") ?: matcher.group("multPost")

            val rawMatch = matcher.group(0) ?: continue
            val token = prefix ?: suffix ?: continue

            val currency = resolveCurrency(token, text, sourceLang) ?: continue
            val multiplier = resolveMultiplier(multStr)
            val parsedNumber = parseLocalizedNumber(amountStr, currency, multStr != null) ?: continue

            // Filter false positives (e.g. 0 amount, year numbers like 2024 without explicit currency, version numbers)
            if (parsedNumber <= 0.0) continue
            if (parsedNumber >= 1900 && parsedNumber <= 2100 && multStr == null && (token == "р" || token == "р.")) {
                continue // likely year e.g. "1995 г." or "2024 г."
            }

            matches.add(
                CurrencyMatch(
                    startIndex = matcher.start(),
                    endIndex = matcher.end(),
                    rawMatchedText = rawMatch,
                    detectedCurrency = currency,
                    parsedAmount = parsedNumber,
                    multiplier = multiplier
                )
            )
        }
        return matches
    }

    private fun resolveCurrency(token: String, fullText: String, sourceLang: Language?): Currency? {
        val clean = token.trim()

        // Disambiguate Yen / Yuan symbol ¥
        if (clean == "¥") {
            return when {
                sourceLang == Language.JAPANESE || fullText.any { it in '\u3040'..'\u30ff' } -> Currency.JPY
                sourceLang == Language.CHINESE_SIMPLIFIED || sourceLang == Language.CHINESE_TRADITIONAL || fullText.any { it in '\u4e00'..'\u9fff' } -> Currency.CNY
                else -> Currency.CNY
            }
        }

        // Disambiguate Dollar $
        if (clean == "$") {
            return Currency.USD
        }

        return Currency.fromCodeOrSymbol(clean)
    }

    private fun resolveMultiplier(mult: String?): Double {
        if (mult == null) return 1.0
        return when (mult.lowercase()) {
            "k", "тыс" -> 1_000.0
            "m", "млн", "tr", "triệu" -> 1_000_000.0
            else -> 1.0
        }
    }

    private fun parseLocalizedNumber(raw: String, currency: Currency, hasMultiplier: Boolean): Double? {
        var clean = raw.replace(" ", "").replace("'", "").replace("\u00A0", "").replace("\u202F", "")

        val hasDot = clean.contains('.')
        val hasComma = clean.contains(',')

        if (hasDot && hasComma) {
            val lastDot = clean.lastIndexOf('.')
            val lastComma = clean.lastIndexOf(',')
            clean = if (lastDot > lastComma) {
                // Comma is thousand separator, dot is decimal (1,250.50)
                clean.replace(",", "")
            } else {
                // Dot is thousand separator, comma is decimal (1.250,50)
                clean.replace(".", "").replace(',', '.')
            }
        } else if (hasComma) {
            val parts = clean.split(',')
            clean = if (parts.size == 2 && parts[1].length in 1..2) {
                clean.replace(',', '.')
            } else {
                clean.replace(",", "")
            }
        } else if (hasDot) {
            val parts = clean.split('.')
            if (!hasMultiplier && (currency == Currency.VND || currency == Currency.IDR) && parts.size > 1 && parts.drop(1).all { it.length == 3 }) {
                clean = clean.replace(".", "")
            }
        }

        return clean.toDoubleOrNull()
    }
}
