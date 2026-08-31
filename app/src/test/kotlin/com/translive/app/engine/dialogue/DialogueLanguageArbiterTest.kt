package com.translive.app.engine.dialogue

import com.translive.app.data.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure JVM Unit Test Suite for DialogueLanguageArbiter (Sub-phase D1.1).
 *
 * Rules:
 * - 100% pure JVM (no Android context, Robolectric, or mocks required).
 * - Zero-emoji compliance in test fixtures, method names, and assertions.
 */
class DialogueLanguageArbiterTest {

    private lateinit var arbiter: DialogueLanguageArbiter

    @Before
    fun setUp() {
        arbiter = DialogueLanguageArbiter()
    }

    // =========================================================================
    // 1. Distinct Script Pairs
    // =========================================================================

    @Test
    fun testDistinctScriptPair_RussianCyrillicAndEnglishLatin() {
        val pair = DialogueSessionPair(Language.RUSSIAN, Language.ENGLISH)

        val ruUtterance1 = "Здравствуйте! Как пройти к музею?"
        val resultRu1 = arbiter.arbitrate(ruUtterance1, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.RUSSIAN, resultRu1.resolvedLanguage)
        assertEquals(Language.ENGLISH, resultRu1.targetLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, resultRu1.resolutionMethod)
        assertTrue(resultRu1.confidence >= 0.90f)

        val ruUtterance2 = "Где находится ближайшая станция метро?"
        val resultRu2 = arbiter.arbitrate(ruUtterance2, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.RUSSIAN, resultRu2.resolvedLanguage)
        assertEquals(Language.ENGLISH, resultRu2.targetLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, resultRu2.resolutionMethod)

        val enUtterance1 = "Good morning! Can you help me find the departure gate?"
        val resultEn1 = arbiter.arbitrate(enUtterance1, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.ENGLISH, resultEn1.resolvedLanguage)
        assertEquals(Language.RUSSIAN, resultEn1.targetLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, resultEn1.resolutionMethod)
        assertTrue(resultEn1.confidence >= 0.90f)

        val enUtterance2 = "The train will arrive at platform three in five minutes."
        val resultEn2 = arbiter.arbitrate(enUtterance2, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.ENGLISH, resultEn2.resolvedLanguage)
        assertEquals(Language.RUSSIAN, resultEn2.targetLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, resultEn2.resolutionMethod)
    }

    @Test
    fun testDistinctScriptPair_ChineseHanziAndEnglishLatin() {
        val pair = DialogueSessionPair(Language.CHINESE_SIMPLIFIED, Language.ENGLISH)

        val zhUtterance1 = "请问去机场怎么走？"
        val resultZh1 = arbiter.arbitrate(zhUtterance1, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.CHINESE_SIMPLIFIED, resultZh1.resolvedLanguage)
        assertEquals(Language.ENGLISH, resultZh1.targetLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, resultZh1.resolutionMethod)
        assertTrue(resultZh1.confidence >= 0.90f)

        val zhUtterance2 = "这里可以刷信用卡结账吗？"
        val resultZh2 = arbiter.arbitrate(zhUtterance2, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.CHINESE_SIMPLIFIED, resultZh2.resolvedLanguage)
        assertEquals(Language.ENGLISH, resultZh2.targetLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, resultZh2.resolutionMethod)

        val enUtterance1 = "Where is the boarding gate for flight CA981?"
        val resultEn1 = arbiter.arbitrate(enUtterance1, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.ENGLISH, resultEn1.resolvedLanguage)
        assertEquals(Language.CHINESE_SIMPLIFIED, resultEn1.targetLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, resultEn1.resolutionMethod)
        assertTrue(resultEn1.confidence >= 0.90f)

        val enUtterance2 = "Yes, we accept all major international cards."
        val resultEn2 = arbiter.arbitrate(enUtterance2, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.ENGLISH, resultEn2.resolvedLanguage)
        assertEquals(Language.CHINESE_SIMPLIFIED, resultEn2.targetLanguage)
    }

