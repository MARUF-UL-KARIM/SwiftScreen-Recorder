package com.example.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.AccentSuccess
import com.example.ui.theme.StudioBlue

@Composable
fun Android8AudioGuideDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(20.dp))
                .testTag("android8_audio_guide_dialog"),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(StudioBlue.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Headphones,
                                contentDescription = null,
                                tint = StudioBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Internal Audio & AirPod Trick",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Android 8.1+ অডিও সিস্টেম গাইড",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioBlue
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_audio_guide")) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Explanatory Intro
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "অ্যান্ড্রয়েড ৮.১-এ সিস্টেম লেভেলে থার্ড-পার্টি অ্যাপের জন্য ইন্টারনাল অডিও ব্লক থাকে। কিন্তু ScreenCraft বিশেষ স্মার্ট ফিল্টার এবং হার্ডওয়্যার ট্রিক দিয়ে নিখুঁত অডিও নিশ্চিত করে:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Item 1: AirPod System
                AudioGuideItem(
                    icon = Icons.Default.Headphones,
                    iconColor = StudioBlue,
                    title = "১. এয়ারপড / হেডফোন সিস্টেম",
                    description = "এয়ারপড হলো আউটপুট ডিভাইস। ফোন এয়ারপডে সরাসরি মিউজিক বা গেমের ডিজিটাল সাউন্ড পাঠায়। ফোনে কোনো এয়ারপড বা ইয়ারফোন কানেক্ট করা থাকলে ScreenCraft হেডসেট পাথ ব্যবহার করে বাইরের নয়েজ এড়িয়ে নিখুঁত সাউন্ড ধরে।"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Item 2: Microphone Noise Ignorer & DSP Filter
                AudioGuideItem(
                    icon = Icons.Default.MicOff,
                    iconColor = AccentSuccess,
                    title = "২. মাইক্রোফোন ভয়েস ইগনোর ফিল্টার",
                    description = "আপনি যখন 'Internal Audio Only' সিলেক্ট করেন, তখন অ্যাপের রিয়েল-টাইম DSP Noise Gate সক্রিয় হয়। এটি ঘরের ফ্যানের শব্দ, নিঃশ্বাস বা পেছনের হালকা কথার আওয়াজ ডিজিটালি কেটে বাদ দেয় এবং ফোনের গেম/মিউজিককে প্রাধান্য দেয়।"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Item 3: Perfect A/V Sync
                AudioGuideItem(
                    icon = Icons.Default.Sync,
                    iconColor = StudioBlue,
                    title = "৩. নিখুঁত অডিও-ভিডিও সিঙ্ক (0ms Delay)",
                    description = "আমাদের এনকোডারে প্রতিটি ভিডিও ফ্রেম ও অডিও স্যাম্পলের টাইমস্ট্যাম্প ০ মাইক্রোসেকেন্ড থেকে একযোগে শুরু হয়। ফলে ভিডিও আগে চলে যাওয়া বা অডিও পিছিয়ে পড়ার সমস্যা একদম হবে না।"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Item 4: Pro Hardware Loopback Tip
                AudioGuideItem(
                    icon = Icons.Default.Hearing,
                    iconColor = MaterialTheme.colorScheme.primary,
                    title = "৪. প্রো ট্রিক (Earphone / Loopback)",
                    description = "আপনি যদি ১০০% বাইরের আওয়াজহীন ডিজিটাল সাউন্ড চান, তবে একটি সাধারণ ইয়ারফোন বা লুপব্যাক ক্যাবল ফোনে প্লাগ ইন করুন। তাহলে কোনো ফ্যানের আওয়াজ ছাড়াই রেকর্ড হবে!"
                )

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("got_it_audio_guide_button")
                ) {
                    Text("বুঝেছি (Got It)")
                }
            }
        }
    }
}

@Composable
private fun AudioGuideItem(
    icon: ImageVector,
    iconColor: androidx.compose.ui.graphics.Color,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(iconColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )
        }
    }
}
