package com.example.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AudioMode
import com.example.model.RecordingSettings
import com.example.model.ResolutionOption
import com.example.ui.theme.AccentSuccess
import com.example.ui.theme.StudioBlue
import com.example.ui.theme.StudioRed

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: RecordingSettings,
    onSettingsChanged: (RecordingSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section: Video Quality
        item {
            SettingsSectionHeader(
                icon = Icons.Default.HighQuality,
                title = "Video Quality & Resolution",
                subtitle = "Optimized for 720×1520 display and customizable for all devices"
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Resolution",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    ResolutionOption.values().forEach { option ->
                        val isSelected = settings.resolution == option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    onSettingsChanged(settings.copy(resolution = option))
                                }
                                .padding(vertical = 8.dp, horizontal = 6.dp)
                                .testTag("resolution_option_${option.name}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSettingsChanged(settings.copy(resolution = option)) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = option.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Frame Rate (FPS)
                    Text(
                        text = "Frame Rate (FPS)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(60, 30, 24).forEach { fps ->
                            SelectableChip(
                                text = "$fps FPS",
                                isSelected = settings.fps == fps,
                                onClick = { onSettingsChanged(settings.copy(fps = fps)) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("fps_chip_$fps")
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Bitrate
                    Text(
                        text = "Video Bitrate (Quality)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            16 to "16M",
                            12 to "12M",
                            8 to "8M",
                            4 to "4M"
                        ).forEach { (mbps, label) ->
                            SelectableChip(
                                text = label,
                                isSelected = settings.bitrateMbps == mbps,
                                onClick = { onSettingsChanged(settings.copy(bitrateMbps = mbps)) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("bitrate_chip_$mbps")
                            )
                        }
                    }
                }
            }
        }

        // Section: Audio Configuration
        item {
            SettingsSectionHeader(
                icon = Icons.Default.GraphicEq,
                title = "Audio Recording Mode",
                subtitle = "Three flexible modes with clean internal sound isolation"
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    AudioMode.values().forEach { mode ->
                        val isSelected = settings.audioMode == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSettingsChanged(settings.copy(audioMode = mode)) }
                                .background(if (isSelected) StudioBlue.copy(alpha = 0.08f) else Color.Transparent)
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) StudioBlue else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp)
                                .testTag("audio_mode_${mode.name}"),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = when (mode) {
                                    AudioMode.INTERNAL_ONLY -> Icons.Default.VolumeUp
                                    AudioMode.INTERNAL_AND_MIC -> Icons.Default.Mic
                                    AudioMode.MUTED -> Icons.Default.VolumeMute
                                },
                                contentDescription = null,
                                tint = if (isSelected) StudioBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mode.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) StudioBlue else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = mode.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = StudioBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Compatibility Note Card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                text = "Audio Compatibility (Android 8.1+ & 10+)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = StudioBlue
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "On Android 8.1+, internal audio captures whatever plays inside the phone in 48kHz high-definition stereo without using the microphone. Ambient room noise, fan hum, and breathing are digitally filtered out so only crystal-clear in-app sound is recorded.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // Section: Timing & Auto-Trimming
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Timer,
                title = "Start Delay & Auto-Trimming",
                subtitle = "Eliminate awkward start countdowns and end button taps"
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Start Delay
                    Text(
                        text = "Start Delay Countdown",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Countdown seconds before recording starts after tapping toggle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0 to "None", 3 to "3s", 5 to "5s", 10 to "10s").forEach { (sec, label) ->
                            SelectableChip(
                                text = label,
                                isSelected = settings.startDelaySeconds == sec,
                                onClick = { onSettingsChanged(settings.copy(startDelaySeconds = sec)) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("start_delay_chip_$sec")
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

                    // Stop Delay & Video Trimming
                    Text(
                        text = "Stop Delay & Video Trimming",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Automatically cuts the configured seconds from the END of the video when you tap stop, so notification pull-down or stopping clicks are never shown in your final video.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0 to "0s (Off)", 1 to "1 sec", 2 to "2 sec", 3 to "3 sec", 5 to "5 sec").forEach { (sec, label) ->
                            SelectableChip(
                                text = label,
                                isSelected = settings.stopTrimSeconds == sec,
                                onClick = { onSettingsChanged(settings.copy(stopTrimSeconds = sec)) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("stop_trim_chip_$sec")
                            )
                        }
                    }
                }
            }
        }

        // Section: Privacy & Recent Apps
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Security,
                title = "Privacy & System Behavior",
                subtitle = "Multitasking and recent apps screen settings"
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Hide from Recent Apps",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "ScreenCraft will not appear in the Recent Apps multitasking carousel during recording, keeping your task switcher clean.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Switch(
                            checked = settings.hideFromRecents,
                            onCheckedChange = { onSettingsChanged(settings.copy(hideFromRecents = it)) },
                            modifier = Modifier.testTag("hide_recents_switch")
                        )
                    }
                }
            }
        }

        // Section: Device & Display Specs (720x1520 compliance)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(StudioBlue.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = StudioBlue,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "Display Target: 720×1520 • Android 8.1+",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "App UI automatically scales and fits 720×1520 and any screen aspect ratio with zero clipping.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(StudioRed.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = StudioRed,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun SelectableChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(if (isSelected) StudioRed else MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            fontSize = 12.sp
        )
    }
}
