package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.RecordedVideo
import com.example.ui.components.VideoPlayerDialog
import com.example.ui.components.shareVideo
import com.example.ui.theme.StudioBlue
import com.example.ui.theme.StudioRed

@Composable
fun RecordsScreen(
    videos: List<RecordedVideo>,
    onDeleteVideo: (RecordedVideo) -> Unit,
    onRenameVideo: (RecordedVideo, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedVideoForPlayback by remember { mutableStateOf<RecordedVideo?>(null) }
    var videoToDelete by remember { mutableStateOf<RecordedVideo?>(null) }
    var videoToRename by remember { mutableStateOf<RecordedVideo?>(null) }
    var renameInput by remember { mutableStateOf("") }

    Box(modifier = modifier.fillMaxSize()) {
        if (videos.isEmpty()) {
            EmptyRecordsPlaceholder(modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("records_list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Saved Recordings (${videos.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tap to play",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(videos, key = { it.file.absolutePath }) { video ->
                    RecordedVideoItemCard(
                        video = video,
                        onPlay = { selectedVideoForPlayback = video },
                        onShare = { shareVideo(context, video.file) },
                        onRename = {
                            videoToRename = video
                            renameInput = video.name
                        },
                        onDelete = { videoToDelete = video }
                    )
                }
            }
        }

        // Full-screen / In-app video player
        selectedVideoForPlayback?.let { video ->
            VideoPlayerDialog(
                video = video,
                onDismiss = { selectedVideoForPlayback = null }
            )
        }

        // Rename Dialog
        videoToRename?.let { video ->
            AlertDialog(
                onDismissRequest = { videoToRename = null },
                title = { Text("Rename Recording") },
                text = {
                    Column {
                        Text(
                            text = "Enter a new name for this video file:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = renameInput,
                            onValueChange = { renameInput = it },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("rename_input_field"),
                            label = { Text("Filename") }
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (renameInput.isNotBlank()) {
                                onRenameVideo(video, renameInput)
                            }
                            videoToRename = null
                        },
                        modifier = Modifier.testTag("confirm_rename_button")
                    ) {
                        Text("Rename")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { videoToRename = null },
                        modifier = Modifier.testTag("cancel_rename_button")
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Delete Confirmation Dialog
        videoToDelete?.let { video ->
            AlertDialog(
                onDismissRequest = { videoToDelete = null },
                title = { Text("Delete Recording?") },
                text = {
                    Text(
                        "Are you sure you want to permanently delete \"${video.name}\"? This action cannot be undone."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onDeleteVideo(video)
                            videoToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StudioRed),
                        modifier = Modifier.testTag("confirm_delete_button")
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { videoToDelete = null },
                        modifier = Modifier.testTag("cancel_delete_button")
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun RecordedVideoItemCard(
    video: RecordedVideo,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onPlay)
            .testTag("video_card_${video.name}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Video Thumbnail / Icon preview
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = "Play recording",
                    tint = StudioRed,
                    modifier = Modifier.size(34.dp)
                )

                // Duration badge
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = video.formattedDuration,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Information Column
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = video.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${video.formattedSize} • ${video.resolutionLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = video.formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            }

            // Quick Actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("share_video_${video.name}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = StudioBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onRename,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("rename_video_${video.name}")
                ) {
                    Icon(
                        imageVector = Icons.Default.DriveFileRenameOutline,
                        contentDescription = "Rename",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("delete_video_${video.name}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = StudioRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyRecordsPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(StudioRed.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = null,
                tint = StudioRed,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "No Recordings Yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Tap 'Record Now' above or use the Quick Settings toggle to capture your screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
