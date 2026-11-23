package com.example.portrait

import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.View
import com.example.model.CameraFilter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 2.0 real-time Portrait Bokeh Viewfinder Surface (compatible with API 24+ / Android 7.0+ without AGSL/RuntimeShader).
 *
 * Uploads tight YUV_420_888 planes + ML Kit foreground confidence mask onto GPU textures and runs an
 * occlusion-protected circular Vogel disk blur + active CameraFilter color grading inside an ES 2.0 fragment shader.
 */
class PortraitLiveBlurGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer = PortraitGlBokehRenderer()
    private val isGlPaused = AtomicBoolean(true)
    private val isSurfaceAvailable = AtomicBoolean(false)

    var onFrameRenderedListener: ((Long) -> Unit)?
        get() = renderer.onFrameRenderedListener
        set(value) {
            renderer.onFrameRenderedListener = value
        }

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = false
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        super.onPause()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceAvailable.set(true)
        super.surfaceCreated(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceAvailable.set(false)
        renderer.clearCpuBuffers()
        super.surfaceDestroyed(holder)
    }

    fun updateRenderParams(
        blurIntensity: Float,
        filter: CameraFilter,
        isFrontCamera: Boolean
    ) {
        renderer.updateRenderParams(blurIntensity, filter, isFrontCamera)
        if (!isGlPaused.get() && isSurfaceAvailable.get() && visibility == View.VISIBLE) {
            requestRender()
        }
    }

    fun submitCameraYuvFrame(
        yBytes: ByteArray,
        uBytes: ByteArray,
        vBytes: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        isFrontCamera: Boolean
    ) {
        if (isGlPaused.get() || !isSurfaceAvailable.get() || visibility != View.VISIBLE) return
        renderer.submitCameraYuvFrame(
            yBytes = yBytes,
            uBytes = uBytes,
            vBytes = vBytes,
            width = width,
            height = height,
            rotationDegrees = rotationDegrees,
            isFrontCamera = isFrontCamera
        )
        requestRender()
    }

    fun submitSegmentationMask(
        maskAlpha: FloatArray,
        maskWidth: Int,
        maskHeight: Int
    ) {
        if (isGlPaused.get() || !isSurfaceAvailable.get() || visibility != View.VISIBLE) return
        renderer.submitSegmentationMask(maskAlpha, maskWidth, maskHeight)
        requestRender()
    }

    fun clearBuffers() {
        renderer.clearCpuBuffers()
        if (!isGlPaused.get() && isSurfaceAvailable.get()) {
            queueEvent {
                renderer.releaseGlTextures()
            }
        }
    }

    override fun onPause() {
        if (isGlPaused.compareAndSet(false, true)) {
            renderer.clearCpuBuffers()
            super.onPause()
        }
    }

    override fun onResume() {
        if (isGlPaused.compareAndSet(true, false)) {
            super.onResume()
        }
    }

    override fun onDetachedFromWindow() {
        isSurfaceAvailable.set(false)
        isGlPaused.set(true)
        renderer.clearCpuBuffers()
        super.onDetachedFromWindow()
    }
}

private class PortraitGlBokehRenderer : GLSurfaceView.Renderer {

    @Volatile
    var onFrameRenderedListener: ((Long) -> Unit)? = null

    private val lock = Any()

    private var frameW = 0
    private var frameH = 0
    private var rotationDeg = 0
    private var isFront = false
    private var hasNewCameraFrame = false

    private var yBuffer: ByteBuffer? = null
    private var uBuffer: ByteBuffer? = null
    private var vBuffer: ByteBuffer? = null

    private var maskW = 0
    private var maskH = 0
    private var maskByteArray = ByteArray(0)
    private var maskBuffer: ByteBuffer? = null
    private var hasNewMask = false

    @Volatile
    private var blurIntensity = 0.62f

    @Volatile
    private var activeFilter = CameraFilter.NATURAL