    @Test
    fun testDistinctScriptPair_JapaneseKanaKanjiAndEnglishLatin() {
        val pair = DialogueSessionPair(Language.JAPANESE, Language.ENGLISH)

        val jaUtterance1 = "すみません、この電車は東京駅に行きますか？"
        val resultJa1 = arbiter.arbitrate(jaUtterance1, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.JAPANESE, resultJa1.resolvedLanguage)
        assertEquals(Language.ENGLISH, resultJa1.targetLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, resultJa1.resolutionMethod)
        assertTrue(resultJa1.confidence >= 0.90f)

        val jaUtterance2 = "お会計をお願いします。領収書もいただけますか？"
        val resultJa2 = arbiter.arbitrate(jaUtterance2, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.JAPANESE, resultJa2.resolvedLanguage)
        assertEquals(Language.ENGLISH, resultJa2.targetLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, resultJa2.resolutionMethod)

        val enUtterance = "Is this seat currently occupied?"
        val resultEn = arbiter.arbitrate(enUtterance, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.ENGLISH, resultEn.resolvedLanguage)
        assertEquals(Language.JAPANESE, resultEn.targetLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, resultEn.resolutionMethod)
        assertTrue(resultEn.confidence >= 0.90f)
    }

    @Test
    fun testDistinctScriptPair_ArabicAndFrenchLatin() {
        val pair = DialogueSessionPair(Language.ARABIC, Language.FRENCH)

        val arUtterance1 = "مرحبا، كم سعر هذه التذكرة؟"
        val resultAr1 = arbiter.arbitrate(arUtterance1, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.ARABIC, resultAr1.resolvedLanguage)
        assertEquals(Language.FRENCH, resultAr1.targetLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, resultAr1.resolutionMethod)
        assertTrue(resultAr1.confidence >= 0.90f)

        val arUtterance2 = "أين يقع مكتب الاستعلامات السياحي؟"
        val resultAr2 = arbiter.arbitrate(arUtterance2, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.ARABIC, resultAr2.resolvedLanguage)
        assertEquals(Language.FRENCH, resultAr2.targetLanguage)

        val frUtterance1 = "Bonjour, a quelle heure part le prochain train s'il vous plait ?"
        val resultFr1 = arbiter.arbitrate(frUtterance1, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.FRENCH, resultFr1.resolvedLanguage)
        assertEquals(Language.ARABIC, resultFr1.targetLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, resultFr1.resolutionMethod)
        assertTrue(resultFr1.confidence >= 0.90f)

        val frUtterance2 = "Le musee est ouvert tous les jours sauf le mardi."
        val resultFr2 = arbiter.arbitrate(frUtterance2, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.FRENCH, resultFr2.resolvedLanguage)
        assertEquals(Language.ARABIC, resultFr2.targetLanguage)
    }

    // =========================================================================
    // 2. Shared Latin Script Pairs with Diacritics
    // =========================================================================

    @Test
    fun testSharedLatinScript_VietnameseWithTonesAndEnglish() {
        val pair = DialogueSessionPair(Language.VIETNAMESE, Language.ENGLISH)

        val viUtterance1 = "Xin chào các bạn, rất vui được gặp bạn hôm nay."
        val resultVi1 = arbiter.arbitrate(viUtterance1, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.VIETNAMESE, resultVi1.resolvedLanguage)
        assertEquals(Language.ENGLISH, resultVi1.targetLanguage)
        assertTrue(
            resultVi1.resolutionMethod == ResolutionMethod.DIACRITIC_FEATURE ||
            resultVi1.resolutionMethod == ResolutionMethod.LEXICAL_HEURISTIC
        )
        assertTrue(resultVi1.confidence >= 0.80f)

        val viUtterance2 = "Cảm ơn bạn rất nhiều vì sự giúp đỡ nhiệt tình."
        val resultVi2 = arbiter.arbitrate(viUtterance2, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.VIETNAMESE, resultVi2.resolvedLanguage)
        assertEquals(Language.ENGLISH, resultVi2.targetLanguage)
        assertEquals(ResolutionMethod.DIACRITIC_FEATURE, resultVi2.resolutionMethod)
        assertTrue(resultVi2.confidence >= 0.80f)

        val viUtterance3 = "Cho tôi hỏi món phở bò này giá bao nhiêu tiền?"
        val resultVi3 = arbiter.arbitrate(viUtterance3, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.VIETNAMESE, resultVi3.resolvedLanguage)
        assertEquals(Language.ENGLISH, resultVi3.targetLanguage)

        val enUtterance1 = "Hello! It is a real pleasure to meet you."
        val resultEn1 = arbiter.arbitrate(enUtterance1, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.ENGLISH, resultEn1.resolvedLanguage)
        assertEquals(Language.VIETNAMESE, resultEn1.targetLanguage)
        assertEquals(ResolutionMethod.LEXICAL_HEURISTIC, resultEn1.resolutionMethod)
        assertTrue(resultEn1.confidence >= 0.75f)

        val enUtterance2 = "Thank you very much for your great assistance."
        val resultEn2 = arbiter.arbitrate(enUtterance2, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.ENGLISH, resultEn2.resolvedLanguage)
        assertEquals(Language.VIETNAMESE, resultEn2.targetLanguage)
        assertEquals(ResolutionMethod.LEXICAL_HEURISTIC, resultEn2.resolutionMethod)
    }

