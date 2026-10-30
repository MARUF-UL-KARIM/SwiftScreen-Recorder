package com.example

import com.example.model.AudioMode
import com.example.model.RecordedVideo
import com.example.model.RecordingSettings
import com.example.model.ResolutionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScreenCraftUnitTest {

    @Test
    fun testDefaultSettings() {
        val settings = RecordingSettings()
        assertEquals(ResolutionOption.MATCH_SCREEN, settings.resolution)
        assertEquals(30, settings.fps)
        assertEquals(8, settings.bitrateMbps)
        assertEquals(3, settings.startDelaySeconds)
        assertEquals(1, settings.stopTrimSeconds)
        assertEquals(AudioMode.INTERNAL_ONLY, settings.audioMode)
        assertTrue(settings.hideFromRecents)
    }

    @Test
    fun testResolutionCalculationFor720x1520() {
        val option = ResolutionOption.MATCH_SCREEN
        val (width, height) = option.getDimensions(720, 1520)
        assertEquals(720, width)
        assertEquals(1520, height)
    }

    @Test
    fun testRecordedVideoFormatting() {
        val video = RecordedVideo(
            file = File("ScreenCraft_test.mp4"),
            name = "ScreenCraft_test",
            durationMs = 65000L, // 1 min 5 sec
            sizeBytes = 15 * 1024 * 1024L, // 15 MB
            lastModified = System.currentTimeMillis(),
            width = 720,
            height = 1520
        )

        assertEquals("01:05", video.formattedDuration)
        assertEquals("15.0 MB", video.formattedSize)
        assertEquals("720×1520", video.resolutionLabel)
    }
}
