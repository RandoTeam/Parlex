package com.translive.app.data

import com.translive.app.data.model.DictionaryEntry

/**
 * Built-in curated high-frequency dictionary seed data for instant offline lookup.
 */
object CoreDictionarySeeder {

    fun getCoreEntries(): List<DictionaryEntry> {
        val list = mutableListOf<DictionaryEntry>()

        fun add(src: String, tgt: String, word: String, def: String, pos: String? = null, ipa: String? = null, ex: String? = null) {
            val norm = word.trim().lowercase()
            list.add(
                DictionaryEntry(
                    headword = word.trim(),
                    normalizedHeadword = norm,
                    sourceLang = src,
                    targetLang = tgt,
                    partOfSpeech = pos,
                    pronunciation = ipa,
                    definition = def,
                    examples = ex,
                    isCustom = false
                )
            )
        }

        // EN -> RU essential lexicon
        add("en", "ru", "hello", "привет, здравствуйте; алло", "interjection", "/həˈloʊ/", "Hello, how are you? — Привет, как дела?")
        add("en", "ru", "world", "мир, свет, вселенная", "noun", "/wɜːrld/", "All around the world. — По всему миру.")
        add("en", "ru", "language", "язык, речь, стиль", "noun", "/ˈlæŋɡwɪdʒ/", "Foreign language. — Иностранный язык.")
        add("en", "ru", "translate", "переводить, транслировать", "verb", "/trænzˈleɪt/", "Translate into Russian. — Перевести на русский.")
        add("en", "ru", "translation", "перевод, трансляция", "noun", "/trænzˈleɪʃn/", "Accurate translation. — Точный перевод.")
        add("en", "ru", "dictionary", "словарь, справочник", "noun", "/ˈdɪkʃəneri/", "Offline dictionary. — Офлайн-словарь.")
        add("en", "ru", "screen", "экран, монитор, щит", "noun", "/skriːn/", "Touch the screen. — Коснитесь экрана.")
        add("en", "ru", "camera", "камера, фотоаппарат", "noun", "/ˈkæmrə/", "Camera translation. — Перевод камерой.")
        add("en", "ru", "voice", "голос, речь; озвучивать", "noun / verb", "/vɔɪs/", "Voice input. — Голосовой ввод.")
        add("en", "ru", "dialogue", "диалог, разговор, беседа", "noun", "/ˈdaɪəlɔːɡ/", "Live dialogue. — Живой диалог.")
        add("en", "ru", "speech", "речь, выступление, высказывание", "noun", "/spiːtʃ/", "Speech recognition. — Распознавание речи.")
        add("en", "ru", "text", "текст, смс-сообщение; писать текст", "noun / verb", "/tekst/", "Source text. — Исходный текст.")
        add("en", "ru", "model", "модель, образец; моделировать", "noun / verb", "/ˈmɑːdl/", "On-device model. — Локальная модель на устройстве.")
        add("en", "ru", "fast", "быстрый, скорый; быстро", "adj / adv", "/fæst/", "Fast neural translation. — Быстрый нейросетевой перевод.")
        add("en", "ru", "offline", "автономный, офлайн; без сети", "adj / adv", "/ˌɔːfˈlaɪn/", "Works completely offline. — Работает полностью офлайн.")
        add("en", "ru", "device", "устройство, прибор, девайс", "noun", "/dɪˈvaɪs/", "Local mobile device. — Локальное мобильное устройство.")
        add("en", "ru", "settings", "настройки, параметры", "noun pl.", "/ˈsetɪŋz/", "Application settings. — Настройки приложения.")
        add("en", "ru", "download", "скачивать, загружать; загрузка", "verb / noun", "/ˌdaʊnˈloʊd/", "Download language pack. — Скачать языковой пакет.")
        add("en", "ru", "history", "история, хронология", "noun", "/ˈhɪstri/", "Translation history. — История переводов.")
        add("en", "ru", "favorite", "избранный, любимый; фаворит", "adj / noun", "/ˈfeɪvərɪt/", "Add to favorites. — Добавить в избранное.")
        add("en", "ru", "copy", "копировать, воспроизводить; копия", "verb / noun", "/ˈkɑːpi/", "Copy to clipboard. — Скопировать в буфер обмена.")
        add("en", "ru", "clear", "очищать; ясный, понятный", "verb / adj", "/klɪr/", "Clear input. — Очистить ввод.")
        add("en", "ru", "share", "делиться, распространять; доля", "verb / noun", "/ʃer/", "Share translated text. — Поделиться переведённым текстом.")
        add("en", "ru", "listen", "слушать, прислушиваться", "verb", "/ˈlɪsn/", "Listen to pronunciation. — Послушать произношение.")
        add("en", "ru", "pronunciation", "произношение, артикуляция", "noun", "/prəˌnʌnsiˈeɪʃn/", "Check pronunciation. — Проверить произношение.")
        add("en", "ru", "word", "слово, формулировка, обещание", "noun", "/wɜːrd/", "Look up a word. — Найти слово в словаре.")
        add("en", "ru", "help", "помогать; помощь, справка", "verb / noun", "/help/", "Help and feedback. — Помощь и обратная связь.")
        add("en", "ru", "start", "начинать, запускать; старт", "verb / noun", "/stɑːrt/", "Start conversation. — Начать разговор.")
        add("en", "ru", "stop", "останавливать, прекращать; стоп", "verb / noun", "/stɑːp/", "Stop recording. — Остановить запись.")
        add("en", "ru", "pause", "приостанавливать; пауза", "verb / noun", "/pɔːz/", "Pause translation. — Приостановить перевод.")
        add("en", "ru", "speed", "скорость, темп", "noun", "/spiːd/", "Processing speed. — Скорость обработки.")
        add("en", "ru", "accuracy", "точность, правильность, меткость", "noun", "/ˈækjərəsi/", "High translation accuracy. — Высокая точность перевода.")
        add("en", "ru", "quality", "качество, свойство; качественный", "noun / adj", "/ˈkwɑːləti/", "High quality mode. — Режим высокого качества.")
        add("en", "ru", "document", "документ; документировать", "noun / verb", "/ˈdɑːkjumənt/", "Translate document. — Перевести документ.")
        add("en", "ru", "file", "файл; подшивать, сохранять", "noun / verb", "/faɪl/", "Open PDF file. — Открыть PDF-файл.")
        add("en", "ru", "page", "страница, лист", "noun", "/peɪdʒ/", "Page translation. — Постраничный перевод.")
        add("en", "ru", "book", "книга; бронировать, заказывать", "noun / verb", "/bʊk/", "Read a book. — Читать книгу.")
        add("en", "ru", "article", "статья, пункт, артикль", "noun", "/ˈɑːrtɪkl/", "Read article on screen. — Читать статью на экране.")
        add("en", "ru", "note", "заметка, примечание; замечать", "noun / verb", "/noʊt/", "Quick note. — Быстрая заметка.")

        // RU -> EN essential lexicon
        add("ru", "en", "привет", "hello, hi, greetings", "междометие", null, "Привет, как дела? — Hello, how are you?")
        add("ru", "en", "мир", "world, peace, universe", "существительное", null, "Мир технологий. — World of technology.")
        add("ru", "en", "язык", "language, tongue", "существительное", null, "Изучать иностранный язык. — Learn a foreign language.")
        add("ru", "en", "перевод", "translation, transfer, rendering", "существительное", null, "Точный перевод. — Accurate translation.")
        add("ru", "en", "переводить", "translate, render, interpret", "глагол", null, "Переводить текст без интернета. — Translate text without internet.")
        add("ru", "en", "словарь", "dictionary, vocabulary, glossary", "существительное", null, "Встроенный словарь. — Built-in dictionary.")
        add("ru", "en", "экран", "screen, display, monitor", "существительное", null, "Перевод поверх экрана. — Translation over screen.")
        add("ru", "en", "камера", "camera, camcorder", "существительное", null, "Распознавание камерой. — Camera recognition.")
        add("ru", "en", "голос", "voice, vote", "существительное", null, "Голосовой перевод. — Voice translation.")
        add("ru", "en", "диалог", "dialogue, conversation", "существительное", null, "Живой диалог двух людей. — Live dialogue between two people.")
        add("ru", "en", "речь", "speech, discourse, language", "существительное", null, "Синтез и распознавание речи. — Speech synthesis and recognition.")
        add("ru", "en", "текст", "text, lyrics", "существительное", null, "Ввести текст для перевода. — Enter text to translate.")
        add("ru", "en", "быстро", "fast, quickly, swiftly", "наречие", null, "Быстрый перевод нейросетью. — Fast neural translation.")
        add("ru", "en", "слово", "word, term, pledge", "существительное", null, "Значение слова в контексте. — Meaning of the word in context.")
        add("ru", "en", "документ", "document, paper", "существительное", null, "Перевод PDF документов. — PDF document translation.")
        add("ru", "en", "настройки", "settings, preferences, configuration", "существительное", null, "Настройки приложения. — App settings.")
        add("ru", "en", "избранное", "favorites, bookmarks", "существительное", null, "Сохранить в избранное. — Save to favorites.")
        add("ru", "en", "копировать", "copy, duplicate, replicate", "глагол", null, "Копировать результат. — Copy result.")
        add("ru", "en", "очистить", "clear, clean, purge", "глагол", null, "Очистить историю. — Clear history.")
        add("ru", "en", "слушать", "listen, hear", "глагол", null, "Слушать произношение. — Listen to pronunciation.")

        return list
    }
}
