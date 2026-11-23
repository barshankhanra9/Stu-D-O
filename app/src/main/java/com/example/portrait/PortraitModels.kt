package com.example.portrait

import com.example.model.PortraitCaptureTier
import com.example.model.PortraitDepthSource
import com.example.model.PortraitPreviewStrategy
import com.example.model.PortraitSubjectModelStatus

data class HardwareDepthFrame(
    val width: Int,
    val height: Int,
    val depthMeters: FloatArray,
    val confidence: FloatArray,
    val validFraction: Float
) {
    fun toNormalizedDepthMap(): FloatArray {
        val count = width * height
        if (count <= 0) return FloatArray(0)
        var minD = Float.MAX_VALUE
        var maxD = 0f
        for (i in 0 until count) {
            if (confidence[i] > 0.1f && depthMeters[i] > 0.05f) {
                if (depthMeters[i] < minD) minD = depthMeters[i]
                if (depthMeters[i] > maxD) maxD = depthMeters[i]
            }
        }
        val out = FloatArray(count)
        if (maxD <= minD || minD == Float.MAX_VALUE) {
            out.fill(0.5f)
            return out
        }
        val span = (maxD - minD).coerceAtLeast(0.25f)
        for (i in 0 until count) {
            val d = depthMeters[i]
            out[i] = if (confidence[i] > 0.05f && d > 0.05f) {
                ((d - minD) / span).coerceIn(0f, 1f)
            } else {
                0.75f
            }
        }
        return out
    }
}

data class PortraitSubjectRegion(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val estimatedDepth: Float
)

data class PortraitSegmentationResult(
    val maskWidth: Int,
    val maskHeight: Int,
    /** 1.0f = crisp foreground subject, 0.0f = background */
    val alphaMatte: FloatArray,
    /** Continuous depth map in [0f..1f]: 0f = near camera plane, 1f = distant background */
    val depthMap: FloatArray,
    /** Normalized depth of the active focal plane [0f..1f] */
    val focusDepth: Float,
    val subjectCount: Int,
    val hasSubject: Boolean,
    val source: PortraitDepthSource,
    val subjects: List<PortraitSubjectRegion> = emptyList(),
    val captureTier: PortraitCaptureTier = PortraitCaptureTier.MLKIT_SELFIE_SEGMENTATION
)

data class BokehRenderOptions(
    val blurIntensity: Float = 0.62f,
    val focusX: Float? = null,
    val focusY: Float? = null,
    val maxBokehRadiusAtFullIntensity: Float = 42f,
    val highlightBloomStrength: Float = 0.55f
)

data class PortraitPipelineStatus(
    val previewStrategy: PortraitPreviewStrategy = PortraitPreviewStrategy.CHECKING,
    val activeCaptureTier: PortraitCaptureTier = PortraitCaptureTier.MLKIT_SELFIE_SEGMENTATION,
    val subjectModelStatus: PortraitSubjectModelStatus = PortraitSubjectModelStatus.UNCHECKED,
    val subjectModelProgress: Int = 0,
    val isLiveBlurPausedByWatchdog: Boolean = false,
    val livePreviewFps: Float = 0f,
    val isSubjectDetected: Boolean = false,
    val subjectCount: Int = 0
)

data class PortraitCaptureWaterfallResult(
    val captureTier: PortraitCaptureTier,
    val segmentation: PortraitSegmentationResult?,
    val handledByOemExtension: Boolean = false
)

