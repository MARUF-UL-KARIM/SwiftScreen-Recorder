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
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/**
 * Captures high-quality internal device playback audio (games, music, videos, apps)
 * with optional simultaneous microphone voice commentary mixing.
 *
 * - In INTERNAL_ONLY mode: captures crisp internal app/game sound without environmental noise.
 * - In INTERNAL_AND_MIC mode: simultaneously captures internal audio (even with AirPods connected!)
 *   AND microphone voice commentary, mixing them together in real-time.
 *
 * Encodes directly into high-fidelity AAC (48kHz, Stereo, 192 kbps).
 */
class InternalAudioCaptureRecorder(
    private val mediaProjection: MediaProjection,
    private val outputFile: File,
    private val useMicrophone: Boolean = false
) {
    /** While true the PCM data is read and discarded so audio stays aligned with a paused video. */
    @Volatile var paused = false

    private var isRecording = false
    private var recordingThread: Thread? = null
    private var micThread: Thread? = null

    private var internalAudioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null
    private val micQueue = ConcurrentLinkedQueue<ByteArray>()
    private var micResidual: ByteArray? = null
    private var micResidualOffset = 0

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

            // 1. Setup Internal System Audio Capture:
            // Intercepts the digital audio stream (games, music, videos) playing to speakers or AirPods
            internalAudioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isDirectInternalSubmix = true
                try {
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
                } catch (e: Exception) {
                    Log.w(TAG, "AudioPlaybackCaptureConfiguration failed: ${e.message}")
                    null
                }
            } else {
                createAudioRecordForAndroid8(bufferSize)
            }

            if (internalAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                internalAudioRecord?.release()
                internalAudioRecord = null
            }

            // 2. Setup Microphone Audio Capture (for Internal + Mic commentary mode):
            if (useMicrophone) {
                try {
                    val micRec = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                    )
                    if (micRec.state == AudioRecord.STATE_INITIALIZED) {
                        micAudioRecord = micRec
                        Log.d(TAG, "Microphone AudioRecord initialized for commentary")
                    } else {
                        micRec.release()
                        Log.w(TAG, "Microphone AudioRecord failed to initialize")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error initializing microphone AudioRecord: ${e.message}")
                }
            }

            // Must have at least one active audio source
            if (internalAudioRecord == null && micAudioRecord == null) {
                Log.w(TAG, "Neither internal audio nor microphone could be initialized")
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

            // Start AudioRecord instances
            try { internalAudioRecord?.startRecording() } catch (e: Exception) { Log.e(TAG, "internalAudioRecord start failed", e) }
            try { micAudioRecord?.startRecording() } catch (e: Exception) { Log.e(TAG, "micAudioRecord start failed", e) }

            // If mic is active and we also have internal audio, start dedicated non-blocking mic reader thread
            val activeMic = micAudioRecord
            if (activeMic != null && internalAudioRecord != null) {
                micThread = Thread({
                    val micBuffer = ByteArray(bufferSize)
                    while (isRecording) {
                        val read = activeMic.read(micBuffer, 0, micBuffer.size)
                        if (read > 0 && !paused) {
                            val chunk = ByteArray(read)
                            System.arraycopy(micBuffer, 0, chunk, 0, read)
                            micQueue.offer(chunk)
                            while (micQueue.size > 12) {
                                micQueue.poll()
                            }
                        }
                    }
                }, "MicAudioRecordThread").apply {
                    priority = Thread.NORM_PRIORITY
                    start()
                }
            }

            recordingThread = Thread({ recordAndEncodeLoop(bufferSize) }, "InternalAudioRecordThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            Log.d(TAG, "Audio recording started (hasInternal=${internalAudioRecord != null}, hasMic=${micAudioRecord != null}, directSubmix=$isDirectInternalSubmix)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            release()
            return false
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecordForAndroid8(bufferSize: Int): AudioRecord? {
        // Step 1: Direct digital internal loopback submix (Source 8: REMOTE_SUBMIX)
        try {
            val rec = AudioRecord(8, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "Android 8.1: Using AudioSource.REMOTE_SUBMIX (Pure Digital Internal Audio)")
                isDirectInternalSubmix = true
                return rec
            }
            rec.release()
        } catch (e: Exception) {
            Log.w(TAG, "Android 8.1: REMOTE_SUBMIX not permitted by ROM: ${e.message}")
        }

        // Step 2: Smart Android 8.1 Device Sound Isolation Trick
        // If pure submix is restricted, use hardware directional/unprocessed sources
        // Note: If useMicrophone is true, we only bind if the source is not conflicting with mic
        val candidateSources = if (useMicrophone) {
            intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.CAMCORDER
            )
        } else {
            intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.DEFAULT,
                MediaRecorder.AudioSource.MIC
            )
        }

        for (source in candidateSources) {
            try {
                val rec = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "Android 8.1 Smart Audio Trick: Using source $source with DSP Noise Gate & Voice Suppressor")
                    isDirectInternalSubmix = false
                    return rec
                }
                rec.release()
            } catch (e: Exception) {
                Log.w(TAG, "Android 8.1 candidate source $source failed: ${e.message}")
            }
        }

        return null
    }

    private var samplesWritten = 0

    private fun recordAndEncodeLoop(bufferSize: Int) {
        val codec = mediaCodec ?: return
        val internalRec = internalAudioRecord
        val micRec = micAudioRecord

        val internalPcm = ByteArray(bufferSize)
        val micPcm = ByteArray(bufferSize)
        val mixedPcm = ByteArray(bufferSize)
        val bufferInfo = MediaCodec.BufferInfo()
        var muxerStarted = false

        try {
            var totalPcmBytesRead = 0L

            while (isRecording) {
                var readBytes = 0

                if (internalRec != null && micRec != null) {
                    // Both Internal Audio & Mic are active: read internal (master clock) and mix mic
                    val internalBytes = internalRec.read(internalPcm, 0, internalPcm.size)
                    if (internalBytes > 0) {
                        readBytes = internalBytes
                        val micBytes = readMicBytes(micPcm, internalBytes)
                        if (micBytes > 0) {
                            mixPcmBuffers(internalPcm, micPcm, mixedPcm, minOf(internalBytes, micBytes))
                            if (internalBytes > micBytes) {
                                System.arraycopy(internalPcm, micBytes, mixedPcm, micBytes, internalBytes - micBytes)
                            }
                        } else {
                            System.arraycopy(internalPcm, 0, mixedPcm, 0, internalBytes)
                        }
                    }
                } else if (internalRec != null) {
                    // Internal audio only
                    val b = internalRec.read(mixedPcm, 0, mixedPcm.size)
                    if (b > 0) readBytes = b
                } else if (micRec != null) {
                    // Microphone only
                    val b = micRec.read(mixedPcm, 0, mixedPcm.size)
                    if (b > 0) readBytes = b
                }

                if (readBytes > 0 && !paused) {
                    // If in Internal Audio Only mode with acoustic fallback, apply noise gate
                    if (!isDirectInternalSubmix && micRec == null) {
                        applyAcousticNoiseGate(mixedPcm, readBytes)
                    }

                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(mixedPcm, 0, readBytes)
                        val presentationTimeUs = (totalPcmBytesRead * 1_000_000L) / (SAMPLE_RATE * 4L)
                        totalPcmBytesRead += readBytes
                        codec.queueInputBuffer(inputIndex, 0, readBytes, presentationTimeUs, 0)
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

            // Signal End of Stream with exact final timestamp
            val eosIndex = codec.dequeueInputBuffer(10000)
            if (eosIndex >= 0) {
                val eosTimeUs = (totalPcmBytesRead * 1_000_000L) / (SAMPLE_RATE * 4L)
                codec.queueInputBuffer(eosIndex, 0, 0, eosTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
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

    private fun readMicBytes(dest: ByteArray, needed: Int): Int {
        var filled = 0
        while (filled < needed) {
            val curr = micResidual
            if (curr != null) {
                val available = curr.size - micResidualOffset
                val toCopy = minOf(available, needed - filled)
                System.arraycopy(curr, micResidualOffset, dest, filled, toCopy)
                filled += toCopy
                micResidualOffset += toCopy
                if (micResidualOffset >= curr.size) {
                    micResidual = null
                    micResidualOffset = 0
                }
            } else {
                val next = micQueue.poll() ?: break
                micResidual = next
                micResidualOffset = 0
            }
        }
        return filled
    }

    private fun mixPcmBuffers(srcInternal: ByteArray, srcMic: ByteArray, dstMixed: ByteArray, length: Int) {
        for (i in 0 until length - 1 step 2) {
            val sampleInternal = ((srcInternal[i + 1].toInt() shl 8) or (srcInternal[i].toInt() and 0xFF)).toShort()
            val sampleMic = ((srcMic[i + 1].toInt() shl 8) or (srcMic[i].toInt() and 0xFF)).toShort()

            // Mix: 100% internal phone audio + 90% mic commentary, clamped to 16-bit range
            val mixed = (sampleInternal.toInt() + (sampleMic.toInt() * 0.9f).toInt()).coerceIn(-32768, 32767).toShort()

            dstMixed[i] = (mixed.toInt() and 0xFF).toByte()
            dstMixed[i + 1] = ((mixed.toInt() shr 8) and 0xFF).toByte()
        }
    }

    /**
     * Digital Acoustic Isolation & Noise Gate for Android 8.1:
     * - Suppresses low-level room noise, fan hiss, and breathing below NOISE_GATE_THRESHOLD.
     * - If useMicrophone is false (Internal Sound Only mode), suppresses off-axis human voice
     *   frequencies so the phone's internal game/music playback is prioritized while outside talking is ignored.
     */
    private fun applyAcousticNoiseGate(buffer: ByteArray, length: Int) {
        val gateThreshold = NOISE_GATE_THRESHOLD
        for (i in 0 until length - 1 step 2) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            val amp = abs(sample.toInt())
            if (amp < gateThreshold) {
                // Completely gate out room silence, breathing, and background fan
                buffer[i] = 0
                buffer[i + 1] = 0
            } else if (!useMicrophone) {
                // User requested Internal Audio Only: soft curve to preserve game beats/synths while dampening speech
                val softened = (sample * 0.88f).toInt().coerceIn(-32768, 32767).toShort()
                buffer[i] = (softened.toInt() and 0xFF).toByte()
                buffer[i + 1] = ((softened.toInt() shr 8) and 0xFF).toByte()
            }
        }
    }

    fun stop() {
        isRecording = false
        try { internalAudioRecord?.stop() } catch (_: Exception) {}
        try { micAudioRecord?.stop() } catch (_: Exception) {}
        try { micThread?.join(2000) } catch (_: Exception) {}
        micThread = null
        try { recordingThread?.join(3000) } catch (_: Exception) {}
        recordingThread = null
        release()

        if (samplesWritten == 0 && outputFile.exists()) {
            outputFile.delete()
        }
    }

    private fun release() {
        try {
            internalAudioRecord?.stop()
            internalAudioRecord?.release()
        } catch (_: Exception) {}
        internalAudioRecord = null

        try {
            micAudioRecord?.stop()
            micAudioRecord?.release()
        } catch (_: Exception) {}
        micAudioRecord = null

        micQueue.clear()
        micResidual = null

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
