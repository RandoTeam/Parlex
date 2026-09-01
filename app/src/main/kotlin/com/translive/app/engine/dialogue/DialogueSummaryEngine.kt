package com.translive.app.engine.dialogue

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.translive.app.data.ModelRepository
import com.translive.app.data.SettingsRepository
import com.translive.app.data.db.DialogueDao
import com.translive.app.data.model.DialogueMessage
import com.translive.app.data.model.DialogueSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SummaryUiState {
    object Idle : SummaryUiState
    data class Generating(val partialText: String) : SummaryUiState
    data class Success(val summaryMarkdown: String, val timestamp: Long) : SummaryUiState
    data class Error(val message: String) : SummaryUiState
}

@Singleton
class DialogueSummaryEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
    private val dialogueDao: DialogueDao
) {
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun generateSummaryStreaming(
        session: DialogueSession,
        messages: List<DialogueMessage>
    ): Flow<SummaryUiState> = flow {
        if (messages.isEmpty()) {
            emit(SummaryUiState.Error("Conversation is empty"))
            return@flow
        }

        emit(SummaryUiState.Generating(""))

        val modelFile = resolveGemmaModelFile()
        if (modelFile == null || !modelFile.exists()) {
            emit(SummaryUiState.Error("LiteRT Gemma model is not installed"))
            return@flow
        }

        val prompt = buildSummaryPrompt(session, messages)
        val accumulatedSummary = StringBuilder()

        try {
            val backend = if (settingsRepository.backend == SettingsRepository.BACKEND_GPU) {
                Backend.GPU()
            } else {
                Backend.CPU(numOfThreads = settingsRepository.threads.coerceAtLeast(2))
            }

            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                maxNumTokens = 1024,
                cacheDir = context.cacheDir.absolutePath
            )

            Engine(config).use { engine ->
                engine.initialize()
                val samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.3)
                engine.createConversation(ConversationConfig(samplerConfig = samplerConfig)).use { conversation ->
                    conversation.sendMessageAsync(prompt).collect { chunk ->
                        val cleanChunk = chunk.toString().replace(Regex("<ctrl.*?>"), "")
                        accumulatedSummary.append(cleanChunk)
                        emit(SummaryUiState.Generating(accumulatedSummary.toString()))
                    }
                }
            }

            val finalSummary = postProcessMarkdown(accumulatedSummary.toString().trim())
            val timestamp = System.currentTimeMillis()

            dialogueDao.updateSessionSummary(session.id, finalSummary, timestamp)

            emit(SummaryUiState.Success(finalSummary, timestamp))
        } catch (e: Exception) {
            Log.e(TAG, "Error generating dialogue summary", e)
            emit(SummaryUiState.Error(e.message ?: "Failed to generate AI summary"))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun clearSummary(sessionId: Long) = withContext(Dispatchers.IO) {
        dialogueDao.updateSessionSummary(sessionId, null, null)
    }

    private fun resolveGemmaModelFile(): File? {
        val modelsDir = File(context.filesDir, "models")
        val candidates = listOf(
            "gemma-4-E2B-it-gpu.litertlm",
            "gemma-4-E4B-it.litertlm",
            "gemma-2-2b-it-gpu.litertlm",
            "gemma-2-2b-it.litertlm"
        )
        for (name in candidates) {
            val f = File(modelsDir, name)
            if (f.exists() && f.length() > 0) return f
        }
        return modelsDir.listFiles()?.firstOrNull { it.name.endsWith(".litertlm", ignoreCase = true) }
    }

    fun buildSummaryPrompt(
        session: DialogueSession,
        messages: List<DialogueMessage>
    ): String {
        val transcriptBuilder = StringBuilder()
        for (msg in messages) {
            val time = timeFormat.format(Date(msg.timestamp))
            val langBadge = "[${msg.originalLanguage.uppercase(Locale.ROOT)}]"
            val speakerTag = if (msg.speaker.equals("A", ignoreCase = true)) "Speaker A" else "Speaker B"
            transcriptBuilder.append("$time $langBadge $speakerTag: ${msg.originalText}\n")
            if (msg.translatedText.isNotBlank() && msg.translatedText != msg.originalText) {
                val transBadge = "[${msg.translatedLanguage.uppercase(Locale.ROOT)}]"
                transcriptBuilder.append("    -> $transBadge: ${msg.translatedText}\n")
            }
        }

        return """<start_of_turn>user
You are an on-device conversation intelligence assistant.
Analyze the bilingual dialogue below and generate a concise executive summary formatted in clean Markdown.

Instructions:
1. Write an Executive Summary of 2-3 sentences capturing the core context and goals.
2. Extract Key Points & Decisions as a concise bullet list.
3. Highlight Next Steps or Action Items if any exist.
4. Strictly omit any emojis or decorative symbols.
5. Output in clear, professional Russian or English matching the conversation tone.

Language Pair: ${session.languageA.uppercase()} <-> ${session.languageB.uppercase()}

Dialogue Transcript:
${transcriptBuilder.toString()}
<end_of_turn>
<start_of_turn>model
""".trimIndent()
    }

    private fun postProcessMarkdown(rawText: String): String {
        return rawText
            .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"), "")
            .trim()
    }

    companion object {
        private const val TAG = "DialogueSummaryEngine"
    }
}
