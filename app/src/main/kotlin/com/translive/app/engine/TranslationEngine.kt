package com.translive.app.engine

import com.translive.app.data.ModelRepository
import com.translive.app.data.model.Language
import com.translive.app.data.model.PromptStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * JNI bridge to llama.cpp for GGUF model inference.
 * Supports multiple model families with per-family prompt templates.
 * Native implementation in src/main/cpp/translive_jni.cpp
 *
 * IMPORTANT: llama.cpp is NOT thread-safe. All native calls are
 * serialized through [inferenceMutex]. Never call native methods directly.
 */
class TranslationEngine {

    /** Set by DI after construction — used to determine active model's prompt style. */
    var modelRepository: ModelRepository? = null

    /** Mutex to serialize all native calls — llama.cpp is not thread-safe. */
    val inferenceMutex = Mutex()

    /** Also protects synchronous load/unload/isLoaded calls from racing inference. */
    private val nativeLock = ReentrantLock()

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
    private external fun nativeTranslate(
        contextPtr: Long, prompt: String, maxTokens: Int, useChatTemplate: Boolean
    ): String

    /** Run streaming translation. Calls callback.onToken() per token. Returns [promptTokens, genTokens]. */
    private external fun nativeTranslateStreaming(
        contextPtr: Long, prompt: String, maxTokens: Int, useChatTemplate: Boolean,
        callback: TokenCallback
    ): IntArray

    /** Release model from memory. */
    private external fun nativeUnloadModel(contextPtr: Long)

    /** Check if model is loaded. */
    private external fun nativeIsLoaded(contextPtr: Long): Boolean

    // --- Kotlin API ---

    private var contextPtr: Long = 0L

    val isLoaded: Boolean
        get() = nativeLock.withLock { isLoadedLocked() }

    fun loadModel(modelPath: String, nThreads: Int = 4): Boolean {
        return nativeLock.withLock {
            if (isLoadedLocked()) {
                nativeUnloadModel(contextPtr)
                contextPtr = 0L
            }
            val optimalThreads = getOptimalThreadCount(nThreads)
            android.util.Log.i("TranslationEngine", "Loading model: threads=$optimalThreads (requested=$nThreads, cores=${Runtime.getRuntime().availableProcessors()})")
            contextPtr = nativeLoadModel(modelPath, optimalThreads)
            isLoadedLocked()
        }
    }

    /**
     * Clamp thread count to performance cores.
     * On big.LITTLE SoCs (e.g. 4 perf + 4 efficiency), using all 8 cores
     * pushes threads to slow efficiency cores, degrading throughput.
     */
    private fun getOptimalThreadCount(requested: Int): Int {
        val totalCores = Runtime.getRuntime().availableProcessors()
        val perfCores = (totalCores / 2).coerceAtLeast(2)
        return requested.coerceIn(1, perfCores)
    }

    fun unloadModel() {
        nativeLock.withLock {
            if (contextPtr != 0L) {
                nativeUnloadModel(contextPtr)
                contextPtr = 0L
            }
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
        return nativeLock.withLock {
            if (!isLoadedLocked()) throw IllegalStateException("Модель перевода не загружена")
            val style = getActivePromptStyle()
            val prompt = buildPrompt(sourceText, source, target, style)
            val useChatTemplate = true  // Both HY-MT and TranslateGemma use chat template
            nativeTranslate(contextPtr, prompt, maxTokens, useChatTemplate).trim()
        }
    }

    /** Thread-safe translate for use from coroutines. */
    suspend fun translateSafe(
        sourceText: String,
        source: Language,
        target: Language,
        maxTokens: Int = 2048
    ): String = inferenceMutex.withLock {
        translate(sourceText, source, target, maxTokens)
    }

    /**
     * Translate OCR lines while asking the model to preserve stable line IDs.
     * Callers must still validate the returned structure and fall back if IDs are not preserved.
     */
    fun translateStructured(
        sourceText: String,
        source: Language,
        target: Language,
        maxTokens: Int = 2048
    ): String {
        return nativeLock.withLock {
            if (!isLoadedLocked()) throw IllegalStateException("Модель перевода не загружена")
            val style = getActivePromptStyle()
            val prompt = buildStructuredPrompt(sourceText, source, target, style)
            val useChatTemplate = true
            nativeTranslate(contextPtr, prompt, maxTokens, useChatTemplate).trim()
        }
    }

    /** Thread-safe structured translate for use from coroutines. */
    suspend fun translateStructuredSafe(
        sourceText: String,
        source: Language,
        target: Language,
        maxTokens: Int = 2048
    ): String = inferenceMutex.withLock {
        translateStructured(sourceText, source, target, maxTokens)
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
    ): Flow<String> = channelFlow {
        val streamResult = nativeLock.withLock {
            if (!isLoadedLocked()) throw IllegalStateException("Модель перевода не загружена")
            val style = getActivePromptStyle()
            val prompt = buildPrompt(sourceText, source, target, style)
            val useChatTemplate = true  // Both HY-MT and TranslateGemma use chat template

            val callback = object : TokenCallback {
                override fun onToken(token: String): Boolean {
                    return try {
                        trySend(token).isSuccess
                    } catch (_: Exception) {
                        false
                    }
                }
            }

            val counts = nativeTranslateStreaming(contextPtr, prompt, maxTokens, useChatTemplate, callback)
            StreamResult(
                promptTokens = counts.getOrElse(0) { 0 },
                generatedTokens = counts.getOrElse(1) { 0 }
            )
        }
        onComplete?.invoke(streamResult)
    }

    private fun isLoadedLocked(): Boolean = contextPtr != 0L && nativeIsLoaded(contextPtr)

    private fun getActivePromptStyle(): PromptStyle =
        modelRepository?.getActiveFamily()?.promptStyle ?: PromptStyle.HY_MT

    private fun buildPrompt(text: String, source: Language, target: Language, style: PromptStyle): String {
        return when (style) {
            PromptStyle.HY_MT -> buildHyMtPrompt(text, source, target)
            PromptStyle.TRANSLATE_GEMMA -> buildTranslateGemmaPrompt(text, source, target)
        }
    }

    private fun buildStructuredPrompt(
        text: String,
        source: Language,
        target: Language,
        style: PromptStyle
    ): String {
        return when (style) {
            PromptStyle.HY_MT,
            PromptStyle.TRANSLATE_GEMMA -> """
                Translate the OCR lines from ${source.displayName} to ${target.displayName}.
                Preserve every line ID exactly, for example [L1].
                Return one translated line for each input line.
                Do not add explanations or extra lines.
                $text
            """.trimIndent()
        }
    }

    /** HY-MT: Chinese prompt for zh pairs, English for others */
    private fun buildHyMtPrompt(text: String, source: Language, target: Language): String {
        val isChinese = source.code.startsWith("zh") || target.code.startsWith("zh")
        return if (isChinese) {
            "将以下文本翻译为${target.nativeName}，注意只需要输出翻译后的结果，不要额外解释：\n$text"
        } else {
            "Translate the following segment into ${target.displayName}, without additional explanation.\n$text"
        }
    }

    /**
     * TranslateGemma: simple instruction format.
     * Chat template wrapping is handled by the native layer.
     * Do NOT include output markers like "English:" — the model generates after <start_of_turn>model.
     */
    private fun buildTranslateGemmaPrompt(text: String, source: Language, target: Language): String {
        return "Translate the following text from ${source.displayName} to ${target.displayName}:\n$text"
    }
}
