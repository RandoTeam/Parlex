package com.translive.app.engine.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
import com.translive.app.data.model.AudioRecordingFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class RecordingSessionResult(
    val file: File,
    val format: AudioRecordingFormat,
    val durationMs: Long,
    val fileSizeBytes: Long
)

@Singleton
class DialogueAudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DialogueAudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_COUNT = 1
    }

    private var activeEncoder: AudioEncoder? = null
    private var sessionStartRealtimeMs = 0L
    private var currentOutputFile: File? = null
    private var currentFormat: AudioRecordingFormat = AudioRecordingFormat.AAC
    private val isRecording = AtomicBoolean(false)

    val isCurrentlyRecording: Boolean get() = isRecording.get()

    fun getDialoguesDirectory(): File {
        val externalDir = context.getExternalFilesDir("dialogues")
        val targetDir = externalDir ?: File(context.filesDir, "dialogues")
        if (!targetDir.exists()) targetDir.mkdirs()
        return targetDir
    }

    fun startSession(format: AudioRecordingFormat): File {
        stopSession()

        val timestampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = getDialoguesDirectory()
        val file = File(dir, "dialogue_${timestampStr}_${System.currentTimeMillis()}.${format.extension}")

        currentOutputFile = file
        currentFormat = format
        sessionStartRealtimeMs = SystemClock.elapsedRealtime()

        activeEncoder = when (format) {
            AudioRecordingFormat.AAC -> AacMediaCodecEncoder(file, SAMPLE_RATE, CHANNEL_COUNT, format.defaultBitrate)
            AudioRecordingFormat.WAV -> WavPcmEncoder(file, SAMPLE_RATE, CHANNEL_COUNT)
        }

        activeEncoder?.start()
        isRecording.set(true)
        Log.i(TAG, "Started dialogue recording: ${file.absolutePath} format=$format")
        return file
    }

    fun writePcmSamples(samples: ShortArray, readCount: Int) {
        if (!isRecording.get()) return
        activeEncoder?.feedPcm(samples, readCount)
    }

    fun getRelativeOffsetMs(): Long {
        if (!isRecording.get()) return 0L
        return (SystemClock.elapsedRealtime() - sessionStartRealtimeMs).coerceAtLeast(0L)
    }

    fun stopSession(): RecordingSessionResult? {
        if (!isRecording.compareAndSet(true, false)) return null

        val encoder = activeEncoder
        activeEncoder = null
        val file = currentOutputFile
        val durationMs = (SystemClock.elapsedRealtime() - sessionStartRealtimeMs).coerceAtLeast(0L)

        encoder?.stop()

        return if (file != null && file.exists()) {
            Log.i(TAG, "Stopped dialogue recording: ${file.absolutePath} (${file.length()} bytes, ${durationMs}ms)")
            RecordingSessionResult(
                file = file,
                format = currentFormat,
                durationMs = durationMs,
                fileSizeBytes = file.length()
            )
        } else null
    }

    private interface AudioEncoder {
        fun start()
        fun feedPcm(samples: ShortArray, count: Int)
        fun stop()
    }

    private class AacMediaCodecEncoder(
        private val outputFile: File,
        private val sampleRate: Int,
        private val channels: Int,
        private val bitrate: Int
    ) : AudioEncoder {
        private var mediaCodec: MediaCodec? = null
        private var mediaMuxer: MediaMuxer? = null
        private var trackIndex = -1
        private var muxerStarted = false
        private val queue = LinkedBlockingQueue<ShortArray>()
        private var workerJob: Job? = null
        private val isRunning = AtomicBoolean(false)
        private var presentationTimeUs = 0L

        override fun start() {
            try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }

                mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }

                mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                isRunning.set(true)

                workerJob = CoroutineScope(Dispatchers.IO).launch {
                    val bufferInfo = MediaCodec.BufferInfo()
                    val byteBuffer = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN)

                    while (isRunning.get() || queue.isNotEmpty()) {
                        val pcmChunk = queue.poll()
                        if (pcmChunk != null) {
                            encodePcmChunk(pcmChunk, byteBuffer, bufferInfo)
                        } else {
                            drainEncoder(bufferInfo, endOfStream = false)
                            delay(10)
                        }
                    }
                    drainEncoder(bufferInfo, endOfStream = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AAC encoder", e)
            }
        }

        private fun encodePcmChunk(pcm: ShortArray, byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
            val codec = mediaCodec ?: return
            val inIndex = codec.dequeueInputBuffer(10_000L)
            if (inIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inIndex) ?: return
                inputBuffer.clear()
                byteBuf.clear()
                for (s in pcm) byteBuf.putShort(s)
                byteBuf.flip()
                inputBuffer.put(byteBuf)

                val samplesCount = pcm.size
                val chunkDurationUs = (samplesCount * 1_000_000L) / sampleRate
                codec.queueInputBuffer(inIndex, 0, pcm.size * 2, presentationTimeUs, 0)
                presentationTimeUs += chunkDurationUs
            }
            drainEncoder(bufferInfo, endOfStream = false)
        }

        private fun drainEncoder(bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
            val codec = mediaCodec ?: return
            val muxer = mediaMuxer ?: return

            if (endOfStream) {
                val inIndex = codec.dequeueInputBuffer(10_000L)
                if (inIndex >= 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }

            while (true) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIndex >= 0) {
                    val outBuffer = codec.getOutputBuffer(outIndex) ?: continue
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0 && muxerStarted) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                } else {
                    break
                }
            }
        }

        override fun feedPcm(samples: ShortArray, count: Int) {
            if (!isRunning.get()) return
            queue.offer(samples.copyOf(count))
        }

        override fun stop() {
            isRunning.set(false)
            runBlocking {
                workerJob?.join()
            }
            runCatching { mediaCodec?.stop(); mediaCodec?.release() }
            runCatching { if (muxerStarted) mediaMuxer?.stop(); mediaMuxer?.release() }
            mediaCodec = null
            mediaMuxer = null
        }
    }

    private class WavPcmEncoder(
        private val outputFile: File,
        private val sampleRate: Int,
        private val channels: Int
    ) : AudioEncoder {
        private var raf: RandomAccessFile? = null
        private var totalAudioLen = 0L

        override fun start() {
            try {
                raf = RandomAccessFile(outputFile, "rw")
                raf?.setLength(0)
                writeWavHeader(0L)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WAV encoder", e)
            }
        }

        override fun feedPcm(samples: ShortArray, count: Int) {
            val file = raf ?: return
            val byteBuffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) byteBuffer.putShort(samples[i])
            file.write(byteBuffer.array())
            totalAudioLen += count * 2
        }

        override fun stop() {
            val file = raf ?: return
            try {
                file.seek(0)
                writeWavHeader(totalAudioLen)
                file.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing WAV file", e)
            }
            raf = null
        }

        private fun writeWavHeader(audioLength: Long) {
            val file = raf ?: return
            val totalDataLen = audioLength + 36
            val byteRate = sampleRate * channels * 2
            val header = ByteArray(44)

            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
            header[20] = 1; header[21] = 0
            header[22] = channels.toByte(); header[23] = 0
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = (channels * 2).toByte(); header[33] = 0
            header[34] = 16; header[35] = 0
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            header[40] = (audioLength and 0xff).toByte()
            header[41] = ((audioLength shr 8) and 0xff).toByte()
            header[42] = ((audioLength shr 16) and 0xff).toByte()
            header[43] = ((audioLength shr 24) and 0xff).toByte()

            file.write(header)
        }
    }
}
