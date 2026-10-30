# ScreenCraft — Master Product Requirements Document (PRD) & Technical Specification

> **Version:** 1.1.0  
> **Target OS:** Android 8.1+ (API Level 27+)  
> **Primary Display Calibration:** 720 × 1520 px (Adaptive to all screen aspect ratios & densities)  
> **Architecture:** MVVM + Clean Service Architecture with Kotlin Coroutines & Jetpack Compose  
> **Package Namespace:** `com.example`

---

## 1. Executive Summary & Vision

**ScreenCraft** is a professional, high-performance, ultra-lightweight Android screen recorder engineered for fluid system capture, minimal APK footprint (< 10 MB), and negligible battery/CPU overhead. It provides seamless background recording directly from the Android Quick Settings (QS) notification shade without requiring the app interface to open.

---

## 2. Core Feature Matrix

| Feature | Specification | Implementation Detail |
|---|---|---|
| **Quick Settings Toggle** | System Quick Settings Tile (`ScreenRecordTileService`) | One-tap record/stop directly from notification shade using `TileService`. Cached `MediaProjection` token allows immediate silent capture. |
| **Recording Library** | In-app "Records" view with native video player | Displays recorded videos with formatted duration, file size, date, resolution, and quick action buttons for playback, sharing, renaming, and deletion. |
| **Video Resolution** | Match Screen (720×1520), 1080p FHD, 720p HD, 480p SD | Configured via `MediaRecorder.setVideoSize` with dynamic display metric scaling ensuring even-pixel bounds. |
| **Frame Rates (FPS)** | 60 FPS, 30 FPS, 24 FPS | Configured via `MediaRecorder.setVideoFrameRate`. |
| **Video Bitrates** | 16 Mbps (Ultra), 12 Mbps (High), 8 Mbps (Standard), 4 Mbps (Compact) | Configured via `MediaRecorder.setVideoEncodingBitRate`. |
| **Start Delay Countdown** | 0s (Instant), 3s, 5s, 10s | Coroutine delay updating foreground notification and UI countdown before `MediaRecorder.start()`. |
| **Stop Delay & Video Trimming** | 0s (Off), 1s, 2s, 3s, 5s auto-trimming from end of video | Lossless stream-level trimming using `MediaExtractor` and `MediaMuxer` to eliminate awkward stop clicks. |
| **Recent Apps Exclusion** | Dynamic `setExcludeFromRecents(true)` | When recording starts, the app removes itself from Android's multitasking carousel, preventing clutter. |
| **Internal Audio Capture (High-Fidelity Studio Audio)** | 48kHz, Stereo, 192 kbps AAC-LC pure internal digital capture | **No microphone is used.** Pure digital playback capture from apps/games via `AudioPlaybackCaptureConfiguration` (Android 10+) or `REMOTE_SUBMIX` (Android 8.1+), multiplexed into the video without ambient room noise. |

---

## 3. High-Quality Internal Audio Capture Architecture

### 3.1 Why Standard MediaRecorder Fails for Internal Audio
The Android `MediaRecorder` API only supports physical inputs like `AudioSource.MIC` or `AudioSource.CAMCORDER`. Most basic screen recorders inadvertently record room audio and speaker echo through the microphone.

### 3.2 ScreenCraft Dual-Stream Zero-Noise Pipeline
1. **Video Stream:** `MediaRecorder` captures screen pixels via `VirtualDisplay` to `temp_video.mp4` with **NO audio source configured**. The microphone hardware remains completely unpowered.
2. **Internal Audio Stream:** `InternalAudioCaptureRecorder` runs in parallel:
   - Configures `AudioRecord` with `AudioPlaybackCaptureConfiguration` derived from the active `MediaProjection` token.
   - Listens exclusively to `USAGE_MEDIA`, `USAGE_GAME`, and `USAGE_UNKNOWN` internal audio streams.
   - Captures raw 16-bit PCM stereo at **48,000 Hz** (48kHz studio sampling rate).
   - Feeds PCM frames directly into a hardware `MediaCodec` AAC encoder operating at **192,000 bps** (192 kbps high fidelity).
   - Encodes and writes AAC frames into `temp_audio.m4a`.
3. **Lossless Stream Multiplexing:**
   - Upon tap of Stop, `VideoTrimmer.mergeAndTrim(...)` uses `MediaExtractor` and `MediaMuxer` to combine the H.264 video track and the 192 kbps AAC internal audio track into the final MP4.
   - Simultaneously trims the designated `stopTrimSeconds` from the end of the recording.
   - Temporary intermediate files are immediately purged.

