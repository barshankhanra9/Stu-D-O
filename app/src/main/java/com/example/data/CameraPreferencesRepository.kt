package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.model.CameraFilter
import com.example.model.CaptureAspectRatio
import com.example.model.CaptureTimer
import com.example.model.GridOption
import com.example.model.PhotoResolution
import com.example.model.VideoCodecOption
import com.example.model.VideoFpsOption
import com.example.model.VideoQualityOption
import com.example.model.VolumeKeyAction
import com.example.model.WatermarkCacheLimit
import com.example.model.WatermarkConfig
import com.example.model.WatermarkFont
import com.example.model.WatermarkLogoAlignment
import com.example.model.WatermarkLogoType
import com.example.model.WatermarkPosition
import com.example.model.WatermarkStyle
import com.example.model.WatermarkTextAlignment
import org.json.JSONObject

class CameraPreferencesRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "studo_camera_preferences"

        private const val KEY_ASPECT_RATIO = "pref_aspect_ratio"
        private const val KEY_TIMER = "pref_timer"
        private const val KEY_CUSTOM_TIMER_SECONDS = "pref_custom_timer_seconds"
        private const val KEY_GRID = "pref_grid"
        private const val KEY_LEVEL = "pref_level"
        private const val KEY_HISTOGRAM = "pref_histogram"
        private const val KEY_SHUTTER_SOUND = "pref_shutter_sound"
        private const val KEY_HAPTIC_FEEDBACK = "pref_haptic_feedback"
        private const val KEY_KEEP_SCREEN_AWAKE = "pref_keep_screen_awake"
        private const val KEY_VOLUME_KEY_ACTION = "pref_volume_key_action"
        private const val KEY_FILTER = "pref_filter"
        private const val KEY_SCREEN_FLASH_MODE = "pref_screen_flash_mode"

        private const val KEY_PHOTO_RESOLUTION = "pref_photo_resolution"
        private const val KEY_HDR_ENABLED = "pref_hdr_enabled"
        private const val KEY_WATERMARK_ENABLED = "pref_watermark_enabled"
        private const val KEY_WATERMARK_STYLE = "pref_watermark_style"
        private const val KEY_WATERMARK_CUSTOM_TEXT = "pref_watermark_custom_text"
        private const val KEY_WATERMARK_POSITION = "pref_watermark_position"
        private const val KEY_WATERMARK_JSON = "pref_watermark_json"
        private const val KEY_WATERMARK_PRESETS_JSON = "pref_watermark_presets_json"
        private const val KEY_POST_CAPTURE_WATERMARK_EDITING = "pref_post_capture_watermark_editing"
        private const val KEY_WATERMARK_CACHE_LIMIT_CONFIGURED = "pref_watermark_cache_limit_configured"
        private const val KEY_WATERMARK_CACHE_LIMIT = "pref_watermark_cache_limit"

        private const val KEY_VIDEO_QUALITY = "pref_video_quality"
        private const val KEY_VIDEO_ASPECT_RATIO = "pref_video_aspect_ratio"
        private const val KEY_VIDEO_FPS = "pref_video_fps"
        private const val KEY_VIDEO_CODEC = "pref_video_codec"
        private const val KEY_VIDEO_AUDIO = "pref_video_audio"
        private const val KEY_VIDEO_STABILIZATION = "pref_video_stabilization"
        private const val KEY_SELFIE_MIRROR = "pref_selfie_mirror"
        private const val KEY_SAVE_LOCATION = "pref_save_location"
        private const val KEY_PORTRAIT_BLUR_INTENSITY = "pref_portrait_blur_intensity"

        fun watermarkConfigToJson(config: WatermarkConfig): JSONObject {
            return JSONObject().apply {
                put("isEnabled", config.isEnabled)
                put("style", config.style.name)
                put("customText", config.customText)
                put("secondaryText", config.secondaryText)
                put("position", config.position.name)
                put("customOffsetX", config.customOffsetX.toDouble())
                put("customOffsetY", config.customOffsetY.toDouble())
                put("font", config.font.name)
                put("isBold", config.isBold)
                put("fontSizeScale", config.fontSizeScale.toDouble())
                put("textColorHex", config.textColorHex)
                put("letterSpacing", config.letterSpacing.toDouble())
                put("hasShadow", config.hasShadow)
                put("textOpacity", config.textOpacity.toDouble())
                put("lineSpacing", config.lineSpacing.toDouble())
                put("textAlignment", config.textAlignment.name)
                put("logoType", config.logoType.name)
                put("customLogoUri", config.customLogoUri ?: JSONObject.NULL)
                put("logoScale", config.logoScale.toDouble())
                put("logoOpacity", config.logoOpacity.toDouble())
                put("logoSpacingDp", config.logoSpacingDp)
                put("logoAlignment", config.logoAlignment.name)
                put("backgroundOpacity", config.backgroundOpacity.toDouble())
                put("backgroundColorHex", config.backgroundColorHex)
                put("borderThicknessDp", config.borderThicknessDp)
                put("cornerRadiusDp", config.cornerRadiusDp)
                put("paddingDp", config.paddingDp)
                put("rotationDegrees", config.rotationDegrees.toDouble())
                put("marginScale", config.marginScale.toDouble())
                put("showDateTime", config.showDateTime)
                put("showDeviceModel", config.showDeviceModel)
                put("showLensInfo", config.showLensInfo)
                put("showExifSpecs", config.showExifSpecs)
                put("showResolution", config.showResolution)
                put("showManufacturer", config.showManufacturer)
            }
        }

        fun watermarkConfigFromJson(json: JSONObject, defaultEnabled: Boolean = false): WatermarkConfig {
            val style = try {
                WatermarkStyle.valueOf(json.optString("style", WatermarkStyle.LEICA_FRAME.name))
            } catch (_: Exception) {
                WatermarkStyle.LEICA_FRAME
            }
            val position = try {
                WatermarkPosition.valueOf(json.optString("position", WatermarkPosition.FRAME_BORDER.name))
            } catch (_: Exception) {
                WatermarkPosition.FRAME_BORDER
            }
            val font = try {
                WatermarkFont.valueOf(json.optString("font", WatermarkFont.SANS_SERIF.name))
            } catch (_: Exception) {
                WatermarkFont.SANS_SERIF
            }
            val logoType = try {
                WatermarkLogoType.valueOf(json.optString("logoType", WatermarkLogoType.APP_ICON.name))
            } catch (_: Exception) {
                WatermarkLogoType.APP_ICON
            }
            val textAlignment = try {
                WatermarkTextAlignment.valueOf(json.optString("textAlignment", WatermarkTextAlignment.START.name))
            } catch (_: Exception) {
                WatermarkTextAlignment.START
            }
            val logoAlignment = try {
                WatermarkLogoAlignment.valueOf(json.optString("logoAlignment", WatermarkLogoAlignment.START.name))
            } catch (_: Exception) {
                WatermarkLogoAlignment.START
            }
            return WatermarkConfig(
                isEnabled = json.optBoolean("isEnabled", defaultEnabled),
                style = style,
                customText = json.optString("customText", "Shot on STU•D•O"),
                secondaryText = json.optString("secondaryText", "OPTICAL SYSTEM · ULTRA HD"),
                position = position,
                customOffsetX = json.optDouble("customOffsetX", 0.85).toFloat(),
                customOffsetY = json.optDouble("customOffsetY", 0.92).toFloat(),
                font = font,
                isBold = json.optBoolean("isBold", true),
                fontSizeScale = json.optDouble("fontSizeScale", 1.0).toFloat(),
                textColorHex = json.optString("textColorHex", "#FFFFFF"),
                letterSpacing = json.optDouble("letterSpacing", 0.05).toFloat(),
                hasShadow = json.optBoolean("hasShadow", true),
                textOpacity = json.optDouble("textOpacity", 1.0).toFloat(),
                lineSpacing = json.optDouble("lineSpacing", 1.15).toFloat(),
                textAlignment = textAlignment,
                logoType = logoType,
                customLogoUri = if (json.isNull("customLogoUri")) null else json.optString("customLogoUri").takeIf { it.isNotEmpty() },
                logoScale = json.optDouble("logoScale", 1.0).toFloat(),
                logoOpacity = json.optDouble("logoOpacity", 1.0).toFloat(),
                logoSpacingDp = json.optInt("logoSpacingDp", 12),
                logoAlignment = logoAlignment,
                backgroundOpacity = json.optDouble("backgroundOpacity", 1.0).toFloat(),
                backgroundColorHex = json.optString("backgroundColorHex", "#FFFFFF"),
                borderThicknessDp = json.optInt("borderThicknessDp", 0),
                cornerRadiusDp = json.optInt("cornerRadiusDp", 0),
                paddingDp = json.optInt("paddingDp", 16),
                rotationDegrees = json.optDouble("rotationDegrees", 0.0).toFloat(),
                marginScale = json.optDouble("marginScale", 1.0).toFloat(),
                showDateTime = json.optBoolean("showDateTime", true),
                showDeviceModel = json.optBoolean("showDeviceModel", true),
                showLensInfo = json.optBoolean("showLensInfo", true),
                showExifSpecs = json.optBoolean("showExifSpecs", true),
                showResolution = json.optBoolean("showResolution", false),
                showManufacturer = json.optBoolean("showManufacturer", true)
            )
        }
    }

    // Aspect Ratio
    fun getAspectRatio(): CaptureAspectRatio {
        val name = prefs.getString(KEY_ASPECT_RATIO, CaptureAspectRatio.RATIO_4_3.name)
        return try {
            CaptureAspectRatio.valueOf(name ?: CaptureAspectRatio.RATIO_4_3.name)
        } catch (_: Exception) {
            CaptureAspectRatio.RATIO_4_3
        }
    }

    fun saveAspectRatio(ratio: CaptureAspectRatio) {
        prefs.edit().putString(KEY_ASPECT_RATIO, ratio.name).apply()
    }

    // Video Aspect Ratio
    fun getVideoAspectRatio(): CaptureAspectRatio {
        val name = prefs.getString(KEY_VIDEO_ASPECT_RATIO, CaptureAspectRatio.RATIO_16_9.name)
        return try {
            CaptureAspectRatio.valueOf(name ?: CaptureAspectRatio.RATIO_16_9.name)
        } catch (_: Exception) {
            CaptureAspectRatio.RATIO_16_9
        }
    }

    fun saveVideoAspectRatio(ratio: CaptureAspectRatio) {
        prefs.edit().putString(KEY_VIDEO_ASPECT_RATIO, ratio.name).apply()
    }

    // Timer
    fun getTimer(): CaptureTimer {
        val name = prefs.getString(KEY_TIMER, CaptureTimer.OFF.name)
        return try {
            CaptureTimer.valueOf(name ?: CaptureTimer.OFF.name)
        } catch (_: Exception) {
            CaptureTimer.OFF
        }
    }

    fun saveTimer(timer: CaptureTimer) {
        prefs.edit().putString(KEY_TIMER, timer.name).apply()
    }

    fun getCustomTimerSeconds(): Int {
        return prefs.getInt(KEY_CUSTOM_TIMER_SECONDS, CaptureTimer.DEFAULT_CUSTOM_SECONDS)
            .coerceIn(CaptureTimer.MIN_CUSTOM_SECONDS, CaptureTimer.MAX_CUSTOM_SECONDS)
    }

    fun saveCustomTimerSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(CaptureTimer.MIN_CUSTOM_SECONDS, CaptureTimer.MAX_CUSTOM_SECONDS)
        prefs.edit().putInt(KEY_CUSTOM_TIMER_SECONDS, clamped).apply()
    }

    // Grid
    fun getGrid(): GridOption {
        val name = prefs.getString(KEY_GRID, GridOption.OFF.name)
        return try {
            GridOption.valueOf(name ?: GridOption.OFF.name)
        } catch (_: Exception) {
            GridOption.OFF
        }
    }

    fun saveGrid(grid: GridOption) {
        prefs.edit().putString(KEY_GRID, grid.name).apply()
    }

    // Filter
    fun getFilter(): CameraFilter {
        val name = prefs.getString(KEY_FILTER, CameraFilter.NATURAL.name)
        return try {
            CameraFilter.valueOf(name ?: CameraFilter.NATURAL.name)
        } catch (_: Exception) {
            CameraFilter.NATURAL
        }
    }

    fun saveFilter(filter: CameraFilter) {
        prefs.edit().putString(KEY_FILTER, filter.name).apply()
    }

    // Screen Flash Mode
    fun getScreenFlashMode(): com.example.model.ScreenFlashMode {
        val name = prefs.getString(KEY_SCREEN_FLASH_MODE, com.example.model.ScreenFlashMode.OFF.name)
        return try {
            com.example.model.ScreenFlashMode.valueOf(name ?: com.example.model.ScreenFlashMode.OFF.name)
        } catch (_: Exception) {
            com.example.model.ScreenFlashMode.OFF
        }
    }

    fun saveScreenFlashMode(mode: com.example.model.ScreenFlashMode) {
        prefs.edit().putString(KEY_SCREEN_FLASH_MODE, mode.name).apply()
    }

    // Horizon Level
    fun isLevelEnabled(): Boolean = prefs.getBoolean(KEY_LEVEL, false)
    fun saveLevelEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LEVEL, enabled).apply()
    }

    // Histogram
    fun isHistogramEnabled(): Boolean = prefs.getBoolean(KEY_HISTOGRAM, false)
    fun saveHistogramEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HISTOGRAM, enabled).apply()
    }

    // Shutter Sound
    fun isShutterSoundEnabled(): Boolean = prefs.getBoolean(KEY_SHUTTER_SOUND, true)
    fun saveShutterSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHUTTER_SOUND, enabled).apply()
    }

    // Haptic Feedback
    fun isHapticFeedbackEnabled(): Boolean = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true)
    fun saveHapticFeedbackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, enabled).apply()
    }

    // Keep Screen Awake
    fun isKeepScreenAwakeEnabled(): Boolean = prefs.getBoolean(KEY_KEEP_SCREEN_AWAKE, true)
    fun saveKeepScreenAwakeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_AWAKE, enabled).apply()
    }

    // Volume Key Action
    fun getVolumeKeyAction(): VolumeKeyAction {
        val name = prefs.getString(KEY_VOLUME_KEY_ACTION, VolumeKeyAction.SHUTTER.name)
        return try {
            VolumeKeyAction.valueOf(name ?: VolumeKeyAction.SHUTTER.name)
        } catch (_: Exception) {
            VolumeKeyAction.SHUTTER
        }
    }

    fun saveVolumeKeyAction(action: VolumeKeyAction) {
        prefs.edit().putString(KEY_VOLUME_KEY_ACTION, action.name).apply()
    }

    // Photo Resolution
    fun getPhotoResolution(): PhotoResolution {
        val name = prefs.getString(KEY_PHOTO_RESOLUTION, PhotoResolution.MAX.name)
        return try {
            PhotoResolution.valueOf(name ?: PhotoResolution.MAX.name)
        } catch (_: Exception) {
            PhotoResolution.MAX
        }
    }

    fun savePhotoResolution(resolution: PhotoResolution) {
        prefs.edit().putString(KEY_PHOTO_RESOLUTION, resolution.name).apply()
    }

    // RAW Capture Mode
    fun getRawCaptureMode(): com.example.model.RawCaptureMode {
        val name = prefs.getString("pref_raw_capture_mode", com.example.model.RawCaptureMode.OFF.name)
        return try {
            com.example.model.RawCaptureMode.valueOf(name ?: com.example.model.RawCaptureMode.OFF.name)
        } catch (_: Exception) {
            com.example.model.RawCaptureMode.OFF
        }
    }

    fun saveRawCaptureMode(mode: com.example.model.RawCaptureMode) {
        prefs.edit().putString("pref_raw_capture_mode", mode.name).apply()
    }

    // HDR
    fun isHdrEnabled(): Boolean = prefs.getBoolean(KEY_HDR_ENABLED, false)
    fun saveHdrEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HDR_ENABLED, enabled).apply()
    }

    // Watermark
    fun getWatermarkConfig(): WatermarkConfig {
        val jsonStr = prefs.getString(KEY_WATERMARK_JSON, null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val json = JSONObject(jsonStr)
                return watermarkConfigFromJson(json, prefs.getBoolean(KEY_WATERMARK_ENABLED, false))
            } catch (_: Exception) {}
        }

        // Fallback to basic keys
        val isEnabled = prefs.getBoolean(KEY_WATERMARK_ENABLED, false)
        val styleName = prefs.getString(KEY_WATERMARK_STYLE, WatermarkStyle.LEICA_FRAME.name)
        val style = try {
            WatermarkStyle.valueOf(styleName ?: WatermarkStyle.LEICA_FRAME.name)
        } catch (_: Exception) {
            WatermarkStyle.LEICA_FRAME
        }
        val customText = prefs.getString(KEY_WATERMARK_CUSTOM_TEXT, "Shot on STU•D•O") ?: "Shot on STU•D•O"
        val posName = prefs.getString(KEY_WATERMARK_POSITION, WatermarkPosition.FRAME_BORDER.name)
        val position = try {
            WatermarkPosition.valueOf(posName ?: WatermarkPosition.FRAME_BORDER.name)
        } catch (_: Exception) {
            WatermarkPosition.FRAME_BORDER
        }
        return WatermarkConfig(
            isEnabled = isEnabled,
            style = style,
            customText = customText,
            position = position
        )
    }

    fun saveWatermarkConfig(config: WatermarkConfig) {
        val json = watermarkConfigToJson(config)
        prefs.edit()
            .putBoolean(KEY_WATERMARK_ENABLED, config.isEnabled)
            .putString(KEY_WATERMARK_STYLE, config.style.name)
            .putString(KEY_WATERMARK_CUSTOM_TEXT, config.customText)
            .putString(KEY_WATERMARK_POSITION, config.position.name)
            .putString(KEY_WATERMARK_JSON, json.toString())
            .apply()
    }

    fun isPostCaptureWatermarkEditingEnabled(): Boolean =
        prefs.getBoolean(KEY_POST_CAPTURE_WATERMARK_EDITING, false)

    fun savePostCaptureWatermarkEditingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_POST_CAPTURE_WATERMARK_EDITING, enabled).apply()
    }

    fun isWatermarkCacheLimitConfigured(): Boolean =
        prefs.getBoolean(KEY_WATERMARK_CACHE_LIMIT_CONFIGURED, false)

    fun markWatermarkCacheLimitConfigured() {
        prefs.edit().putBoolean(KEY_WATERMARK_CACHE_LIMIT_CONFIGURED, true).apply()
    }

    fun getWatermarkCacheLimit(): WatermarkCacheLimit {
        val name = prefs.getString(KEY_WATERMARK_CACHE_LIMIT, WatermarkCacheLimit.GB_1.name)
        return try {
            WatermarkCacheLimit.valueOf(name ?: WatermarkCacheLimit.GB_1.name)
        } catch (_: Exception) {
            WatermarkCacheLimit.GB_1
        }
    }

    fun saveWatermarkCacheLimit(limit: WatermarkCacheLimit) {
        prefs.edit()
            .putString(KEY_WATERMARK_CACHE_LIMIT, limit.name)
            .putBoolean(KEY_WATERMARK_CACHE_LIMIT_CONFIGURED, true)
            .apply()
    }

    fun getWatermarkPresets(): List<com.example.model.WatermarkPreset> {
        val jsonStr = prefs.getString(KEY_WATERMARK_PRESETS_JSON, null) ?: return emptyList()
        val list = mutableListOf<com.example.model.WatermarkPreset>()
        try {
            val array = org.json.JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", java.util.UUID.randomUUID().toString())
                val name = obj.optString("name", "Preset")
                val configObj = obj.getJSONObject("config")
                val config = WatermarkConfig(
                    isEnabled = configObj.optBoolean("isEnabled", true),
                    style = WatermarkStyle.valueOf(configObj.optString("style", WatermarkStyle.LEICA_FRAME.name)),
                    customText = configObj.optString("customText", "Shot on STU•D•O"),
                    secondaryText = configObj.optString("secondaryText", "OPTICAL SYSTEM · ULTRA HD"),
                    position = WatermarkPosition.valueOf(configObj.optString("position", WatermarkPosition.FRAME_BORDER.name)),
                    customOffsetX = configObj.optDouble("customOffsetX", 0.85).toFloat(),
                    customOffsetY = configObj.optDouble("customOffsetY", 0.92).toFloat(),
                    font = com.example.model.WatermarkFont.valueOf(configObj.optString("font", com.example.model.WatermarkFont.SANS_SERIF.name)),
                    isBold = configObj.optBoolean("isBold", true),
                    fontSizeScale = configObj.optDouble("fontSizeScale", 1.0).toFloat(),
                    textColorHex = configObj.optString("textColorHex", "#FFFFFF"),
                    letterSpacing = configObj.optDouble("letterSpacing", 0.05).toFloat(),
                    hasShadow = configObj.optBoolean("hasShadow", true),
                    logoType = com.example.model.WatermarkLogoType.valueOf(configObj.optString("logoType", com.example.model.WatermarkLogoType.APP_ICON.name)),
                    customLogoUri = if (configObj.isNull("customLogoUri")) null else configObj.optString("customLogoUri").takeIf { it.isNotEmpty() },
                    logoScale = configObj.optDouble("logoScale", 1.0).toFloat(),
                    logoOpacity = configObj.optDouble("logoOpacity", 1.0).toFloat(),
                    logoSpacingDp = configObj.optInt("logoSpacingDp", 12),
                    backgroundOpacity = configObj.optDouble("backgroundOpacity", 1.0).toFloat(),
                    backgroundColorHex = configObj.optString("backgroundColorHex", "#FFFFFF"),
                    borderThicknessDp = configObj.optInt("borderThicknessDp", 0),
                    cornerRadiusDp = configObj.optInt("cornerRadiusDp", 0),
                    paddingDp = configObj.optInt("paddingDp", 16),
                    showDateTime = configObj.optBoolean("showDateTime", true),
                    showDeviceModel = configObj.optBoolean("showDeviceModel", true),
                    showLensInfo = configObj.optBoolean("showLensInfo", true),
                    showExifSpecs = configObj.optBoolean("showExifSpecs", true),
                    showResolution = configObj.optBoolean("showResolution", false),
                    showManufacturer = configObj.optBoolean("showManufacturer", true)
                )
                list.add(com.example.model.WatermarkPreset(id, name, config))
            }
        } catch (_: Exception) {}
        return list
    }

    fun saveWatermarkPresets(presets: List<com.example.model.WatermarkPreset>) {
        val array = org.json.JSONArray()
        for (preset in presets) {
            val obj = org.json.JSONObject().apply {
                put("id", preset.id)
                put("name", preset.name)
                val config = preset.config
                val configObj = org.json.JSONObject().apply {
                    put("isEnabled", config.isEnabled)
                    put("style", config.style.name)
                    put("customText", config.customText)
                    put("secondaryText", config.secondaryText)
                    put("position", config.position.name)
                    put("customOffsetX", config.customOffsetX.toDouble())
                    put("customOffsetY", config.customOffsetY.toDouble())
                    put("font", config.font.name)
                    put("isBold", config.isBold)
                    put("fontSizeScale", config.fontSizeScale.toDouble())
                    put("textColorHex", config.textColorHex)
                    put("letterSpacing", config.letterSpacing.toDouble())
                    put("hasShadow", config.hasShadow)
                    put("logoType", config.logoType.name)
                    put("customLogoUri", config.customLogoUri ?: org.json.JSONObject.NULL)
                    put("logoScale", config.logoScale.toDouble())
                    put("logoOpacity", config.logoOpacity.toDouble())
                    put("logoSpacingDp", config.logoSpacingDp)
                    put("backgroundOpacity", config.backgroundOpacity.toDouble())
                    put("backgroundColorHex", config.backgroundColorHex)
                    put("borderThicknessDp", config.borderThicknessDp)
                    put("cornerRadiusDp", config.cornerRadiusDp)
                    put("paddingDp", config.paddingDp)
                    put("showDateTime", config.showDateTime)
                    put("showDeviceModel", config.showDeviceModel)
                    put("showLensInfo", config.showLensInfo)
                    put("showExifSpecs", config.showExifSpecs)
                    put("showResolution", config.showResolution)
                    put("showManufacturer", config.showManufacturer)
                }
                put("config", configObj)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_WATERMARK_PRESETS_JSON, array.toString()).apply()
    }

    // Video Quality
    fun getVideoQuality(): VideoQualityOption {
        val name = prefs.getString(KEY_VIDEO_QUALITY, VideoQualityOption.QUALITY_1080P.name)
        return try {
            VideoQualityOption.valueOf(name ?: VideoQualityOption.QUALITY_1080P.name)
        } catch (_: Exception) {
            VideoQualityOption.QUALITY_1080P
        }
    }

    fun saveVideoQuality(quality: VideoQualityOption) {
        prefs.edit().putString(KEY_VIDEO_QUALITY, quality.name).apply()
    }

    // Video Codec
    fun getVideoCodec(): VideoCodecOption {
        val name = prefs.getString(KEY_VIDEO_CODEC, VideoCodecOption.H264.name)
        return try {
            VideoCodecOption.valueOf(name ?: VideoCodecOption.H264.name)
        } catch (_: Exception) {
            VideoCodecOption.H264
        }
    }

    fun saveVideoCodec(codec: VideoCodecOption) {
        prefs.edit().putString(KEY_VIDEO_CODEC, codec.name).apply()
    }

    // Video FPS
    fun getVideoFps(): VideoFpsOption {
        val name = prefs.getString(KEY_VIDEO_FPS, VideoFpsOption.FPS_AUTO.name)
        return try {
            VideoFpsOption.valueOf(name ?: VideoFpsOption.FPS_AUTO.name)
        } catch (_: Exception) {
            VideoFpsOption.FPS_AUTO
        }
    }

    fun saveVideoFps(fps: VideoFpsOption) {
        prefs.edit().putString(KEY_VIDEO_FPS, fps.name).apply()
    }

    // Video Audio (Microphone)
    fun isVideoAudioEnabled(): Boolean = prefs.getBoolean(KEY_VIDEO_AUDIO, true)
    fun saveVideoAudioEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIDEO_AUDIO, enabled).apply()
    }

    // Video Stabilization (EIS)
    fun isVideoStabilizationEnabled(): Boolean = prefs.getBoolean(KEY_VIDEO_STABILIZATION, true)
    fun saveVideoStabilizationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIDEO_STABILIZATION, enabled).apply()
    }

    // Selfie Mirror
    fun isSelfieMirrorEnabled(): Boolean = prefs.getBoolean(KEY_SELFIE_MIRROR, true)
    fun saveSelfieMirrorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SELFIE_MIRROR, enabled).apply()
    }

    // Save Location
    fun isSaveLocationEnabled(): Boolean = prefs.getBoolean(KEY_SAVE_LOCATION, false)
    fun saveSaveLocationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SAVE_LOCATION, enabled).apply()
    }

    // OEM Enhancement Mode
    fun getOemEnhancementMode(): com.example.model.OemEnhancementMode {
        val name = prefs.getString("pref_oem_enhancement_mode", com.example.model.OemEnhancementMode.AUTOMATIC.name)
        return try {
            com.example.model.OemEnhancementMode.valueOf(name ?: com.example.model.OemEnhancementMode.AUTOMATIC.name)
        } catch (_: Exception) {
            com.example.model.OemEnhancementMode.AUTOMATIC
        }
    }

    fun saveOemEnhancementMode(mode: com.example.model.OemEnhancementMode) {
        prefs.edit().putString("pref_oem_enhancement_mode", mode.name).apply()
    }

    // Capture Priority (Quality vs Speed)
    fun getCapturePriority(): com.example.model.CapturePriority {
        val name = prefs.getString("pref_capture_priority", com.example.model.CapturePriority.QUALITY.name)
        return try {
            com.example.model.CapturePriority.valueOf(name ?: com.example.model.CapturePriority.QUALITY.name)
        } catch (_: Exception) {
            com.example.model.CapturePriority.QUALITY
        }
    }

    fun saveCapturePriority(priority: com.example.model.CapturePriority) {
        prefs.edit().putString("pref_capture_priority", priority.name).apply()
    }

    // Portrait Mode Blur Intensity (0f = f/16 sharp, 1f = f/1.4 strong bokeh)
    fun getPortraitBlurIntensity(): Float {
        return prefs.getFloat(KEY_PORTRAIT_BLUR_INTENSITY, 0.62f).coerceIn(0f, 1f)
    }

    fun savePortraitBlurIntensity(intensity: Float) {
        prefs.edit().putFloat(KEY_PORTRAIT_BLUR_INTENSITY, intensity.coerceIn(0f, 1f)).apply()
    }
}