    @Test
    fun testSharedLatinScript_GermanWithUmlautsAndEnglish() {
        val pair = DialogueSessionPair(Language.GERMAN, Language.ENGLISH)

        val deUtterance1 = "Guten Tag! Möchten Sie ein Glas Wasser trinken?"
        val resultDe1 = arbiter.arbitrate(deUtterance1, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.GERMAN, resultDe1.resolvedLanguage)
        assertEquals(Language.ENGLISH, resultDe1.targetLanguage)
        assertEquals(ResolutionMethod.DIACRITIC_FEATURE, resultDe1.resolutionMethod)
        assertTrue(resultDe1.confidence >= 0.80f)

        val deUtterance2 = "Entschuldigung, wo ist der Ausgang zur Hauptstraße?"
        val resultDe2 = arbiter.arbitrate(deUtterance2, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.GERMAN, resultDe2.resolvedLanguage)
        assertEquals(Language.ENGLISH, resultDe2.targetLanguage)
        assertEquals(ResolutionMethod.DIACRITIC_FEATURE, resultDe2.resolutionMethod)

        val deUtterance3 = "Wir müssen für die Reservierung noch bezahlen."
        val resultDe3 = arbiter.arbitrate(deUtterance3, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.GERMAN, resultDe3.resolvedLanguage)
        assertEquals(Language.ENGLISH, resultDe3.targetLanguage)

        val enUtterance1 = "Good afternoon! Would you like something cold to drink?"
        val resultEn1 = arbiter.arbitrate(enUtterance1, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.ENGLISH, resultEn1.resolvedLanguage)
        assertEquals(Language.GERMAN, resultEn1.targetLanguage)
        assertEquals(ResolutionMethod.LEXICAL_HEURISTIC, resultEn1.resolutionMethod)

        val enUtterance2 = "Excuse me, where is the main central train station?"
        val resultEn2 = arbiter.arbitrate(enUtterance2, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.ENGLISH, resultEn2.resolvedLanguage)
        assertEquals(Language.GERMAN, resultEn2.targetLanguage)
    }

    @Test
    fun testSharedLatinScript_SpanishAndEnglish() {
        val pair = DialogueSessionPair(Language.SPANISH, Language.ENGLISH)

        val esUtterance1 = "¿Cómo estás? Espero que tengas un excelente día."
        val resultEs1 = arbiter.arbitrate(esUtterance1, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.SPANISH, resultEs1.resolvedLanguage)
        assertEquals(Language.ENGLISH, resultEs1.targetLanguage)
        assertEquals(ResolutionMethod.DIACRITIC_FEATURE, resultEs1.resolutionMethod)
        assertTrue(resultEs1.confidence >= 0.80f)

        val esUtterance2 = "Nos vemos mañana por la mañana en el hotel."
        val resultEs2 = arbiter.arbitrate(esUtterance2, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.SPANISH, resultEs2.resolvedLanguage)
        assertEquals(Language.ENGLISH, resultEs2.targetLanguage)
        assertEquals(ResolutionMethod.DIACRITIC_FEATURE, resultEs2.resolutionMethod)

        val esUtterance3 = "Muchas gracias por toda la información."
        val resultEs3 = arbiter.arbitrate(esUtterance3, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.SPANISH, resultEs3.resolvedLanguage)
        assertEquals(Language.ENGLISH, resultEs3.targetLanguage)
        assertTrue(
            resultEs3.resolutionMethod == ResolutionMethod.DIACRITIC_FEATURE ||
            resultEs3.resolutionMethod == ResolutionMethod.LEXICAL_HEURISTIC
        )

        val enUtterance1 = "How are you doing today? Have a wonderful day."
        val resultEn1 = arbiter.arbitrate(enUtterance1, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.ENGLISH, resultEn1.resolvedLanguage)
        assertEquals(Language.SPANISH, resultEn1.targetLanguage)
        assertEquals(ResolutionMethod.LEXICAL_HEURISTIC, resultEn1.resolutionMethod)

        val enUtterance2 = "See you tomorrow morning at the reception desk."
        val resultEn2 = arbiter.arbitrate(enUtterance2, pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.ENGLISH, resultEn2.resolvedLanguage)
        assertEquals(Language.SPANISH, resultEn2.targetLanguage)
    }

