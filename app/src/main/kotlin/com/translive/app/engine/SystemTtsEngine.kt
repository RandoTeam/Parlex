package com.translive.app.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * System TTS wrapper with per-utterance completion tracking and a hard timeout.
 *
 * A missing [UtteranceProgressListener] callback used to leave the dialogue turn
 * mutex locked forever. Every awaited utterance now has its own id, a timeout and
 * an explicit cleanup on stop/release.
 */
@Singleton
class SystemTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SystemTtsEngine"
        const val AWAIT_TIMEOUT_MS = 15_000L
    }

    private var tts: TextToSpeech? = null
    private val speakMutex = Mutex()
    private val utteranceCounter = AtomicLong(0L)
    private val pending = Collections.synchronizedMap(mutableMapOf<String, CancellableContinuation<Unit>>())

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    fun initialize() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                _isReady.value = true
                installProgressListener()
                Log.i(TAG, "System TTS initialized")
            } else {
                Log.e(TAG, "System TTS init failed: $status")
                _isReady.value = false
            }
        }
    }

    private fun installProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
                finish(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                finish(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error: $errorCode (utterance=$utteranceId)")
                _isSpeaking.value = false
                finish(utteranceId)
            }
        })
    }

    private fun finish(utteranceId: String?) {
        val continuation = utteranceId?.let { pending.remove(it) } ?: return
        if (continuation.isActive) continuation.resume(Unit)
    }

    /**
     * Speaks [text] and suspends until the engine reports completion, an error,
     * cancellation or [AWAIT_TIMEOUT_MS]. Never blocks the turn loop forever.
     */
    suspend fun speakAndWait(text: String, languageCode: String): Boolean = speakMutex.withLock {
        val engine = tts ?: return@withLock false
        if (!_isReady.value || text.isBlank()) return@withLock false

        applyLanguage(engine, languageCode)

        val utteranceId = "dialogue_${utteranceCounter.incrementAndGet()}"
        val completed = withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                pending[utteranceId] = cont
                cont.invokeOnCancellation {
                    pending.remove(utteranceId)
                    runCatching { engine.stop() }
                    _isSpeaking.value = false
                }

                val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (result == TextToSpeech.ERROR) {
                    pending.remove(utteranceId)
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }

        if (completed == null) {
            Log.w(TAG, "TTS timeout after ${AWAIT_TIMEOUT_MS}ms; stopping utterance $utteranceId")
            pending.remove(utteranceId)
            runCatching { engine.stop() }
            _isSpeaking.value = false
        }
        completed != null
    }

    /** Fire-and-forget speak (non-blocking). */
    fun speak(text: String, languageCode: String): Boolean {
        val engine = tts ?: return false
        if (!_isReady.value || text.isBlank()) return false
        applyLanguage(engine, languageCode)
        val utteranceId = "speak_${utteranceCounter.incrementAndGet()}"
        return engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) != TextToSpeech.ERROR
    }

    private fun applyLanguage(engine: TextToSpeech, languageCode: String) {
        val locale = Locale.forLanguageTag(languageCode.ifBlank { "en" })
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language $languageCode is not supported by system TTS")
        }
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
        val ids = pending.keys.toList()
        ids.forEach { finish(it) }
        pending.clear()
    }

    fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        _isReady.value = false
    }
}
