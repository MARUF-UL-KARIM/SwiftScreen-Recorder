package com.example.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.example.R
import com.example.ui.permission.ScreenCapturePermissionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class ScreenRecordTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            ScreenRecorderService.recorderState.collectLatest { state ->
                updateTileState(state)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        stateJob?.cancel()
    }

    override fun onClick() {
        super.onClick()

        if (ScreenRecorderService.isRecording()) {
            // Stop recording immediately
            val stopIntent = Intent(this, ScreenRecorderService::class.java).apply {
                action = ScreenRecorderService.ACTION_STOP
            }
            startService(stopIntent)
        } else {
            // Start recording
            val cachedCode = ScreenRecorderService.cachedResultCode
            val cachedData = ScreenRecorderService.cachedResultData

            if (cachedCode != null && cachedData != null) {
                // We have cached projection credentials; start recording immediately in background!
                val startIntent = Intent(this, ScreenRecorderService::class.java).apply {
                    action = ScreenRecorderService.ACTION_START
                    putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, cachedCode)
                    putExtra(ScreenRecorderService.EXTRA_RESULT_DATA, cachedData)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(startIntent)
                } else {
                    startService(startIntent)
                }
            } else {
                // Launch translucent permission activity directly from QS tile
                val permIntent = Intent(this, ScreenCapturePermissionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        this,
                        0,
                        permIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(permIntent)
                }
            }
        }
    }

    private fun updateTileState(state: RecorderState) {
        val tile = qsTile ?: return
        val isRecording = state.status == RecorderStatus.RECORDING ||
                state.status == RecorderStatus.PAUSED ||
                state.status == RecorderStatus.COUNTDOWN

        tile.state = if (isRecording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_record)

        tile.label = when (state.status) {
            RecorderStatus.RECORDING -> getString(R.string.tile_recording_active)
            RecorderStatus.COUNTDOWN -> "Starting (${state.countdownSeconds}s)"
            RecorderStatus.PAUSED -> "Paused"
            RecorderStatus.STOPPING -> "Saving..."
            RecorderStatus.IDLE -> getString(R.string.tile_recording_inactive)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isRecording) "Tap to stop" else "Tap to record"
        }

        tile.updateTile()
    }
}