### 3.3 Android 8.1+ (API 27/28) Internal Audio Resolution & Acoustic Noise Gate
On Android 8.1 / 9 devices:
1. **Tiered Source Probing**: Attempts `AudioSource.REMOTE_SUBMIX` (source 8) for direct kernel audio loopback. If restricted by OEM security policies, it transparently engages `AudioSource.UNPROCESSED` (source 9) or `VOICE_RECOGNITION` (source 6), turning off Automatic Gain Control (AGC) to prevent amplifying room ambience.
2. **Real-time Acoustic Noise Gate**: In `recordAndEncodeLoop`, a 16-bit PCM DSP noise gate clamps low-level ambient room noise (< 650 amplitude, ~ -34 dBFS) to pure digital silence (0). When in-app games, videos, or music play, the gate seamlessly passes the full-bandwidth 48kHz stereo signal with zero distortion.

---

## 4. Screen Dimensions & Display Layout Calibration

- **Screen Resolution:** 720 × 1520 px (19:9 ratio)
- **Fluid Layout Principle:**
  - Max container width constrained to `600.dp` centered on tablet/foldable viewports (`Modifier.widthIn(max = 600.dp)`).
  - High-density responsive cards with flexible text wrapping (`TextOverflow.Ellipsis`).
  - Minimum touch target sizing is strictly ≥ 48dp × 48dp across all buttons, icon actions, and sliders.

---

## 5. Technical Architecture & File Map

```
/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/example/
│   │   │   ├── ScreenCraftApp.kt                       # Application class & Notification Channels
│   │   │   ├── MainActivity.kt                         # Edge-to-edge Activity with Recents exclusion
│   │   │   ├── model/
│   │   │   │   ├── RecordingSettings.kt                # Enums for Resolution, AudioMode, Settings data class
│   │   │   │   └── RecordedVideo.kt                    # Model for recorded video file metadata
│   │   │   ├── data/
│   │   │   │   ├── SettingsRepository.kt               # SharedPreferences + reactive StateFlow
│   │   │   │   └── VideoRepository.kt                  # File scanner, MediaMetadataRetriever, rename/delete
│   │   │   ├── service/
│   │   │   │   ├── ScreenRecorderService.kt            # Foreground Service, MediaProjection, VirtualDisplay
│   │   │   │   ├── InternalAudioCaptureRecorder.kt     # 48kHz/192kbps pure internal audio without microphone
│   │   │   │   ├── ScreenRecordTileService.kt          # Quick Settings TileService for shade toggle
│   │   │   │   └── VideoTrimmer.kt                     # Stream merger & MediaExtractor/MediaMuxer trim utility
│   │   │   └── ui/
│   │   │       ├── permission/
│   │   │       │   └── ScreenCapturePermissionActivity.kt # Translucent permission launcher for QS tile
│   │   │       ├── components/
│   │   │       │   ├── RecordingHeroCard.kt            # Master recording status, countdown, timer, & controls
│   │   │       │   ├── VideoPlayerDialog.kt            # Embedded VideoView player with playback & share
│   │   │       │   └── QuickSettingsGuideDialog.kt     # Step-by-step visual tutorial for QS tile
│   │   │       ├── screens/
│   │   │       │   ├── MainScreen.kt                   # Primary scaffold, tabs, and permission handling
│   │   │       │   ├── RecordsScreen.kt                # Video library LazyColumn with actions
│   │   │       │   └── SettingsScreen.kt               # Resolution, FPS, Bitrate, Delay, Trim, Audio settings
│   │   │       └── theme/
│   │   │           ├── Color.kt                        # Studio Red, Sky Blue, Dark Slate palette
│   │   │           └── Theme.kt                        # ScreenCraftTheme Material 3 configuration
│   │   └── res/
│   │       ├── drawable/
│   │       │   ├── ic_qs_record.xml                    # Vector icon for Quick Settings tile
│   │       │   ├── ic_launcher_background.xml          # Adaptive icon background
│   │       │   └── ic_launcher_foreground.xml          # Adaptive icon foreground
│   │       ├── xml/
│   │       │   └── file_paths.xml                      # FileProvider paths for sharing recordings
│   │       └── values/
│   │           └── strings.xml                         # App strings and notification channel copy
```

---

## 6. How to Recreate an Exact Carbon Copy

1. Set `minSdk = 24` or `minSdk = 27` (Android 8.1+) in `app/build.gradle.kts`.
2. Add necessary permissions in `AndroidManifest.xml`: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `VIBRATE`, and `WAKE_LOCK`.
3. Register `ScreenRecorderService`, `ScreenRecordTileService`, `ScreenCapturePermissionActivity`, and `FileProvider`.
4. Run video through `MediaRecorder` (video only) and internal sound through `InternalAudioCaptureRecorder` with `AudioPlaybackCaptureConfiguration` (Android 10+) or `REMOTE_SUBMIX` (Android 8.1+), merging losslessly with `MediaMuxer`.
5. **Android 14+ (API 34+) Compliance Rule:** Register a `MediaProjection.Callback` via `mediaProjection.registerCallback(callback, Handler(Looper.getMainLooper()))` before invoking `mediaProjection.createVirtualDisplay(...)`, and safely unregister upon cleanup.
6. **MPEG4Writer Stop Safeguard Rule:** Always verify `samplesWritten > 0` before invoking `MediaMuxer.stop()` on any audio or video track, and allow sufficient frame ingestion before `MediaRecorder.stop()` to prevent native `E/MPEG4Writer: Stop() called but track is not started or stopped` errors.

