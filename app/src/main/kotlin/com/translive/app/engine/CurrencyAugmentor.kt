package com.translive.app.engine

import com.translive.app.data.ExchangeRateRepository
import com.translive.app.data.SettingsRepository
import com.translive.app.data.model.Currency
import com.translive.app.data.model.Language
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrencyAugmentor @Inject constructor(
    private val exchangeRateRepository: ExchangeRateRepository,
    private val settings: SettingsRepository
) {

    suspend fun augment(
        text: String,
        sourceLang: Language? = null,
        forcedHomeCurrency: Currency? = null
    ): String {
        if (!settings.enableCurrencyConversion && forcedHomeCurrency == null) {
            return text
        }
        if (text.isBlank()) return text

        val targetHomeCurrency = forcedHomeCurrency ?: resolveUserHomeCurrency()
        val matches = CurrencyParser.parseAll(text, sourceLang)
        if (matches.isEmpty()) return text

        val sb = StringBuilder(text)

        // Process in reverse to maintain correct string index offsets
        for (match in matches.reversed()) {
            if (match.detectedCurrency == targetHomeCurrency) continue // No need to convert to same currency

            val rate = exchangeRateRepository.getRate(match.detectedCurrency.code, targetHomeCurrency.code)
            if (rate <= 0.0) continue

            val convertedValue = match.totalAmount * rate
            val formattedEquivalent = formatCurrency(convertedValue, targetHomeCurrency)

            // Insert converted value in parentheses right after the matched currency token
            val insertText = " (≈ $formattedEquivalent)"
            sb.insert(match.endIndex, insertText)
        }

        return sb.toString()
    }

    private fun resolveUserHomeCurrency(): Currency {
        val userSetting = settings.homeCurrencyCode
        if (userSetting != "AUTO") {
            Currency.fromCodeOrSymbol(userSetting)?.let { return it }
        }

        // Automatic deduction based on user target translation language or system locale
        return when (settings.textTargetLanguage) {
            Language.RUSSIAN, Language.UKRAINIAN -> Currency.RUB
            Language.VIETNAMESE -> Currency.VND
            Language.GERMAN, Language.FRENCH, Language.SPANISH, Language.ITALIAN,
            Language.DUTCH, Language.PORTUGUESE -> Currency.EUR
            Language.CHINESE_SIMPLIFIED, Language.CHINESE_TRADITIONAL -> Currency.CNY
            Language.JAPANESE -> Currency.JPY
            Language.KOREAN -> Currency.KRW
            Language.TURKISH -> Currency.TRY
            Language.THAI -> Currency.THB
            Language.HINDI, Language.BENGALI, Language.MARATHI, Language.TAMIL, Language.TELUGU, Language.URDU, Language.GUJARATI -> Currency.INR
            Language.INDONESIAN -> Currency.IDR
            Language.MALAY -> Currency.MYR
            Language.HEBREW -> Currency.ILS
            Language.ARABIC -> Currency.AED
            Language.POLISH -> Currency.PLN
            Language.CZECH -> Currency.CZK
            Language.FILIPINO -> Currency.PHP
            else -> Currency.USD
        }
    }

    private fun formatCurrency(amount: Double, currency: Currency): String {
        val symbols = DecimalFormatSymbols(Locale.US).apply {
            groupingSeparator = ' '
            decimalSeparator = '.'
        }

        val pattern = when {
            currency.defaultDecimals == 0 || amount >= 1000.0 -> "#,##0"
            amount < 1.0 -> "#,##0.00"
            else -> "#,##0.00"
        }

        val df = DecimalFormat(pattern, symbols)
        val formattedNumber = df.format(amount)

        return when (currency) {
            Currency.USD, Currency.GBP, Currency.EUR -> "${currency.symbol}$formattedNumber"
            else -> "$formattedNumber ${currency.symbol}"
        }
    }
}
