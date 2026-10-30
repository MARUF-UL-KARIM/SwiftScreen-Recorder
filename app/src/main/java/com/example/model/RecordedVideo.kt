package com.example.model

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordedVideo(
    val file: File,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val lastModified: Long,
    val width: Int,
    val height: Int
) {
    val formattedDuration: String
        get() {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }

    val formattedSize: String
        get() {
            val mb = sizeBytes.toDouble() / (1024 * 1024)
            return if (mb >= 1.0) {
                String.format(Locale.US, "%.1f MB", mb)
            } else {
                val kb = sizeBytes.toDouble() / 1024
                String.format(Locale.US, "%.0f KB", kb)
            }
        }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
            return sdf.format(Date(lastModified))
        }

    val resolutionLabel: String
        get() {
            return if (width > 0 && height > 0) {
                "${width}×${height}"
            } else {
                "MP4 Video"
            }
        }
}