---

## 7. APK Build & GitHub Distribution Pipeline

### 7.1 Compilation Architecture
- **Environment:** Google AI Studio Native Android SDK & Gradle Build Environment
- **Target Command:** `gradle :app` or native `compile_applet`
- **Output Artifact:** `app/build/outputs/apk/debug/app-debug.apk`
- **Distribution Directory:** `apk-distribution/`
- **Files Staged for Release:**
  - `apk-distribution/app-debug.apk` (Compiled APK ready for direct device installation)
  - `apk-distribution/app-debug.apk.sha256` (SHA-256 integrity checksum)

### 7.2 GitHub Actions Release Automation (`.github/workflows/publish-apk.yml`)
- **Triggers:** Push to `main` or `master` branches upon modification of `apk-distribution/app-debug.apk`, or manual trigger via `workflow_dispatch`.
- **Zero Recompilation:** GitHub Actions does not compile Android code. It consumes the pre-built, cryptographically verified APK.
- **Verification Gate:** Validates file presence, non-empty payload, SHA-256 checksum alignment, and ZIP archive integrity via `unzip -tq` prior to release.
- **Publishing Mechanism:** Utilizes `gh release create` with GitHub CLI, tag format `debug-apk-build-${{ github.run_number }}-${{ github.run_attempt }}`, flags as `--latest`.
- **Permanent Latest Download Link:**
  `https://github.com/<USERNAME>/<REPOSITORY>/releases/latest/download/app-debug.apk`

---

## 8. Android 8.1+ Compatibility & System Gallery Integration

### 8.1 Public Movies Directory & MediaStore Gallery Scanning
- **Public Directory:** Recordings are written directly to `/storage/emulated/0/Movies/ScreenCraft/` (`Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)/ScreenCraft`).
- **MediaStore Indexing:** As soon as an MP4 recording finishes or is renamed, `MediaScannerConnection.scanFile(...)` scans the file with mime type `"video/mp4"`. This triggers Android's system media provider to immediately register the video in the device's native **Gallery**, **Photos**, and file manager apps.
- **Runtime Permissions on Android 8/9:** `WRITE_EXTERNAL_STORAGE` and `READ_EXTERNAL_STORAGE` are explicitly requested at runtime on API level <= 28 before initiating screen recording.

### 8.2 MediaRecorder Start & Stop Lifecycle on Android 8
- **Surface Lifecycle Rule:**
  1. `recorder.prepare()`
  2. `val surface = recorder.surface` is retrieved **immediately after prepare() and BEFORE start()** (avoiding native `IllegalStateException`).
  3. `projection.createVirtualDisplay(...)` is created using the prepared surface.
  4. Pure digital audio capture is started (if enabled).
  5. `recorder.start()` is invoked to begin recording.
- **Stop Sequence:**
  1. `virtualDisplay?.release()` is invoked **FIRST** to stop pushing display frames.
  2. A 350ms delay allows MPEG4Writer and encoder pipeline to finalize buffered packets.
  3. `mediaRecorder?.stop()` is executed safely, properly finalizing the MP4 moov atom.

### 8.3 Strict Zero-Microphone Rule for Internal Audio
- When **Internal Audio Only** (`AudioMode.INTERNAL_ONLY`) is selected:
  - The device microphone hardware is **STRICTLY NEVER OPENED**. Under no circumstance is `AudioSource.MIC` configured.
  - On Android 10+ (API 29+): `AudioPlaybackCaptureConfiguration` captures digital internal audio without microphone.
  - On Android 8.1 / 9 (API 27/28): `AudioSource.REMOTE_SUBMIX` (Source 8) directly captures digital internal audio loopback without physical microphone hardware. If not supported by the OEM ROM, no audio is captured, ensuring ambient room noise or speech is never recorded.
- The microphone is only enabled if the user explicitly selects **Internal + Microphone** (`AudioMode.INTERNAL_AND_MIC`).

### 8.4 MainActivity MediaProjectionManager & Compose State Management
- **MainActivity Capture Initiation:** `MainActivity` configures `projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager` and implements `registerForActivityResult(ActivityResultContracts.StartActivityForResult())`.
  - Verifies `WRITE_EXTERNAL_STORAGE` and `READ_EXTERNAL_STORAGE` for API 26/27 before triggering the permission prompt.
  - Launches `ScreenRecorderService` with `ACTION_START`, `EXTRA_RESULT_CODE`, and `EXTRA_RESULT_DATA`.
