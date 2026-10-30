package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.SettingsRepository
import com.example.data.VideoRepository
import com.example.model.AudioMode
import com.example.service.RecorderStatus
import com.example.service.ScreenRecorderService
import com.example.ui.components.QuickSettingsGuideDialog
import com.example.ui.components.RecordingHeroCard
import com.example.ui.theme.StudioBlue
import com.example.ui.theme.StudioRed
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onSetExcludeFromRecents: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val videoRepository = remember { VideoRepository.getInstance(context) }

    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    val videos by videoRepository.videos.collectAsStateWithLifecycle()
    val recorderState by ScreenRecorderService.recorderState.collectAsStateWithLifecycle()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showQsGuide by remember { mutableStateOf(false) }

    // Initial load of saved videos
    LaunchedEffect(Unit) {
        videoRepository.refreshVideos()
    }

    // Update Recent Apps exclusion dynamically when recording status changes
    LaunchedEffect(recorderState.status, settings.hideFromRecents) {
        val isRecordingNow = recorderState.status == RecorderStatus.RECORDING ||
                recorderState.status == RecorderStatus.COUNTDOWN ||
                recorderState.status == RecorderStatus.PAUSED
        if (settings.hideFromRecents) {
            onSetExcludeFromRecents(isRecordingNow)
        } else {
            onSetExcludeFromRecents(false)
        }
    }

    // Launcher for Screen Capture MediaProjection
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val startIntent = Intent(context, ScreenRecorderService::class.java).apply {
                action = ScreenRecorderService.ACTION_START
                putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenRecorderService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
        } else {
            Toast.makeText(context, "Screen recording permission was not granted", Toast.LENGTH_SHORT).show()
        }
    }

    // Permission launcher for Audio and Notifications
    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = projectionManager.createScreenCaptureIntent()
        captureLauncher.launch(captureIntent)
    }

    fun startRecordingFlow() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (settings.audioMode != AudioMode.MUTED) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            val projectionManager =
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = projectionManager.createScreenCaptureIntent()
            captureLauncher.launch(captureIntent)
        }
    }

    fun stopRecordingFlow() {
        val stopIntent = Intent(context, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_STOP
        }
        context.startService(stopIntent)
    }

    fun pauseResumeFlow() {
        val isPaused = recorderState.status == RecorderStatus.PAUSED
        val action = if (isPaused) ScreenRecorderService.ACTION_RESUME else ScreenRecorderService.ACTION_PAUSE
        val intent = Intent(context, ScreenRecorderService::class.java).apply {
            this.action = action
        }
        context.startService(intent)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "ScreenCraft",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FiberManualRecord,
                            contentDescription = "ScreenCraft Logo",
                            tint = StudioRed,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showQsGuide = true },
                        modifier = Modifier.testTag("appbar_qs_guide_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Quick Settings guide",
                            tint = StudioBlue
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.testTag("bottom_navigation")
            ) {
                NavigationBarItem(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = "Recordings") },
                    label = { Text("Records (${videos.size})") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = StudioRed,
                        selectedTextColor = StudioRed,
                        indicatorColor = StudioRed.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.testTag("tab_records")
                )

                NavigationBarItem(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = StudioBlue,
                        selectedTextColor = StudioBlue,
                        indicatorColor = StudioBlue.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.testTag("tab_settings")
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .widthIn(max = 600.dp) // Optimized for 720x1520 and adaptive layout
        ) {
            // Persistent Recording Hero Card
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                RecordingHeroCard(
                    recorderState = recorderState,
                    settings = settings,
                    onStartRecording = { startRecordingFlow() },
                    onStopRecording = { stopRecordingFlow() },
                    onPauseResume = { pauseResumeFlow() },
                    onOpenQsGuide = { showQsGuide = true }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Main Tab Content
            Box(modifier = Modifier.weight(1f)) {
                if (selectedTabIndex == 0) {
                    RecordsScreen(
                        videos = videos,
                        onDeleteVideo = { video ->
                            coroutineScope.launch {
                                videoRepository.deleteVideo(video)
                            }
                        },
                        onRenameVideo = { video, newName ->
                            coroutineScope.launch {
                                videoRepository.renameVideo(video, newName)
                            }
                        }
                    )
                } else {
                    SettingsScreen(
                        settings = settings,
                        onSettingsChanged = { newSettings ->
                            settingsRepository.updateSettings(newSettings)
                        }
                    )
                }
            }
        }
    }

    if (showQsGuide) {
        QuickSettingsGuideDialog(onDismiss = { showQsGuide = false })
    }
}
