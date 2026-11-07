package com.example.service

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny file logger so recording problems can be diagnosed on a phone without a computer.
 * File: Android/data/<app id>/files/screencraft_log.txt
 */
object DebugLog {
    fun log(context: Context, tag: String, message: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, message, t) else Log.d(tag, message)
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val f = File(dir, "screencraft_log.txt")
            if (f.exists() && f.length() > 200_000) f.delete()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val sb = StringBuilder("$time [$tag] $message\n")
            if (t != null) sb.append(Log.getStackTraceString(t)).append('\n')
            f.appendText(sb.toString())
        } catch (_: Throwable) {
        }
    }
}
