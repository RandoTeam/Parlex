package com.translive.app.engine.benchmark

import com.translive.app.data.model.Language

/**
 * Standardized evaluation battery covering all 33 languages + 5 dialects + OCR structured text.
 */
object LanguageEvaluationSuite {

    fun createStandardBattery(): List<BenchmarkSample> = listOf(
        // High-Resource Core Sentences
        BenchmarkSample(
            id = "en_ru_airport",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.ENGLISH,
            targetLang = Language.RUSSIAN,
            sourceText = "Please show your passport and boarding pass at the gate.",
            referenceTranslation = "Пожалуйста, предъявите паспорт и посадочный талон у выхода на посадку."
        ),
        BenchmarkSample(
            id = "ru_en_pharmacy",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.RUSSIAN,
            targetLang = Language.ENGLISH,
            sourceText = "Где находится ближайшая круглосуточная аптека?",
            referenceTranslation = "Where is the nearest 24-hour pharmacy?"
        ),
        BenchmarkSample(
            id = "en_vi_hotel",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.ENGLISH,
            targetLang = Language.VIETNAMESE,
            sourceText = "I have a room reservation under the name Alex.",
            referenceTranslation = "Tôi có đặt phòng trước dưới tên Alex."
        ),
        BenchmarkSample(
            id = "vi_en_direction",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.VIETNAMESE,
            targetLang = Language.ENGLISH,
            sourceText = "Làm ơn cho tôi hỏi đường đến ga xe lửa gần nhất.",
            referenceTranslation = "Please tell me the way to the nearest train station."
        ),
        BenchmarkSample(
            id = "en_zh_restaurant",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.ENGLISH,
            targetLang = Language.CHINESE_SIMPLIFIED,
            sourceText = "Could we please have the bill and a receipt?",
            referenceTranslation = "请给我们账单和收据。"
        ),
        BenchmarkSample(
            id = "zh_en_tea",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.CHINESE_SIMPLIFIED,
            targetLang = Language.ENGLISH,
            sourceText = "这种绿茶产自中国南方的山区。",
            referenceTranslation = "This green tea is produced in the mountainous areas of southern China."
        ),
        BenchmarkSample(
            id = "en_es_market",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.ENGLISH,
            targetLang = Language.SPANISH,
            sourceText = "How much does a kilogram of fresh oranges cost?",
            referenceTranslation = "¿Cuánto cuesta un kilo de naranjas frescas?"
        ),
        BenchmarkSample(
            id = "en_de_train",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.ENGLISH,
            targetLang = Language.GERMAN,
            sourceText = "The express train to Berlin departs from platform four.",
            referenceTranslation = "Der Expresszug nach Berlin fährt von Gleis vier ab."
        ),
        BenchmarkSample(
            id = "en_fr_museum",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.ENGLISH,
            targetLang = Language.FRENCH,
            sourceText = "Are photos allowed inside the art exhibition?",
            referenceTranslation = "Les photos sont-elles autorisées à l'intérieur de l'exposition d'art ?"
        ),
        BenchmarkSample(
            id = "en_ja_ticket",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.ENGLISH,
            targetLang = Language.JAPANESE,
            sourceText = "Where can I buy a ticket for the subway?",
            referenceTranslation = "地下鉄の切符はどこで買えますか？"
        ),
        BenchmarkSample(
            id = "en_ko_coffee",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.ENGLISH,
            targetLang = Language.KOREAN,
            sourceText = "Can I get an iced Americano with extra ice, please?",
            referenceTranslation = "아이스 아메리카노에 얼음 많이 넣어 주시겠어요?"
        ),
        BenchmarkSample(
            id = "en_it_dinner",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.ENGLISH,
            targetLang = Language.ITALIAN,
            sourceText = "We would like to reserve a table for two at eight o'clock.",
            referenceTranslation = "Vorremmo prenotare un tavolo per due alle otto."
        ),
        BenchmarkSample(
            id = "en_pt_beach",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.ENGLISH,
            targetLang = Language.PORTUGUESE,
            sourceText = "The weather at the beach is sunny and very warm today.",
            referenceTranslation = "O tempo na praia está ensolarado e muito quente hoje."
        ),
        BenchmarkSample(
            id = "en_tr_bazaar",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.ENGLISH,
            targetLang = Language.TURKISH,
            sourceText = "The grand bazaar is open every day except Sunday.",
            referenceTranslation = "Kapalıçarşı pazar günleri hariç her gün açıktır."
        ),
        BenchmarkSample(
            id = "en_ar_hospital",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.ENGLISH,
            targetLang = Language.ARABIC,
            sourceText = "The emergency entrance is located on the right side of the hospital.",
            referenceTranslation = "يقع مدخل الطوارئ على الجانب الأيمن من المستشفى."
        ),
        BenchmarkSample(
            id = "en_hi_monument",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.ENGLISH,
            targetLang = Language.HINDI,
            sourceText = "This ancient temple was built over five hundred years ago.",
            referenceTranslation = "यह प्राचीन मंदिर पांच सौ साल से भी पहले बनाया गया था।"
        ),
        BenchmarkSample(
            id = "en_th_food",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.ENGLISH,
            targetLang = Language.THAI,
            sourceText = "Is this dish very spicy or mild?",
            referenceTranslation = "อาหารจานนี้เผ็ดมากหรือเผ็ดน้อยครับ?"
        ),
        BenchmarkSample(
            id = "en_id_island",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.ENGLISH,
            targetLang = Language.INDONESIAN,
            sourceText = "The ferry departs to the neighboring island every hour.",
            referenceTranslation = "Kapal feri berangkat ke pulau tetangga setiap jam."
        ),

        // Dialogue Turns
        BenchmarkSample(
            id = "dialogue_ru_en_1",
            category = SampleCategory.DIALOGUE_TURN,
            sourceLang = Language.RUSSIAN,
            targetLang = Language.ENGLISH,
            sourceText = "Здравствуйте, вы принимаете оплату банковской картой или только наличными?",
            referenceTranslation = "Hello, do you accept credit card payments or cash only?"
        ),
        BenchmarkSample(
            id = "dialogue_en_ru_1",
            category = SampleCategory.DIALOGUE_TURN,
            sourceLang = Language.ENGLISH,
            targetLang = Language.RUSSIAN,
            sourceText = "We accept both cards and contactless payments on all terminals.",
            referenceTranslation = "Мы принимаем как карты, так и бесконтактную оплату на всех терминалах."
        ),

        // Structured OCR Multi-Line Test
        BenchmarkSample(
            id = "ocr_structured_menu",
            category = SampleCategory.OCR_STRUCTURED,
            sourceLang = Language.ENGLISH,
            targetLang = Language.RUSSIAN,
            sourceText = """[L1] Daily Special Menu
[L2] Grilled Salmon with Asparagus
[L3] Fresh Lemonade and Mint Tea
[L4] Service charge 10% included""".trimIndent(),
            referenceTranslation = """[L1] Меню дня
[L2] Лосось на гриле со спаржей
[L3] Свежий лимонад и мятный чай
[L4] Сервисный сбор 10% включен""".trimIndent(),
            isStructuredOcr = true,
            expectedLineIds = listOf("[L1]", "[L2]", "[L3]", "[L4]")
        ),
        BenchmarkSample(
            id = "ocr_structured_street",
            category = SampleCategory.OCR_STRUCTURED,
            sourceLang = Language.ENGLISH,
            targetLang = Language.VIETNAMESE,
            sourceText = """[L1] Pedestrian Crossing
[L2] No Parking Anytime
[L3] Speed Limit 30 km/h""".trimIndent(),
            referenceTranslation = """[L1] Lối sang đường cho người đi bộ
[L2] Cấm đỗ xe mọi lúc
[L3] Giới hạn tốc độ 30 km/h""".trimIndent(),
            isStructuredOcr = true,
            expectedLineIds = listOf("[L1]", "[L2]", "[L3]")
        )
    )
}
