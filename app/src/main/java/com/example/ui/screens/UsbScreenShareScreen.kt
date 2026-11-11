package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.UsbQualityPreset
import com.example.service.UsbScreenShareService
import com.example.ui.theme.AccentSuccess
import com.example.ui.theme.AccentWarning
import com.example.ui.theme.StudioBlue
import com.example.ui.theme.StudioRed
import java.util.Locale

@Composable
fun UsbScreenShareScreen(
    onRequestStartShare: () -> Unit,
    onStopShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val shareState by UsbScreenShareService.shareState.collectAsStateWithLifecycle()
    var copiedCommand by remember { mutableStateOf(false) }

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Copied: $text", Toast.LENGTH_SHORT).show()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("usb_screen_share_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Live Streaming Control Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("usb_hero_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Status Badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (shareState.isStreaming) AccentSuccess else Color.Gray)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (shareState.isStreaming) "LIVE USB MIRROR ACTIVE" else "USB SCREEN SHARE READY",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (shareState.isStreaming) AccentSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Title & Description
                    Text(
                        text = "Browser Screen Share",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "USB দিয়ে কানেক্ট করে ল্যাপটপ বা পিসির ব্রাউজারে ফোন স্ক্রিন দেখুন — ০ ল্যাগ, এমনকি ২০০০ সালের পুরানো কম্পিউটারেও ফুল স্পিড!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Live Stats Bar (when active)
                    AnimatedVisibility(visible = shareState.isStreaming) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(StudioBlue.copy(alpha = 0.08f))
                                .border(1.dp, StudioBlue.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("FPS (live ${shareState.liveFps})", style = MaterialTheme.typography.labelSmall, color = StudioBlue)
                                    Text(
                                        text = "${shareState.currentFps}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("LATENCY", style = MaterialTheme.typography.labelSmall, color = StudioBlue)
                                    Text(
                                        text = "< 1 ms",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentSuccess
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("RESOLUTION", style = MaterialTheme.typography.labelSmall, color = StudioBlue)
                                    Text(
                                        text = "${shareState.width}×${shareState.height}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("VIEWERS", style = MaterialTheme.typography.labelSmall, color = StudioBlue)
                                    Text(
                                        text = "${shareState.connectedClients}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentSuccess
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "ব্রাউজারে সরাসরি ওপেন করার লিংকসমূহ (No CMD needed):",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioBlue,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.align(Alignment.Start)
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // USB Tethering / Direct OTG Phone-to-Phone Links
                            val activeIps = shareState.ipAddresses.ifEmpty { listOf("192.168.42.129:8080") }
                            activeIps.forEach { ip ->
                                val fullUrl = if (ip.startsWith("http://")) ip else "http://$ip"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.5f))
                                        .border(1.dp, AccentSuccess.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .clickable { copyToClipboard("ScreenCraft Direct Link", fullUrl) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(
                                            Icons.Default.FlashOn,
                                            contentDescription = null,
                                            tint = AccentSuccess,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = fullUrl,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            Text(
                                                text = "CMD ছাড়া সরাসরি ব্রাউজারে চলবে (Phone / PC)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = AccentSuccess,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy Link",
                                        tint = AccentSuccess,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            // Also show localhost option
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.35f))
                                    .clickable { copyToClipboard("Localhost URL", "http://localhost:${shareState.port}") }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Laptop, contentDescription = null, tint = StudioBlue, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "http://localhost:${shareState.port}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = StudioBlue, modifier = Modifier.size(14.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Primary Start/Stop Button
                    if (shareState.isStreaming) {
                        Button(
                            onClick = onStopShare,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("btn_stop_usb_share"),
                            colors = ButtonDefaults.buttonColors(containerColor = StudioRed),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Stop USB Screen Share (বন্ধ করুন)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Button(
                            onClick = onRequestStartShare,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("btn_start_usb_share"),
                            colors = ButtonDefaults.buttonColors(containerColor = StudioBlue),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.ScreenShare, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Start USB Screen Share (শুরু করুন)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }
                }
            }
        }

        // Quality Preset Selector
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = StudioBlue, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Stream Quality & Performance Mode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    UsbQualityPreset.values().forEach { preset ->
                        val isSelected = shareState.qualityPreset == preset
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    UsbScreenShareService.updateQuality(context, preset)
                                }
                                .background(if (isSelected) StudioBlue.copy(alpha = 0.08f) else Color.Transparent)
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) StudioBlue else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (preset) {
                                            UsbQualityPreset.ULTRA_FAST_ZERO_LAG -> StudioRed.copy(alpha = 0.18f)
                                            UsbQualityPreset.POTATO_2000 -> AccentWarning.copy(alpha = 0.15f)
                                            UsbQualityPreset.BALANCED -> StudioBlue.copy(alpha = 0.15f)
                                            UsbQualityPreset.HIGH_NATIVE -> AccentSuccess.copy(alpha = 0.15f)
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when (preset) {
                                        UsbQualityPreset.ULTRA_FAST_ZERO_LAG -> Icons.Default.FlashOn
                                        UsbQualityPreset.POTATO_2000 -> Icons.Default.Speed
                                        UsbQualityPreset.BALANCED -> Icons.Default.Laptop
                                        UsbQualityPreset.HIGH_NATIVE -> Icons.Default.ScreenShare
                                    },
                                    contentDescription = null,
                                    tint = when (preset) {
                                        UsbQualityPreset.ULTRA_FAST_ZERO_LAG -> StudioRed
                                        UsbQualityPreset.POTATO_2000 -> AccentWarning
                                        UsbQualityPreset.BALANCED -> StudioBlue
                                        UsbQualityPreset.HIGH_NATIVE -> AccentSuccess
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) StudioBlue else Color.White
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = preset.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 16.sp
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
                }
            }
        }

        // Step-by-Step USB Connection Guide Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cable, contentDescription = null, tint = AccentSuccess, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "CMD ছাড়া সরাসরি লিংক দিয়ে চালানোর নিয়ম",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Phone থেকে Phone বা Laptop এর ব্রাউজারে CMD ছাড়াই নিমেষেই কানেক্ট করুন:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Mode A: Phone to Phone (OTG / USB Tethering)
                    GuideStepCard(
                        step = "A",
                        title = "ফোন থেকে অন্য ফোনে স্ক্রিন শেয়ার (Phone to Phone OTG)",
                        description = "একটি টাইপ-সি কেবল বা OTG দিয়ে দুটি ফোন একসাথে লাগান। আপনার ফোনে Settings > Network/Hotspot > USB Tethering অন করুন। অন্য ফোনের যেকোনো ব্রাউজারে (Chrome) গিয়ে উপরে দেওয়া লিংকটি (যেমন http://192.168.42.129:8080) লিখুন — ব্যাস সাথে সাথে স্ক্রিন দেখা যাবে! কোনো CMD লাগবে না।"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Mode B: Phone to Laptop without CMD (USB Tethering)
                    GuideStepCard(
                        step = "B",
                        title = "ল্যাপটপে CMD ছাড়া সরাসরি লিংক দিয়ে চালানো",
                        description = "ডাটা ক্যাবল লাগানোর পর ফোনে Settings > USB Tethering (ইউএসবি টেথারিং) চালু করে দিন। ল্যাপটপের ব্রাউজারে সরাসরি উপরের সবুজ লিংকটিতে (http://192.168.42.129:8080) ঢুকুন। কোনো CMD বা কোড লেখার প্রয়োজন নেই!",
                        actionButtonText = "Open Tethering Settings",
                        onAction = {
                            try {
                                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                try {
                                    val tetherIntent = Intent().apply {
                                        setClassName("com.android.settings", "com.android.settings.TetherSettings")
                                    }
                                    context.startActivity(tetherIntent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Settings থেকে USB Tethering চালু করুন", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Mode C: ADB Forward (Alternative)
                    GuideStepCard(
                        step = "C",
                        title = "বিকল্প পদ্ধতি: ADB Port Forward (পিসি ব্রাউজার localhost)",
                        description = "পিসিতে USB Debugging দিয়ে 'http://localhost:8080' চালাতে চাইলে CMD তে নিচের কমান্ড দিন:",
                        codeSnippet = "adb forward tcp:8080 tcp:8080",
                        onCopyCode = {
                            copyToClipboard("ADB Command", "adb forward tcp:8080 tcp:8080")
                            copiedCommand = true
                        }
                    )
                }
            }
        }

        // Why USB over Wi-Fi Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Usb, contentDescription = null, tint = StudioBlue, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "কেন ওয়াই-ফাইয়ের চেয়ে USB সেরা?",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = StudioBlue
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "• কোনো ইন্টারনেট বা রাউটার সংযোগের প্রয়োজন নেই।\n• USB ক্যাবলে লেটেন্সি ১-২ মিলিসেকেন্ড (Zero Lag)।\n• ব্রাউজারে কোনো সফটওয়্যার বা এক্সটেনশন ইনস্টল করতে হয় না।\n• ২০০০ সালের পুরানো পেন্টিয়াম বা সেলেরন ল্যাপটপেও অনায়াসে চলবে।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideStepCard(
    step: String,
    title: String,
    description: String,
    codeSnippet: String? = null,
    onCopyCode: (() -> Unit)? = null,
    actionButtonText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(StudioBlue.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = StudioBlue
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )

            if (codeSnippet != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { onCopyCode?.invoke() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = codeSnippet,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = StudioBlue,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        tint = StudioBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (actionButtonText != null && onAction != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onAction,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(actionButtonText, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
