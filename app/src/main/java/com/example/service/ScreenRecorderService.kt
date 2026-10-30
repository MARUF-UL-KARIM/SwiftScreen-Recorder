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
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
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
    private var mediaRecorder: MediaRecorder? = null
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
                }
            }
            ACTION_STOP -> {
                stopRecording()
            }
            ACTION_PAUSE -> {
                pauseRecording()
            }
            ACTION_RESUME -> {
                resumeRecording()
            }
        }

        return START_NOT_STICKY
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
        val delaySec = settings.startDelaySeconds

        // Secure MediaProjection immediately upon receiving token
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            Log.e(TAG, "MediaProjection token invalid or expired")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        mediaProjection = projection

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.d(TAG, "MediaProjection stopped by system")
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

            // Begin recording using pre-validated projection
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
                        val formatted = formatSeconds(elapsedSec)
                        updateNotification("Recording • $formatted")
                    }
                }
            } else {
                stopSelf()
            }
        }
    }

    private fun initAndStartRecorder(
        projection: MediaProjection,
        settings: RecordingSettings
    ): Boolean {
        return try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            val (baseWidth, baseHeight) = settings.resolution.getDimensions(metrics.widthPixels, metrics.heightPixels)
            val isPortrait = metrics.heightPixels >= metrics.widthPixels
            // Ensure 720x1520 exact dimensions for MATCH_SCREEN and HD_720P on 720x1520 device
            val targetWidth = if (settings.resolution.isMatchScreen && minOf(metrics.widthPixels, metrics.heightPixels) in 700..740) 720 else minOf(baseWidth, baseHeight)
            val targetHeight = if (settings.resolution.isMatchScreen && maxOf(metrics.widthPixels, metrics.heightPixels) in 1500..1540) 1520 else maxOf(baseWidth, baseHeight)
            val width = if (isPortrait) targetWidth else targetHeight
            val height = if (isPortrait) targetHeight else targetWidth
            val dpi = if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEVICE_STABLE

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val recordingsDir = VideoRepository.getInstance(this).getRecordingsDir()

            val finalFile = File(recordingsDir, "ScreenCraft_$timeStamp.mp4")
            currentFinalOutputFile = finalFile

            val isInternalAudio = settings.audioMode == AudioMode.INTERNAL_ONLY
            val isMicAudio = settings.audioMode == AudioMode.INTERNAL_AND_MIC

            // When INTERNAL_ONLY is requested: STRICTLY DO NOT TOUCH MICROPHONE!
            // Video and internal audio are captured via dedicated pure digital pipeline
            val videoTargetFile = if (isInternalAudio) {
                File(recordingsDir, "temp_video_$timeStamp.mp4")
            } else {
                finalFile
            }
            tempVideoFile = videoTargetFile

            // Ensure parent directory exists and File object is cleanly initialized before starting MediaRecorder
            try {
                videoTargetFile.parentFile?.mkdirs()
                if (videoTargetFile.exists()) {
                    videoTargetFile.delete()
                }
                videoTargetFile.createNewFile()
                Log.d(TAG, "Output File initialized at: ${videoTargetFile.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "File object creation notice: ${e.message}")
            }

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder = recorder

            // ONLY engage microphone if the user explicitly chose INTERNAL_AND_MIC
            if (isMicAudio) {
                try {
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                } catch (e: Exception) {
                    Log.w(TAG, "Microphone audio source warning", e)
                }
            }

            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            if (isMicAudio) {
                try {
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    recorder.setAudioEncodingBitRate(192_000)
                    recorder.setAudioSamplingRate(48000)
                } catch (e: Exception) {
                    Log.w(TAG, "Audio encoder setup warning", e)
                }
            }

            recorder.setVideoSize(width, height)
            recorder.setVideoFrameRate(settings.fps)
            recorder.setVideoEncodingBitRate(settings.bitrateMbps * 1000 * 1000)
            recorder.setOutputFile(videoTargetFile.absolutePath)

            recorder.prepare()

            // 1. Surface MUST be retrieved after prepare() and BEFORE start()
            val surface = recorder.surface

            // 2. Create VirtualDisplay attached to the prepared surface
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

            // 3. Start pure digital internal audio capture (ZERO microphone hardware)
            if (isInternalAudio) {
                val audioFile = File(recordingsDir, "temp_audio_$timeStamp.m4a")
                tempAudioFile = audioFile
                val internalRecorder = InternalAudioCaptureRecorder(projection, audioFile)
                val started = internalRecorder.start()
                if (started) {
                    internalAudioRecorder = internalRecorder
                    Log.d(TAG, "Started pure internal digital audio recorder (No microphone used)")
                } else {
                    Log.w(TAG, "Pure internal audio not available on this ROM; recording video cleanly without microphone")
                    tempAudioFile = null
                }
            }

            // 4. Start MediaRecorder
            recorder.start()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen recording", e)
            cleanup()
            _recorderState.value = RecorderState(status = RecorderStatus.IDLE)
            false
        }
    }

    private fun pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isPaused) {
            try {
                mediaRecorder?.pause()
                isPaused = true
                _recorderState.value = _recorderState.value.copy(status = RecorderStatus.PAUSED)
                updateNotification("Recording Paused")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pause recording", e)
            }
        }
    }

    private fun resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isPaused) {
            try {
                mediaRecorder?.resume()
                isPaused = false
                _recorderState.value = _recorderState.value.copy(status = RecorderStatus.RECORDING)
                val formatted = formatSeconds(_recorderState.value.elapsedSeconds)
                updateNotification("Recording • $formatted")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume recording", e)
            }
        }
    }

    private fun stopRecording() {
        timerJob?.cancel()
        _recorderState.value = RecorderState(status = RecorderStatus.STOPPING)
        updateNotification("Saving & Processing video...")

        serviceScope.launch(Dispatchers.IO) {
            val elapsed = _recorderState.value.elapsedSeconds
            if (elapsed == 0L) {
                try {
                    Thread.sleep(1000)
                } catch (_: Exception) {}
            }

            // 1. Release VirtualDisplay FIRST so no more surface frames push during shutdown
            try {
                virtualDisplay?.release()
                virtualDisplay = null
            } catch (e: Exception) {
                Log.w(TAG, "VirtualDisplay release: ${e.message}")
            }

            // 2. Allow MPEG4Writer and encoder pipeline 350ms to complete pending frame buffers
            try {
                Thread.sleep(350)
            } catch (_: Exception) {}

            // 3. Stop MediaRecorder cleanly
            try {
                mediaRecorder?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "MediaRecorder stop handled: ${e.message}")
            }
            try {
                mediaRecorder?.reset()
            } catch (_: Exception) {}

            // 4. Stop internal audio recorder if active
            try {
                internalAudioRecorder?.stop()
                internalAudioRecorder = null
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping internal audio recorder", e)
            } finally {
                cleanup()
            }

            // Post-Processing: Lossless Video and Internal Audio Muxing + Stop Trimming
            val finalTarget = currentFinalOutputFile
            val videoFile = tempVideoFile
            val audioFile = tempAudioFile
            val trimSeconds = currentSettings?.stopTrimSeconds ?: 0

            if (finalTarget != null && videoFile != null && videoFile.exists() && videoFile.length() > 0) {
                if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
                    VideoTrimmer.mergeAndTrim(videoFile, audioFile, finalTarget, trimSeconds)
                } else if (trimSeconds > 0) {
                    VideoTrimmer.trimEnd(videoFile, trimSeconds, finalTarget)
                } else if (videoFile != finalTarget) {
                    videoFile.renameTo(finalTarget)
                }

                // Register final file in Android MediaStore so it immediately appears in phone's Gallery
                if (finalTarget.exists() && finalTarget.length() > 0) {
                    VideoRepository.getInstance(applicationContext).scanFileForGallery(finalTarget)
                }
            } else {
                // Delete orphaned 0-byte files if any
                if (videoFile?.exists() == true && videoFile.length() == 0L) {
                    videoFile.delete()
                }
                if (audioFile?.exists() == true) {
                    audioFile.delete()
                }
            }

            VideoRepository.getInstance(applicationContext).refreshVideos()

            _recorderState.value = RecorderState(status = RecorderStatus.IDLE)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cleanup() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (_: Exception) {}

        try {
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (_: Exception) {}

        try {
            internalAudioRecorder?.stop()
            internalAudioRecorder = null
        } catch (_: Exception) {}

        try {
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
            projectionCallback = null
        } catch (_: Exception) {}

        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Exception) {}
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
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenRecorderService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, ScreenCraftApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_qs_record)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_action_stop), stopPendingIntent)

        return builder.build()
    }

    private fun formatSeconds(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        cleanup()
        _recorderState.value = RecorderState(status = RecorderStatus.IDLE)
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