    // =========================================================================
    // 3. Short Utterances and Greetings
    // =========================================================================

    @Test
    fun testShortSingleWordUtterances_RussianAndEnglishGreetings() {
        val pair = DialogueSessionPair(Language.RUSSIAN, Language.ENGLISH)

        val resDa = arbiter.arbitrate("Да", pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.RUSSIAN, resDa.resolvedLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, resDa.resolutionMethod)

        val resNet = arbiter.arbitrate("Нет", pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.RUSSIAN, resNet.resolvedLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, resNet.resolutionMethod)

        val resPrivet = arbiter.arbitrate("Привет", pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.RUSSIAN, resPrivet.resolvedLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, resPrivet.resolutionMethod)

        val resYes = arbiter.arbitrate("Yes", pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.ENGLISH, resYes.resolvedLanguage)
        assertTrue(
            resYes.resolutionMethod == ResolutionMethod.SCRIPT_DISPARITY ||
            resYes.resolutionMethod == ResolutionMethod.LEXICAL_HEURISTIC
        )

        val resHi = arbiter.arbitrate("Hi", pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.ENGLISH, resHi.resolvedLanguage)
        assertTrue(
            resHi.resolutionMethod == ResolutionMethod.SCRIPT_DISPARITY ||
            resHi.resolutionMethod == ResolutionMethod.LEXICAL_HEURISTIC
        )

        val resThanks = arbiter.arbitrate("Thanks", pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.ENGLISH, resThanks.resolvedLanguage)
        assertTrue(
            resThanks.resolutionMethod == ResolutionMethod.SCRIPT_DISPARITY ||
            resThanks.resolutionMethod == ResolutionMethod.LEXICAL_HEURISTIC
        )
    }

    @Test
    fun testShortSingleWordUtterances_EuropeanAndAsianGreetings() {
        val dePair = DialogueSessionPair(Language.GERMAN, Language.ENGLISH)
        val resDanke = arbiter.arbitrate("Danke", dePair, DialogueTurnContext.EMPTY)
        assertEquals(Language.GERMAN, resDanke.resolvedLanguage)
        assertEquals(ResolutionMethod.LEXICAL_HEURISTIC, resDanke.resolutionMethod)

        val resBitte = arbiter.arbitrate("Bitte", dePair, DialogueTurnContext.EMPTY)
        assertEquals(Language.GERMAN, resBitte.resolvedLanguage)

        val esPair = DialogueSessionPair(Language.SPANISH, Language.ENGLISH)
        val resHola = arbiter.arbitrate("Hola", esPair, DialogueTurnContext.EMPTY)
        assertEquals(Language.SPANISH, resHola.resolvedLanguage)
        assertEquals(ResolutionMethod.LEXICAL_HEURISTIC, resHola.resolutionMethod)

        val resAdios = arbiter.arbitrate("Adiós", esPair, DialogueTurnContext.EMPTY)
        assertEquals(Language.SPANISH, resAdios.resolvedLanguage)
        assertEquals(ResolutionMethod.DIACRITIC_FEATURE, resAdios.resolutionMethod)

        val viPair = DialogueSessionPair(Language.VIETNAMESE, Language.ENGLISH)
        val resChao = arbiter.arbitrate("Chào", viPair, DialogueTurnContext.EMPTY)
        assertEquals(Language.VIETNAMESE, resChao.resolvedLanguage)

        val resCamOn = arbiter.arbitrate("Cảm ơn", viPair, DialogueTurnContext.EMPTY)
        assertEquals(Language.VIETNAMESE, resCamOn.resolvedLanguage)
        assertEquals(ResolutionMethod.DIACRITIC_FEATURE, resCamOn.resolutionMethod)
    }

    // =========================================================================
    // 4. Ambiguous Utterances and Dialogue Turn Bias
    // =========================================================================

