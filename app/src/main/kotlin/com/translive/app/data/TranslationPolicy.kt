package com.translive.app.data

/** App-wide choice between compact offline NMT and the local LLM. */
enum class TranslationPolicy(val persistedValue: String) {
    FAST("fast"),
    FAST_WITH_LLM_IMPROVE("fast_with_llm_improve"),
    LLM_ONLY("llm_only");

    companion object {
        fun fromPersisted(value: String?): TranslationPolicy =
            entries.firstOrNull { it.persistedValue == value } ?: FAST_WITH_LLM_IMPROVE
    }
}
