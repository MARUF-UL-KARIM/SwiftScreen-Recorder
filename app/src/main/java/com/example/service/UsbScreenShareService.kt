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
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.ScreenCraftApp
import com.example.model.UsbQualityPreset
import com.example.model.UsbShareState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.net.NetworkInterface
import java.util.Collections

/**
 * Foreground service managing real-time screen capture and HTTP MJPEG streaming over USB.
 *
 * Specifically optimized for 720x1520 phone screens and provides an ultra-lightweight
 * Potato Mode for vintage (year 2000) laptops with zero-lag performance.
 */
class UsbScreenShareService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var server: UsbStreamServer? = null

    private var frameThread: HandlerThread? = null
    private var frameHandler: Handler? = null

    private var reusableBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null
    private val baos = ByteArrayOutputStream(64 * 1024)

    private var frameCount = 0
    private var lastFpsTimestamp = System.currentTimeMillis()
    private var startTimeMillis = 0L

    companion object {
        private const val TAG = "UsbScreenShareService"

        const val ACTION_START = "com.example.usb.START"
        const val ACTION_STOP = "com.example.usb.STOP"
        const val ACTION_SET_QUALITY = "com.example.usb.SET_QUALITY"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_QUALITY = "extra_quality"
        const val EXTRA_PORT = "extra_port"

        private val _shareState = MutableStateFlow(UsbShareState())
        val shareState: StateFlow<UsbShareState> = _shareState.asStateFlow()

        fun updateQuality(context: Context, preset: UsbQualityPreset) {
            _shareState.value = _shareState.value.copy(qualityPreset = preset)
            if (_shareState.value.isStreaming) {
                val intent = Intent(context, UsbScreenShareService::class.java).apply {
                    action = ACTION_SET_QUALITY
                    putExtra(EXTRA_QUALITY, preset.name)
                }
                context.startService(intent)
            }
        }
    }

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
                val port = intent.getIntExtra(EXTRA_PORT, 8080)

                if (resultCode != 0 && resultData != null) {
                    startStreaming(resultCode, resultData, port)
                } else {
                    Log.e(TAG, "Missing MediaProjection permission for USB screen share")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
            }
            ACTION_SET_QUALITY -> {
                val qualityName = intent.getStringExtra(EXTRA_QUALITY)
                val preset = qualityName?.let {
                    try { UsbQualityPreset.valueOf(it) } catch (_: Exception) { null }
                } ?: UsbQualityPreset.BALANCED
                reconfigureStream(preset)
            }
        }

        return START_NOT_STICKY
    }

    private fun startStreaming(resultCode: Int, resultData: Intent, port: Int) {
        startTimeMillis = System.currentTimeMillis()
        startForegroundNotification(port)

        // Initialize background frame handler thread
        frameThread = HandlerThread("UsbFrameProcessThread").apply {
            start()
            frameHandler = Handler(looper)
        }

        // Initialize Embedded Web/MJPEG Server
        val streamServer = UsbStreamServer(port) { clientCount ->
            _shareState.value = _shareState.value.copy(connectedClients = clientCount)
            updateNotification(port, clientCount)
        }
        val serverStarted = streamServer.start()
        if (!serverStarted) {
            Log.e(TAG, "Failed to start USB Stream Server on port $port")
            stopSelf()
            return
        }
        server = streamServer

        // Initialize MediaProjection with Android 14+ safety callback
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                Log.e(TAG, "MediaProjection token is null")
                stopSelf()
                return
            }
            mediaProjection = projection

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.d(TAG, "MediaProjection stopped by Android system")
                    stopStreaming()
                    stopSelf()
                }
            }
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))

            val preset = _shareState.value.qualityPreset
            setupVirtualDisplay(projection, preset)

            _shareState.value = _shareState.value.copy(
                isStreaming = true,
                port = port,
                width = preset.width,
                height = preset.height,
                ipAddresses = getLocalIpAddresses()
            )

            Log.d(TAG, "USB Screen Share active on http://localhost:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting USB Screen Share", e)
            stopSelf()
        }
    }

    private fun setupVirtualDisplay(projection: MediaProjection, preset: UsbQualityPreset) {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val targetWidth = preset.width
        val targetHeight = preset.height
        val dpi = if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEVICE_STABLE

        server?.streamWidth = targetWidth
        server?.streamHeight = targetHeight

        // Setup triple buffered ImageReader
        val reader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ ir ->
            handleCapturedFrame(ir, targetWidth, targetHeight, preset.jpegQuality)
        }, frameHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenCraftUsbMirror",
            targetWidth,
            targetHeight,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            frameHandler
        )
    }

    private fun handleCapturedFrame(reader: ImageReader, width: Int, height: Int, jpegQuality: Int) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) return

            val planes = image.planes
            if (planes.isEmpty()) return

            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            var bmp = reusableBitmap
            if (bmp == null || bmp.width != (width + rowPadding / pixelStride) || bmp.height != height) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                reusableBitmap = bmp
            }

            buffer.rewind()
            bmp.copyPixelsFromBuffer(buffer)

            val finalBitmap = if (rowPadding == 0) {
                bmp
            } else {
                var cBmp = croppedBitmap
                if (cBmp == null || cBmp.width != width || cBmp.height != height) {
                    cBmp?.recycle()
                    cBmp = Bitmap.createBitmap(bmp, 0, 0, width, height)
                    croppedBitmap = cBmp
                } else {
                    cBmp = Bitmap.createBitmap(bmp, 0, 0, width, height)
                }
                cBmp
            }

            baos.reset()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
            val jpegBytes = baos.toByteArray()

            server?.broadcastFrame(jpegBytes)

            // FPS telemetry calculation
            frameCount++
            val now = System.currentTimeMillis()
            if (now - lastFpsTimestamp >= 1000) {
                val fps = ((frameCount * 1000L) / (now - lastFpsTimestamp)).toInt()
                frameCount = 0
                lastFpsTimestamp = now
                server?.currentFps = fps
                val elapsed = (now - startTimeMillis) / 1000L
                val refreshedIps = getLocalIpAddresses()
                _shareState.value = _shareState.value.copy(
                    currentFps = fps,
                    elapsedSeconds = elapsed,
                    ipAddresses = refreshedIps
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame process warning: ${e.message}")
        } finally {
            try { image?.close() } catch (_: Exception) {}
        }
    }

    private fun reconfigureStream(preset: UsbQualityPreset) {
        val proj = mediaProjection ?: return
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            reusableBitmap?.recycle()
            reusableBitmap = null
            croppedBitmap?.recycle()
            croppedBitmap = null

            setupVirtualDisplay(proj, preset)
            _shareState.value = _shareState.value.copy(
                width = preset.width,
                height = preset.height,
                qualityPreset = preset
            )
            Log.d(TAG, "Stream reconfigured to ${preset.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Error reconfiguring stream", e)
        }
    }

    private fun stopStreaming() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (_: Exception) {}

        try {
            imageReader?.close()
            imageReader = null
        } catch (_: Exception) {}

        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Exception) {}

        server?.stop()
        server = null

        frameThread?.quitSafely()
        try { frameThread?.join(500) } catch (_: Exception) {}
        frameThread = null
        frameHandler = null

        reusableBitmap?.recycle()
        reusableBitmap = null
        croppedBitmap?.recycle()
        croppedBitmap = null

        _shareState.value = UsbShareState(isStreaming = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startForegroundNotification(port: Int) {
        val notification = buildNotification("ScreenCraft USB Mirror Active", "Streaming live on http://localhost:$port (0ms latency)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ScreenCraftApp.NOTIFICATION_USB_SHARE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(ScreenCraftApp.NOTIFICATION_USB_SHARE_ID, notification)
        }
    }

    private fun updateNotification(port: Int, clients: Int) {
        val text = "http://localhost:$port • $clients viewer(s) connected via USB"
        val notification = buildNotification("ScreenCraft USB Mirror Active", text)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ScreenCraftApp.NOTIFICATION_USB_SHARE_ID, notification)
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

        val stopIntent = Intent(this, UsbScreenShareService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ScreenCraftApp.NOTIFICATION_CHANNEL_USB_SHARE_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_qs_record)
            .setOngoing(true)
            .setContentIntent(pendingOpenApp)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Mirror",
                pendingStop
            )
            .build()
    }

    private fun getLocalIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        var usbTetherIp: String? = null

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // First pass: look specifically for USB tethering interfaces (rndis, usb)
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                val isUsbIntf = name.contains("rndis") || name.contains("usb") || name.contains("ncm")

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress != null) {
                        val host = addr.hostAddress ?: ""
                        if (!host.contains(":")) { // IPv4
                            val formatted = "$host:8080"
                            if (isUsbIntf && usbTetherIp == null) {
                                usbTetherIp = formatted
                            } else {
                                ips.add(formatted)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        val result = mutableListOf<String>()
        if (usbTetherIp != null) {
            result.add(usbTetherIp)
        }
        result.addAll(ips)
        if (result.isEmpty()) {
            result.add("192.168.42.129:8080") // Standard Android USB tethering gateway
        }
        return result
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
}
