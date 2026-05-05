package com.translive.app.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class ListeningState {
    IDLE,
    LISTENING,
    PROCESSING,
    ERROR
}

data class SpeechResult(
    val text: String,
    val language: String  // "ru", "en", etc.
)

/**
 * Continuous speech recognition engine:
 * Silero VAD → Whisper tiny (offline) → text + detected language.
 */
@Singleton
class SpeechEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SpeechEngine"
        private const val SAMPLE_RATE = 16000
    }

    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var listenJob: Job? = null

    private val _state = MutableStateFlow(ListeningState.IDLE)
    val state: StateFlow<ListeningState> = _state.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val sttDir: File get() = File(context.filesDir, "stt")
    val vadFile: File get() = File(sttDir, "silero_vad.onnx")
    val whisperDir: File get() = File(sttDir, "sherpa-onnx-whisper-tiny")

    fun isVadDownloaded(): Boolean = vadFile.exists()

    fun isWhisperDownloaded(): Boolean {
        val dir = whisperDir
        return dir.exists() &&
                File(dir, "tiny-encoder.onnx").exists() &&
                File(dir, "tiny-decoder.onnx").exists() &&
                File(dir, "tiny-tokens.txt").exists()
    }

    fun areModelsDownloaded(): Boolean = isVadDownloaded() && isWhisperDownloaded()

    fun initialize(): Boolean {
        if (vad != null && recognizer != null) return true
        if (!areModelsDownloaded()) return false

        return try {
            val assetManager = context.assets

            // Initialize VAD
            val sileroConfig = SileroVadModelConfig(
                model = vadFile.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = 512
            )
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = sileroConfig,
                sampleRate = SAMPLE_RATE,
                numThreads = 1
            )
            vad = Vad(assetManager, vadConfig)

            // Initialize Whisper (non-streaming / offline)
            val wDir = whisperDir.absolutePath
            val whisperModelConfig = OfflineWhisperModelConfig(
                encoder = "$wDir/tiny-encoder.onnx",
                decoder = "$wDir/tiny-decoder.onnx",
                language = "",  // Empty = auto-detect
                task = "transcribe"
            )
            val modelConfig = OfflineModelConfig(
                whisper = whisperModelConfig,
                tokens = "$wDir/tiny-tokens.txt",
                numThreads = 2,
                debug = false
            )
            val recConfig = OfflineRecognizerConfig(
                modelConfig = modelConfig
            )
            recognizer = OfflineRecognizer(assetManager, recConfig)

            _isReady.value = true
            Log.i(TAG, "SpeechEngine initialized (VAD + Whisper)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize: ${e.message}", e)
            _isReady.value = false
            false
        }
    }

    /**
     * Start continuous listening. Calls [onResult] each time speech is detected and recognized.
     */
    fun startListening(onResult: (SpeechResult) -> Unit) {
        if (_state.value == ListeningState.LISTENING) return
        if (!_isReady.value) {
            if (!initialize()) {
                _state.value = ListeningState.ERROR
                return
            }
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            _state.value = ListeningState.ERROR
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            _state.value = ListeningState.ERROR
            return
        }

        audioRecord?.startRecording()
        _state.value = ListeningState.LISTENING

        listenJob = CoroutineScope(Dispatchers.IO).launch {
            val chunkSize = 512  // Match VAD window size
            val shortBuffer = ShortArray(chunkSize)

            while (isActive && _state.value == ListeningState.LISTENING) {
                val read = audioRecord?.read(shortBuffer, 0, chunkSize) ?: -1
                if (read <= 0) continue

                // Convert short to float [-1, 1]
                val floatBuffer = FloatArray(read) { shortBuffer[it] / 32768.0f }

                // Feed to VAD
                vad?.acceptWaveform(floatBuffer)

                // Check if VAD detected complete speech segment
                while (vad?.empty() == false) {
                    val segment = vad?.front()
                    vad?.pop()

                    if (segment != null && segment.samples.isNotEmpty()) {
                        _state.value = ListeningState.PROCESSING

                        val result = recognizeSegment(segment.samples)
                        if (result != null && result.text.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                onResult(result)
                            }
                        }

                        _state.value = ListeningState.LISTENING
                    }
                }
            }
        }
    }

    private fun recognizeSegment(samples: FloatArray): SpeechResult? {
        val rec = recognizer ?: return null
        val stream = rec.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        rec.decode(stream)
        val text = rec.getResult(stream).text.trim()

        if (text.isBlank()) return null

        val language = detectLanguage(text)
        return SpeechResult(text = text, language = language)
    }

    /**
     * Simple heuristic: count Cyrillic vs Latin chars.
     */
    private fun detectLanguage(text: String): String {
        val cyrillicCount = text.count { it in '\u0400'..'\u04FF' }
        val latinCount = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        return if (cyrillicCount > latinCount) "ru" else "en"
    }

    fun stopListening() {
        _state.value = ListeningState.IDLE
        listenJob?.cancel()
        listenJob = null

        audioRecord?.apply {
            try {
                stop()
                release()
            } catch (_: Exception) {}
        }
        audioRecord = null
    }

    fun release() {
        stopListening()
        vad?.release()
        vad = null
        recognizer?.release()
        recognizer = null
        _isReady.value = false
    }
}
