package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.RecordingSettings
import com.example.service.RecorderState
import com.example.service.RecorderStatus
import com.example.ui.theme.AccentSuccess
import com.example.ui.theme.StudioBlue
import com.example.ui.theme.StudioRed
import java.util.Locale

@Composable
fun RecordingHeroCard(
    recorderState: RecorderState,
    settings: RecordingSettings,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onPauseResume: () -> Unit,
    onOpenQsGuide: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRecording = recorderState.status == RecorderStatus.RECORDING
    val isPaused = recorderState.status == RecorderStatus.PAUSED
    val isCountdown = recorderState.status == RecorderStatus.COUNTDOWN
    val isStopping = recorderState.status == RecorderStatus.STOPPING

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = 1.dp,
                color = if (isRecording) StudioRed else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(20.dp)
            )
            .testTag("recording_hero_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Status and QS badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isRecording -> StudioRed.copy(alpha = pulseAlpha)
                                    isCountdown -> StudioBlue
                                    isPaused -> Color(0xFFF59E0B)
                                    isStopping -> Color(0xFF8B5CF6)
                                    else -> AccentSuccess
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            isRecording -> "RECORDING LIVE"
                            isCountdown -> "STARTING COUNTDOWN"
                            isPaused -> "RECORDING PAUSED"
                            isStopping -> "SAVING & TRIMMING"
                            else -> "READY TO RECORD"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isRecording -> StudioRed
                            isCountdown -> StudioBlue
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Surface(
                    onClick = onOpenQsGuide,
                    shape = RoundedCornerShape(12.dp),
                    color = StudioBlue.copy(alpha = 0.12f),
                    modifier = Modifier.testTag("qs_guide_pill_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Quick Settings guide",
                            tint = StudioBlue,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "QS Tile Guide",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Display (Timer or Countdown or Ready)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(vertical = 14.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isCountdown -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${recorderState.countdownSeconds}",
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Black,
                                color = StudioBlue,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Recording begins in seconds...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    isRecording || isPaused -> {
                        val m = recorderState.elapsedSeconds / 60
                        val s = recorderState.elapsedSeconds % 60
                        val formattedTime = String.format(Locale.US, "%02d:%02d", m, s)

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = formattedTime,
                                fontSize = 44.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isPaused) Color(0xFFF59E0B) else StudioRed,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = if (isPaused) "Paused • Tap Resume" else "Auto-trimming ${settings.stopTrimSeconds}s on stop",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    isStopping -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp,
                                color = StudioRed
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Trimming & saving video...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    else -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "ScreenCraft Studio",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Or swipe down to use the Quick Settings toggle anytime",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Settings Summary Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SettingMiniBadge(
                    label = if (settings.resolution.isMatchScreen) "720×1520" else settings.resolution.name.replace("_", " "),
                    modifier = Modifier.weight(1f)
                )
                SettingMiniBadge(
                    label = "${settings.fps} FPS",
                    modifier = Modifier.weight(1f)
                )
                SettingMiniBadge(
                    label = "${settings.bitrateMbps} Mbps",
                    modifier = Modifier.weight(1f)
                )
                SettingMiniBadge(
                    label = if (settings.stopTrimSeconds > 0) "Trim -${settings.stopTrimSeconds}s" else "No trim",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            if (isRecording || isPaused) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onPauseResume,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pause_resume_recording_button")
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isPaused) "Resume" else "Pause",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isPaused) "Resume" else "Pause")
                    }

                    Button(
                        onClick = onStopRecording,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("stop_recording_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = StudioRed)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop Recording",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Stop & Save")
                    }
                }
            } else if (isCountdown) {
                Button(
                    onClick = onStopRecording,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cancel_countdown_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.outline)
                ) {
                    Text("Cancel Countdown")
                }
            } else {
                Button(
                    onClick = onStartRecording,
                    enabled = !isStopping,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("start_recording_hero_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = StudioRed)
                ) {
                    Icon(
                        imageVector = Icons.Default.FiberManualRecord,
                        contentDescription = "Start recording",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (settings.startDelaySeconds > 0) "Record (${settings.startDelaySeconds}s delay)" else "Record Now",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingMiniBadge(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            fontSize = 11.sp
        )
    }
}
