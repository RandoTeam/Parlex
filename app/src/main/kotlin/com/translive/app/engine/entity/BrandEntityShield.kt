package com.translive.app.engine.entity

/**
 * High-performance brand entity and proper noun protection engine.
 * Protects tech publications, operating systems, hardware brands, and proper nouns
 * from literal or sci-fi machine calques (e.g. preventing "Android Police" -> "Человекоподобный робот Полиция").
 */
object BrandEntityShield {

    // Common tech brands and publications that should NEVER be translated into Russian or other target languages
    val PROTECTED_ENTITIES = listOf(
        "Android Police",
        "AndroidPolice",
        "Android",
        "DuckDuckGo",
        "YouTube",
        "Valnet",
        "Valnet Inc",
        "Valnet Inc.",
        "Google",
        "Google Play",
        "GitHub",
        "Reddit",
        "Telegram",
        "WhatsApp",
        "Twitter",
        "PocketPal AI",
        "PocketPal",
        "OpenAI",
        "ChatGPT",
        "DeepMind",
        "OnePlus",
        "Snapdragon",
        "GeckoView",
        "Chromium",
        "Firefox"
    )

    private val EXACT_MATCH_SET = PROTECTED_ENTITIES.map { it.lowercase() }.toSet()

    /**
     * Returns true if the string is exactly an entity name (ignoring case and surrounding whitespace).
     */
    fun isExactEntity(text: String): Boolean {
        val trimmed = text.trim().lowercase()
        return EXACT_MATCH_SET.contains(trimmed)
    }

    /**
     * Shields entities within text by replacing them with index tokens [[ENTITY_N]] before translation,
     * returning the shielded text and a map to restore them.
     */
    fun shieldEntities(text: String): Pair<String, Map<String, String>> {
        var result = text
        val map = mutableMapOf<String, String>()
        var counter = 0

        // Sort by length descending to match longer multi-word entities first (e.g. "Android Police" before "Android")
        val sorted = PROTECTED_ENTITIES.sortedByDescending { it.length }

        for (entity in sorted) {
            val regex = Regex("""(?i)\b${Regex.escape(entity)}\b""")
            if (regex.containsMatchIn(result)) {
                val matches = regex.findAll(result).toList()
                for (match in matches) {
                    val original = match.value
                    val token = "[[ENTITY_${counter++}]]"
                    map[token] = original
                    result = result.replaceFirst(original, token)
                }
            }
        }

        return result to map
    }

    /**
     * Unshields text by replacing tokens [[ENTITY_N]] with the original entity strings.
     */
    fun unshieldEntities(translatedText: String, entityMap: Map<String, String>): String {
        if (entityMap.isEmpty()) return translatedText
        var restored = translatedText
        for ((token, original) in entityMap) {
            val bare = token.trim('[', ']', ' ', '_', '(', ')')
            // Resilient regex matching any bracket / spacing / casing variation produced by NMT tokenizers:
            // Matches "[[ENTITY_0]]", "[Entity_0]]", "[Entity_0]", "[ [ ENTITY_0 ] ]", or bare "Entity_0"
            // strictly without consuming any surrounding text whitespace.
            val relaxedRegex = Regex(
                """\[\s*\[?\s*${Regex.escape(bare)}\s*\]?\s*\]|\b${Regex.escape(bare)}\b""",
                RegexOption.IGNORE_CASE
            )
            restored = relaxedRegex.replace(restored, original)
            restored = restored.replace(token, original)
        }
        return restored
    }
}
