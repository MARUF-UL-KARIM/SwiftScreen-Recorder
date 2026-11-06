package com.example.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.ScreenCraftApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * Service to manage MediaProjection screen capture permissions, lifecycle,
 * and high-performance frame buffering for screen recording on Android 8.1+.
 *
 * Calibrated specifically for 720x1520 resolution with multi-buffered ImageReader pool
 * to prevent frame drops, memory leaks, and UI thread stutters.
 */
class MediaProjectionFrameService : Service() {

    private val binder = LocalBinder()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var captureWidth: Int = DEFAULT_WIDTH
    private var captureHeight: Int = DEFAULT_HEIGHT
    private var captureDpi: Int = DEFAULT_DPI

    inner class LocalBinder : Binder() {
        fun getService(): MediaProjectionFrameService = this@MediaProjectionFrameService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startBackgroundThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                captureWidth = intent.getIntExtra(EXTRA_WIDTH, DEFAULT_WIDTH)
                captureHeight = intent.getIntExtra(EXTRA_HEIGHT, DEFAULT_HEIGHT)
                captureDpi = intent.getIntExtra(EXTRA_DPI, DEFAULT_DPI)

                if (resultCode != 0 && resultData != null) {
                    cachedResultCode = resultCode
                    cachedResultData = resultData
                    startForegroundNotification()
                    val initialized = initMediaProjection(resultCode, resultData)
                    if (initialized) {
                        startFrameBuffering(captureWidth, captureHeight, captureDpi)
                    } else {
                        stopSelf()
                    }
                } else {
                    Log.e(TAG, "Missing MediaProjection permission credentials")
                    stopSelf()
                }
            }
            ACTION_STOP_CAPTURE -> {
                stopCapture()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("FrameBufferThread").apply {
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
        } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
    }

