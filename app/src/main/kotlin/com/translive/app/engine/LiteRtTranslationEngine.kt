package com.translive.app.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.translive.app.data.SettingsRepository
import com.translive.app.data.model.Language
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

@Singleton
class LiteRtTranslationEngine @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val nativeLock = ReentrantLock()
    private val inferenceMutex = Mutex()

    private var engine: Engine? = null
    private var activeConversation: Conversation? = null
    private var loadedModelPath: String? = null
    private var loadedBackend: String? = null
    private var samplerConfig: SamplerConfig? = null

    val isLoaded: Boolean
        get() = nativeLock.withLock { engine?.isInitialized() == true }

    val currentBackend: String?
        get() = nativeLock.withLock { loadedBackend }

    fun loadModel(modelPath: String, backendSetting: String, threads: Int): Boolean {
        val requestedBackend = normalizeBackend(backendSetting)
        return nativeLock.withLock {
            if (engine?.isInitialized() == true &&
                loadedModelPath == modelPath &&
                loadedBackend == requestedBackend
            ) {
                return@withLock true
            }

            closeLocked()

            val backend = backendFor(requestedBackend, threads)
            if (loadWithBackendLocked(modelPath, requestedBackend, backend)) {
                return@withLock true
            }

            if (requestedBackend != SettingsRepository.BACKEND_CPU) {
                Log.w(TAG, "Falling back to CPU backend after $requestedBackend failed")
                closeLocked()
                return@withLock loadWithBackendLocked(
                    modelPath = modelPath,
                    backendName = SettingsRepository.BACKEND_CPU,
                    backend = Backend.CPU(numOfThreads = threads.coerceAtLeast(1))
                )
            }

            false
        }
    }

    fun unloadModel() {
        nativeLock.withLock { closeLocked() }
    }

    fun translate(
        sourceText: String,
        source: Language,
        target: Language
    ): String {
        val prompt = buildTranslateGemmaLiteRtPrompt(sourceText, source, target)
        return nativeLock.withLock {
            val currentEngine = engine ?: throw IllegalStateException("LiteRT model is not loaded")
            currentEngine.createConversation(conversationConfig()).use { conversation ->
                conversation.sendMessage(prompt).toString().trim()
            }
        }
    }

    suspend fun translateSafe(
        sourceText: String,
        source: Language,
        target: Language
    ): String = inferenceMutex.withLock {
        translate(sourceText, source, target)
    }

    fun translateStreaming(
        sourceText: String,
        source: Language,
        target: Language
    ): Flow<String> = flow {
        val prompt = buildTranslateGemmaLiteRtPrompt(sourceText, source, target)
        inferenceMutex.withLock {
            val conversation = nativeLock.withLock {
                val currentEngine = engine ?: throw IllegalStateException("LiteRT model is not loaded")
                activeConversation?.close()
                currentEngine.createConversation(conversationConfig()).also {
                    activeConversation = it
                }
            }

            try {
                conversation.sendMessageAsync(prompt).collect { message ->
                    emit(stripControlText(message.toString()))
                }
            } finally {
                nativeLock.withLock {
                    if (activeConversation === conversation) activeConversation = null
                }
                conversation.close()
            }
        }
    }

    fun cancel() {
        nativeLock.withLock { activeConversation?.cancelProcess() }
    }

    private fun backendFor(backendSetting: String, threads: Int): Backend =
        when (backendSetting) {
            SettingsRepository.BACKEND_GPU -> Backend.GPU()
            SettingsRepository.BACKEND_NPU -> Backend.NPU(
                nativeLibraryDir = context.applicationInfo.nativeLibraryDir
            )
            else -> Backend.CPU(numOfThreads = threads.coerceAtLeast(1))
        }

    private fun normalizeBackend(backendSetting: String): String =
        when (backendSetting) {
            SettingsRepository.BACKEND_GPU,
            SettingsRepository.BACKEND_NPU -> backendSetting
            else -> SettingsRepository.BACKEND_CPU
        }

    private fun loadWithBackendLocked(
        modelPath: String,
        backendName: String,
        backend: Backend
    ): Boolean {
        samplerConfig = if (backend is Backend.NPU) {
            null
        } else {
            SamplerConfig(topK = 10, topP = 0.95, temperature = 0.2)
        }

        return try {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = 2048,
                cacheDir = context.cacheDir.absolutePath
            )
            val nextEngine = Engine(config)
            val start = System.currentTimeMillis()
            nextEngine.initialize()
            val elapsed = System.currentTimeMillis() - start

            engine = nextEngine
            loadedModelPath = modelPath
            loadedBackend = backendName
            Log.i(TAG, "Loaded LiteRT-LM model with $backendName backend in ${elapsed}ms")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load LiteRT-LM model with $backendName backend", e)
            closeLocked()
            false
        }
    }

    private fun conversationConfig(): ConversationConfig =
        ConversationConfig(samplerConfig = samplerConfig)

    private fun closeLocked() {
        try {
            activeConversation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close LiteRT-LM conversation", e)
        }
        activeConversation = null

        try {
            engine?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close LiteRT-LM engine", e)
        }
        engine = null
        loadedModelPath = null
        loadedBackend = null
        samplerConfig = null
    }

    private fun buildTranslateGemmaLiteRtPrompt(
        text: String,
        source: Language,
        target: Language
    ): String = "<src>${source.code}</src><dst>${target.code}</dst><text>$text</text>"

    private fun stripControlText(text: String): String =
        if (text.startsWith("<ctrl")) "" else text

    companion object {
        private const val TAG = "LiteRtTranslationEngine"
    }
}