    private var viewportW = 1
    private var viewportH = 1

    private var programId = 0
    private val textureIds = IntArray(4)
    private var texInitialized = false
    private val releasedFlag = AtomicBoolean(false)

    private var posHandle = 0
    private var texCoordHandle = 0
    private var yTexHandle = 0
    private var uTexHandle = 0
    private var vTexHandle = 0
    private var maskTexHandle = 0
    private var uBlurRadiusHandle = 0
    private var uBlurActiveHandle = 0
    private var uMirrorHandle = 0
    private var uRotationHandle = 0
    private var uCropScaleHandle = 0
    private var uTexelStepHandle = 0
    private var uColorMatrixHandle = 0
    private var uColorOffsetHandle = 0
    private var uVignetteHandle = 0
    private var uHasMaskHandle = 0

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_VERTICES)
            position(0)
        }

    fun updateRenderParams(
        blur: Float,
        filter: CameraFilter,
        front: Boolean
    ) {
        blurIntensity = blur.coerceIn(0f, 1f)
        activeFilter = filter
        isFront = front
    }

    fun submitCameraYuvFrame(
        yBytes: ByteArray,
        uBytes: ByteArray,
        vBytes: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        isFrontCamera: Boolean
    ) {
        if (width <= 2 || height <= 2) return
        val ySize = width * height
        val uvSize = (width / 2) * (height / 2)
        if (yBytes.size < ySize || uBytes.size < uvSize || vBytes.size < uvSize) return

        synchronized(lock) {
            if (yBuffer == null || yBuffer!!.capacity() < ySize) {
                yBuffer = ByteBuffer.allocateDirect(ySize).order(ByteOrder.nativeOrder())
            }
            if (uBuffer == null || uBuffer!!.capacity() < uvSize) {
                uBuffer = ByteBuffer.allocateDirect(uvSize).order(ByteOrder.nativeOrder())
            }
            if (vBuffer == null || vBuffer!!.capacity() < uvSize) {
                vBuffer = ByteBuffer.allocateDirect(uvSize).order(ByteOrder.nativeOrder())
            }
            yBuffer!!.apply {
                clear()
                put(yBytes, 0, ySize)
                position(0)
            }
            uBuffer!!.apply {
                clear()
                put(uBytes, 0, uvSize)
                position(0)
            }
            vBuffer!!.apply {
                clear()
                put(vBytes, 0, uvSize)
                position(0)
            }
            frameW = width
            frameH = height
            rotationDeg = rotationDegrees
            isFront = isFrontCamera
            hasNewCameraFrame = true
        }
    }

    fun submitSegmentationMask(
        maskAlpha: FloatArray,
        width: Int,
        height: Int
    ) {
        if (width <= 0 || height <= 0 || maskAlpha.size < width * height) return
        val count = width * height
        synchronized(lock) {
            if (maskByteArray.size < count) {
                maskByteArray = ByteArray(count)
            }
            if (maskBuffer == null || maskBuffer!!.capacity() < count) {
                maskBuffer = ByteBuffer.allocateDirect(count).order(ByteOrder.nativeOrder())
            }
            for (i in 0 until count) {
                maskByteArray[i] = (maskAlpha[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
            maskBuffer!!.apply {
                clear()
                put(maskByteArray, 0, count)
                position(0)
            }
            maskW = width
            maskH = height
            hasNewMask = true
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        releasedFlag.set(false)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (programId == 0) return

        posHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
        yTexHandle = GLES20.glGetUniformLocation(programId, "uTexY")
        uTexHandle = GLES20.glGetUniformLocation(programId, "uTexU")
        vTexHandle = GLES20.glGetUniformLocation(programId, "uTexV")
        maskTexHandle = GLES20.glGetUniformLocation(programId, "uTexMask")
        uBlurRadiusHandle = GLES20.glGetUniformLocation(programId, "uBlurRadius")
        uBlurActiveHandle = GLES20.glGetUniformLocation(programId, "uBlurActive")
        uMirrorHandle = GLES20.glGetUniformLocation(programId, "uMirror")
        uRotationHandle = GLES20.glGetUniformLocation(programId, "uRotationCode")
        uCropScaleHandle = GLES20.glGetUniformLocation(programId, "uCropScale")
        uTexelStepHandle = GLES20.glGetUniformLocation(programId, "uTexelStep")
        uColorMatrixHandle = GLES20.glGetUniformLocation(programId, "uColorMatrix")
        uColorOffsetHandle = GLES20.glGetUniformLocation(programId, "uColorOffset")
        uVignetteHandle = GLES20.glGetUniformLocation(programId, "uVignette")
        uHasMaskHandle = GLES20.glGetUniformLocation(programId, "uHasMask")

        GLES20.glGenTextures(4, textureIds, 0)
        for (id in textureIds) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        texInitialized = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportW, viewportH)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (releasedFlag.get() || programId == 0 || !texInitialized) return
        val hasCurrentContext = try {
            EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT &&
                EGL14.eglGetCurrentDisplay() != EGL14.EGL_NO_DISPLAY
        } catch (_: Throwable) {
            false
        }
        if (!hasCurrentContext) return

        var curW: Int
        var curH: Int
        var curRot: Int
        var curFront: Boolean
        var hasFrame = false
        var hasMaskTex = false

        synchronized(lock) {
            curW = frameW
            curH = frameH
            curRot = rotationDeg
            curFront = isFront
            if (curW > 0 && curH > 0) {
                hasFrame = true
                if (hasNewCameraFrame) {
                    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                    uploadLuminancePlane(textureIds[0], GLES20.GL_TEXTURE0, curW, curH, yBuffer)
                    uploadLuminancePlane(textureIds[1], GLES20.GL_TEXTURE1, curW / 2, curH / 2, uBuffer)
                    uploadLuminancePlane(textureIds[2], GLES20.GL_TEXTURE2, curW / 2, curH / 2, vBuffer)
                    hasNewCameraFrame = false
                }
            }
            if (maskW > 0 && maskH > 0 && maskBuffer != null) {
                hasMaskTex = true
                if (hasNewMask) {
                    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                    uploadLuminancePlane(textureIds[3], GLES20.GL_TEXTURE3, maskW, maskH, maskBuffer)
                    hasNewMask = false
                }
            }
        }

        if (!hasFrame) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }

        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])
        GLES20.glUniform1i(yTexHandle, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[1])
        GLES20.glUniform1i(uTexHandle, 1)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[2])
        GLES20.glUniform1i(vTexHandle, 2)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[3])
        GLES20.glUniform1i(maskTexHandle, 3)

        val swapped = (curRot == 90 || curRot == 270)
        val uprightW = if (swapped) curH.toFloat() else curW.toFloat()
        val uprightH = if (swapped) curW.toFloat() else curH.toFloat()
        val camAspect = (uprightW / uprightH.coerceAtLeast(1f)).coerceAtLeast(0.1f)
        val viewAspect = (viewportW.toFloat() / viewportH.coerceAtLeast(1)).coerceAtLeast(0.1f)

        val cropScaleX = if (camAspect > viewAspect) viewAspect / camAspect else 1f
        val cropScaleY = if (camAspect < viewAspect) camAspect / viewAspect else 1f
        GLES20.glUniform2f(uCropScaleHandle, cropScaleX, cropScaleY)

        val rotCode = when (curRot) {
            90 -> 1
            180 -> 2
            270 -> 3
            else -> 0
        }
        GLES20.glUniform1i(uRotationHandle, rotCode)
        GLES20.glUniform1f(uMirrorHandle, if (curFront) 1f else 0f)
        GLES20.glUniform1f(uHasMaskHandle, if (hasMaskTex) 1f else 0f)

        val blurVal = blurIntensity.coerceIn(0f, 1f)
        val radiusUv = 0.004f + blurVal * 0.022f
        GLES20.glUniform1f(uBlurRadiusHandle, radiusUv)
        GLES20.glUniform1f(uBlurActiveHandle, if (blurVal > 0.02f && hasMaskTex) 1f else 0f)
        GLES20.glUniform2f(uTexelStepHandle, 1f / uprightW.coerceAtLeast(1f), 1f / uprightH.coerceAtLeast(1f))

        val filter = activeFilter
        val cm = filter.toAndroidColorMatrix()?.array
        if (cm != null && cm.size >= 20) {
            val mat3 = floatArrayOf(
                cm[0], cm[5], cm[10],
                cm[1], cm[6], cm[11],
                cm[2], cm[7], cm[12]
            )
            GLES20.glUniformMatrix3fv(uColorMatrixHandle, 1, false, mat3, 0)
            GLES20.glUniform3f(uColorOffsetHandle, cm[4] / 255f, cm[9] / 255f, cm[14] / 255f)
            GLES20.glUniform1f(uVignetteHandle, filter.gradingSpec.vignetteStrength.coerceIn(0f, 0.5f))
        } else {
            GLES20.glUniformMatrix3fv(uColorMatrixHandle, 1, false, IDENTITY_MAT3, 0)
            GLES20.glUniform3f(uColorOffsetHandle, 0f, 0f, 0f)
            GLES20.glUniform1f(uVignetteHandle, 0f)
        }

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        onFrameRenderedListener?.invoke(System.nanoTime())
    }

    fun clearCpuBuffers() {
        synchronized(lock) {
            hasNewCameraFrame = false
            hasNewMask = false
            frameW = 0
            frameH = 0
            maskW = 0
            maskH = 0
            yBuffer = null
            uBuffer = null
            vBuffer = null
            maskBuffer = null
            maskByteArray = ByteArray(0)
        }
    }

    fun releaseGlTextures() {
        releasedFlag.set(true)
        clearCpuBuffers()
        val hasCurrentContext = try {
            EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT &&
                EGL14.eglGetCurrentDisplay() != EGL14.EGL_NO_DISPLAY
        } catch (_: Throwable) {
            false
        }
        if (hasCurrentContext) {
            if (texInitialized) {
                try {
                    GLES20.glDeleteTextures(textureIds.size, textureIds, 0)
                } catch (_: Throwable) {}
            }
            if (programId != 0) {
                try {
                    GLES20.glDeleteProgram(programId)
                } catch (_: Throwable) {}
            }
        }
        texInitialized = false
        programId = 0
    }

    private fun uploadLuminancePlane(
        texId: Int,
        activeUnit: Int,
        width: Int,
        height: Int,
        buf: ByteBuffer?
    ) {
        if (buf == null || width <= 0 || height <= 0) return
        buf.position(0)
        GLES20.glActiveTexture(activeUnit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_LUMINANCE,
            width,
            height,
            0,
            GLES20.GL_LUMINANCE,
            GLES20.GL_UNSIGNED_BYTE,
            buf
        )
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (vShader == 0 || fShader == 0) return 0
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vShader)
        GLES20.glAttachShader(program, fShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vShader)
        GLES20.glDeleteShader(fShader)
        if (linkStatus[0] == 0) {
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        // X, Y, U_topLeft, V_topLeft
        private val QUAD_VERTICES = floatArrayOf(
            -1f,  1f, 0f, 0f,
            -1f, -1f, 0f, 1f,
             1f,  1f, 1f, 0f,
             1f, -1f, 1f, 1f
        )

        private val IDENTITY_MAT3 = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )

        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vViewUv;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vViewUv = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vViewUv;

            uniform sampler2D uTexY;
            uniform sampler2D uTexU;
            uniform sampler2D uTexV;
            uniform sampler2D uTexMask;

            uniform vec2 uCropScale;
            uniform float uMirror;
            uniform int uRotationCode;
            uniform float uBlurRadius;
            uniform float uBlurActive;
            uniform float uHasMask;
            uniform vec2 uTexelStep;

            uniform mat3 uColorMatrix;
            uniform vec3 uColorOffset;
            uniform float uVignette;

            vec2 mapUprightToRaw(vec2 uv) {
                float mx = mix(uv.x, 1.0 - uv.x, uMirror);
                float ny = uv.y;
                if (uRotationCode == 1) {
                    return vec2(ny, 1.0 - mx);
                } else if (uRotationCode == 2) {
                    return vec2(1.0 - mx, 1.0 - ny);
                } else if (uRotationCode == 3) {
                    return vec2(1.0 - ny, mx);
                } else {
                    return vec2(mx, ny);
                }
            }

            vec3 sampleRgbUpright(vec2 uprightUv) {
                vec2 rawUv = clamp(mapUprightToRaw(clamp(uprightUv, 0.0, 1.0)), 0.0, 1.0);
                float y = texture2D(uTexY, rawUv).r;
                float u = texture2D(uTexU, rawUv).r - 0.5;
                float v = texture2D(uTexV, rawUv).r - 0.5;
                float r = y + 1.402 * v;
                float g = y - 0.344136 * u - 0.714136 * v;
                float b = y + 1.772 * u;
                return clamp(vec3(r, g, b), 0.0, 1.0);
            }

            float sampleMaskUpright(vec2 uprightUv) {
                if (uHasMask < 0.5) return 1.0;
                float mx = mix(uprightUv.x, 1.0 - uprightUv.x, uMirror);
                vec2 mUv = clamp(vec2(mx, uprightUv.y), 0.0, 1.0);
                return texture2D(uTexMask, mUv).r;
            }

            void main() {
                vec2 uprightUv = (vViewUv - 0.5) * uCropScale + 0.5;
                vec3 sharpRgb = sampleRgbUpright(uprightUv);
                float centerMask = sampleMaskUpright(uprightUv);

                vec3 finalRgb = sharpRgb;
                if (uBlurActive > 0.5 && centerMask < 0.96) {
                    vec3 accRgb = sharpRgb * 0.15;
                    float accW = 0.15;
                    vec2 aspectFix = vec2(1.0, max(0.5, uTexelStep.y / max(uTexelStep.x, 0.0001)));

                    // 12-tap Vogel aperture disk with foreground occlusion protection (prevents foreground halos)
                    for (int k = 0; k < 12; k++) {
                        float fk = float(k);
                        float r = sqrt((fk + 0.5) / 12.0) * uBlurRadius;
                        float angle = fk * 2.3999632;
                        vec2 offset = vec2(cos(angle), sin(angle)) * r;
                        vec2 sampleUv = clamp(uprightUv + offset, 0.0, 1.0);
                        float sMask = sampleMaskUpright(sampleUv);
                        float bgWeight = max(0.05, 1.0 - sMask * 0.90);
                        vec3 sRgb = sampleRgbUpright(sampleUv);
                        accRgb += sRgb * bgWeight;
                        accW += bgWeight;
                    }
                    vec3 blurRgb = accRgb / max(accW, 0.0001);
                    float fgBlend = smoothstep(0.22, 0.86, centerMask);
                    finalRgb = mix(blurRgb, sharpRgb, fgBlend);
                }

                // Apply active CameraFilter grading + optical vignette
                finalRgb = clamp(uColorMatrix * finalRgb + uColorOffset, 0.0, 1.0);
                if (uVignette > 0.001) {
                    vec2 d = vViewUv - 0.5;
                    float vig = 1.0 - dot(d, d) * uVignette * 2.1;
                    finalRgb *= clamp(vig, 0.45, 1.0);
                }

                gl_FragColor = vec4(finalRgb, 1.0);
            }
        """
    }
}
