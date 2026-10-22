package com.translive.app.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.*
import com.translive.app.data.SettingsRepository
import com.translive.app.data.model.SttModelInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class ListeningState { IDLE, LISTENING, PROCESSING, ERROR }

data class SpeechResult(
    val text: String,
    /** The language used by the recognizer. Qwen3-ASR supplies its detected language. */
    val language: String
)

/**
 * Offline microphone pipeline: Silero VAD then explicitly selected recognizer.
 * Whisper Tiny is the fast default; Qwen3-ASR is an opt-in quality model.
 */
@Singleton
class SpeechEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository
) {
    companion object {
        private const val TAG = "SpeechEngine"
        private const val SAMPLE_RATE = 16000
        private const val POST_TTS_REVERB_COOLDOWN_MS = 300L
    }

    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var audioJob: Job? = null
    private var recognitionJob: Job? = null
    private var segmentChannel: Channel<FloatArray>? = null
    private var currentLanguage = ""
    private var currentSpeechModel = ""
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }
    private var audioFocusRequest: AudioFocusRequest? = null

    private val _state = MutableStateFlow(ListeningState.IDLE)
    val state: StateFlow<ListeningState> = _state.asStateFlow()
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /** Optional PCM sample callback hook for audio session recording */
    var onPcmSamplesRead: ((ShortArray, Int) -> Unit)? = null

    // AEC Suppression & Reverb Cooldown Tracking
    private val aecMuteUntilTimestampMs = AtomicLong(0L)
    private val isAecSuppressed = AtomicBoolean(false)

    private val sttDir: File get() = File(context.filesDir, "stt")
    val vadFile: File get() = File(sttDir, "silero_vad.onnx")
    val whisperDir: File get() = File(sttDir, "sherpa-onnx-whisper-tiny")
    val qwen3Dir: File get() = File(sttDir, SttModelInfo.QWEN3_DIR)

    fun isVadDownloaded() = vadFile.exists() && vadFile.length() > 500_000L

    fun isWhisperDownloaded(): Boolean {
        val dir = whisperDir
        return File(dir, "tiny-encoder.onnx").let { it.exists() && it.length() > 10_000_000L } &&
            File(dir, "tiny-decoder.onnx").let { it.exists() && it.length() > 15_000_000L } &&
            File(dir, "tiny-tokens.txt").let { it.exists() && it.length() > 100_000L }
    }

    fun isQwen3Downloaded(): Boolean {
        val dir = qwen3Dir
        return File(dir, "conv_frontend.onnx").let { it.exists() && it.length() > 30_000_000L } &&
            File(dir, "encoder.int8.onnx").let { it.exists() && it.length() > 150_000_000L } &&
            File(dir, "decoder.int8.onnx").let { it.exists() && it.length() > 650_000_000L } &&
            File(dir, "tokenizer.json").let { it.exists() && it.length() > 100_000L }
    }

    fun isSelectedModelDownloaded(): Boolean = isVadDownloaded() && when (settings.speechModel) {
        SettingsRepository.SPEECH_MODEL_QWEN3_ASR_06B -> isQwen3Downloaded()
        else -> isWhisperDownloaded()
    }

    fun areModelsDownloaded(): Boolean = isSelectedModelDownloaded()

    private fun modelFiles(model: String): List<File> = when (model) {
        SettingsRepository.SPEECH_MODEL_QWEN3_ASR_06B -> listOf(
            File(qwen3Dir, "conv_frontend.onnx"), File(qwen3Dir, "encoder.int8.onnx"),
            File(qwen3Dir, "decoder.int8.onnx")
        )
        else -> listOf(File(whisperDir, "tiny-encoder.onnx"), File(whisperDir, "tiny-decoder.onnx"))
    }

    /** Reject malformed ONNX before sherpa can abort the process from native code. */
    private fun validateOnnxFiles(files: List<File>): Boolean = try {
        files.all { file ->
            file.inputStream().use { input ->
                val header = ByteArray(4)
                input.read(header) == 4 && header[0] == 0x08.toByte()
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "ONNX validation error", e)
        false
    }

    fun initialize(language: String = ""): Boolean {
        val requestedModel = settings.speechModel
        if (recognizer != null && (language != currentLanguage || requestedModel != currentSpeechModel)) {
            recognizer?.release()
            recognizer = null
            _isReady.value = false
        }
        if (vad != null && recognizer != null) return true
        if (!isSelectedModelDownloaded()) return false

        if (!validateOnnxFiles(modelFiles(requestedModel))) {
            val dir = if (requestedModel == SettingsRepository.SPEECH_MODEL_QWEN3_ASR_06B) qwen3Dir else whisperDir
            dir.deleteRecursively()
            Log.e(TAG, "Invalid ONNX files removed; model must be downloaded again")
            return false
        }

        return try {
            if (vad == null) {
                vad = Vad(null, VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadFile.absolutePath,
                        threshold = 0.5f,
                        minSilenceDuration = 0.5f,
                        minSpeechDuration = 0.25f,
                        maxSpeechDuration = 15.0f,
                        windowSize = 512
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1
                ))
            }

            val modelConfig = when (requestedModel) {
                SettingsRepository.SPEECH_MODEL_QWEN3_ASR_06B -> {
                    val dir = qwen3Dir.absolutePath
                    OfflineModelConfig(
                        qwen3Asr = OfflineQwen3AsrModelConfig(
                            convFrontend = "$dir/conv_frontend.onnx",
                            encoder = "$dir/encoder.int8.onnx",
                            decoder = "$dir/decoder.int8.onnx",
                            tokenizer = "$dir/tokenizer.json",
                            maxTotalLen = 512,
                            maxNewTokens = 128,
                            temperature = 1.0e-6f,
                            topP = 0.8f,
                            seed = 42,
                            hotwords = ""
                        ),
                        numThreads = 4,
                        debug = false,
                        provider = "cpu"
                    )
                }
                else -> {
                    val dir = whisperDir.absolutePath
                    OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = "$dir/tiny-encoder.onnx",
                            decoder = "$dir/tiny-decoder.onnx",
                            language = language.take(2),
                            task = "transcribe"
                        ),
                        tokens = "$dir/tiny-tokens.txt",
                        numThreads = 2,
                        debug = false
                    )
                }
            }
            recognizer = OfflineRecognizer(null, OfflineRecognizerConfig(modelConfig = modelConfig))
            currentLanguage = language.take(2)
            currentSpeechModel = requestedModel
            _isReady.value = true
            Log.i(TAG, "SpeechEngine initialized: model=$requestedModel, language=$currentLanguage")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize speech engine", e)
            _isReady.value = false
            false
        }
    }

    /**
     * Signals AEC suppression and resets the post-TTS cooldown timer.
     */
    fun notifyTtsPlaybackStarted() {
        isAecSuppressed.set(true)
    }

    fun notifyTtsPlaybackFinished() {
        aecMuteUntilTimestampMs.set(SystemClock.elapsedRealtime() + POST_TTS_REVERB_COOLDOWN_MS)
        isAecSuppressed.set(false)
    }

    /**
     * Resets the Silero VAD state to discard partial frames.
     */
    fun resetVad() {
        vad?.reset()
    }

    fun startListening(language: String, singleShot: Boolean, onResult: (SpeechResult) -> Unit) {
        if (_state.value == ListeningState.LISTENING || _state.value == ListeningState.PROCESSING) return
        if (!_isReady.value || currentLanguage != language.take(2) || currentSpeechModel != settings.speechModel) {
            if (!initialize(language)) {
                _state.value = ListeningState.ERROR
                return
            }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = ListeningState.ERROR
            return
        }
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(SAMPLE_RATE * 2)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            _state.value = ListeningState.ERROR
            return
        }
        audioRecord = record
        enableAudioEffects(record)
        requestAudioFocus()
        record.startRecording()
        vad?.reset()
        _state.value = ListeningState.LISTENING

        // Recognition runs on its own coroutine. It must never block the audio
        // read loop, otherwise a 1-3s Whisper decode drops the next phrase.
        val channel = Channel<FloatArray>(Channel.UNLIMITED)
        segmentChannel = channel

        recognitionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                for (segment in channel) {
                    if (!isActive) break
                    _state.value = ListeningState.PROCESSING
                    val result = runCatching { recognizeSegment(segment) }.getOrNull()
                    if (_state.value == ListeningState.IDLE) return@launch
                    if (result != null) {
                        withContext(Dispatchers.Main) { onResult(result) }
                    }
                    if (singleShot) {
                        stopListening()
                        return@launch
                    }
                    if (_state.value == ListeningState.PROCESSING) {
                        _state.value = ListeningState.LISTENING
                    }
                }
            } catch (_: CancellationException) {
                // Normal stop.
            } finally {
                if (_state.value == ListeningState.PROCESSING) {
                    _state.value = ListeningState.LISTENING
                }
            }
        }

        audioJob = CoroutineScope(Dispatchers.IO).launch {
            val samples = ShortArray(512)
            var wasSuppressed = false
            try {
                while (isActive && _state.value != ListeningState.IDLE) {
                    val read = runCatching { record.read(samples, 0, samples.size) }.getOrDefault(-1)
                    if (read <= 0) continue

                    onPcmSamplesRead?.invoke(samples, read)

                    val isSuppressed = isAecSuppressed.get()
                    val isInCooldown = SystemClock.elapsedRealtime() < aecMuteUntilTimestampMs.get()
                    if (isSuppressed || isInCooldown) {
                        wasSuppressed = true
                        continue
                    }
                    if (wasSuppressed) {
                        vad?.reset()
                        wasSuppressed = false
                    }

                    vad?.acceptWaveform(FloatArray(read) { samples[it] / 32768.0f })
                    while (vad?.empty() == false) {
                        val segment = vad?.front()
                        vad?.pop()
                        if (segment?.samples?.isNotEmpty() == true) {
                            channel.trySend(segment.samples)
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Normal stop.
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture loop failed", e)
                _state.value = ListeningState.ERROR
            } finally {
                channel.close()
            }
        }
    }

    private fun enableAudioEffects(record: AudioRecord) {
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
            }
        }.onFailure { Log.w(TAG, "AEC unavailable: ${it.message}") }
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
            }
        }.onFailure { Log.w(TAG, "NoiseSuppressor unavailable: ${it.message}") }
    }

    private fun releaseAudioEffects() {
        runCatching { echoCanceler?.enabled = false; echoCanceler?.release() }
        runCatching { noiseSuppressor?.enabled = false; noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
    }

    private fun requestAudioFocus() {
        val manager = audioManager ?: return
        if (audioFocusRequest != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    // A short guard is enough; a real loss is also surfaced through
                    // the microphone read error path.
                    aecMuteUntilTimestampMs.set(SystemClock.elapsedRealtime() + 1_000L)
                }
            }
            .build()
        runCatching { manager.requestAudioFocus(request) }
        audioFocusRequest = request
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        audioFocusRequest?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
        audioFocusRequest = null
    }

    private fun recognizeSegment(samples: FloatArray): SpeechResult? {
        val rec = recognizer ?: return null
        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val result = rec.getResult(stream)
            val text = result.text.trim()
            if (text.length < 3) return null
            val language = if (currentSpeechModel == SettingsRepository.SPEECH_MODEL_QWEN3_ASR_06B) {
                normalizeQwenLanguage(result.lang)
            } else currentLanguage
            val resolvedLanguage = language.ifBlank { "auto" }
            SpeechResult(text, resolvedLanguage)
        } finally {
            stream.release()
        }
    }

    private fun normalizeQwenLanguage(value: String): String = when (value.trim().lowercase()) {
        "ru", "russian", "русский" -> "ru"
        "en", "english", "английский" -> "en"
        "zh", "zh-cn", "chinese", "mandarin" -> "zh"
        "ja", "japanese" -> "ja"
        "ko", "korean" -> "ko"
        "de", "german" -> "de"
        "fr", "french" -> "fr"
        "es", "spanish" -> "es"
        "it", "italian" -> "it"
        "pt", "portuguese" -> "pt"
        "vi", "vietnamese" -> "vi"
        else -> value.trim().lowercase().take(8)
    }

    fun stopListening() {
        _state.value = ListeningState.IDLE
        audioJob?.cancel()
        audioJob = null
        recognitionJob?.cancel()
        recognitionJob = null
        segmentChannel?.close()
        segmentChannel = null
        val record = audioRecord
        audioRecord = null
        record?.runCatching { stop() }
        releaseAudioEffects()
        record?.runCatching { release() }
        abandonAudioFocus()
    }

    fun release() {
        stopListening()
        vad?.release(); vad = null
        recognizer?.release(); recognizer = null
        currentSpeechModel = ""
        _isReady.value = false
    }
}