    private fun startForegroundNotification() {
        val notification = buildNotification(getString(R.string.notification_recording_title), "Screen capture buffering active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MediaProjectionFrameService::class.java).apply {
            action = ACTION_STOP_CAPTURE
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ScreenCraftApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_qs_record)
            .setOngoing(true)
            .setContentIntent(pendingOpenApp)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_stop),
                pendingStop
            )
            .build()
    }

    /**
     * Initializes the MediaProjection instance with callback handling required for Android 14+.
     */
    fun initMediaProjection(resultCode: Int, resultData: Intent): Boolean {
        return try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                Log.e(TAG, "Failed to retrieve MediaProjection token")
                _captureState.value = CaptureState.ERROR
                return false
            }

            mediaProjection = projection

            // Android 14+ strict rule: register callback before createVirtualDisplay
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.d(TAG, "MediaProjection stopped by Android system")
                    stopCapture()
                }
            }
            projectionCallback = callback
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))

            _captureState.value = CaptureState.READY
            Log.d(TAG, "MediaProjection successfully initialized and registered")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MediaProjection", e)
            _captureState.value = CaptureState.ERROR
            false
        }
    }

    /**
     * Sets up a high-performance ImageReader frame buffer pool and connects a VirtualDisplay.
     * Uses a triple-buffer pool (maxImages = 3) with PixelFormat.RGBA_8888.
     */
    fun startFrameBuffering(
        width: Int = captureWidth,
        height: Int = captureHeight,
        dpi: Int = captureDpi,
        customSurface: Surface? = null
    ): Surface? {
        val projection = mediaProjection ?: run {
            Log.e(TAG, "Cannot start frame buffering: MediaProjection is null")
            return null
        }

        try {
            // Clean up previous buffer if active
            releaseVirtualDisplayAndBuffer()

            val targetSurface: Surface
            if (customSurface != null) {
                targetSurface = customSurface
            } else {
                // Triple buffering for high throughput without memory bloat
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
                imageReader = reader

                reader.setOnImageAvailableListener({ ir ->
                    handleNewFrame(ir)
                }, backgroundHandler)

                targetSurface = reader.surface
            }

            // Create VirtualDisplay attached to the frame buffer surface
            virtualDisplay = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                targetSurface,
                null,
                backgroundHandler
            )

            _captureState.value = CaptureState.CAPTURING
            Log.d(TAG, "VirtualDisplay frame buffer initialized: ${width}x${height} @ ${dpi}dpi")
            return targetSurface
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup VirtualDisplay frame buffer", e)
            _captureState.value = CaptureState.ERROR
            return null
        }
    }

    /**
     * Processes incoming frames from the ImageReader pool on the background handler thread.
     */
    private fun handleNewFrame(reader: ImageReader) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) return

            val planes = image.planes
            if (planes.isNotEmpty()) {
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride

                val frame = BufferedFrame(
                    width = image.width,
                    height = image.height,
                    timestampNs = image.timestamp,
                    buffer = buffer,
                    pixelStride = pixelStride,
                    rowStride = rowStride
                )

                // Dispatch to active frame listener
                frameListener?.invoke(frame)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame buffer processing warning: ${e.message}")
        } finally {
            try {
                image?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Releases virtual display and reader buffers cleanly.
     */
    private fun releaseVirtualDisplayAndBuffer() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {
            Log.w(TAG, "VirtualDisplay release: ${e.message}")
        }

        try {
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.w(TAG, "ImageReader close: ${e.message}")
        }
    }

    /**
     * Stops screen capture and releases all resources safely.
     */
    fun stopCapture() {
        releaseVirtualDisplayAndBuffer()

        try {
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
            projectionCallback = null
        } catch (_: Exception) {}

        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Exception) {}

        _captureState.value = CaptureState.IDLE
        Log.d(TAG, "MediaProjectionFrameService capture stopped and cleaned up")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        stopBackgroundThread()
    }

    fun getMediaProjection(): MediaProjection? = mediaProjection
    fun getVirtualDisplay(): VirtualDisplay? = virtualDisplay
    fun isCapturing(): Boolean = _captureState.value == CaptureState.CAPTURING

    companion object {
        private const val TAG = "MediaProjectionFrameSvc"
        private const val NOTIFICATION_ID = 9021
        private const val VIRTUAL_DISPLAY_NAME = "ScreenCraftFrameBuffer"

        const val DEFAULT_WIDTH = 720
        const val DEFAULT_HEIGHT = 1520
        const val DEFAULT_DPI = 320

        const val ACTION_START_CAPTURE = "com.example.service.action.START_FRAME_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.example.service.action.STOP_FRAME_CAPTURE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DPI = "extra_dpi"

        // Cached permission token so features can instantly acquire capture permissions
        var cachedResultCode: Int? = null
        var cachedResultData: Intent? = null

        private val _captureState = MutableStateFlow(CaptureState.IDLE)
        val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

        private var frameListener: ((BufferedFrame) -> Unit)? = null

        fun setFrameListener(listener: ((BufferedFrame) -> Unit)?) {
            frameListener = listener
        }

        fun hasCachedPermission(): Boolean {
            return cachedResultCode != null && cachedResultData != null
        }

        /**
         * Convenience helper to launch frame buffer capture service
         */
        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            width: Int = DEFAULT_WIDTH,
            height: Int = DEFAULT_HEIGHT,
            dpi: Int = DEFAULT_DPI
        ) {
            val intent = Intent(context, MediaProjectionFrameService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_DPI, dpi)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MediaProjectionFrameService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            }
            context.startService(intent)
        }
    }
}

enum class CaptureState {
    IDLE,
    READY,
    CAPTURING,
    ERROR
}

/**
 * Representation of a captured frame buffer with row/pixel stride handling for 720x1520 screens.
 */
data class BufferedFrame(
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val buffer: ByteBuffer,
    val pixelStride: Int,
    val rowStride: Int
) {
    /**
     * Converts the raw buffer into a recycled or newly allocated Bitmap safely.
     */
    fun toBitmap(): Bitmap? {
        return try {
            val pixelPadding = (rowStride - pixelStride * width) / pixelStride
            val bitmap = Bitmap.createBitmap(
                width + pixelPadding,
                height,
                Bitmap.Config.ARGB_8888
            )
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            if (pixelPadding == 0) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, 0, 0, width, height)
            }
        } catch (e: Exception) {
            Log.w("BufferedFrame", "Failed to convert frame to bitmap: ${e.message}")
            null
        }
    }
}
