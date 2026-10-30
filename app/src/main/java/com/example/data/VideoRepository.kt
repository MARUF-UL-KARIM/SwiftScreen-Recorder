package com.example.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import com.example.model.RecordedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

class VideoRepository(private val context: Context) {

    private val _videos = MutableStateFlow<List<RecordedVideo>>(emptyList())
    val videos: StateFlow<List<RecordedVideo>> = _videos.asStateFlow()

    fun getRecordingsDir(): File {
        // Primary directory: app external files directory for Movies
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, "Movies")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    suspend fun refreshVideos() = withContext(Dispatchers.IO) {
        val dir = getRecordingsDir()
        val files = dir.listFiles { file ->
            file.isFile && file.extension.lowercase() == "mp4" && file.length() > 0
        } ?: emptyArray()

        val list = files.mapNotNull { file ->
            extractVideoInfo(file)
        }.sortedByDescending { it.lastModified }

        _videos.value = list
    }

    private fun extractVideoInfo(file: File): RecordedVideo? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val width = widthStr?.toIntOrNull() ?: 0
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val height = heightStr?.toIntOrNull() ?: 0

            RecordedVideo(
                file = file,
                name = file.nameWithoutExtension,
                durationMs = duration,
                sizeBytes = file.length(),
                lastModified = file.lastModified(),
                width = width,
                height = height
            )
        } catch (_: Exception) {
            // Fallback for file with basic info
            RecordedVideo(
                file = file,
                name = file.nameWithoutExtension,
                durationMs = 0L,
                sizeBytes = file.length(),
                lastModified = file.lastModified(),
                width = 0,
                height = 0
            )
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    suspend fun deleteVideo(video: RecordedVideo): Boolean = withContext(Dispatchers.IO) {
        val deleted = try {
            if (video.file.exists()) {
                video.file.delete()
            } else false
        } catch (_: Exception) {
            false
        }
        if (deleted) {
            refreshVideos()
        }
        deleted
    }

    suspend fun renameVideo(video: RecordedVideo, newName: String): Boolean = withContext(Dispatchers.IO) {
        val sanitized = newName.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
        if (sanitized.isEmpty()) return@withContext false

        val targetFile = File(video.file.parentFile, "$sanitized.mp4")
        if (targetFile.exists() && targetFile != video.file) return@withContext false

        val renamed = try {
            video.file.renameTo(targetFile)
        } catch (_: Exception) {
            false
        }
        if (renamed) {
            refreshVideos()
        }
        renamed
    }

    companion object {
        @Volatile
        private var INSTANCE: VideoRepository? = null

        fun getInstance(context: Context): VideoRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VideoRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