    @Test
    fun testAmbiguousUtteranceAlternationPrior_RussianSpeakerFollowedByEnglishResponse() {
        val pair = DialogueSessionPair(Language.RUSSIAN, Language.ENGLISH)

        val contextAfterRussian = DialogueTurnContext(
            previousLanguage = Language.RUSSIAN,
            isSameSpeaker = false,
            turnIndex = 1
        )

        val resultOk = arbiter.arbitrate("OK", pair, contextAfterRussian)
        assertEquals(Language.ENGLISH, resultOk.resolvedLanguage)
        assertEquals(Language.RUSSIAN, resultOk.targetLanguage)
        assertEquals(ResolutionMethod.ALTERNATION_PRIOR, resultOk.resolutionMethod)
        assertTrue(resultOk.confidence in 0.55f..0.75f)

        val resultNumber = arbiter.arbitrate("120", pair, contextAfterRussian)
        assertEquals(Language.ENGLISH, resultNumber.resolvedLanguage)
        assertEquals(ResolutionMethod.ALTERNATION_PRIOR, resultNumber.resolutionMethod)

        val resultBrand = arbiter.arbitrate("Uber", pair, contextAfterRussian)
        assertEquals(Language.ENGLISH, resultBrand.resolvedLanguage)
        assertEquals(ResolutionMethod.ALTERNATION_PRIOR, resultBrand.resolutionMethod)
    }

    @Test
    fun testAmbiguousUtteranceAlternationPrior_EnglishSpeakerFollowedByRussianResponse() {
        val pair = DialogueSessionPair(Language.RUSSIAN, Language.ENGLISH)

        val contextAfterEnglish = DialogueTurnContext(
            previousLanguage = Language.ENGLISH,
            isSameSpeaker = false,
            turnIndex = 2
        )

        val resultOk = arbiter.arbitrate("OK", pair, contextAfterEnglish)
        assertEquals(Language.RUSSIAN, resultOk.resolvedLanguage)
        assertEquals(Language.ENGLISH, resultOk.targetLanguage)
        assertEquals(ResolutionMethod.ALTERNATION_PRIOR, resultOk.resolutionMethod)

        val resultTaxi = arbiter.arbitrate("Taxi", pair, contextAfterEnglish)
        assertEquals(Language.RUSSIAN, resultTaxi.resolvedLanguage)
        assertEquals(ResolutionMethod.ALTERNATION_PRIOR, resultTaxi.resolutionMethod)
    }

    @Test
    fun testAmbiguousUtteranceSameSpeakerContinuationBias() {
        val pair = DialogueSessionPair(Language.RUSSIAN, Language.ENGLISH)

        val sameSpeakerContext = DialogueTurnContext(
            previousLanguage = Language.RUSSIAN,
            isSameSpeaker = true,
            turnIndex = 1
        )

        val resultOk = arbiter.arbitrate("OK", pair, sameSpeakerContext)
        assertEquals(Language.RUSSIAN, resultOk.resolvedLanguage)
        assertEquals(Language.ENGLISH, resultOk.targetLanguage)
        assertEquals(ResolutionMethod.SAME_SPEAKER_PRIOR, resultOk.resolutionMethod)
    }

    @Test
    fun testAmbiguousUtteranceWithNoPriorContextFallsBackToPrimarySessionLanguage() {
        val pair = DialogueSessionPair(Language.RUSSIAN, Language.ENGLISH)

        val result = arbiter.arbitrate("OK", pair, DialogueTurnContext.EMPTY)
        assertNotNull(result.resolvedLanguage)
        assertTrue(pair.contains(result.resolvedLanguage))
        assertEquals(ResolutionMethod.DEFAULT_FALLBACK, result.resolutionMethod)
        assertTrue(result.confidence <= 0.60f)
    }

    // =========================================================================
    // 5. Confidence Calculation and Resolution Method Tagging
    // =========================================================================

    @Test
    fun testConfidenceHierarchyAndTaggingIntegrity() {
        val pair = DialogueSessionPair(Language.GERMAN, Language.ENGLISH)

        val ruPair = DialogueSessionPair(Language.RUSSIAN, Language.ENGLISH)
        val scriptResult = arbiter.arbitrate("Доброе утро", ruPair, DialogueTurnContext.EMPTY)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, scriptResult.resolutionMethod)
        assertTrue(scriptResult.confidence >= 0.90f)

        val diacriticResult = arbiter.arbitrate("Möchten Sie Kaffee?", pair, DialogueTurnContext.EMPTY)
        assertEquals(ResolutionMethod.DIACRITIC_FEATURE, diacriticResult.resolutionMethod)
        assertTrue(diacriticResult.confidence >= 0.80f)

