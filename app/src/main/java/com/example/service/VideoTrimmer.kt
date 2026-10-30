package com.example.service

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object VideoTrimmer {
    private const val TAG = "VideoTrimmer"
    private const val DEFAULT_BUFFER_SIZE = 1024 * 1024 // 1MB buffer

    /**
     * Merges [videoFile] and optional [audioFile] (internal audio) into [outputFile],
     * while simultaneously trimming [trimSeconds] off the end of the recording.
     * Deletes temporary intermediate files upon completion.
     */
    fun mergeAndTrim(
        videoFile: File,
        audioFile: File?,
        outputFile: File,
        trimSeconds: Int
    ): File {
        if (!videoFile.exists() || videoFile.length() == 0L) {
            return videoFile
        }

        // If no audio file exists or audio file is empty (< 1KB), use standard single-file trim
        if (audioFile == null || !audioFile.exists() || audioFile.length() < 1024L) {
            if (audioFile?.exists() == true) audioFile.delete()
            return trimEnd(videoFile, trimSeconds, outputFile)
        }

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Select video track
            var videoTrackIn = -1
            var videoTrackOut = -1
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoExtractor.selectTrack(i)
                    videoTrackIn = i
                    videoTrackOut = muxer.addTrack(format)
                    break
                }
            }

            // Select audio track only if track exists
            var audioTrackIn = -1
            var audioTrackOut = -1
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioExtractor.selectTrack(i)
                    audioTrackIn = i
                    audioTrackOut = muxer.addTrack(format)
                    break
                }
            }

            // Calculate video duration for trimming
            val retriever = MediaMetadataRetriever()
            val durationUs: Long = try {
                retriever.setDataSource(videoFile.absolutePath)
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                durMs * 1000L
            } catch (e: Exception) {
                0L
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }

            val trimUs = trimSeconds * 1_000_000L
            val targetEndUs = if (durationUs > trimUs + 1_000_000L) durationUs - trimUs else durationUs

            muxer.start()

            val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            var videoSamplesWritten = 0
            var audioSamplesWritten = 0

            var videoDone = (videoTrackIn < 0 || videoTrackOut < 0)
            var audioDone = (audioTrackIn < 0 || audioTrackOut < 0)

            // Interleaved processing: Feed samples chronologically by timestamp
            // Prevents native MediaMuxer buffer overfill on Android 8.1
            while (!videoDone || !audioDone) {
                val vTime = if (!videoDone) videoExtractor.sampleTime else Long.MAX_VALUE
                val aTime = if (!audioDone) audioExtractor.sampleTime else Long.MAX_VALUE

                if (vTime == -1L && aTime == -1L) break

                if (!videoDone && (audioDone || (vTime != -1L && vTime <= aTime))) {
                    if (vTime > targetEndUs) {
                        videoDone = true
                    } else {
                        buffer.clear()
                        val sampleSize = videoExtractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            videoDone = true
                        } else {
                            bufferInfo.offset = 0
                            bufferInfo.size = sampleSize
                            bufferInfo.presentationTimeUs = vTime
                            bufferInfo.flags = videoExtractor.sampleFlags
                            muxer.writeSampleData(videoTrackOut, buffer, bufferInfo)
                            videoSamplesWritten++
                            videoExtractor.advance()
                        }
                    }
                } else if (!audioDone) {
                    if (aTime > targetEndUs) {
                        audioDone = true
                    } else {
                        buffer.clear()
                        val sampleSize = audioExtractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            audioDone = true
                        } else {
                            bufferInfo.offset = 0
                            bufferInfo.size = sampleSize
                            bufferInfo.presentationTimeUs = aTime
                            bufferInfo.flags = audioExtractor.sampleFlags
                            muxer.writeSampleData(audioTrackOut, buffer, bufferInfo)
                            audioSamplesWritten++
                            audioExtractor.advance()
                        }
                    }
                }
            }

            if (videoSamplesWritten > 0 || audioSamplesWritten > 0) {
                try {
                    muxer.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Muxer stop warning", e)
                }
            }
            muxer.release()
            muxer = null

            videoExtractor.release()
            audioExtractor.release()

            // Clean up temporary files
            videoFile.delete()
            audioFile.delete()

            Log.d(TAG, "Successfully merged internal audio and video with trimming: ${outputFile.name}")
            return outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Error merging video and audio, falling back to raw video", e)
            try { muxer?.release() } catch (_: Exception) {}
            try { videoExtractor.release() } catch (_: Exception) {}
            try { audioExtractor.release() } catch (_: Exception) {}

            // Critical fix: Delete partially written or corrupt outputFile before renaming videoFile
            if (outputFile.exists() && outputFile != videoFile) {
                outputFile.delete()
            }
            if (videoFile.exists()) {
                videoFile.renameTo(outputFile)
            }
            if (audioFile?.exists() == true) {
                audioFile.delete()
            }
            return outputFile
        }
    }

    /**
     * Trims [trimSeconds] off the END of the MP4 file at [sourceFile].
     */
    fun trimEnd(sourceFile: File, trimSeconds: Int, destinationFile: File? = null): File {
        if (!sourceFile.exists() || trimSeconds <= 0) {
            if (destinationFile != null && destinationFile != sourceFile) {
                if (destinationFile.exists()) destinationFile.delete()
                sourceFile.renameTo(destinationFile)
                return destinationFile
            }
            return sourceFile
        }

        val targetDest = destinationFile ?: File(sourceFile.parentFile, "${sourceFile.nameWithoutExtension}_trimmed.mp4")

        val retriever = MediaMetadataRetriever()
        val durationUs: Long = try {
            retriever.setDataSource(sourceFile.absolutePath)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            durMs * 1000L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read duration for trimming", e)
            return sourceFile
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        val trimUs = trimSeconds * 1_000_000L
        val targetEndUs = durationUs - trimUs

        if (targetEndUs <= 1_000_000L) {
            Log.w(TAG, "Video duration too short to trim. Keeping original.")
            if (destinationFile != null && destinationFile != sourceFile) {
                sourceFile.renameTo(destinationFile)
                return destinationFile
            }
            return sourceFile
        }

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(sourceFile.absolutePath)
            val trackCount = extractor.trackCount
            muxer = MediaMuxer(targetDest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val indexMap = HashMap<Int, Int>(trackCount)
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    val dstIndex = muxer.addTrack(format)
                    indexMap[i] = dstIndex
                }
            }

            muxer.start()

            val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            var samplesWritten = 0
            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime > targetEndUs) {
                    extractor.advance()
                    continue
                }

                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val dstTrack = indexMap[trackIndex]
                if (dstTrack != null) {
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = sampleTime
                    bufferInfo.flags = extractor.sampleFlags

                    muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                    samplesWritten++
                }

                extractor.advance()
            }

            if (samplesWritten > 0) {
                try {
                    muxer.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Muxer stop warning in trimEnd", e)
                }
            }
            muxer.release()
            muxer = null
            extractor.release()

            if (targetDest.exists() && targetDest.length() > 0) {
                sourceFile.delete()
                return targetDest
            } else {
                targetDest.delete()
                return sourceFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error trimming video, keeping original", e)
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            if (targetDest.exists() && targetDest != sourceFile) targetDest.delete()
            return sourceFile
        }
    }
}
