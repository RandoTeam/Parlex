package com.translive.app.engine

import com.translive.app.data.model.Currency
import com.translive.app.data.model.Language
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CurrencyParserTest {

    @Test
    fun `parseAll extracts USD prefix and suffix`() {
        val matches1 = CurrencyParser.parseAll("The total price is $45.50 today.")
        assertEquals(1, matches1.size)
        assertEquals(Currency.USD, matches1[0].detectedCurrency)
        assertEquals(45.50, matches1[0].totalAmount)

        val matches2 = CurrencyParser.parseAll("Payment of 120 USD received.")
        assertEquals(1, matches2.size)
        assertEquals(Currency.USD, matches2[0].detectedCurrency)
        assertEquals(120.0, matches2[0].totalAmount)
    }

    @Test
    fun `parseAll extracts RUB with localized suffixes`() {
        val matches1 = CurrencyParser.parseAll("Стоимость билета 2 500 руб.")
        assertEquals(1, matches1.size)
        assertEquals(Currency.RUB, matches1[0].detectedCurrency)
        assertEquals(2500.0, matches1[0].totalAmount)

        val matches2 = CurrencyParser.parseAll("Скидка 500 рублей на заказ.")
        assertEquals(1, matches2.size)
        assertEquals(Currency.RUB, matches2[0].detectedCurrency)
        assertEquals(500.0, matches2[0].totalAmount)
    }

    @Test
    fun `parseAll handles multipliers correctly`() {
        // 150k -> 150,000
        val matchesK = CurrencyParser.parseAll("Hotel room is 150k VND per night.")
        assertEquals(1, matchesK.size)
        assertEquals(Currency.VND, matchesK[0].detectedCurrency)
        assertEquals(150_000.0, matchesK[0].totalAmount)

        // 2.5tr -> 2,500,000
        val matchesTr = CurrencyParser.parseAll("Giá phòng là 2.5tr đ.")
        assertEquals(1, matchesTr.size)
        assertEquals(Currency.VND, matchesTr[0].detectedCurrency)
        assertEquals(2_500_000.0, matchesTr[0].totalAmount)
    }

    @Test
    fun `parseAll disambiguates Yen and Yuan based on script context`() {
        val matchesJpy = CurrencyParser.parseAll("ラーメン ¥850", Language.JAPANESE)
        assertEquals(1, matchesJpy.size)
        assertEquals(Currency.JPY, matchesJpy[0].detectedCurrency)
        assertEquals(850.0, matchesJpy[0].totalAmount)

        val matchesCny = CurrencyParser.parseAll("苹果 ¥15.5", Language.CHINESE_SIMPLIFIED)
        assertEquals(1, matchesCny.size)
        assertEquals(Currency.CNY, matchesCny[0].detectedCurrency)
        assertEquals(15.5, matchesCny[0].totalAmount)
    }

    @Test
    fun `parseAll ignores year dates without currency`() {
        val matches = CurrencyParser.parseAll("In 2024 the system was upgraded.")
        assertTrue(matches.isEmpty())
    }
}
