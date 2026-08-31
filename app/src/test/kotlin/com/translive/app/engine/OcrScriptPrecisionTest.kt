package com.translive.app.engine

import com.translive.app.data.model.Language
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrScriptPrecisionTest {

    private val engine = LanguageDetectionEngine()

    @Test
    fun detect_detectsCyrillicCorrectly() = runBlocking {
        val detectedRu = engine.detect("Привет, как ваши дела? Выход в город.")
        assertEquals(Language.RUSSIAN, detectedRu)

        val detectedUk = engine.detect("Доброго дня, шановні пасажири! Поїзд прибуває на станцію.")
        assertEquals(Language.UKRAINIAN, detectedUk)
    }

    @Test
    fun detect_detectsCjkCorrectly() = runBlocking {
        val detectedZh = engine.detect("欢迎光临我们的商店，这里有最新的商品。")
        assertEquals(Language.CHINESE_SIMPLIFIED, detectedZh)

        val detectedJa = engine.detect("こんにちは、東京駅へようこそ。")
        assertEquals(Language.JAPANESE, detectedJa)

        val detectedKo = engine.detect("안녕하세요, 대한민국에 오신 것을 환영합니다.")
        assertEquals(Language.KOREAN, detectedKo)
    }

    @Test
    fun detect_detectsVietnameseAndEuropeanLanguagesCorrectly() = runBlocking {
        val detectedVi = engine.detect("Xin chào các bạn, chúc một ngày tốt lành.")
        assertEquals(Language.VIETNAMESE, detectedVi)

        val detectedEn = engine.detect("Welcome to London! The exit is on the right side.")
        assertEquals(Language.ENGLISH, detectedEn)

        val detectedDe = engine.detect("Guten Tag! Der Ausgang befindet sich auf der rechten Seite.")
        assertEquals(Language.GERMAN, detectedDe)
    }
}
