package com.example

import android.content.Context
import android.media.CamcorderProfile
import android.media.EncoderProfiles
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.impl.CameraInfoInternal
import androidx.camera.core.impl.EncoderProfilesProvider
import androidx.camera.core.impl.EncoderProfilesProxy
import androidx.camera.core.impl.Quirks
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.CameraXVideoCapabilitiesCache
import com.example.ui.components.hasValidatedCamcorderEncoderProfiles
import com.example.ui.components.probeCameraXVideoCapabilities
import com.example.util.VideoEncoderUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowMediaCodecList
import org.robolectric.shadows.MediaCodecInfoBuilder
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EncoderProfilesFixTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CameraXVideoCapabilitiesCache.clearForTesting()
        VideoEncoderUtil.clearCacheForTesting()
        ShadowLog.clear()
        registerAvcEncoderForTest()
    }

    @After
    fun tearDown() {
        CameraXVideoCapabilitiesCache.clearForTesting()
        VideoEncoderUtil.clearCacheForTesting()
        ShadowMediaCodecList.reset()
        ShadowLog.clear()
    }

    private fun registerAvcEncoderForTest() {
        val fmt = MediaFormat.createVideoFormat("video/avc", 1280, 720).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setString("bitrate-mode", "VBR")
            setString("size-range", "160x120-1920x1080")
            setString("frame-rate-range", "1-60")
            setString("bitrate-range", "100000-20000000")
        }
        val profileLevel = MediaCodecInfo.CodecProfileLevel().apply {
            profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            level = MediaCodecInfo.CodecProfileLevel.AVCLevel31
        }
        val avcCaps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(fmt)
            .setIsEncoder(true)
            .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
            .setProfileLevels(arrayOf(profileLevel))
            .build()
        val avcCodec = MediaCodecInfoBuilder.newBuilder()
            .setName("c2.android.avc.encoder")
            .setIsEncoder(true)
            .setCapabilities(avcCaps)
            .build()
        ShadowMediaCodecList.addCodec(avcCodec)
    }

    private fun createFakeCameraInfoInternal(
        cameraId: String,
        provider: EncoderProfilesProvider,
        supportedDynamicRanges: Set<DynamicRange> = setOf(DynamicRange.SDR)
    ): CameraInfoInternal {
        val iface = CameraInfoInternal::class.java
        return Proxy.newProxyInstance(
            iface.classLoader,
            arrayOf(iface)
        ) { proxy, method, args ->
            when (method.name) {
                "getCameraId" -> cameraId
                "getEncoderProfilesProvider" -> provider
                "getSupportedDynamicRanges" -> supportedDynamicRanges
                "querySupportedDynamicRanges" -> {
                    val requested = args?.firstOrNull() as? Set<*>
                    if (requested.isNullOrEmpty()) {
                        supportedDynamicRanges
                    } else {
                        supportedDynamicRanges.filter { it in requested }.toSet()
                    }
                }
                "getCameraQuirks" -> Quirks(emptyList())
                "getImplementation" -> proxy
                "getLensFacing" -> CameraSelector.LENS_FACING_BACK
                "getSensorRotationDegrees" -> 90
                "hasFlashUnit" -> false
                "isVideoStabilizationSupported" -> false
                "isPreviewStabilizationSupported" -> false
                "getCameraSelector" -> CameraSelector.DEFAULT_BACK_CAMERA
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakeCameraInfoInternal($cameraId)"
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0
                    Set::class.java -> emptySet<Any>()
                    List::class.java -> emptyList<Any>()
                    else -> null
                }
            }
        } as CameraInfoInternal
    }

    private fun createProvider(
        profilesByQuality: Map<Int, EncoderProfilesProxy>
    ): EncoderProfilesProvider {
        return object : EncoderProfilesProvider {
            override fun hasProfile(quality: Int): Boolean = profilesByQuality.containsKey(quality)
            override fun getAll(quality: Int): EncoderProfilesProxy? = profilesByQuality[quality]
        }
    }

    private fun createTestEncoderProfilesProxy(
        width: Int,
        height: Int,
        bitDepth: Int,
        hdrFormat: Int,
        mime: String = "video/avc"
    ): EncoderProfilesProxy {
        val videoProfile = EncoderProfilesProxy.VideoProfileProxy.create(
            android.media.MediaRecorder.VideoEncoder.H264,
            mime,
            6_000_000,
            30,
            width,
            height,
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            bitDepth,
            EncoderProfiles.VideoProfile.YUV_420,
            hdrFormat
        )
        return EncoderProfilesProxy.ImmutableEncoderProfilesProxy.create(
            60,
            android.media.MediaRecorder.AudioEncoder.AAC,
            emptyList(),
            listOf(videoProfile)
        )
    }

    @Test
    fun `empty CameraX EncoderProfilesProvider skips Recorder capabilities without CapabilitiesByQuality error logs`() {
        val cameraInfo = createFakeCameraInfoInternal(
            cameraId = "test_empty_0",
            provider = EncoderProfilesProvider.EMPTY
        )

        assertFalse(hasValidatedCamcorderEncoderProfiles(context, cameraInfo, "test_empty_0"))
        val probe = probeCameraXVideoCapabilities(context, cameraInfo)
        assertTrue(probe.supportedQualities.isEmpty())

        val logs = ShadowLog.getLogsForTag("CapabilitiesByQuality")
        assertTrue(
            "Expected zero CapabilitiesByQuality error logs, got: $logs",
            logs.none { it.msg.contains("No supported EncoderProfiles") }
        )
    }

    @Test
    fun `profile with bitDepth 0 or HDR format is rejected before Recorder probing with zero error logs`() {
        val bitDepthZeroProvider = createProvider(
            mapOf(
                CamcorderProfile.QUALITY_720P to createTestEncoderProfilesProxy(
                    width = 1280,
                    height = 720,
                    bitDepth = 0,
                    hdrFormat = EncoderProfiles.VideoProfile.HDR_NONE
                )
            )
        )
        val hdrOnlyProvider = createProvider(
            mapOf(
                CamcorderProfile.QUALITY_720P to createTestEncoderProfilesProxy(
                    width = 1280,
                    height = 720,
                    bitDepth = EncoderProfilesProxy.VideoProfileProxy.BIT_DEPTH_10,
                    hdrFormat = EncoderProfiles.VideoProfile.HDR_HLG
                )
            )
        )

        val camZero = createFakeCameraInfoInternal("test_bd0", bitDepthZeroProvider)
        val camHdrOnly = createFakeCameraInfoInternal("test_hdr_only", hdrOnlyProvider)

        assertFalse(hasValidatedCamcorderEncoderProfiles(context, camZero, "test_bd0"))
        assertFalse(hasValidatedCamcorderEncoderProfiles(context, camHdrOnly, "test_hdr_only"))

        assertTrue(probeCameraXVideoCapabilities(context, camZero).supportedQualities.isEmpty())
        assertTrue(probeCameraXVideoCapabilities(context, camHdrOnly).supportedQualities.isEmpty())

        val logs = ShadowLog.getLogsForTag("CapabilitiesByQuality")
        assertTrue(
            "Expected zero CapabilitiesByQuality error logs, got: $logs",
            logs.none { it.msg.contains("No supported EncoderProfiles") }
        )
    }

    @Test
    fun `valid 8-bit SDR profile probes cleanly even when camera advertises HDR dynamic ranges`() {
        val sdrProvider = createProvider(
            mapOf(
                CamcorderProfile.QUALITY_720P to createTestEncoderProfilesProxy(
                    width = 1280,
                    height = 720,
                    bitDepth = EncoderProfilesProxy.VideoProfileProxy.BIT_DEPTH_8,
                    hdrFormat = EncoderProfiles.VideoProfile.HDR_NONE
                )
            )
        )
        val cameraInfo = createFakeCameraInfoInternal(
            cameraId = "test_sdr_with_hdr_caps",
            provider = sdrProvider,
            supportedDynamicRanges = setOf(
                DynamicRange.SDR,
                DynamicRange.HLG_10_BIT,
                DynamicRange.HDR10_10_BIT,
                DynamicRange.HDR10_PLUS_10_BIT,
                DynamicRange.DOLBY_VISION_10_BIT
            )
        )

        assertTrue(hasValidatedCamcorderEncoderProfiles(context, cameraInfo, "test_sdr_with_hdr_caps"))
        val probe = probeCameraXVideoCapabilities(context, cameraInfo)
        assertTrue(probe.supportedQualities.contains(Quality.HD))

        val recorder = Recorder.Builder()
            .setVideoCapabilitiesSource(probe.capabilitiesSource)
            .build()
        val sdrOutput = com.example.ui.components.SdrScopedVideoOutput(recorder)
        val mediaCaps = sdrOutput.getMediaCapabilities(cameraInfo, 0)
        assertTrue(mediaCaps.getSupportedQualities(DynamicRange.SDR).contains(Quality.HD))

        val videoCapture = VideoCapture.Builder(sdrOutput)
            .setDynamicRange(DynamicRange.SDR)
            .build()
        assertEquals(DynamicRange.SDR, videoCapture.dynamicRange)
        assertTrue(videoCapture.output === sdrOutput)
        assertTrue(sdrOutput.recorder === recorder)

        val logs = ShadowLog.getLogsForTag("CapabilitiesByQuality")
        assertTrue(
            "Expected zero CapabilitiesByQuality error logs, got: $logs",
            logs.none { it.msg.contains("No supported EncoderProfiles") }
        )
    }
}
