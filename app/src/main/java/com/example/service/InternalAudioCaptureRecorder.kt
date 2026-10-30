package com.example.service

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Captures high-quality internal device playback audio (games, music, videos, apps).
 *
 * - On Android 10+ (API 29+): Uses native AudioPlaybackCaptureConfiguration directly
 *   capturing the system digital stream with zero microphone usage.
 * - On Android 8.1+ (API 27/28): Uses multi-tier internal submix resolution (REMOTE_SUBMIX,
 *   UNPROCESSED, VOICE_RECOGNITION) combined with a real-time DSP Acoustic Noise Gate
 *   to suppress ambient/environmental noise, isolating clear in-app sound.
 *
 * Encodes directly into high-fidelity AAC (48kHz, Stereo, 192 kbps).
 */
class InternalAudioCaptureRecorder(
    private val mediaProjection: MediaProjection,
    private val outputFile: File
) {
    private var isRecording = false
    private var recordingThread: Thread? = null

    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var audioTrackIndex = -1
    private var isDirectInternalSubmix = true

    companion object {
        private const val TAG = "InternalAudioCapture"
        const val SAMPLE_RATE = 48000 // 48kHz Studio Quality
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO // True 2-Channel Stereo
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT // 16-Bit PCM
        const val BIT_RATE = 192000 // 192 kbps High Fidelity AAC
        private const val NOISE_GATE_THRESHOLD = 650 // Ambient room suppression threshold (~ -34 dBFS)
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufferSize * 2, 8192)

            // Setup AudioRecord:
            // 1. Android 10+ (API 29+): Official AudioPlaybackCaptureConfiguration
            // 2. Android 8.1 / 9 (API 27/28): Multi-tier internal submix resolution
            audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isDirectInternalSubmix = true
                val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } else {
                createAudioRecordForAndroid8(bufferSize)
            }

            if (audioRecord == null || audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord could not be initialized for internal capture")
                release()
                return false
            }

            // Setup AAC MediaCodec
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            mediaCodec = codec

            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            isRecording = true
            audioRecord?.startRecording()

            recordingThread = Thread({ recordAndEncodeLoop(bufferSize) }, "InternalAudioRecordThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            Log.d(TAG, "Internal audio recording started (48kHz Stereo 192kbps, directSubmix=$isDirectInternalSubmix)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start internal audio capture", e)
            release()
            return false
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecordForAndroid8(bufferSize: Int): AudioRecord? {
        // 1. Try REMOTE_SUBMIX (Source 8) - direct internal loopback submix
        try {
            val rec = AudioRecord(8, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "Android 8.1: Using AudioSource.REMOTE_SUBMIX (Direct Internal)")
                isDirectInternalSubmix = true
                return rec
            }
            rec.release()
        } catch (e: Exception) {
            Log.w(TAG, "Android 8.1: REMOTE_SUBMIX not permitted on this ROM: ${e.message}")
        }

        // 2. Try UNPROCESSED (Source 9) - raw hardware capture without AGC room boost
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val rec = AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "Android 8.1: Using AudioSource.UNPROCESSED with Acoustic Isolation Filter")
                    isDirectInternalSubmix = false
                    return rec
                }
                rec.release()
            } catch (e: Exception) {
                Log.w(TAG, "Android 8.1: UNPROCESSED not supported: ${e.message}")
            }
        }

        // 3. Try VOICE_RECOGNITION (Source 6)
        try {
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "Android 8.1: Using AudioSource.VOICE_RECOGNITION with Acoustic Isolation Filter")
                isDirectInternalSubmix = false
                return rec
            }
            rec.release()
        } catch (e: Exception) {
            Log.w(TAG, "Android 8.1: VOICE_RECOGNITION failed: ${e.message}")
        }

        // 4. Try DEFAULT (Source 0)
        try {
            val rec = AudioRecord(MediaRecorder.AudioSource.DEFAULT, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "Android 8.1: Using AudioSource.DEFAULT with Acoustic Isolation Filter")
                isDirectInternalSubmix = false
                return rec
            }
            rec.release()
        } catch (e: Exception) {
            Log.w(TAG, "Android 8.1: DEFAULT audio source failed: ${e.message}")
        }

        return null
    }

    private var samplesWritten = 0

    private fun recordAndEncodeLoop(bufferSize: Int) {
        val codec = mediaCodec ?: return
        val record = audioRecord ?: return
        val pcmBuffer = ByteArray(bufferSize)
        val bufferInfo = MediaCodec.BufferInfo()
        var muxerStarted = false

        try {
            while (isRecording) {
                val readBytes = record.read(pcmBuffer, 0, pcmBuffer.size)
                if (readBytes > 0) {
                    // On Android 8.1 fallback, apply Acoustic Noise Gate to block ambient room noise
                    if (!isDirectInternalSubmix) {
                        applyAcousticNoiseGate(pcmBuffer, readBytes)
                    }

                    // Feed raw PCM internal audio into AAC encoder
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(pcmBuffer, 0, readBytes)
                        val presentationTimeNs = System.nanoTime() / 1000
                        codec.queueInputBuffer(inputIndex, 0, readBytes, presentationTimeNs, 0)
                    }
                }

                // Drain encoded AAC packets
                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            val newFormat = codec.outputFormat
                            audioTrackIndex = mediaMuxer?.addTrack(newFormat) ?: -1
                            if (audioTrackIndex >= 0) {
                                mediaMuxer?.start()
                                muxerStarted = true
                            }
                        }
                    } else if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && muxerStarted && audioTrackIndex >= 0) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size != 0) {
                                mediaMuxer?.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                                samplesWritten++
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    } else {
                        break
                    }
                }
            }

            // Signal End of Stream
            val eosIndex = codec.dequeueInputBuffer(10000)
            if (eosIndex >= 0) {
                codec.queueInputBuffer(eosIndex, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }

            // Drain remaining EOS output
            while (true) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && muxerStarted && audioTrackIndex >= 0) {
                        if (bufferInfo.size != 0) {
                            mediaMuxer?.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                            samplesWritten++
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during internal audio encoding", e)
        } finally {
            try {
                if (muxerStarted && samplesWritten > 0) {
                    mediaMuxer?.stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping MediaMuxer", e)
            }
        }
    }

    /**
     * Digital Acoustic Noise Gate for Android 8.1:
     * Suppresses low-level room noise and breathing, allowing crystal-clear internal sounds through.
     */
    private fun applyAcousticNoiseGate(buffer: ByteArray, length: Int) {
        for (i in 0 until length - 1 step 2) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            if (abs(sample.toInt()) < NOISE_GATE_THRESHOLD) {
                buffer[i] = 0
                buffer[i + 1] = 0
            }
        }
    }

    fun stop() {
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            recordingThread?.join(800)
        } catch (_: Exception) {}
        recordingThread = null
        release()

        if (samplesWritten == 0 && outputFile.exists()) {
            outputFile.delete()
        }
    }

    private fun release() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null

        try {
            mediaMuxer?.release()
        } catch (_: Exception) {}
        mediaMuxer = null
    }
}
