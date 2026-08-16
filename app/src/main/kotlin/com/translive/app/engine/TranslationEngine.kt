package com.translive.app.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.translive.app.data.ModelRepository
import com.translive.app.data.SettingsRepository
import com.translive.app.data.model.Language
import com.translive.app.data.model.PromptStyle
import com.translive.app.data.model.TranslationProfile
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
    private external fun nativeLoadModel(modelPath: String, nThreads: Int, useGpu: Boolean): Long

    /** Run translation inference. Returns translated text. */
    private external fun nativeTranslate(
        contextPtr: Long,
        prompt: String,
        maxTokens: Int,
        useChatTemplate: Boolean,
        temperature: Float,
        topK: Int,
        topP: Float,
        repetitionPenalty: Float
    ): String

    /** Run streaming translation. Calls callback.onToken() per token. Returns [promptTokens, genTokens]. */
    private external fun nativeTranslateStreaming(
        contextPtr: Long,
        prompt: String,
        maxTokens: Int,
        useChatTemplate: Boolean,
        temperature: Float,
        topK: Int,
        topP: Float,
        repetitionPenalty: Float,
        callback: TokenCallback
    ): IntArray

    /** Release model from memory. */
    private external fun nativeUnloadModel(contextPtr: Long)

    /** Check if model is loaded. */
    private external fun nativeIsLoaded(contextPtr: Long): Boolean

    /** Hardware/runtime probe. Does not allocate or load a language model. */
    private external fun nativeRuntimeDiagnostics(): String

    // --- Kotlin API ---

    private var contextPtr: Long = 0L

    /** Backend of the currently loaded GGUF context, never a UI assumption. */
    @Volatile
    var currentBackend: String? = null
        private set

    val isLoaded: Boolean
        get() = nativeLock.withLock { isLoadedLocked() }

    fun loadModel(
        modelPath: String,
        nThreads: Int = 4,
        backend: String = SettingsRepository.BACKEND_CPU
    ): Boolean {
        return nativeLock.withLock {
            if (isLoadedLocked()) {
                nativeUnloadModel(contextPtr)
                contextPtr = 0L
                currentBackend = null
            }
            val optimalThreads = getOptimalThreadCount(nThreads)
            android.util.Log.i("TranslationEngine", "Loading model: threads=$optimalThreads (requested=$nThreads, cores=${Runtime.getRuntime().availableProcessors()})")
            contextPtr = nativeLoadModel(
                modelPath,
                optimalThreads,
                backend == SettingsRepository.BACKEND_GPU
            )
            val loaded = isLoadedLocked()
            currentBackend = if (loaded) {
                if (backend == SettingsRepository.BACKEND_GPU) "opencl" else "cpu"
            } else {
                null
            }
            loaded
        }
    }

    /**
     * Honor the user's selected thread count up to the number of online cores.
     * A fixed half-core clamp is incorrect on all-performance designs such as
     * Snapdragon 8 Elite (six 3.53 GHz cores plus two 4.32 GHz prime cores).
     */
    private fun getOptimalThreadCount(requested: Int): Int {
        val totalCores = Runtime.getRuntime().availableProcessors()
        return requested.coerceIn(1, totalCores.coerceAtLeast(1))
    }

    fun unloadModel() {
        nativeLock.withLock {
            if (contextPtr != 0L) {
                nativeUnloadModel(contextPtr)
                contextPtr = 0L
                currentBackend = null
            }
        }
    }

    fun collectRuntimeDiagnostics(context: Context): String {
        val memory = ActivityManager.MemoryInfo().also { info ->
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        }
        val mib = 1024L * 1024L
        return buildString {
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("App-visible RAM: ${memory.availMem / mib} MiB free / ${memory.totalMem / mib} MiB total")
            append(nativeRuntimeDiagnostics())
        }
    }

    /**
     * Translate text between two languages using appropriate prompt template.
     */
    fun translate(
        sourceText: String,
        source: Language,
        target: Language,
        maxTokens: Int = 512
    ): String {
        return nativeLock.withLock {
            if (!isLoadedLocked()) throw IllegalStateException("Модель перевода не загружена")
            val profile = getActiveProfile()
            val prompt = buildPrompt(sourceText, source, target, profile.promptStyle)
            nativeTranslate(
                contextPtr,
                prompt,
                maxTokens,
                profile.useChatTemplate,
                profile.sampling.temperature,
                profile.sampling.topK,
                profile.sampling.topP,
                profile.sampling.repetitionPenalty
            ).trim()
        }
    }

    /** Thread-safe translate for use from coroutines. */
    suspend fun translateSafe(
        sourceText: String,
        source: Language,
        target: Language,
        maxTokens: Int = 512
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
        maxTokens: Int = 512
    ): String {
        return nativeLock.withLock {
            if (!isLoadedLocked()) throw IllegalStateException("Модель перевода не загружена")
            val profile = getActiveProfile()
            val prompt = buildStructuredPrompt(sourceText, source, target, profile.promptStyle)
            nativeTranslate(
                contextPtr,
                prompt,
                maxTokens,
                profile.useChatTemplate,
                profile.sampling.temperature,
                profile.sampling.topK,
                profile.sampling.topP,
                profile.sampling.repetitionPenalty
            ).trim()
        }
    }

    /** Thread-safe structured translate for use from coroutines. */
    suspend fun translateStructuredSafe(
        sourceText: String,
        source: Language,
        target: Language,
        maxTokens: Int = 512
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
        maxTokens: Int = 512,
        onComplete: ((StreamResult) -> Unit)? = null
    ): Flow<String> = channelFlow {
        val streamResult = nativeLock.withLock {
            if (!isLoadedLocked()) throw IllegalStateException("Модель перевода не загружена")
            val profile = getActiveProfile()
            val prompt = buildPrompt(sourceText, source, target, profile.promptStyle)

            val callback = object : TokenCallback {
                override fun onToken(token: String): Boolean {
                    return try {
                        trySend(token).isSuccess
                    } catch (_: Exception) {
                        false
                    }
                }
            }

            val counts = nativeTranslateStreaming(
                contextPtr,
                prompt,
                maxTokens,
                profile.useChatTemplate,
                profile.sampling.temperature,
                profile.sampling.topK,
                profile.sampling.topP,
                profile.sampling.repetitionPenalty,
                callback
            )
            StreamResult(
                promptTokens = counts.getOrElse(0) { 0 },
                generatedTokens = counts.getOrElse(1) { 0 }
            )
        }
        onComplete?.invoke(streamResult)
    }

    private fun isLoadedLocked(): Boolean = contextPtr != 0L && nativeIsLoaded(contextPtr)

    private fun getActiveProfile(): TranslationProfile =
        modelRepository?.getActiveTranslationProfile()
            ?: com.translive.app.data.model.TranslationProfiles.forModel(null, com.translive.app.data.model.ModelRuntime.GGUF)

    private fun buildPrompt(text: String, source: Language, target: Language, style: PromptStyle): String {
        return when (style) {
            PromptStyle.HY_MT -> buildHyMtPrompt(text, source, target)
            PromptStyle.HY_MT2 -> buildHyMt2Prompt(text, source, target)
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
            PromptStyle.HY_MT2,
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

    /** HY-MT2: official default translation instruction from Tencent's model card. */
    private fun buildHyMt2Prompt(text: String, source: Language, target: Language): String {
        val isChinese = source.code.startsWith("zh") || target.code.startsWith("zh")
        return if (isChinese) {
            "将以下文本翻译为 ${target.nativeName}，注意只需要输出翻译后的结果，不要额外解释：\n\n$text"
        } else {
            "Translate the following text into ${target.displayName}. Note that you should only output the translated result without any additional explanation:\n\n$text"
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
