package com.example.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.ScreenCraftApp
import com.example.data.SettingsRepository
import com.example.data.VideoRepository
import com.example.model.AudioMode
import com.example.model.RecordingSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecorderService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var timerJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenEncoder: ScreenVideoEncoder? = null
    private var internalAudioRecorder: InternalAudioCaptureRecorder? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var currentFinalOutputFile: File? = null
    private var tempVideoFile: File? = null
    private var tempAudioFile: File? = null
    private var currentSettings: RecordingSettings? = null
    private var isPaused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != 0 && resultData != null) {
                    cachedResultCode = resultCode
                    cachedResultData = resultData
                    startForegroundServiceWithNotification("Preparing...")
                    startCountdownAndRecording(resultCode, resultData)
                } else {
                    // Must still satisfy startForegroundService() on Android 8+
                    startForegroundServiceWithNotification("Preparing...")
                    toast("Screen capture permission paoa jayni")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
        }

        return START_NOT_STICKY
    }

    private fun toast(message: String) {
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun startForegroundServiceWithNotification(content: String) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ScreenCraftApp.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(ScreenCraftApp.NOTIFICATION_ID, notification)
        }
    }

    private fun startCountdownAndRecording(resultCode: Int, resultData: Intent) {
        val settings = SettingsRepository.getInstance(this).settings.value
        currentSettings = settings
        isPaused = false
        val delaySec = settings.startDelaySeconds

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            projectionManager.getMediaProjection(resultCode, resultData)
        } catch (t: Throwable) {
            DebugLog.log(this, TAG, "getMediaProjection threw", t)
            null
        }
        if (projection == null) {
            DebugLog.log(this, TAG, "MediaProjection token invalid or expired")
            toast("Screen capture permission expired, abar try koro")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        mediaProjection = projection

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                DebugLog.log(this@ScreenRecorderService, TAG, "MediaProjection stopped by system")
                stopRecording()
            }
        }
        projectionCallback = callback
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))

        timerJob?.cancel()
        timerJob = serviceScope.launch {
            if (delaySec > 0) {
                for (remaining in delaySec downTo 1) {
                    _recorderState.value = RecorderState(
                        status = RecorderStatus.COUNTDOWN,
                        countdownSeconds = remaining
                    )
                    updateNotification("Starting in ${remaining}s...")
                    delay(1000)
                }
            }

            val success = initAndStartRecorder(projection, settings)
            if (success) {
                var elapsedSec = 0L
                _recorderState.value = RecorderState(
                    status = RecorderStatus.RECORDING,
                    elapsedSeconds = elapsedSec
                )
                while (isActive) {
                    delay(1000)
                    if (!isPaused) {
                        elapsedSec++
                        _recorderState.value = _recorderState.value.copy(elapsedSeconds = elapsedSec)
                        updateNotification("Recording • ${formatSeconds(elapsedSec)}")
                    }
                }
            } else {
                cleanup()
                _recorderState.value = RecorderState(status = RecorderStatus.IDLE)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun initAndStartRecorder(
        projection: MediaProjection,
        settings: RecordingSettings
    ): Boolean {
        // Attempt 1: user settings. Attempt 2/3: safer values for weak Android 8 encoders.
        val attempts = listOf(
            Triple(1.0f, settings.fps, settings.bitrateMbps),
            Triple(0.75f, minOf(settings.fps, 30), minOf(settings.bitrateMbps, 4)),
            Triple(0.5f, 24, 2)
        )
        for ((index, a) in attempts.withIndex()) {
            if (tryInitAndStartRecorder(projection, settings, a.first, a.second, a.third)) return true
            if (index < attempts.lastIndex) {
                DebugLog.log(this, TAG, "Recorder start failed, retrying with safer config #${index + 2}")
            }
        }
        toast("Screen recording start korte parlo na (encoder error). Log: Android/data/$packageName/files/screencraft_log.txt")
        return false
    }

    private fun tryInitAndStartRecorder(
        projection: MediaProjection,
        settings: RecordingSettings,
        scale: Float,
        fpsToUse: Int,
        bitrateMbpsToUse: Int
    ): Boolean {
        return try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            val (baseWidth, baseHeight) = settings.resolution.getDimensions(metrics.widthPixels, metrics.heightPixels)
            val isPortrait = metrics.heightPixels >= metrics.widthPixels
            val targetWidth = if (settings.resolution.isMatchScreen && minOf(metrics.widthPixels, metrics.heightPixels) in 700..740) 720 else minOf(baseWidth, baseHeight)
            val targetHeight = if (settings.resolution.isMatchScreen && maxOf(metrics.widthPixels, metrics.heightPixels) in 1500..1540) 1520 else maxOf(baseWidth, baseHeight)
            val rawW = if (isPortrait) targetWidth else targetHeight
            val rawH = if (isPortrait) targetHeight else targetWidth
            // Align to multiple of 16 (hardware H.264 encoders on Android 8 are picky)
            val width = maxOf(16, ((rawW * scale).toInt() / 16) * 16)
            val height = maxOf(16, ((rawH * scale).toInt() / 16) * 16)
            val dpi = if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEVICE_STABLE

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val recordingsDir = VideoRepository.getInstance(this).getRecordingsDir()
            currentFinalOutputFile = File(recordingsDir, "ScreenCraft_$timeStamp.mp4")

            val isInternalAudio = settings.audioMode == AudioMode.INTERNAL_ONLY
            val isMicAudio = settings.audioMode == AudioMode.INTERNAL_AND_MIC &&
                androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

            // Temp files live in the app's private cache: nothing else can touch/delete them while recording
            val tempDir = File(cacheDir, "recording_tmp").apply { mkdirs() }
            val videoTargetFile = File(tempDir, "temp_video_$timeStamp.mp4")
            tempVideoFile = videoTargetFile

            DebugLog.log(this, TAG, "Starting encoder ${width}x$height @${fpsToUse}fps ${bitrateMbpsToUse}Mbps -> ${videoTargetFile.absolutePath}; final=${currentFinalOutputFile?.absolutePath}")

            val encoder = ScreenVideoEncoder(width, height, fpsToUse, bitrateMbpsToUse * 1_000_000, videoTargetFile)
            screenEncoder = encoder
            val surface = encoder.start()

            if (isInternalAudio || isMicAudio) {
                val audioFile = File(tempDir, "temp_audio_$timeStamp.m4a")
                val rec = InternalAudioCaptureRecorder(projection, audioFile, useMicrophone = isMicAudio)
                if (rec.start()) {
                    internalAudioRecorder = rec
                    tempAudioFile = audioFile
                } else {
                    DebugLog.log(this, TAG, "Audio capture not available on this ROM; recording video only")
                    tempAudioFile = null
                }
            }

            virtualDisplay = projection.createVirtualDisplay(
                "ScreenCraftDisplay",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )
            true
        } catch (t: Throwable) {
            DebugLog.log(this, TAG, "Failed to start screen recording", t)
            releaseRecorderOnly()
            false
        }
    }

    private fun pauseRecording() {
        if (isPaused || _recorderState.value.status != RecorderStatus.RECORDING) return
        screenEncoder?.pause()
        internalAudioRecorder?.paused = true
        isPaused = true
        _recorderState.value = _recorderState.value.copy(status = RecorderStatus.PAUSED)
        updateNotification("Recording Paused")
    }

    private fun resumeRecording() {
        if (!isPaused) return
        screenEncoder?.resume()
        internalAudioRecorder?.paused = false
        isPaused = false
        _recorderState.value = _recorderState.value.copy(status = RecorderStatus.RECORDING)
        updateNotification("Recording • ${formatSeconds(_recorderState.value.elapsedSeconds)}")
    }

    private fun stopRecording() {
        if (_recorderState.value.status == RecorderStatus.STOPPING) return
        if (_recorderState.value.status == RecorderStatus.IDLE && screenEncoder == null) {
            // Nothing is recording (e.g. stale Stop tap)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        timerJob?.cancel()
        _recorderState.value = RecorderState(status = RecorderStatus.STOPPING)
        updateNotification("Saving & Processing video...")

        val handler = CoroutineExceptionHandler { _, t ->
            DebugLog.log(this, TAG, "Uncaught error while stopping", t)
        }

        serviceScope.launch(Dispatchers.IO + handler) {
            val finalTarget = currentFinalOutputFile
            val videoFile = tempVideoFile
            val audioFile = tempAudioFile
            val trimSeconds = currentSettings?.stopTrimSeconds ?: 0

            try {
                // 1. Stop feeding frames, then finish the encoder (flushes the last frames + writes the MP4 index)
                try {
                    virtualDisplay?.release()
                } catch (t: Throwable) {
                    DebugLog.log(this@ScreenRecorderService, TAG, "VirtualDisplay release failed", t)
                }
                virtualDisplay = null

                val encoder = screenEncoder
                val encoderOk = try {
                    encoder?.stop() ?: false
                } catch (t: Throwable) {
                    DebugLog.log(this@ScreenRecorderService, TAG, "Encoder stop failed", t)
                    false
                }
                screenEncoder = null
                DebugLog.log(
                    this@ScreenRecorderService, TAG,
                    "Encoder stopped ok=$encoderOk samples=${encoder?.samplesWritten} err=${encoder?.lastError} videoSize=${videoFile?.length()}"
                )

                // 2. Stop audio
                try {
                    internalAudioRecorder?.stop()
                } catch (t: Throwable) {
                    DebugLog.log(this@ScreenRecorderService, TAG, "Audio stop failed", t)
                }
                internalAudioRecorder = null
                cleanup()

                // 3. Save
                if (finalTarget != null && videoFile != null && videoFile.exists() && videoFile.length() > 0) {
                    val saved = finalizeVideo(videoFile, audioFile, finalTarget, trimSeconds)
                    if (saved != null) {
                        VideoRepository.getInstance(applicationContext).scanFileForGallery(saved)
                        DebugLog.log(this@ScreenRecorderService, TAG, "Saved: ${saved.absolutePath} (${saved.length()} bytes)")
                        toast("Video saved: ${saved.parentFile?.name}/${saved.name}")
                    } else {
                        toast("Video save korte parlo na. Log dekho: Android/data/$packageName/files/screencraft_log.txt")
                    }
                } else {
                    DebugLog.log(this@ScreenRecorderService, TAG, "No video data produced. encoderError=${encoder?.lastError}")
                    if (finalTarget != null) {
                        toast("Recording e kono video data paoa jayni: ${encoder?.lastError ?: "unknown"}")
                    }
                    videoFile?.delete()
                    audioFile?.delete()
                }
            } catch (t: Throwable) {
                DebugLog.log(this@ScreenRecorderService, TAG, "Stop/save pipeline crashed", t)
                toast("Save korar somoy error: ${t.message}")
            } finally {
                try {
                    VideoRepository.getInstance(applicationContext).refreshVideos()
                } catch (_: Throwable) {
                }
                _recorderState.value = RecorderState(status = RecorderStatus.IDLE)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Turns the raw recorded temp file into the final video and puts it in the public folder.
     * The raw video is never deleted until a playable replacement exists.
     * Returns the saved file, or null if it could not be saved anywhere.
     */
    private fun finalizeVideo(videoFile: File, audioFile: File?, finalTarget: File, trimSeconds: Int): File? {
        val workDir = videoFile.parentFile ?: cacheDir
        val candidate = File(workDir, "processed_${finalTarget.name}")
        candidate.delete()

        var best: File = videoFile
        try {
            val audio = audioFile?.takeIf { it.exists() && it.length() > 0 }
            val out: File = when {
                audio != null -> VideoTrimmer.mergeAndTrim(videoFile, audio, candidate, trimSeconds)
                trimSeconds > 0 -> VideoTrimmer.trimEnd(videoFile, trimSeconds, candidate)
                else -> videoFile
            }
            if (out.exists() && out.length() > 0) {
                best = out
            } else if (candidate.exists() && candidate.length() > 0) {
                best = candidate
            }
        } catch (t: Throwable) {
            DebugLog.log(this, TAG, "Post-processing failed, saving raw video", t)
        }
        if (!best.exists() || best.length() == 0L) best = videoFile
        if (!best.exists() || best.length() == 0L) return null

        var target = finalTarget
        var moved = moveFile(best, target)
        if (!moved || !target.exists() || target.length() == 0L) {
            // Public folder not writable: fall back to the app's own external folder (still listed in the app)
            val fallbackDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(filesDir, "Movies")
            target = File(fallbackDir, finalTarget.name)
            DebugLog.log(this, TAG, "Public save failed, falling back to ${target.absolutePath}")
            moved = moveFile(best, target)
        }
        audioFile?.delete()
        return if (moved && target.exists() && target.length() > 0) target else null
    }

    // Releases only recorder-side resources so the MediaProjection can be reused for a retry
    private fun releaseRecorderOnly() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { internalAudioRecorder?.stop() } catch (_: Throwable) {}
        internalAudioRecorder = null
        try { screenEncoder?.release() } catch (_: Throwable) {}
        screenEncoder = null
        try { tempVideoFile?.delete() } catch (_: Throwable) {}
        tempVideoFile = null
        try { tempAudioFile?.delete() } catch (_: Throwable) {}
        tempAudioFile = null
    }

    // renameTo fails between cache dir and public storage, so copy+delete when needed
    private fun moveFile(src: File, dst: File): Boolean {
        return try {
            dst.parentFile?.mkdirs()
            if (src.renameTo(dst)) return true
            src.copyTo(dst, overwrite = true)
            src.delete()
            true
        } catch (e: Exception) {
            DebugLog.log(this, TAG, "moveFile ${src.absolutePath} -> ${dst.absolutePath} failed", e)
            false
        }
    }

    private fun cleanup() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null

        try { screenEncoder?.release() } catch (_: Throwable) {}
        screenEncoder = null

        try { internalAudioRecorder?.stop() } catch (_: Throwable) {}
        internalAudioRecorder = null

        try {
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
            projectionCallback = null
        } catch (_: Throwable) {}

        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Throwable) {}
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(ScreenCraftApp.NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenRecorderService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ScreenCraftApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_qs_record)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_action_stop), stopPendingIntent)
            .build()
    }

    private fun formatSeconds(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        // If we are destroyed mid-recording, the stop pipeline is already running or nothing is active
        if (_recorderState.value.status != RecorderStatus.STOPPING) {
            cleanup()
            _recorderState.value = RecorderState(status = RecorderStatus.IDLE)
        }
    }

    companion object {
        private const val TAG = "ScreenRecorderService"

        const val ACTION_START = "com.example.action.START_RECORDING"
        const val ACTION_STOP = "com.example.action.STOP_RECORDING"
        const val ACTION_PAUSE = "com.example.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.example.action.RESUME_RECORDING"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        // Cached permission token so Quick Settings tile can instantly toggle
        var cachedResultCode: Int? = null
        var cachedResultData: Intent? = null

        private val _recorderState = MutableStateFlow(RecorderState())
        val recorderState: StateFlow<RecorderState> = _recorderState.asStateFlow()

        fun isRecording(): Boolean {
            val s = _recorderState.value.status
            return s == RecorderStatus.RECORDING || s == RecorderStatus.PAUSED || s == RecorderStatus.COUNTDOWN
        }
    }
}

enum class RecorderStatus {
    IDLE,
    COUNTDOWN,
    RECORDING,
    PAUSED,
    STOPPING
}

data class RecorderState(
    val status: RecorderStatus = RecorderStatus.IDLE,
    val elapsedSeconds: Long = 0,
    val countdownSeconds: Int = 0
)
