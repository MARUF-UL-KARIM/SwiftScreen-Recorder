package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.model.AudioMode
import com.example.model.RecordingSettings
import com.example.model.ResolutionOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("screencraft_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<RecordingSettings> = _settings.asStateFlow()

    private fun loadSettings(): RecordingSettings {
        val resName = prefs.getString(KEY_RESOLUTION, ResolutionOption.MATCH_SCREEN.name)
            ?: ResolutionOption.MATCH_SCREEN.name
        val resolution = try {
            ResolutionOption.valueOf(resName)
        } catch (_: Exception) {
            ResolutionOption.MATCH_SCREEN
        }

        val fps = prefs.getInt(KEY_FPS, 30)
        val bitrate = prefs.getInt(KEY_BITRATE, 8)
        val startDelay = prefs.getInt(KEY_START_DELAY, 3)
        val stopTrim = prefs.getInt(KEY_STOP_TRIM, 1)

        val audioName = prefs.getString(KEY_AUDIO_MODE, AudioMode.INTERNAL_ONLY.name)
            ?: AudioMode.INTERNAL_ONLY.name
        val audioMode = try {
            AudioMode.valueOf(audioName)
        } catch (_: Exception) {
            AudioMode.INTERNAL_ONLY
        }

        val hideFromRecents = prefs.getBoolean(KEY_HIDE_RECENTS, true)

        return RecordingSettings(
            resolution = resolution,
            fps = fps,
            bitrateMbps = bitrate,
            startDelaySeconds = startDelay,
            stopTrimSeconds = stopTrim,
            audioMode = audioMode,
            hideFromRecents = hideFromRecents
        )
    }

    fun updateSettings(newSettings: RecordingSettings) {
        prefs.edit()
            .putString(KEY_RESOLUTION, newSettings.resolution.name)
            .putInt(KEY_FPS, newSettings.fps)
            .putInt(KEY_BITRATE, newSettings.bitrateMbps)
            .putInt(KEY_START_DELAY, newSettings.startDelaySeconds)
            .putInt(KEY_STOP_TRIM, newSettings.stopTrimSeconds)
            .putString(KEY_AUDIO_MODE, newSettings.audioMode.name)
            .putBoolean(KEY_HIDE_RECENTS, newSettings.hideFromRecents)
            .apply()
        _settings.value = newSettings
    }

    companion object {
        private const val KEY_RESOLUTION = "pref_resolution"
        private const val KEY_FPS = "pref_fps"
        private const val KEY_BITRATE = "pref_bitrate"
        private const val KEY_START_DELAY = "pref_start_delay"
        private const val KEY_STOP_TRIM = "pref_stop_trim"
        private const val KEY_AUDIO_MODE = "pref_audio_mode"
        private const val KEY_HIDE_RECENTS = "pref_hide_recents"

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
