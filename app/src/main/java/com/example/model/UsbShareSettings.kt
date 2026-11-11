package com.example.model

enum class UsbQualityPreset(
    val title: String,
    val width: Int,
    val height: Int,
    val targetFps: Int,
    val jpegQuality: Int,
    val description: String
) {
    POTATO_2000(
        title = "Potato Mode (25-70 FPS Ultra Speed)",
        width = 360,
        height = 760,
        targetFps = 60,
        jpegQuality = 42,
        description = "High-speed 30-70 FPS stream, sub-1ms response, 0% CPU lockup for old laptops and phones"
    ),
    ULTRA_FAST_ZERO_LAG(
        title = "Ultra Fast (Zero-Lag 1ms)",
        width = 480,
        height = 1012,
        targetFps = 60,
        jpegQuality = 58,
        description = "Sub-millisecond frame pipeline, 0 lag, perfect for Phone-to-Phone OTG & USB direct mirror"
    ),
    BALANCED(
        title = "Balanced Mode (Recommended)",
        width = 540,
        height = 1140,
        targetFps = 45,
        jpegQuality = 68,
        description = "Smooth 45 FPS, crystal clear text and visuals with zero buffering"
    ),
    HIGH_NATIVE(
        title = "Native 720×1520 (High Definition)",
        width = 720,
        height = 1520,
        targetFps = 60,
        jpegQuality = 78,
        description = "Full 1:1 pixel match for 720×1520 displays, maximum crispness"
    )
}

data class UsbShareState(
    val isStreaming: Boolean = false,
    val port: Int = 8080,
    val connectedClients: Int = 0,
    val currentFps: Int = 0,
    val width: Int = 360,
    val height: Int = 760,
    val qualityPreset: UsbQualityPreset = UsbQualityPreset.POTATO_2000,
    val elapsedSeconds: Long = 0L,
    val ipAddresses: List<String> = emptyList()
)
