package com.translive.app.engine

import com.translive.app.data.model.Language

/**
 * JNI bridge to llama.cpp for Hy-MT1.5-1.8B GGUF inference.
 * Native implementation in src/main/cpp/translive_jni.cpp
 */
class TranslationEngine {

    companion object {
        init {
            System.loadLibrary("translive")
        }
    }

    // --- Native methods (JNI) ---

    /** Load GGUF model from file path. Returns context pointer or 0 on failure. */
    private external fun nativeLoadModel(modelPath: String, nThreads: Int): Long

    /** Run translation inference. Returns translated text. */
    private external fun nativeTranslate(contextPtr: Long, prompt: String, maxTokens: Int): String

    /** Release model from memory. */
    private external fun nativeUnloadModel(contextPtr: Long)

    /** Check if model is loaded. */
    private external fun nativeIsLoaded(contextPtr: Long): Boolean

    // --- Kotlin API ---

    private var contextPtr: Long = 0L

    val isLoaded: Boolean get() = contextPtr != 0L && nativeIsLoaded(contextPtr)

    fun loadModel(modelPath: String, nThreads: Int = 4): Boolean {
        if (isLoaded) unloadModel()
        contextPtr = nativeLoadModel(modelPath, nThreads)
        return isLoaded
    }

    fun unloadModel() {
        if (contextPtr != 0L) {
            nativeUnloadModel(contextPtr)
            contextPtr = 0L
        }
    }

    /**
     * Translate text between two languages using appropriate prompt template.
     *
     * Uses ZH prompt template when either source or target is Chinese,
     * and EN prompt template for all other XX↔XX pairs.
     */
    fun translate(
        sourceText: String,
        source: Language,
        target: Language,
        maxTokens: Int = 2048
    ): String {
        require(isLoaded) { "Model not loaded. Call loadModel() first." }
        val prompt = buildPrompt(sourceText, source, target)
        return nativeTranslate(contextPtr, prompt, maxTokens).trim()
    }

    private fun buildPrompt(text: String, source: Language, target: Language): String {
        val isChinese = source.code.startsWith("zh") || target.code.startsWith("zh")
        return if (isChinese) {
            "将以下文本翻译为${target.nativeName}，注意只需要输出翻译后的结果，不要额外解释：\n$text"
        } else {
            "Translate the following segment into ${target.displayName}, without additional explanation.\n$text"
        }
    }
}