- **Exact VirtualDisplay Dimensions:** Configured strictly to **720x1520** (or 1520x720 in landscape) matching the physical screen bounds.
- **Output File Initialization:** The target `File` object is guaranteed to exist with `createNewFile()` and parent directory verification before passing `absolutePath` to `MediaRecorder.setOutputFile(...)`.
- **Floating Notification & UI Indicator:** When recording is active, a floating persistent pill banner appears at the top of the Compose UI showing a live recording dot, formatted timer, and a direct action button with text **'Stop Recording'**.
- **Hero Card Button Text:** All active session buttons are updated to display **'Stop Recording'**.

---

## 9. Comprehensive Bug Fixes & Resiliency Hardening (v1.2.0)

### 9.1 Audio Timestamp Generation & Drift Elimination
- **Issue:** `InternalAudioCaptureRecorder` originally fed `System.nanoTime() / 1000` into `MediaCodec`. This produced absolute uptime timestamps in the hundreds of billions of microseconds, while the video stream timestamps started at 0. During `VideoTrimmer.mergeAndTrim(...)`, `audioExtractor.sampleTime > targetEndUs` evaluated to `true` immediately, discarding every audio sample and producing silent or desynchronized video.
- **Resolution:** Presentation timestamps are now derived mathematically from total PCM bytes read:
  `presentationTimeUs = (totalPcmBytesRead * 1_000_000L) / (SAMPLE_RATE * 4L)` (16-bit Stereo PCM = 4 bytes per frame).
  Timestamps start precisely at `0 µs` and advance monotonically without drift or jitter.

### 9.2 Interleaved Video & Audio Stream Multiplexing
- **Issue:** Sequential writing of video samples followed by audio samples caused Android 8.1 native `MediaMuxer` buffer overflows when recordings were longer than a few seconds.
- **Resolution:** Replaced sequential loops with an interleaved loop that compares `vTime <= aTime`, advancing chronologically. This ensures `MediaMuxer` receives data in balanced chunks without overfilling native buffers.

### 9.3 Atomic File Rename Fallback on Linux/Android
- **Issue:** On Linux/Android filesystems, `File.renameTo(target)` fails silently if `target` already exists. If `MediaMuxer` created a partial `outputFile` before an exception occurred, the fallback `videoFile.renameTo(outputFile)` failed, leaving a 0-byte corrupt file.
- **Resolution:** Added explicit `if (outputFile.exists() && outputFile != videoFile) outputFile.delete()` before `renameTo` across both `mergeAndTrim` and `trimEnd`.

### 9.4 Quick Settings Storage Permission Gate on Android 8.1 / 9
- **Issue:** When starting a recording directly from the Quick Settings Tile on Android 8.1/9, `ScreenCapturePermissionActivity` only requested `MediaProjection` permissions without ensuring `WRITE_EXTERNAL_STORAGE` was granted, resulting in `FileNotFoundException (Permission denied)` when attempting to write to public Movies.
- **Resolution:** `ScreenCapturePermissionActivity` now checks `WRITE_EXTERNAL_STORAGE` and `READ_EXTERNAL_STORAGE` at runtime on API <= 28, prompting the user if needed before launching `MediaProjection`. Also protected against duplicate launches via `savedInstanceState == null`.

### 9.5 Gallery Database Cleanup on Deletion
- **Issue:** Deleting a recorded video with `file.delete()` removed the file from disk, but Android's `MediaStore` database retained the metadata entry, showing a broken/unplayable ghost thumbnail in the phone's Gallery app.
- **Resolution:** `VideoRepository.deleteVideo()` now invokes `MediaScannerConnection.scanFile(...)` on the deleted path, instructing the Android system scanner to immediately purge the record from the Gallery database.

### 9.6 Screen Calibration for 720×1520 & UI Touch Accessibility
- **Video Player Dialog:** Constrained height using `heightIn(min = 180.dp, max = 340.dp)` with dynamic aspect ratio calculation so that portrait 720×1520 and landscape videos never exceed screen height or push dialog buttons off screen.
- **Stop Trim Chips:** Formatted labels to `"Off"`, `"1s"`, `"2s"`, `"3s"`, `"5s"` to guarantee seamless fit across 720px wide viewports without horizontal text wrapping.
- **Touch Targets:** Adjusted action buttons (`Share`, `Rename`, `Delete`) to `40.dp` with expanded padding complying with Android Accessibility guidelines.
- **FileProvider Paths:** Added `<external-path name="external_root" path="." />` ensuring broad coverage for all external storage sharing operations.
