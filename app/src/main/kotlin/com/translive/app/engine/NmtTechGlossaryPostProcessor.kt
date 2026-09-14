package com.translive.app.engine

/**
 * Zero-latency post-processor for Neural Machine Translation outputs.
 * Corrects grotesque literal calques commonly produced by small on-device NMT models
 * (e.g. Google ML Kit Translate) on technical and open-source software terminology.
 */
object NmtTechGlossaryPostProcessor {

    private const val B_START = """(?:^|(?<=\s|[.,!?:;«"'\(]))"""
    private const val B_END = """(?=$|\s|[.,!?:;»"'\)])"""

    private val REPLACEMENTS: List<Pair<Regex, String>> = listOf(
        // "built in the open" -> "построен на открытом воздухе"
        Regex("""(?i)${B_START}построен(?:о|а|ы)?\s+на\s+открытом\s+воздухе$B_END""") to "разрабатывается открыто",
        Regex("""(?i)${B_START}на\s+открытом\s+воздухе$B_END""") to "открыто",
        Regex("""(?i)${B_START}в\s+открытом\s+воздухе$B_END""") to "в открытом доступе",

        // "flips that around" -> "PocketPal Flips, что вокруг"
        Regex("""(?i)PocketPal\s+[Ff]lips,?\s+что\s+вокруг:?""") to "PocketPal меняет этот подход:",
        Regex("""(?i)${B_START}[Ff]lips,?\s+что\s+вокруг:?""") to "меняет этот подход:",
        Regex("""(?i)${B_START}переворачивает\s+это\s+вокруг$B_END""") to "меняет этот подход",

        // "and your conversations never leave it" -> "никогда не оставляют ее"
        Regex("""(?i)${B_START}никогда\s+не\s+оставляют\s+ее$B_END""") to "никогда не покидают его",
        Regex("""(?i)${B_START}никогда\s+не\s+покидают\s+ее$B_END""") to "никогда не покидают его",
        Regex("""(?i)${B_START}никогда\s+не\s+оставляют\s+его$B_END""") to "никогда не покидают его",

        // "thin window onto someone else's server" -> "тонкое окно на чужом сервере"
        Regex("""(?i)${B_START}тонкое\s+окно\s+на\s+чуж(?:ом\s+сервере|ой\s+сервер)$B_END""") to "простая оболочка для чужого сервера",

        // "open source" -> "открытый источник"
        Regex("""(?i)${B_START}открыт(?:ый|ого)\s+источник(?:а)?$B_END""") to "открытый исходный код",

        // Clean up duplicate "FREE бесплатно"
        Regex("""(?i)${B_START}FREE\s+бесплатно$B_END""") to "Бесплатно",

        // "Android" / "Android Police" -> "Человекоподобный робот"
        Regex("""(?i)${B_START}человекоподобн(?:ый|ого|ому|ым|ом)\s+робот(?:а|у|ом|е)?\s+полици(?:я|и|ю|ей)$B_END""") to "Android Police",
        Regex("""(?i)${B_START}человекоподобн(?:ый|ого|ому|ым|ом)\s+робот(?:а|у|ом|е)?$B_END""") to "Android"
    )

    fun postProcess(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        for ((pattern, replacement) in REPLACEMENTS) {
            result = pattern.replace(result, replacement)
        }
        return result
    }
}
