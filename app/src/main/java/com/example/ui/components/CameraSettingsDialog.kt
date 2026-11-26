package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.model.CameraUiState
import com.example.model.CaptureAspectRatio
import com.example.model.CapturePriority
import com.example.model.CaptureTimer
import com.example.model.GridOption
import com.example.model.OemEnhancementMode
import com.example.model.PhotoResolution
import com.example.model.RawCaptureMode
import com.example.model.SettingsUiState
import com.example.model.VideoCodecOption
import com.example.model.VideoFpsOption
import com.example.model.VideoQualityOption
import com.example.model.VolumeKeyAction
import com.example.model.WatermarkCacheLimit
import com.example.model.WatermarkPosition
import com.example.model.WatermarkStyle
import com.example.model.toSettingsUiState
import com.example.ui.theme.StuDOBlack
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

// Fully opaque backdrop enables HWUI/SurfaceFlinger opaque layer occlusion over PreviewView and HUD
private val DialogBackdropColor = Color(0xFF0C0D12)
private val CloseButtonBrush = Brush.verticalGradient(
    listOf(
        Color.White.copy(alpha = 0.18f),
        Color(0xFF14151B).copy(alpha = 0.75f)
    )
)
private val CloseButtonBorderColor = Color.White.copy(alpha = 0.25f)
private val HeaderDividerColor = Color.White.copy(alpha = 0.1f)
private val ListContentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp)

@Composable
fun CameraSettingsDialog(
    uiState: CameraUiState,
    onDismiss: () -> Unit,
    onToggleHistogram: () -> Unit,
    onToggleLevel: () -> Unit,
    onSelectGrid: (GridOption) -> Unit,
    onToggleShutterSound: () -> Unit,
    onSelectTimer: (CaptureTimer) -> Unit,
    onSetCustomTimerSeconds: (Int) -> Unit = {},
    onSelectAspectRatio: (CaptureAspectRatio) -> Unit,
    // Pro Photo settings
    onSelectPhotoResolution: (PhotoResolution) -> Unit,
    onSelectCapturePriority: (CapturePriority) -> Unit = {},
    onSelectRawCaptureMode: (RawCaptureMode) -> Unit,
    onToggleHdr: () -> Unit,
    onToggleWatermark: () -> Unit,
    onOpenWatermarkStudio: () -> Unit = {},
    onSetPostCaptureWatermarkEditing: (Boolean, WatermarkCacheLimit?) -> Unit = { _, _ -> },
    onSetWatermarkCacheLimit: (WatermarkCacheLimit) -> Unit = {},
    onClearAllPrivateOriginals: () -> Unit = {},
    onSelectWatermarkStyle: (WatermarkStyle) -> Unit = {},
    onSetWatermarkCustomText: (String) -> Unit = {},
    onSelectWatermarkPosition: (WatermarkPosition) -> Unit = {},
    // Pro Video settings
    onSelectVideoQuality: (VideoQualityOption) -> Unit,
    onSelectVideoCodec: (VideoCodecOption) -> Unit,
    onSelectVideoFps: (VideoFpsOption) -> Unit = {},
    onToggleVideoStabilization: () -> Unit = {},
    onToggleAudio: () -> Unit = {},
    // General & Hardware settings
    onToggleSelfieMirror: () -> Unit,
    onToggleSaveLocation: (Boolean) -> Unit,
    onToggleHapticFeedback: () -> Unit,
    onToggleKeepScreenAwake: () -> Unit,
    onSelectVolumeKeyAction: (VolumeKeyAction) -> Unit,
    onSelectOemEnhancementMode: (OemEnhancementMode) -> Unit = {},
    onOpenRecycleBin: () -> Unit = {},
    onVersionTapped: () -> Unit,
    onContentComposed: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val settingsState = remember(uiState) { uiState.toSettingsUiState() }
    CameraSettingsDialog(
        settingsState = settingsState,
        onDismiss = onDismiss,
        onToggleHistogram = onToggleHistogram,
        onToggleLevel = onToggleLevel,
        onSelectGrid = onSelectGrid,
        onToggleShutterSound = onToggleShutterSound,
        onSelectTimer = onSelectTimer,
        onSetCustomTimerSeconds = onSetCustomTimerSeconds,
        onSelectAspectRatio = onSelectAspectRatio,
        onSelectPhotoResolution = onSelectPhotoResolution,
        onSelectCapturePriority = onSelectCapturePriority,
        onSelectRawCaptureMode = onSelectRawCaptureMode,
        onToggleHdr = onToggleHdr,
        onToggleWatermark = onToggleWatermark,
        onOpenWatermarkStudio = onOpenWatermarkStudio,
        onSetPostCaptureWatermarkEditing = onSetPostCaptureWatermarkEditing,
        onSetWatermarkCacheLimit = onSetWatermarkCacheLimit,
        onClearAllPrivateOriginals = onClearAllPrivateOriginals,
        onSelectVideoQuality = onSelectVideoQuality,
        onSelectVideoCodec = onSelectVideoCodec,
        onSelectVideoFps = onSelectVideoFps,
        onToggleVideoStabilization = onToggleVideoStabilization,
        onToggleAudio = onToggleAudio,
        onToggleSelfieMirror = onToggleSelfieMirror,
        onToggleSaveLocation = onToggleSaveLocation,
        onToggleHapticFeedback = onToggleHapticFeedback,
        onToggleKeepScreenAwake = onToggleKeepScreenAwake,
        onSelectVolumeKeyAction = onSelectVolumeKeyAction,
        onSelectOemEnhancementMode = onSelectOemEnhancementMode,
        onOpenRecycleBin = onOpenRecycleBin,
        onVersionTapped = onVersionTapped,
        onContentComposed = onContentComposed,
        modifier = modifier
    )
}

