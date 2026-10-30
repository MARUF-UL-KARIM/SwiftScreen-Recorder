package com.example.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
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
        // Preferred: public Movies/ScreenCraft directory so videos appear directly in Gallery and Files
        val publicMovies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val screenCraftDir = File(publicMovies, "ScreenCraft")
        if (!screenCraftDir.exists()) {
            screenCraftDir.mkdirs()
        }
        if (screenCraftDir.exists() && screenCraftDir.canWrite()) {
            return screenCraftDir
        }

        // Fallback: app external files directory if public directory is not writable yet
        val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, "Movies")
        if (!fallbackDir.exists()) {
            fallbackDir.mkdirs()
        }
        return fallbackDir
    }

    fun scanFileForGallery(file: File) {
        if (file.exists() && file.length() > 0) {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("video/mp4")
            ) { path, uri ->
                Log.d("VideoRepository", "Video scanned into MediaStore for Gallery: $path -> $uri")
            }
        }
    }

    private fun cleanupOrphanedTempFiles(dir: File) {
        try {
            dir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val isTemp = file.name.startsWith("temp_")
                    val isZeroByte = file.length() == 0L
                    val isAudioTemp = file.extension.lowercase() == "m4a" && file.name.startsWith("temp_")
                    if (isTemp || isZeroByte || isAudioTemp) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun refreshVideos() = withContext(Dispatchers.IO) {
        val primaryDir = getRecordingsDir()
        val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)

        cleanupOrphanedTempFiles(primaryDir)
        if (fallbackDir != null && fallbackDir.exists() && fallbackDir != primaryDir) {
            cleanupOrphanedTempFiles(fallbackDir)
        }

        val allFiles = mutableListOf<File>()
        primaryDir.listFiles { file ->
            file.isFile && file.extension.lowercase() == "mp4" && file.length() > 0 && !file.name.startsWith("temp_")
        }?.let { allFiles.addAll(it) }

        if (fallbackDir != null && fallbackDir.exists() && fallbackDir != primaryDir) {
            fallbackDir.listFiles { file ->
                file.isFile && file.extension.lowercase() == "mp4" && file.length() > 0 && !file.name.startsWith("temp_")
            }?.let { allFiles.addAll(it) }
        }

        val list = allFiles.distinctBy { it.name }.mapNotNull { file ->
            scanFileForGallery(file)
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
            scanFileForGallery(targetFile)
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
