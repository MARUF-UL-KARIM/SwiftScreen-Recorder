package com.example.service

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * Screen video encoder built on MediaCodec (surface input) + MediaMuxer.
 *
 * Why not MediaRecorder: on many Android 8 devices MediaRecorder receives no frames when the
 * screen is static, then stop() fails and an empty file is produced. Here the encoder is told to
 * repeat the previous frame (KEY_REPEAT_PREVIOUS_FRAME_AFTER), so a valid video is always written,
 * and every failure is reported through [lastError] instead of being swallowed.
 */
class ScreenVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitRate: Int,
    private val outputFile: File
) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null

    @Volatile private var abort = false
    @Volatile private var paused = false
    @Volatile private var resumePending = false
    @Volatile private var muxerStarted = false
    private var videoTrack = -1

    @Volatile var samplesWritten = 0
        private set

    @Volatile var lastError: String? = null
        private set

    /** Configures and starts the encoder. Returns the Surface the VirtualDisplay must render into. */
    fun start(): Surface {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Static screen => no new frames from the display. Repeat the last one (every 100ms).
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }

        return try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = c
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = c.createInputSurface()
            inputSurface = surface
            c.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val t = Thread({ drainLoop(c) }, "ScreenVideoEncoderDrain")
            drainThread = t
            t.start()
            surface
        } catch (t: Throwable) {
            releaseInternal()
            throw t
        }
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        resumePending = true
        paused = false
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec?.setParameters(params)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not request sync frame", t)
        }
    }

    /** Finishes the file. Returns true only if a playable video was written. */
    fun stop(): Boolean {
        try {
            codec?.signalEndOfInputStream()
        } catch (t: Throwable) {
            lastError = "signalEndOfInputStream: ${t.message}"
            Log.w(TAG, lastError, t)
        }

        try {
            drainThread?.join(5000)
        } catch (_: InterruptedException) {
        }
        if (drainThread?.isAlive == true) {
            abort = true
            try {
                drainThread?.join(1500)
            } catch (_: InterruptedException) {
            }
        }

        var ok = samplesWritten > 0 && muxerStarted
        if (muxerStarted) {
            try {
                muxer?.stop()
            } catch (t: Throwable) {
                ok = false
                lastError = "muxer.stop: ${t.message}"
                Log.e(TAG, lastError, t)
            }
        } else if (lastError == null) {
            lastError = "Encoder produced no output (no frames received from the screen)"
        }
        releaseInternal()
        return ok && outputFile.exists() && outputFile.length() > 0
    }

    /** Abort without finalizing (used when start-up fails and we retry). */
    fun release() {
        abort = true
        try {
            drainThread?.join(1000)
        } catch (_: InterruptedException) {
        }
        releaseInternal()
        try {
            outputFile.delete()
        } catch (_: Exception) {
        }
    }

    private fun releaseInternal() {
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
        try { inputSurface?.release() } catch (_: Throwable) {}
        inputSurface = null
        try { muxer?.release() } catch (_: Throwable) {}
        muxer = null
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val frameIntervalUs = 1_000_000L / maxOf(fps, 1)
        var firstPtsUs = -1L
        var lastRawPtsUs = 0L
        var lastOutPtsUs = -1L
        var pauseOffsetUs = 0L

        try {
            while (!abort) {
                val index = c.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        val m = muxer ?: break
                        videoTrack = m.addTrack(c.outputFormat)
                        m.start()
                        muxerStarted = true
                    }
                    continue
                }
                if (index < 0) {
                    continue
                }

                val buf = c.getOutputBuffer(index)
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                if (buf != null && info.size > 0 && !isConfig && muxerStarted && !paused) {
                    if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                    if (resumePending) {
                        pauseOffsetUs += (info.presentationTimeUs - lastRawPtsUs) - frameIntervalUs
                        if (pauseOffsetUs < 0) pauseOffsetUs = 0
                        resumePending = false
                    }
                    var pts = info.presentationTimeUs - firstPtsUs - pauseOffsetUs
                    if (pts <= lastOutPtsUs) pts = lastOutPtsUs + 1
                    lastRawPtsUs = info.presentationTimeUs
                    lastOutPtsUs = pts

                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    info.set(info.offset, info.size, pts, info.flags)
                    muxer?.writeSampleData(videoTrack, buf, info)
                    samplesWritten++
                }

                c.releaseOutputBuffer(index, false)
                if (isEos) break
            }
        } catch (t: Throwable) {
            lastError = "encoder loop: ${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, lastError, t)
        }
    }

    companion object {
        private const val TAG = "ScreenVideoEncoder"
    }
}
