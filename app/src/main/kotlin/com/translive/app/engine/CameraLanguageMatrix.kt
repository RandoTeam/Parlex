package com.translive.app.engine

import com.translive.app.data.model.Language

/** Single source of truth for camera pair availability. */
object CameraLanguageMatrix {
    enum class Route { FAST_PACKAGE, LOCAL_LLM }

    data class Pair(
        val source: Language,
        val target: Language,
        val route: Route,
        val requiredFastPackages: Set<String> = emptySet()
    ) {
        val isSameLanguage: Boolean get() = source == target
    }

    /**
     * Builds the complete directed matrix. A pair is fast only when both
     * languages have a real ML Kit code; every other pair remains available
     * through the already-installed offline LLM.
     */
    fun all(toFastCode: (String) -> String?): List<Pair> =
        Language.allLanguages.flatMap { source ->
            Language.allLanguages.filter { it != source }.map { target ->
                val sourceCode = toFastCode(source.code)
                val targetCode = toFastCode(target.code)
                if (sourceCode != null && targetCode != null) {
                    Pair(source, target, Route.FAST_PACKAGE, linkedSetOf(sourceCode, targetCode))
                } else {
                    Pair(source, target, Route.LOCAL_LLM)
                }
            }
        }

    fun forPair(source: Language, target: Language, toFastCode: (String) -> String?): Pair {
        val sourceCode = toFastCode(source.code)
        val targetCode = toFastCode(target.code)
        return if (sourceCode != null && targetCode != null) {
            Pair(source, target, Route.FAST_PACKAGE, linkedSetOf(sourceCode, targetCode))
        } else {
            Pair(source, target, Route.LOCAL_LLM)
        }
    }
}
