package com.translive.app.engine

import com.translive.app.data.model.Language
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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

    /** Callback interface for streaming token output from JNI. */
    interface TokenCallback {
        /** Called for each generated token. Return true to continue, false to cancel. */
        fun onToken(token: String): Boolean
    }

    /** Result of a streaming translation with accurate token counts from native layer. */
    data class StreamResult(
        val promptTokens: Int,
        val generatedTokens: Int
    )

    // --- Native methods (JNI) ---

    /** Load GGUF model from file path. Returns context pointer or 0 on failure. */
    private external fun nativeLoadModel(modelPath: String, nThreads: Int): Long

    /** Run translation inference. Returns translated text. */
    private external fun nativeTranslate(contextPtr: Long, prompt: String, maxTokens: Int): String

    /** Run streaming translation. Calls callback.onToken() per token. Returns [promptTokens, genTokens]. */
    private external fun nativeTranslateStreaming(
        contextPtr: Long, prompt: String, maxTokens: Int, callback: TokenCallback
    ): IntArray

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

    /**
     * Streaming translation: emits each token as it's generated.
     * Collect the Flow to build up the translated text in real-time.
     * Returns StreamResult with accurate token counts after completion.
     */
    fun translateStreaming(
        sourceText: String,
        source: Language,
        target: Language,
        maxTokens: Int = 2048,
        onComplete: ((StreamResult) -> Unit)? = null
    ): Flow<String> = callbackFlow {
        require(isLoaded) { "Model not loaded. Call loadModel() first." }
        val prompt = buildPrompt(sourceText, source, target)

        val callback = object : TokenCallback {
            override fun onToken(token: String): Boolean {
                val result = trySend(token)
                return result.isSuccess
            }
        }

        val counts = nativeTranslateStreaming(contextPtr, prompt, maxTokens, callback)
        val streamResult = StreamResult(
            promptTokens = counts.getOrElse(0) { 0 },
            generatedTokens = counts.getOrElse(1) { 0 }
        )
        onComplete?.invoke(streamResult)
        close()

        awaitClose { /* native call already finished */ }
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