        val lexicalResult = arbiter.arbitrate("Good morning", pair, DialogueTurnContext.EMPTY)
        assertEquals(ResolutionMethod.LEXICAL_HEURISTIC, lexicalResult.resolutionMethod)
        assertTrue(lexicalResult.confidence >= 0.75f)

        val context = DialogueTurnContext(previousLanguage = Language.GERMAN, isSameSpeaker = false)
        val priorResult = arbiter.arbitrate("OK", pair, context)
        assertEquals(ResolutionMethod.ALTERNATION_PRIOR, priorResult.resolutionMethod)
        assertTrue(priorResult.confidence in 0.55f..0.75f)

        val fallbackResult = arbiter.arbitrate("12345", pair, DialogueTurnContext.EMPTY)
        assertEquals(ResolutionMethod.DEFAULT_FALLBACK, fallbackResult.resolutionMethod)
        assertTrue(fallbackResult.confidence <= 0.50f)
    }

    // =========================================================================
    // 6. Edge Cases & Boundary Conditions
    // =========================================================================

    @Test
    fun testEdgeCases_EmptyStringWhitespaceAndPunctuationOnly() {
        val pair = DialogueSessionPair(Language.RUSSIAN, Language.ENGLISH)

        val emptyResult = arbiter.arbitrate("", pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.RUSSIAN, emptyResult.resolvedLanguage)
        assertEquals(ResolutionMethod.DEFAULT_FALLBACK, emptyResult.resolutionMethod)

        val whitespaceResult = arbiter.arbitrate("   \t\n  ", pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.RUSSIAN, whitespaceResult.resolvedLanguage)
        assertEquals(ResolutionMethod.DEFAULT_FALLBACK, whitespaceResult.resolutionMethod)

        val punctResult = arbiter.arbitrate("... ??? !!! ---", pair, DialogueTurnContext.EMPTY)
        assertTrue(pair.contains(punctResult.resolvedLanguage))
        assertEquals(ResolutionMethod.DEFAULT_FALLBACK, punctResult.resolutionMethod)
    }

    @Test
    fun testEdgeCases_CaseInsensitivityAndMixedUppercase() {
        val pair = DialogueSessionPair(Language.RUSSIAN, Language.ENGLISH)

        val upperRu = arbiter.arbitrate("СПАСИБО", pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.RUSSIAN, upperRu.resolvedLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, upperRu.resolutionMethod)

        val lowerRu = arbiter.arbitrate("спасибо", pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.RUSSIAN, lowerRu.resolvedLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, lowerRu.resolutionMethod)

        val mixedEn = arbiter.arbitrate("tHaNk YoU vErY mUcH", pair, DialogueTurnContext.EMPTY)
        assertEquals(Language.ENGLISH, mixedEn.resolvedLanguage)
        assertTrue(
            mixedEn.resolutionMethod == ResolutionMethod.SCRIPT_DISPARITY ||
            mixedEn.resolutionMethod == ResolutionMethod.LEXICAL_HEURISTIC
        )
    }

    @Test
    fun testSessionLanguageConstraint_NeverResolvesToUnconfiguredThirdLanguage() {
        val pair = DialogueSessionPair(Language.RUSSIAN, Language.ENGLISH)

        val germanText = "Guten Tag, wie geht es Ihnen?"
        val result = arbiter.arbitrate(germanText, pair, DialogueTurnContext.EMPTY)

        assertTrue(
            "Resolved language must be strictly within session pair",
            result.resolvedLanguage == Language.RUSSIAN || result.resolvedLanguage == Language.ENGLISH
        )
        assertEquals(Language.ENGLISH, result.resolvedLanguage)
        assertEquals(Language.RUSSIAN, result.targetLanguage)
    }

    @Test
    fun testBidirectionalSymmetryBetweenPrimaryAndSecondaryLanguageConfigurations() {
        val pairA = DialogueSessionPair(Language.RUSSIAN, Language.ENGLISH)
        val pairB = DialogueSessionPair(Language.ENGLISH, Language.RUSSIAN)

        val ruText = "Здравствуйте"
        val resA = arbiter.arbitrate(ruText, pairA, DialogueTurnContext.EMPTY)
        val resB = arbiter.arbitrate(ruText, pairB, DialogueTurnContext.EMPTY)

        assertEquals(Language.RUSSIAN, resA.resolvedLanguage)
        assertEquals(Language.RUSSIAN, resB.resolvedLanguage)
        assertEquals(Language.ENGLISH, resA.targetLanguage)
        assertEquals(Language.ENGLISH, resB.targetLanguage)
    }
}
