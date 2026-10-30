package com.example

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.data.SettingsRepository
import com.example.model.AudioMode
import com.example.service.ScreenRecorderService
import com.example.ui.screens.MainScreen
import com.example.ui.theme.ScreenCraftTheme

class MainActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager

    // Launcher for screen capture permission via MediaProjectionManager
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenRecorderService::class.java).apply {
                action = ScreenRecorderService.ACTION_START
                putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenRecorderService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            Toast.makeText(this, "Screen recording permission was not granted", Toast.LENGTH_SHORT).show()
        }
    }

    // Permission launcher for storage (API 26/27) and audio/notification permissions
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val storageGranted = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else true

        if (storageGranted) {
            val captureIntent = projectionManager.createScreenCaptureIntent()
            captureLauncher.launch(captureIntent)
        } else {
            Toast.makeText(this, "Storage permission is required to save recordings", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            ScreenCraftTheme {
                MainScreen(
                    onSetExcludeFromRecents = { exclude ->
                        setRecentAppsVisibility(exclude)
                    },
                    onRequestStartCapture = {
                        initiateScreenCapture()
                    }
                )
            }
        }
    }

    fun initiateScreenCapture() {
        val permissionsToRequest = mutableListOf<String>()

        // API 26/27 (Android 8/8.1) runtime storage permission verification
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        val settings = SettingsRepository.getInstance(this).settings.value
        if (settings.audioMode == AudioMode.INTERNAL_AND_MIC) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            val captureIntent = projectionManager.createScreenCaptureIntent()
            captureLauncher.launch(captureIntent)
        }
    }

    private fun setRecentAppsVisibility(exclude: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val tasks = am?.appTasks
                if (!tasks.isNullOrEmpty()) {
                    tasks[0].setExcludeFromRecents(exclude)
                    Log.d("MainActivity", "AppTask setExcludeFromRecents: $exclude")
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not set excludeFromRecents", e)
        }
    }
}
