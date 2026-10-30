package com.example.model

enum class ResolutionOption(val title: String, val width: Int, val height: Int, val isMatchScreen: Boolean = false) {
    MATCH_SCREEN("Match Device (e.g. 720×1520)", 720, 1520, true),
    FHD_1080P("1080p FHD (1080×2280)", 1080, 2280),
    HD_720P("720p HD (720×1520)", 720, 1520),
    SD_480P("480p Compact (480×1012)", 480, 1012);

    fun getDimensions(deviceWidth: Int, deviceHeight: Int): Pair<Int, Int> {
        return if (isMatchScreen) {
            // Ensure even numbers for MediaCodec / MediaRecorder H.264 encoder
            val w = if (deviceWidth % 2 != 0) deviceWidth - 1 else deviceWidth
            val h = if (deviceHeight % 2 != 0) deviceHeight - 1 else deviceHeight
            Pair(w, h)
        } else {
            val w = if (width % 2 != 0) width - 1 else width
            val h = if (height % 2 != 0) height - 1 else height
            Pair(w, h)
        }
    }
}

enum class AudioMode(val displayName: String, val description: String) {
    INTERNAL_ONLY(
        "Internal Audio Only",
        "Captures crisp internal app/game sound without environmental noise (Android 10+ standard)"
    ),
    INTERNAL_AND_MIC(
        "Internal + Microphone",
        "Blends system gameplay/app audio with your voice commentary"
    ),
    MUTED(
        "Muted (No Audio)",
        "Silent video recording, optimal for presentations and smallest file size"
    )
}

data class RecordingSettings(
    val resolution: ResolutionOption = ResolutionOption.MATCH_SCREEN,
    val fps: Int = 30,
    val bitrateMbps: Int = 8,
    val startDelaySeconds: Int = 3,
    val stopTrimSeconds: Int = 1,
    val audioMode: AudioMode = AudioMode.INTERNAL_ONLY,
    val hideFromRecents: Boolean = true
)