@Composable
fun CameraSettingsDialog(
    settingsState: SettingsUiState,
    onDismiss: () -> Unit,
    onToggleHistogram: () -> Unit,
    onToggleLevel: () -> Unit,
    onSelectGrid: (GridOption) -> Unit,
    onToggleShutterSound: () -> Unit,
    onSelectTimer: (CaptureTimer) -> Unit,
    onSetCustomTimerSeconds: (Int) -> Unit = {},
    onSelectAspectRatio: (CaptureAspectRatio) -> Unit,
    // Pro Photo settings
    onSelectPhotoResolution: (PhotoResolution) -> Unit,
    onSelectCapturePriority: (CapturePriority) -> Unit = {},
    onSelectRawCaptureMode: (RawCaptureMode) -> Unit,
    onToggleHdr: () -> Unit,
    onToggleWatermark: () -> Unit,
    onOpenWatermarkStudio: () -> Unit = {},
    onSetPostCaptureWatermarkEditing: (Boolean, WatermarkCacheLimit?) -> Unit = { _, _ -> },
    onSetWatermarkCacheLimit: (WatermarkCacheLimit) -> Unit = {},
    onClearAllPrivateOriginals: () -> Unit = {},
    // Pro Video settings
    onSelectVideoQuality: (VideoQualityOption) -> Unit,
    onSelectVideoCodec: (VideoCodecOption) -> Unit,
    onSelectVideoFps: (VideoFpsOption) -> Unit = {},
    onToggleVideoStabilization: () -> Unit = {},
    onToggleAudio: () -> Unit = {},
    // General & Hardware settings
    onToggleSelfieMirror: () -> Unit,
    onToggleSaveLocation: (Boolean) -> Unit,
    onToggleHapticFeedback: () -> Unit,
    onToggleKeepScreenAwake: () -> Unit,
    onSelectVolumeKeyAction: (VolumeKeyAction) -> Unit,
    onSelectOemEnhancementMode: (OemEnhancementMode) -> Unit = {},
    onOpenRecycleBin: () -> Unit = {},
    onVersionTapped: () -> Unit,
    onContentComposed: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    onContentComposed?.invoke()
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val isScrollInProgress = remember(listState) { { listState.isScrollInProgress } }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val isGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        onToggleSaveLocation(isGranted)
    }

    // Pre-warm the watermark preview cache in the background when idle so scrolling down is instant
    LaunchedEffect(settingsState.watermarkConfig) {
        if (settingsState.watermarkConfig.isEnabled &&
            WatermarkPreviewCache.get(settingsState.watermarkConfig) == null
        ) {
            if (listState.isScrollInProgress) {
                snapshotFlow { listState.isScrollInProgress }.first { !it }
            }
            delay(50L)
            if (listState.isScrollInProgress) {
                snapshotFlow { listState.isScrollInProgress }.first { !it }
            }
            WatermarkPreviewCache.getOrRender(context, settingsState.watermarkConfig)
        }
    }

    val handleSaveLocationChange = remember(context, locationPermissionLauncher, onToggleSaveLocation) {
        { enable: Boolean ->
            if (enable) {
                val hasFine = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (hasFine || hasCoarse) {
                    onToggleSaveLocation(true)
                } else {
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            } else {
                onToggleSaveLocation(false)
            }
        }
    }

    val handleToggleVideoStabilization = remember(onToggleVideoStabilization) { { _: Boolean -> onToggleVideoStabilization() } }
    val handleToggleAudio = remember(onToggleAudio) { { _: Boolean -> onToggleAudio() } }
    val handleSelectVideoCodec = remember(onSelectVideoCodec) {
        { isHevc: Boolean ->
            onSelectVideoCodec(if (isHevc) VideoCodecOption.HEVC else VideoCodecOption.H264)
        }
    }
    val handleToggleSelfieMirror = remember(onToggleSelfieMirror) { { _: Boolean -> onToggleSelfieMirror() } }
    val handleToggleKeepScreenAwake = remember(onToggleKeepScreenAwake) { { _: Boolean -> onToggleKeepScreenAwake() } }
    val handleToggleHapticFeedback = remember(onToggleHapticFeedback) { { _: Boolean -> onToggleHapticFeedback() } }
    val handleToggleHistogram = remember(onToggleHistogram) { { _: Boolean -> onToggleHistogram() } }
    val handleToggleLevel = remember(onToggleLevel) { { _: Boolean -> onToggleLevel() } }
    val handleToggleShutterSound = remember(onToggleShutterSound) { { _: Boolean -> onToggleShutterSound() } }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_dialog_surface"),
        color = DialogBackdropColor,
        contentColor = StuDOWhite
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Header Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StuDOLogo(
                        size = 36.dp,
                        isDarkTheme = true,
                        contentDescription = "StuDO Logo"
                    )
                    Column {
                        Text(
                            text = "SETTINGS",
                            color = StuDOWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = "Preferences automatically saved across sessions",
                            color = StuDOSilverMuted,
                            fontSize = 12.sp
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(CloseButtonBrush)
                        .border(1.dp, CloseButtonBorderColor, CircleShape)
                        .testTag("settings_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close settings",
                        tint = StuDOWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            HorizontalDivider(
                color = HeaderDividerColor,
                thickness = 0.8.dp
            )

            val sharedSwitchColors = SwitchDefaults.colors(
                checkedThumbColor = StuDOBlack,
                checkedTrackColor = StuDOFocusYellow,
                uncheckedThumbColor = StuDOSilver,
                uncheckedTrackColor = Color.White.copy(alpha = 0.12f)
            )
            CompositionLocalProvider(LocalSettingsSwitchColors provides sharedSwitchColors) {
                // Granular Virtualized LazyColumn with stable keys and distinct contentTypes for slot reuse
                LazyColumn(
                    state = listState,
                    flingBehavior = ScrollableDefaults.flingBehavior(),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("settings_lazy_column"),
                    contentPadding = ListContentPadding,
                    verticalArrangement = Arrangement.Top
                ) {
                // SECTION 1: PHOTO CAPTURE
                item(key = "hdr_photo_capture", contentType = "section_header") {
                    SettingsSectionHeader(title = "PHOTO CAPTURE")
                }

                // Virtualized row-level Photo Resolution card items to keep per-frame fling composition lightweight
                item(key = "res_header", contentType = "group_header") {
                    PhotoResolutionHeaderItem()
                }
                val hasStreamFooter = settingsState.actualConfiguredResolution != null
                if (settingsState.availableResolutionOptions.isNotEmpty()) {
                    val dynamicOptions = settingsState.availableResolutionOptions
                    itemsIndexed(
                        items = dynamicOptions,
                        key = { _, opt -> "res_dyn_${opt.resolution.name}" },
                        contentType = { _, opt ->
                            if (opt.resolution == settingsState.photoResolution) "res_row_selected" else "res_row"
                        }
                    ) { index, opt ->
                        PhotoResolutionDynamicRowItem(
                            option = opt,
                            isSelected = opt.resolution == settingsState.photoResolution,
                            isBottomItem = !hasStreamFooter && index == dynamicOptions.lastIndex,
                            onSelectResolution = onSelectPhotoResolution
                        )
                    }
                } else {
                    val fallbackResolutions = if (settingsState.isUhrSupportedOnCurrentLens) {
                        ALL_PHOTO_RESOLUTIONS
                    } else {
                        NON_UHR_PHOTO_RESOLUTIONS
                    }
                    itemsIndexed(
                        items = fallbackResolutions,
                        key = { _, res -> "res_fb_${res.name}" },
                        contentType = { _, res ->
                            if (res == settingsState.photoResolution) "res_row_selected" else "res_row"
                        }
                    ) { index, res ->
                        val title = remember(
                            res,
                            settingsState.uhrMegapixels,
                            settingsState.uhrSize,
                            settingsState.standardSensorResolutionText
                        ) {
                            buildFallbackResolutionTitle(
                                res = res,
                                uhrMegapixels = settingsState.uhrMegapixels,
                                uhrSize = settingsState.uhrSize,
                                standardSensorText = settingsState.standardSensorResolutionText
                            )
                        }
                        PhotoResolutionFallbackRowItem(
                            resolution = res,
                            title = title,
                            isSelected = res == settingsState.photoResolution,
                            isBottomItem = !hasStreamFooter && index == fallbackResolutions.lastIndex,
                            onSelectResolution = onSelectPhotoResolution
                        )
                    }
                }
                settingsState.actualConfiguredResolution?.let { actualRes ->
                    item(key = "res_active_stream", contentType = "res_stream_footer") {
                        PhotoResolutionStreamFooterItem(actualConfiguredSize = actualRes)
                    }
                }

                // Virtualized row-level Capture Priority card items
                item(key = "prio_header", contentType = "group_header") {
                    CapturePriorityHeaderItem()
                }
                itemsIndexed(
                    items = CAPTURE_PRIORITIES,
                    key = { _, priority -> "prio_${priority.name}" },
                    contentType = { _, priority ->
                        if (priority == settingsState.capturePriority) "prio_row_selected" else "prio_row"
                    }
                ) { index, priority ->
                    CapturePriorityRowItem(
                        priority = priority,
                        isSelected = priority == settingsState.capturePriority,
                        isBottomItem = index == CAPTURE_PRIORITIES.lastIndex,
                        onSelectPriority = onSelectCapturePriority
                    )
                }

                if (settingsState.isRawSupportedOnCurrentLens &&
                    settingsState.cameraMode != com.example.model.CameraMode.PORTRAIT &&
                    settingsState.cameraMode != com.example.model.CameraMode.NIGHT
                ) {
                    item(key = "card_raw_capture", contentType = "chip_row_card") {
                        RawCaptureCard(
                            rawCaptureMode = settingsState.rawCaptureMode,
                            onSelectRawMode = onSelectRawCaptureMode
                        )
                    }
                }

                item(key = "card_hdr", contentType = "switch_row_single") {
                    HdrSwitchCard(
                        isHdrEnabled = settingsState.isHdrEnabled,
                        onToggleHdr = onToggleHdr
                    )
                }

                item(key = "card_watermark", contentType = "watermark_card") {
                    WatermarkCard(
                        watermarkConfig = settingsState.watermarkConfig,
                        isPostCaptureWatermarkEditingEnabled = settingsState.isPostCaptureWatermarkEditingEnabled,
                        isWatermarkCacheLimitConfigured = settingsState.isWatermarkCacheLimitConfigured,
                        watermarkCacheLimit = settingsState.watermarkCacheLimit,
                        storageStats = settingsState.privateOriginalStorageStats,
                        onToggleWatermark = onToggleWatermark,
                        onOpenWatermarkStudio = onOpenWatermarkStudio,
                        onSetPostCaptureWatermarkEditing = onSetPostCaptureWatermarkEditing,
                        onSetWatermarkCacheLimit = onSetWatermarkCacheLimit,
                        onClearAllPrivateOriginals = onClearAllPrivateOriginals,
                        isScrollInProgress = isScrollInProgress
                    )
                }

                // SECTION 2: OEM ENHANCEMENT & PROCESSING
                item(key = "hdr_oem_enhancement", contentType = "section_header") {
                    SettingsSectionHeader(title = "OEM ENHANCEMENT & PROCESSING")
                }

                item(key = "card_oem_engine", contentType = "oem_card") {
                    OemEnhancementCard(
                        activeAdapterName = settingsState.activeOemAdapterName,
                        oemEnhancementMode = settingsState.oemEnhancementMode,
                        onSelectMode = onSelectOemEnhancementMode
                    )
                }

                // SECTION 3: VIDEO RECORDING
                item(key = "hdr_video_recording", contentType = "section_header") {
                    SettingsSectionHeader(title = "VIDEO RECORDING")
                }

                item(key = "card_video_quality", contentType = "chip_row_card") {
                    VideoQualityCard(
                        supportedQualities = settingsState.supportedVideoQualities,
                        selectedQuality = settingsState.videoQuality,
                        onSelectQuality = onSelectVideoQuality
                    )
                }

                item(key = "card_video_fps", contentType = "chip_row_card") {
                    VideoFpsCard(
                        supportedFps = settingsState.supportedVideoFps,
                        selectedFps = settingsState.videoFps,
                        onSelectFps = onSelectVideoFps
                    )
                }

                if (settingsState.isVideoStabilizationSupported) {
                    item(
                        key = "switch_video_eis",
                        contentType = if (settingsState.isVideoStabilizationEnabled) "switch_row_on" else "switch_row_off"
                    ) {
                        GroupedSwitchItem(
                            icon = Icons.Default.StayCurrentPortrait,
                            title = "Electronic Video Stabilization (EIS)",
                            subtitle = "Smooth handheld camera jitter and motion vibrations using sensor EIS pipeline",
                            isChecked = settingsState.isVideoStabilizationEnabled,
                            onCheckedChange = handleToggleVideoStabilization,
                            testTag = "settings_toggle_video_stabilization",
                            groupPosition = CardGroupPosition.TOP
                        )
                    }
                }

                val supportsHevc = settingsState.supportedVideoCodecs.contains(VideoCodecOption.HEVC)

                item(
                    key = "switch_video_audio",
                    contentType = if (settingsState.isAudioEnabled) "switch_row_on" else "switch_row_off"
                ) {
                    GroupedSwitchItem(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        title = "Record Audio",
                        subtitle = "Synchronize microphone audio track during video recording",
                        isChecked = settingsState.isAudioEnabled,
                        onCheckedChange = handleToggleAudio,
                        testTag = "settings_toggle_video_audio",
                        groupPosition = if (settingsState.isVideoStabilizationSupported) CardGroupPosition.MIDDLE else CardGroupPosition.TOP
                    )
                }

                if (supportsHevc) {
                    item(
                        key = "switch_video_codec",
                        contentType = if (settingsState.videoCodec == VideoCodecOption.HEVC) "switch_row_on" else "switch_row_off"
                    ) {
                        GroupedSwitchItem(
                            icon = Icons.Default.Tune,
                            title = "High Efficiency Video (H.265 / HEVC)",
                            subtitle = "Captures videos in HEVC format to reduce storage size while preserving quality",
                            isChecked = settingsState.videoCodec == VideoCodecOption.HEVC,
                            onCheckedChange = handleSelectVideoCodec,
                            testTag = "settings_toggle_video_codec",
                            groupPosition = CardGroupPosition.BOTTOM
                        )
                    }
                } else {
                    item(
                        key = "info_video_codec",
                        contentType = "info_row"
                    ) {
                        GroupedInfoItem(
                            icon = Icons.Default.Tune,
                            title = "Video Codec: H.264 / AVC",
                            subtitle = "Standard MP4/AVC encoder active (HEVC encoder not advertised on this device)",
                            badgeText = "AVC",
                            testTag = "settings_info_video_codec",
                            groupPosition = CardGroupPosition.BOTTOM
                        )
                    }
                }

                // SECTION 4: GENERAL & HARDWARE
                item(key = "hdr_general_hardware", contentType = "section_header") {
                    SettingsSectionHeader(title = "GENERAL & HARDWARE")
                }

                item(
                    key = "switch_selfie_mirror",
                    contentType = if (settingsState.isSelfieMirrorEnabled) "switch_row_on" else "switch_row_off"
                ) {
                    GroupedSwitchItem(
                        icon = Icons.Default.FlipCameraAndroid,
                        title = "Mirror Front Camera Selfies",
                        subtitle = "Save front camera selfies as viewed in the preview mirror",
                        isChecked = settingsState.isSelfieMirrorEnabled,
                        onCheckedChange = handleToggleSelfieMirror,
                        testTag = "settings_toggle_selfie_mirror",
                        groupPosition = CardGroupPosition.TOP
                    )
                }

                item(
                    key = "switch_save_location",
                    contentType = if (settingsState.isSaveLocationEnabled) "switch_row_on" else "switch_row_off"
                ) {
                    GroupedSwitchItem(
                        icon = Icons.Default.LocationOn,
                        title = "Save Location (Geotagging)",
                        subtitle = "Attach GPS coordinates to photo and video EXIF metadata",
                        isChecked = settingsState.isSaveLocationEnabled,
                        onCheckedChange = handleSaveLocationChange,
                        testTag = "settings_toggle_save_location",
                        groupPosition = CardGroupPosition.MIDDLE
                    )
                }

                item(
                    key = "switch_keep_screen_awake",
                    contentType = if (settingsState.isKeepScreenAwakeEnabled) "switch_row_on" else "switch_row_off"
                ) {
                    GroupedSwitchItem(
                        icon = Icons.Default.StayCurrentPortrait,
                        title = "Keep Screen Awake",
                        subtitle = "Prevent display from dimming while camera viewfinder is active",
                        isChecked = settingsState.isKeepScreenAwakeEnabled,
                        onCheckedChange = handleToggleKeepScreenAwake,
                        testTag = "settings_toggle_screen_awake",
                        groupPosition = CardGroupPosition.MIDDLE
                    )
                }

                item(
                    key = "switch_haptic_feedback",
                    contentType = if (settingsState.isHapticFeedbackEnabled) "switch_row_on" else "switch_row_off"
                ) {
                    GroupedSwitchItem(
                        icon = Icons.Default.Vibration,
                        title = "Haptic Feedback",
                        subtitle = "Vibrate softly on shutter capture, focus lock, and control toggles",
                        isChecked = settingsState.isHapticFeedbackEnabled,
                        onCheckedChange = handleToggleHapticFeedback,
                        testTag = "settings_toggle_haptics",
                        groupPosition = CardGroupPosition.BOTTOM
                    )
                }

                item(key = "card_volume_key", contentType = "chip_row_card") {
                    VolumeKeyCard(
                        volumeKeyAction = settingsState.volumeKeyAction,
                        onSelectVolumeKeyAction = onSelectVolumeKeyAction
                    )
                }

                item(key = "card_recycle_bin", contentType = "action_card") {
                    RecycleBinSettingsCard(
                        binCount = settingsState.binCount,
                        onOpenRecycleBin = onOpenRecycleBin
                    )
                }

                // SECTION 5: DISPLAY & HUD OVERLAYS
                item(key = "hdr_display_hud", contentType = "section_header") {
                    SettingsSectionHeader(title = "DISPLAY & HUD OVERLAYS")
                }

                item(
                    key = "switch_hud_histogram",
                    contentType = if (settingsState.isHistogramEnabled) "switch_row_on" else "switch_row_off"
                ) {
                    GroupedSwitchItem(
                        icon = Icons.Default.Tune,
                        title = "Live RGB Histogram",
                        subtitle = "Display real-time exposure distribution HUD on the camera preview",
                        isChecked = settingsState.isHistogramEnabled,
                        onCheckedChange = handleToggleHistogram,
                        testTag = "settings_toggle_histogram",
                        groupPosition = CardGroupPosition.TOP
                    )
                }

                item(
                    key = "switch_hud_level",
                    contentType = if (settingsState.isLevelIndicatorEnabled) "switch_row_on" else "switch_row_off"
                ) {
                    GroupedSwitchItem(
                        icon = Icons.Default.ScreenRotation,
                        title = "Horizon Level Indicator",
                        subtitle = "Electronic horizon guide to keep shots level and aligned",
                        isChecked = settingsState.isLevelIndicatorEnabled,
                        onCheckedChange = handleToggleLevel,
                        testTag = "settings_toggle_level",
                        groupPosition = CardGroupPosition.MIDDLE
                    )
                }

                item(
                    key = "switch_hud_sound",
                    contentType = if (settingsState.isShutterSoundEnabled) "switch_row_on" else "switch_row_off"
                ) {
                    GroupedSwitchItem(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        title = "Shutter Click Sound",
                        subtitle = "Audible feedback tone on capture",
                        isChecked = settingsState.isShutterSoundEnabled,
                        onCheckedChange = handleToggleShutterSound,
                        testTag = "settings_toggle_sound",
                        groupPosition = CardGroupPosition.BOTTOM
                    )
                }

                item(key = "card_framing_grid", contentType = "chip_row_card") {
                    FramingGridCard(
                        selectedGrid = settingsState.selectedGrid,
                        onSelectGrid = onSelectGrid
                    )
                }

                item(key = "card_capture_timer", contentType = "chip_row_card") {
                    CaptureTimerCard(
                        selectedTimer = settingsState.selectedTimer,
                        onSelectTimer = onSelectTimer,
                        customTimerSeconds = settingsState.customTimerSeconds,
                        onSetCustomTimerSeconds = onSetCustomTimerSeconds
                    )
                }

                item(key = "card_aspect_ratio", contentType = "chip_row_card") {
                    AspectRatioCard(
                        selectedRatio = settingsState.aspectRatio,
                        onSelectAspectRatio = onSelectAspectRatio
                    )
                }

                // SECTION 6: ABOUT & HARDWARE INFO
                item(key = "hdr_about_hardware", contentType = "section_header") {
                    SettingsSectionHeader(title = "ABOUT & HARDWARE INFO")
                }

                item(key = "card_about_hardware", contentType = "about_card") {
                    AboutHardwareCard(
                        onVersionTapped = onVersionTapped
                    )
                }

                item(key = "settings_bottom_spacer", contentType = "spacer") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
            }
        }
    }
}
