package com.translive.app.data.model

/**
 * All 33 languages + 5 dialects supported by Hy-MT1.5-1.8B.
 * Flag emojis are Unicode regional indicator symbols.
 */
enum class Language(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val flag: String,
    val isDialect: Boolean = false
) {
    // 33 primary languages
    ENGLISH("en", "English", "English", "🇬🇧"),
    CHINESE_SIMPLIFIED("zh", "Chinese (Simplified)", "简体中文", "🇨🇳"),
    CHINESE_TRADITIONAL("zh-Hant", "Chinese (Traditional)", "繁體中文", "🇹🇼"),
    JAPANESE("ja", "Japanese", "日本語", "🇯🇵"),
    KOREAN("ko", "Korean", "한국어", "🇰🇷"),
    FRENCH("fr", "French", "Français", "🇫🇷"),
    GERMAN("de", "German", "Deutsch", "🇩🇪"),
    SPANISH("es", "Spanish", "Español", "🇪🇸"),
    PORTUGUESE("pt", "Portuguese", "Português", "🇧🇷"),
    ITALIAN("it", "Italian", "Italiano", "🇮🇹"),
    DUTCH("nl", "Dutch", "Nederlands", "🇳🇱"),
    POLISH("pl", "Polish", "Polski", "🇵🇱"),
    CZECH("cs", "Czech", "Čeština", "🇨🇿"),
    TURKISH("tr", "Turkish", "Türkçe", "🇹🇷"),
    UKRAINIAN("uk", "Ukrainian", "Українська", "🇺🇦"),
    RUSSIAN("ru", "Russian", "Русский", "🇷🇺"),
    BURMESE("my", "Burmese", "မြန်မာ", "🇲🇲"),
    HINDI("hi", "Hindi", "हिन्दी", "🇮🇳"),
    BENGALI("bn", "Bengali", "বাংলা", "🇧🇩"),
    GUJARATI("gu", "Gujarati", "ગુજરાતી", "🇮🇳"),
    MARATHI("mr", "Marathi", "मराठी", "🇮🇳"),
    TAMIL("ta", "Tamil", "தமிழ்", "🇮🇳"),
    TELUGU("te", "Telugu", "తెలుగు", "🇮🇳"),
    URDU("ur", "Urdu", "اردو", "🇵🇰"),
    PERSIAN("fa", "Persian", "فارسی", "🇮🇷"),
    HEBREW("he", "Hebrew", "עברית", "🇮🇱"),
    ARABIC("ar", "Arabic", "العربية", "🇸🇦"),
    THAI("th", "Thai", "ไทย", "🇹🇭"),
    VIETNAMESE("vi", "Vietnamese", "Tiếng Việt", "🇻🇳"),
    INDONESIAN("id", "Indonesian", "Bahasa Indonesia", "🇮🇩"),
    MALAY("ms", "Malay", "Bahasa Melayu", "🇲🇾"),
    FILIPINO("fil", "Filipino", "Filipino", "🇵🇭"),
    KHMER("km", "Khmer", "ភាសាខ្មែរ", "🇰🇭"),

    // 5 dialects
    CANTONESE("yue", "Cantonese", "粵語", "🇭🇰", isDialect = true),
    HOKKIEN("nan", "Hokkien", "閩南語", "🇨🇳", isDialect = true),
    TIBETAN("bo", "Tibetan", "བོད་སྐད་", "🇨🇳", isDialect = true),
    MONGOLIAN("mn", "Mongolian", "ᠮᠣᠩᠭᠣᠯ", "🇲🇳", isDialect = true),
    UYGHUR("ug", "Uyghur", "ئۇيغۇرچە", "🇨🇳", isDialect = true);

    companion object {
        /** Primary languages only (no dialects) */
        val primaryLanguages = entries.filter { !it.isDialect }

        /** All languages including dialects */
        val allLanguages = entries.toList()

        fun fromCode(code: String): Language? = entries.find { it.code == code }
    }
}
