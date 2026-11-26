package com.example.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.example.model.WatermarkConfig
import com.example.model.WatermarkFont
import com.example.model.WatermarkLogoAlignment
import com.example.model.WatermarkLogoType
import com.example.model.WatermarkPosition
import com.example.model.WatermarkStyle
import com.example.model.WatermarkTextAlignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object WatermarkPainter {

    @JvmOverloads
    fun applyWatermark(
        source: Bitmap,
        config: WatermarkConfig,
        metadata: RealCameraMetadata,
        context: Context? = null,
        recycleSource: Boolean = false
    ): Bitmap {
        if (!config.isEnabled) return source

        return when (config.style) {
            WatermarkStyle.LEICA_FRAME -> drawLeicaFrame(source, config, metadata, context, recycleSource)
            WatermarkStyle.GLASS_FRAME -> drawGlassFrame(source, config, metadata, context, recycleSource)
            WatermarkStyle.HASSELBLAD -> drawHasselbladFrame(source, config, metadata, context, recycleSource)
            WatermarkStyle.CYBERPUNK -> drawCyberpunkHud(source, config, metadata, recycleSource)
            WatermarkStyle.RETRO_90S -> drawRetroDateStamp(source, config, metadata, recycleSource)
            WatermarkStyle.STUDIO_BRAND -> drawStudioBrand(source, config, metadata, context, recycleSource)
            WatermarkStyle.MINIMAL_SIGNATURE -> drawMinimalSignature(source, config, metadata, recycleSource)
            WatermarkStyle.METADATA_STRIP -> drawMetadataStrip(source, config, metadata, context, recycleSource)
            WatermarkStyle.CUSTOM -> drawCustomWatermark(source, config, metadata, context, recycleSource)
        }
    }

    /**
     * Backwards-compatible overload taking an exifData string.
     */
    fun applyWatermark(
        source: Bitmap,
        config: WatermarkConfig,
        exifData: String = "",
        context: Context? = null
    ): Bitmap {
        val meta = CameraMetadataUtil.fallbackMetadata(source.width, source.height)
        return applyWatermark(source, config, meta, context)
    }

    private fun createBitmapOomSafe(width: Int, height: Int): Bitmap {
        return try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            clearSampleCache()
            System.gc()
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }

    private fun obtainMutableTarget(source: Bitmap, recycleSource: Boolean): Bitmap {
        if (recycleSource && source.isMutable && source.config == Bitmap.Config.ARGB_8888) {
            return source
        }
        val copy = try {
            source.copy(Bitmap.Config.ARGB_8888, true)
        } catch (_: OutOfMemoryError) {
            clearSampleCache()
            System.gc()
            source.copy(Bitmap.Config.ARGB_8888, true)
        }
        if (recycleSource && copy !== source && !source.isRecycled) {
            source.recycle()
        }
        return copy
    }

    private val logoCache = LruCache<String, Bitmap>(16)

    private fun isAllowedLogoUri(context: Context, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        return when (scheme) {
            "content", "android.resource" -> true
            "file" -> {
                val path = uri.path ?: return false
                try {
                    val target = java.io.File(path).canonicalPath
                    val forbiddenRoots = listOfNotNull(
                        context.filesDir,
                        context.noBackupFilesDir,
                        context.filesDir.parentFile?.let { java.io.File(it, "shared_prefs") },
                        context.filesDir.parentFile?.let { java.io.File(it, "databases") }
                    )
                    forbiddenRoots.none { root ->
                        val rootPath = root.canonicalPath
                        target == rootPath || target.startsWith(rootPath + java.io.File.separator)
                    }
                } catch (_: Throwable) {
                    false
                }
            }
            else -> false
        }
    }

    private fun decodeSampledUriLogo(context: Context, uri: Uri, targetSize: Int): Bitmap? {
        if (!isAllowedLogoUri(context, uri)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        var sampleSize = 1
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            while ((bounds.outWidth / sampleSize) > targetSize * 2 &&
                (bounds.outHeight / sampleSize) > targetSize * 2
            ) {
                sampleSize *= 2
            }
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        } ?: return null

        val maxDim = targetSize.coerceAtLeast(24)
        val rawW = raw.width.coerceAtLeast(1)
        val rawH = raw.height.coerceAtLeast(1)
        val fitScale = minOf(maxDim.toFloat() / rawW, maxDim.toFloat() / rawH)
        val outW = (rawW * fitScale).roundToInt().coerceAtLeast(1)
        val outH = (rawH * fitScale).roundToInt().coerceAtLeast(1)
        return if (raw.width == outW && raw.height == outH) {
            raw
        } else {
            val scaled = Bitmap.createScaledBitmap(raw, outW, outH, true)
            if (scaled !== raw) raw.recycle()
            scaled
        }
    }

    private fun getOrCreateOpaqueLogo(
        context: Context?,
        logoType: WatermarkLogoType,
        customLogoUri: String?,
        targetSize: Int,
        manufacturerName: String?
    ): Bitmap? {
        if (logoType == WatermarkLogoType.NONE) return null

        val key = when (logoType) {
            WatermarkLogoType.DEVICE_MANUFACTURER -> "MFR|${manufacturerName.orEmpty().lowercase(Locale.US)}|$targetSize"
            WatermarkLogoType.CUSTOM -> "CUSTOM|${customLogoUri.orEmpty()}|$targetSize"
            WatermarkLogoType.APP_ICON -> "APP|${context != null}|$targetSize"
            WatermarkLogoType.NONE -> return null
        }

        synchronized(logoCache) {
            logoCache.get(key)?.let { cached ->
                if (!cached.isRecycled) return cached
                logoCache.remove(key)
            }
        }

        val generated: Bitmap? = when (logoType) {
            WatermarkLogoType.DEVICE_MANUFACTURER -> {
                val make = manufacturerName?.takeIf { it.isNotBlank() } ?: return null
                ManufacturerLogoUtil.createManufacturerLogo(make, targetSize)
            }
            WatermarkLogoType.CUSTOM -> {
                val customDecoded = if (!customLogoUri.isNullOrBlank() && context != null) {
                    try {
                        decodeSampledUriLogo(context, Uri.parse(customLogoUri), targetSize)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                customDecoded ?: createAppOrVectorLogo(context, targetSize)
            }
            WatermarkLogoType.APP_ICON -> createAppOrVectorLogo(context, targetSize)
            WatermarkLogoType.NONE -> null
        }

        if (generated != null && !generated.isRecycled) {
            synchronized(logoCache) {
                logoCache.put(key, generated)
            }
        }
        return generated
    }

    private fun createAppOrVectorLogo(context: Context?, targetSize: Int): Bitmap {
        if (context != null) {
            try {
                val resId = context.resources.getIdentifier("studo_logo_lens", "drawable", context.packageName)
                if (resId != 0) {
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    val rawBmp = BitmapFactory.decodeResource(context.resources, resId, opts)
                    if (rawBmp != null) {
                        val rounded = createCircularEmblem(rawBmp, targetSize)
                        if (rounded !== rawBmp) rawBmp.recycle()
                        return rounded
                    }
                }

                val drawable = ContextCompat.getDrawable(context, com.example.R.mipmap.ic_launcher)
                    ?: ContextCompat.getDrawable(context, com.example.R.drawable.ic_launcher_foreground)
                if (drawable != null) {
                    val bmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, targetSize, targetSize)
                    drawable.draw(canvas)
                    return bmp
                }
            } catch (_: Exception) {}
        }
        return createVectorCameraEmblem(targetSize, 1.0f)
    }

    private fun createLogoAlphaPaint(opacity: Float): Paint? {
        val clamped = opacity.coerceIn(0f, 1f)
        if (clamped >= 0.99f) return null
        return Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (clamped * 255).roundToInt()
        }
    }

    /**
     * Loads the authentic application launcher icon, official device manufacturer logo, or custom user uploaded image.
     * Never displays generic red circles or placeholder letters.
     * Public callers may recycle the returned Bitmap, so returns a safe copy when cached.
     */
    fun loadLogoBitmap(
        context: Context?,
        config: WatermarkConfig,
        targetSizePx: Int,
        metadata: RealCameraMetadata? = null
    ): Bitmap? {
        if (config.logoType == WatermarkLogoType.NONE) return null

        val finalSize = (targetSizePx * config.logoScale).toInt().coerceIn(24, 700)
        val opaque = getOrCreateOpaqueLogo(
            context = context,
            logoType = config.logoType,
            customLogoUri = config.customLogoUri,
            targetSize = finalSize,
            manufacturerName = if (metadata != null) metadata.manufacturer else Build.MANUFACTURER
        ) ?: return null

        val copy = opaque.copy(Bitmap.Config.ARGB_8888, true)
        return applyOpacity(copy, config.logoOpacity)
    }

    private fun createCircularEmblem(source: Bitmap, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val radius = size / 2f

        val path = Path().apply { addCircle(radius, radius, radius - 1.5f, Path.Direction.CW) }
        canvas.clipPath(path)

        val srcRect = Rect(0, 0, source.width, source.height)
        val destRect = Rect(0, 0, size, size)
        canvas.drawBitmap(source, srcRect, destRect, paint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFE57F")
            style = Paint.Style.STROKE
            strokeWidth = (size * 0.04f).coerceAtLeast(1f)
        }
        canvas.drawCircle(radius, radius, radius - (size * 0.02f), ringPaint)

        return output
    }

    private fun createVectorCameraEmblem(size: Int, opacity: Float): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = size / 2f
        val radius = size * 0.46f

        val barrelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#181920")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius, barrelPaint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFB74D")
            style = Paint.Style.STROKE
            strokeWidth = (size * 0.08f).coerceAtLeast(1f)
        }
        canvas.drawCircle(center, center, radius * 0.85f, ringPaint)

        val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                center - radius * 0.5f, center - radius * 0.5f,
                center + radius * 0.5f, center + radius * 0.5f,
                Color.parseColor("#1A237E"),
                Color.parseColor("#00E5FF"),
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius * 0.55f, glassPaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFE57F")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius * 0.2f, dotPaint)

        return applyOpacity(bmp, opacity)
    }

    private fun applyOpacity(bitmap: Bitmap, opacity: Float): Bitmap {
        if (opacity >= 0.99f) return bitmap
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        bitmap.recycle()
        return output
    }

    private fun resolveTypeface(font: WatermarkFont, isBold: Boolean): Typeface {
        val weight = if (isBold) Typeface.BOLD else Typeface.NORMAL
        return when (font) {
            WatermarkFont.SANS_SERIF -> Typeface.create(Typeface.SANS_SERIF, weight)
            WatermarkFont.SERIF -> Typeface.create(Typeface.SERIF, weight)
            WatermarkFont.MONOSPACE -> Typeface.create(Typeface.MONOSPACE, weight)
        }
    }

    private fun parseColorSafely(hex: String, defaultColor: Int): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            defaultColor
        }
    }

    private fun applyColorOpacity(colorInt: Int, opacity: Float, baseAlphaRatio: Float = 1f): Int {
        val alpha = ((Color.alpha(colorInt) / 255f) * opacity.coerceIn(0.05f, 1f) * baseAlphaRatio * 255f)
            .roundToInt()
            .coerceIn(0, 255)
        return Color.argb(alpha, Color.red(colorInt), Color.green(colorInt), Color.blue(colorInt))
    }

    private fun ellipsizeToFit(text: String, paint: Paint, maxWidth: Float): String {
        if (text.isEmpty() || maxWidth <= 4f) return ""
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisW = paint.measureText(ellipsis)
        val avail = (maxWidth - ellipsisW).coerceAtLeast(0f)
        if (avail <= 0f) return ellipsis
        val count = paint.breakText(text, true, avail, null).coerceIn(0, text.length)
        return if (count <= 0) ellipsis else text.substring(0, count).trimEnd() + ellipsis
    }

    private fun proportionalPadding(shortSide: Float, paddingDp: Int): Float {
        val normalized = paddingDp.coerceIn(4, 32) / 32f
        return (shortSide * (0.016f + normalized * 0.044f)).coerceAtLeast(4f)
    }

    private fun proportionalLogoGap(shortSide: Float, spacingDp: Int): Float {
        val normalized = spacingDp.coerceIn(4, 32) / 32f
        return (shortSide * (0.008f + normalized * 0.032f)).coerceAtLeast(2f)
    }

    // -------------------------------------------------------------------------
    // 1. LEICA WHITE FRAME (Clean studio white frame with optical telemetry)
    // -------------------------------------------------------------------------
    private fun drawLeicaFrame(
        source: Bitmap,
        config: WatermarkConfig,
        metadata: RealCameraMetadata,
        context: Context?,
        recycleSource: Boolean = false
    ): Bitmap {
        val width = source.width
        val height = source.height
        val shortSide = minOf(width, height).toFloat()
        val scale = config.fontSizeScale.coerceIn(0.7f, 2.0f)
        val pad = proportionalPadding(shortSide, config.paddingDp)

        val barHeight = (shortSide * 0.112f * scale).roundToInt().coerceAtLeast(28)
        val output = createBitmapOomSafe(width, height + barHeight)
        val canvas = Canvas(output)
        canvas.drawColor(parseColorSafely(config.backgroundColorHex, Color.WHITE))
        canvas.drawBitmap(source, 0f, 0f, null)
        if (recycleSource && !source.isRecycled) source.recycle()

        val barTop = height.toFloat()
        val centerY = barTop + barHeight / 2f
        val lineSpread = barHeight * (0.14f * config.lineSpacing.coerceIn(0.9f, 1.8f))

        val logoSize = ((barHeight * 0.48f) * config.logoScale).roundToInt().coerceIn(16, 700)
        val logoBitmap = if (config.logoType == WatermarkLogoType.NONE) null else {
            getOrCreateOpaqueLogo(context, config.logoType, config.customLogoUri, logoSize, metadata.manufacturer)
        }
        val logoGap = proportionalLogoGap(shortSide, config.logoSpacingDp)
        val logoOnEnd = config.logoAlignment == WatermarkLogoAlignment.END

        var leftStartX = pad
        var rightEndX = width - pad
        if (logoBitmap != null && !logoBitmap.isRecycled) {
            val logoY = centerY - logoBitmap.height / 2f
            if (logoOnEnd) {
                val logoX = rightEndX - logoBitmap.width
                canvas.drawBitmap(logoBitmap, logoX, logoY, createLogoAlphaPaint(config.logoOpacity))
                rightEndX = logoX - logoGap
            } else {
                canvas.drawBitmap(logoBitmap, leftStartX, logoY, createLogoAlphaPaint(config.logoOpacity))
                leftStartX += logoBitmap.width + logoGap
            }
        }

        val titleColor = applyColorOpacity(
            parseColorSafely(config.textColorHex, Color.parseColor("#1A1A1A")),
            config.textOpacity
        )
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = titleColor
            textSize = (barHeight * 0.28f).coerceAtLeast(6f)
            typeface = resolveTypeface(config.font, config.isBold)
            letterSpacing = config.letterSpacing
            if (config.hasShadow) {
                setShadowLayer((barHeight * 0.035f).coerceAtLeast(1f), 1f, 1f, Color.parseColor("#33000000"))
            }
        }
        val customText = config.customText.ifBlank { "STU•D•O" }

        val subColor = applyColorOpacity(
            parseColorSafely(config.textColorHex, Color.parseColor("#1A1A1A")),
            config.textOpacity,
            baseAlphaRatio = 0.62f
        )
        val subTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = subColor
            textSize = (barHeight * 0.17f).coerceAtLeast(5f)
            typeface = resolveTypeface(config.font, false)
            letterSpacing = config.letterSpacing * 0.65f
        }
        val secondaryText = config.secondaryText.ifBlank {
            if (config.showDeviceModel && !metadata.deviceModel.isNullOrBlank()) metadata.deviceModel else ""
        }

        val exifText = metadata.formatAvailableExposureTriangle(config)
        val dateText = if (config.showDateTime) metadata.dateTimeFormatted.orEmpty() else ""
        val resText = if (config.showResolution) metadata.resolutionText.orEmpty() else ""
        val metaSecond = when {
            dateText.isNotBlank() && resText.isNotBlank() -> "$dateText · $resText"
            dateText.isNotBlank() -> dateText
            resText.isNotBlank() -> resText
            else -> ""
        }

        val exifPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = applyColorOpacity(Color.parseColor("#212121"), config.textOpacity)
            textSize = (barHeight * 0.22f).coerceAtLeast(5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = applyColorOpacity(Color.parseColor("#6E6E73"), config.textOpacity)
            textSize = (barHeight * 0.16f).coerceAtLeast(4f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }

        val innerAvailW = (rightEndX - leftStartX).coerceAtLeast(24f)
        val colGap = (barHeight * 0.20f).coerceAtLeast(6f)
        val rightLimitW = innerAvailW * 0.48f
        val safeExif = ellipsizeToFit(exifText, exifPaint, rightLimitW)
        val safeMetaSecond = ellipsizeToFit(metaSecond, datePaint, rightLimitW)
        val usedRightW = maxOf(
            if (safeExif.isNotBlank()) exifPaint.measureText(safeExif) else 0f,
            if (safeMetaSecond.isNotBlank()) datePaint.measureText(safeMetaSecond) else 0f
        )
        val leftLimitW = (innerAvailW - (if (usedRightW > 0f) usedRightW + colGap else 0f)).coerceAtLeast(24f)
        val safeTitle = ellipsizeToFit(customText, titlePaint, leftLimitW)
        val safeSub = ellipsizeToFit(secondaryText, subTitlePaint, leftLimitW)

        val leftHasTwoLines = safeSub.isNotBlank()
        val rightHasTwoLines = safeExif.isNotBlank() && safeMetaSecond.isNotBlank()
        val line1Y = (centerY - lineSpread * 0.45f).coerceAtLeast(barTop + titlePaint.textSize * 0.95f)
        val line2Y = (centerY + lineSpread * 1.15f).coerceAtMost(barTop + barHeight - subTitlePaint.descent() - 2f)
        val singleLeftY = centerY - ((titlePaint.descent() + titlePaint.ascent()) / 2f)
        val singleRightY = centerY - ((exifPaint.descent() + exifPaint.ascent()) / 2f)

        if (leftHasTwoLines) {
            canvas.drawText(safeTitle, leftStartX, line1Y, titlePaint)
            canvas.drawText(safeSub, leftStartX, line2Y, subTitlePaint)
        } else {
            canvas.drawText(safeTitle, leftStartX, singleLeftY, titlePaint)
        }

        if (rightHasTwoLines) {
            canvas.drawText(safeExif, rightEndX, line1Y, exifPaint)
            canvas.drawText(safeMetaSecond, rightEndX, line2Y, datePaint)
        } else if (safeExif.isNotBlank()) {
            canvas.drawText(safeExif, rightEndX, singleRightY, exifPaint)
        } else if (safeMetaSecond.isNotBlank()) {
            val singleDateY = centerY - ((datePaint.descent() + datePaint.ascent()) / 2f)
            canvas.drawText(safeMetaSecond, rightEndX, singleDateY, datePaint)
        }

        return output
    }

    // -------------------------------------------------------------------------
    // 2. HASSELBLAD FILM FRAME (Dark charcoal studio bar with amber telemetry)
    // -------------------------------------------------------------------------
    private fun drawHasselbladFrame(
        source: Bitmap,
        config: WatermarkConfig,
        metadata: RealCameraMetadata,
        context: Context?,
        recycleSource: Boolean = false
    ): Bitmap {
        val width = source.width
        val height = source.height
        val shortSide = minOf(width, height).toFloat()
        val scale = config.fontSizeScale.coerceIn(0.7f, 2.0f)
        val pad = proportionalPadding(shortSide, config.paddingDp)

        val barHeight = (shortSide * 0.118f * scale).roundToInt().coerceAtLeast(28)
        val output = createBitmapOomSafe(width, height + barHeight)
        val canvas = Canvas(output)
        canvas.drawColor(parseColorSafely(config.backgroundColorHex, Color.parseColor("#101115")))
        canvas.drawBitmap(source, 0f, 0f, null)
        if (recycleSource && !source.isRecycled) source.recycle()

        val barTop = height.toFloat()
        val centerY = barTop + barHeight / 2f
        val lineSpread = barHeight * (0.14f * config.lineSpacing.coerceIn(0.9f, 1.8f))

        val logoSize = ((barHeight * 0.46f) * config.logoScale).roundToInt().coerceIn(16, 700)
        val logoBitmap = if (config.logoType == WatermarkLogoType.NONE) null else {
            getOrCreateOpaqueLogo(context, config.logoType, config.customLogoUri, logoSize, metadata.manufacturer)
        }
        val logoGap = proportionalLogoGap(shortSide, config.logoSpacingDp)
        val logoOnEnd = config.logoAlignment == WatermarkLogoAlignment.END

        var leftStartX = pad
        var rightEndX = width - pad
        if (logoBitmap != null && !logoBitmap.isRecycled) {
            val logoY = centerY - logoBitmap.height / 2f
            if (logoOnEnd) {
                val logoX = rightEndX - logoBitmap.width
                canvas.drawBitmap(logoBitmap, logoX, logoY, createLogoAlphaPaint(config.logoOpacity))
                rightEndX = logoX - logoGap
            } else {
                canvas.drawBitmap(logoBitmap, leftStartX, logoY, createLogoAlphaPaint(config.logoOpacity))
                leftStartX += logoBitmap.width + logoGap
            }
        }

        val titleColor = applyColorOpacity(
            parseColorSafely(config.textColorHex, Color.parseColor("#F5F5F7")),
            config.textOpacity
        )
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = titleColor
            textSize = (barHeight * 0.27f).coerceAtLeast(6f)
            typeface = resolveTypeface(config.font, config.isBold)
            letterSpacing = config.letterSpacing
            if (config.hasShadow) {
                setShadowLayer((barHeight * 0.035f).coerceAtLeast(1f), 1f, 1f, Color.BLACK)
            }
        }
        val customText = config.customText.ifBlank { "STU•D•O MASTER" }

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = applyColorOpacity(Color.parseColor("#B0B3C1"), config.textOpacity)
            textSize = (barHeight * 0.17f).coerceAtLeast(5f)
            typeface = resolveTypeface(config.font, false)
            letterSpacing = config.letterSpacing * 0.65f
        }

        val amberColor = applyColorOpacity(Color.parseColor("#FFA726"), config.textOpacity)
        val filmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = amberColor
            textSize = (barHeight * 0.22f).coerceAtLeast(5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = applyColorOpacity(Color.parseColor("#9E9E9E"), config.textOpacity)
            textSize = (barHeight * 0.16f).coerceAtLeast(4f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }

        val exif = metadata.formatAvailableExposureTriangle(config)
        val date = if (config.showDateTime) metadata.dateTimeFormatted.orEmpty() else ""
        val device = if (config.showDeviceModel) metadata.deviceModel.orEmpty() else ""
        val secondRow = when {
            device.isNotBlank() && date.isNotBlank() -> "[$device · $date]"
            device.isNotBlank() -> "[$device]"
            date.isNotBlank() -> "[$date]"
            else -> ""
        }

        val innerAvailW = (rightEndX - leftStartX).coerceAtLeast(24f)
        val colGap = (barHeight * 0.20f).coerceAtLeast(6f)
        val rightLimitW = innerAvailW * 0.48f
        val safeExif = ellipsizeToFit(exif, filmPaint, rightLimitW)
        val safeSecondRow = ellipsizeToFit(secondRow, metaPaint, rightLimitW)
        val usedRightW = maxOf(
            if (safeExif.isNotBlank()) filmPaint.measureText(safeExif) else 0f,
            if (safeSecondRow.isNotBlank()) metaPaint.measureText(safeSecondRow) else 0f
        )
        val leftLimitW = (innerAvailW - (if (usedRightW > 0f) usedRightW + colGap else 0f)).coerceAtLeast(24f)
        val safeTitle = ellipsizeToFit(customText, titlePaint, leftLimitW)
        val safeSub = ellipsizeToFit(config.secondaryText, subPaint, leftLimitW)

        val line1Y = (centerY - lineSpread * 0.45f).coerceAtLeast(barTop + titlePaint.textSize * 0.95f)
        val line2Y = (centerY + lineSpread * 1.15f).coerceAtMost(barTop + barHeight - subPaint.descent() - 2f)

        if (safeSub.isNotBlank()) {
            canvas.drawText(safeTitle, leftStartX, line1Y, titlePaint)
            canvas.drawText(safeSub, leftStartX, line2Y, subPaint)
        } else {
            val singleLeftY = centerY - ((titlePaint.descent() + titlePaint.ascent()) / 2f)
            canvas.drawText(safeTitle, leftStartX, singleLeftY, titlePaint)
        }

        if (safeExif.isNotBlank() && safeSecondRow.isNotBlank()) {
            canvas.drawText(safeExif, rightEndX, line1Y, filmPaint)
            canvas.drawText(safeSecondRow, rightEndX, line2Y, metaPaint)
        } else if (safeExif.isNotBlank()) {
            val singleRightY = centerY - ((filmPaint.descent() + filmPaint.ascent()) / 2f)
            canvas.drawText(safeExif, rightEndX, singleRightY, filmPaint)
        } else if (safeSecondRow.isNotBlank()) {
            val singleMetaY = centerY - ((metaPaint.descent() + metaPaint.ascent()) / 2f)
            canvas.drawText(safeSecondRow, rightEndX, singleMetaY, metaPaint)
        }

        return output
    }

    // -------------------------------------------------------------------------
    // 3. CYBERPUNK TECHNICAL HUD (Sci-fi tech brackets & monospace readouts)
    // -------------------------------------------------------------------------
    private fun drawCyberpunkHud(
        source: Bitmap,
        config: WatermarkConfig,
        metadata: RealCameraMetadata,
        recycleSource: Boolean = false
    ): Bitmap {
        val output = obtainMutableTarget(source, recycleSource)
        val canvas = Canvas(output)
        val width = output.width.toFloat()
        val height = output.height.toFloat()
        val shortSide = minOf(width, height)
        val scale = config.fontSizeScale.coerceIn(0.7f, 2.0f)

        val accentColor = applyColorOpacity(
            parseColorSafely(config.textColorHex, Color.parseColor("#00E5FF")),
            config.textOpacity
        )
        val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            strokeWidth = (shortSide * 0.0045f).coerceAtLeast(1.5f)
            style = Paint.Style.STROKE
        }

        val pad = shortSide * 0.045f * config.marginScale.coerceIn(0.5f, 2.0f)
        val cornerLen = shortSide * 0.068f

        val brX = width - pad
        val brY = height - pad
        canvas.drawLine(brX - cornerLen, brY, brX, brY, bracketPaint)
        canvas.drawLine(brX, brY - cornerLen, brX, brY, bracketPaint)

        val blX = pad
        val blY = height - pad
        canvas.drawLine(blX, blY, blX + cornerLen, blY, bracketPaint)
        canvas.drawLine(blX, blY, blX, blY - cornerLen, bracketPaint)

        val textSizePx = (shortSide * 0.028f * scale).coerceAtLeast(8f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = textSizePx
            typeface = resolveTypeface(config.font, config.isBold)
            textAlign = Paint.Align.RIGHT
            letterSpacing = config.letterSpacing
            if (config.hasShadow) {
                setShadowLayer((textSizePx * 0.35f).coerceAtLeast(2f), 0f, 0f, accentColor)
            }
        }

        val customText = config.customText.ifBlank { "SYSTEM // STU•D•O CORE" }
        val exif = metadata.formatAvailableExposureTriangle(config)
        val date = if (config.showDateTime) metadata.dateTimeFormatted.orEmpty() else ""
        val dev = if (config.showDeviceModel) metadata.deviceModel.orEmpty() else ""

        val lines = buildList {
            add(customText)
            if (dev.isNotBlank()) add("DEV // $dev")
            if (exif.isNotBlank()) add("OPT // $exif")
            if (date.isNotBlank()) add("REC // $date")
        }

        val maxTextW = (width - pad * 2f - shortSide * 0.04f).coerceAtLeast(24f)
        val lineStep = textPaint.textSize * (1.15f * config.lineSpacing.coerceIn(0.9f, 1.8f))
        val textAnchorX = brX - shortSide * 0.022f
        var currentY = brY - shortSide * 0.020f

        for (i in lines.indices.reversed()) {
            val safeLine = ellipsizeToFit(lines[i], textPaint, maxTextW)
            canvas.drawText(safeLine, textAnchorX, currentY, textPaint)
            currentY -= lineStep
        }

        return output
    }

    // -------------------------------------------------------------------------
    // 4. RETRO '90S DATE STAMP (Digital camera 7-segment orange stamp)
    // -------------------------------------------------------------------------
    private fun drawRetroDateStamp(
        source: Bitmap,
        config: WatermarkConfig,
        metadata: RealCameraMetadata,
        recycleSource: Boolean = false
    ): Bitmap {
        val output = obtainMutableTarget(source, recycleSource)
        val canvas = Canvas(output)
        val width = output.width.toFloat()
        val height = output.height.toFloat()
        val shortSide = minOf(width, height)
        val scale = config.fontSizeScale.coerceIn(0.7f, 2.0f)

        val stampColor = applyColorOpacity(
            parseColorSafely(config.textColorHex, Color.parseColor("#FF9100")),
            config.textOpacity
        )
        val primarySize = (shortSide * 0.042f * scale).coerceAtLeast(10f)
        val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stampColor
            textSize = primarySize
            typeface = resolveTypeface(config.font, true)
            letterSpacing = config.letterSpacing
            if (config.hasShadow) {
                setShadowLayer(
                    (primarySize * 0.22f).coerceAtLeast(1.5f),
                    (primarySize * 0.06f).coerceAtLeast(0.5f),
                    (primarySize * 0.06f).coerceAtLeast(0.5f),
                    applyColorOpacity(Color.parseColor("#FF3D00"), config.textOpacity, 0.55f)
                )
            }
        }

        val dateStr = if (config.showDateTime) metadata.dateTimeFormatted.orEmpty() else ""
        val customLabel = config.customText.trim()
        if (dateStr.isBlank() && customLabel.isBlank()) return output

        val subPaint = Paint(stampPaint).apply {
            textSize = primarySize * 0.68f
        }

        val margin = shortSide * 0.045f * config.marginScale.coerceIn(0.5f, 2.0f)
        val maxContentW = (width - margin * 2f).coerceAtLeast(24f)

        val topLine: String
        val topPaint: Paint
        val bottomLine: String
        val bottomPaint: Paint
        when {
            dateStr.isNotBlank() && customLabel.isNotBlank() -> {
                topLine = ellipsizeToFit(customLabel, subPaint, maxContentW)
                topPaint = subPaint
                bottomLine = ellipsizeToFit(dateStr, stampPaint, maxContentW)
                bottomPaint = stampPaint
            }
            dateStr.isNotBlank() -> {
                topLine = ellipsizeToFit(dateStr, stampPaint, maxContentW)
                topPaint = stampPaint
                bottomLine = ""
                bottomPaint = stampPaint
            }
            else -> {
                topLine = ellipsizeToFit(customLabel, stampPaint, maxContentW)
                topPaint = stampPaint
                bottomLine = ""
                bottomPaint = stampPaint
            }
        }

        val topW = topPaint.measureText(topLine)
        val bottomW = if (bottomLine.isNotBlank()) bottomPaint.measureText(bottomLine) else 0f
        val contentW = maxOf(topW, bottomW).coerceAtLeast(1f)
        val topH = topPaint.descent() - topPaint.ascent()
        val gap = if (bottomLine.isNotBlank()) primarySize * 0.25f * config.lineSpacing.coerceIn(0.9f, 1.8f) else 0f
        val bottomH = if (bottomLine.isNotBlank()) (bottomPaint.descent() - bottomPaint.ascent()) else 0f
        val contentH = (topH + gap + bottomH).coerceAtLeast(1f)

        val (calcX, calcY) = calculateAnchoredPosition(
            config.position, width, height, margin, contentW, contentH,
            config.customOffsetX, config.customOffsetY
        )
        val boxX = calcX.coerceIn(margin, (width - contentW - margin).coerceAtLeast(margin))
        val boxY = calcY.coerceIn(margin, (height - contentH - margin).coerceAtLeast(margin))

        val effectiveAlign = when (config.textAlignment) {
            WatermarkTextAlignment.CENTER -> Paint.Align.CENTER
            WatermarkTextAlignment.END -> Paint.Align.RIGHT
            WatermarkTextAlignment.START -> when (config.position) {
                WatermarkPosition.BOTTOM_RIGHT, WatermarkPosition.TOP_RIGHT -> Paint.Align.RIGHT
                WatermarkPosition.BOTTOM_CENTER -> Paint.Align.CENTER
                else -> Paint.Align.LEFT
            }
        }
        val anchorX = when (effectiveAlign) {
            Paint.Align.LEFT -> boxX
            Paint.Align.CENTER -> boxX + contentW / 2f
            Paint.Align.RIGHT -> boxX + contentW
        }
        topPaint.textAlign = effectiveAlign
        bottomPaint.textAlign = effectiveAlign

        canvas.save()
        if (kotlin.math.abs(config.rotationDegrees) > 0.2f) {
            canvas.rotate(config.rotationDegrees.coerceIn(-45f, 45f), boxX + contentW / 2f, boxY + contentH / 2f)
        }
        val line1Baseline = boxY - topPaint.ascent()
        canvas.drawText(topLine, anchorX, line1Baseline, topPaint)
        if (bottomLine.isNotBlank()) {
            val line2Baseline = boxY + topH + gap - bottomPaint.ascent()
            canvas.drawText(bottomLine, anchorX, line2Baseline, bottomPaint)
        }
        canvas.restore()

        return output
    }

    // -------------------------------------------------------------------------
    // 5. STUDIO BRAND MONOGRAM
    // -------------------------------------------------------------------------
    private fun drawStudioBrand(
        source: Bitmap,
        config: WatermarkConfig,
        metadata: RealCameraMetadata,
        context: Context?,
        recycleSource: Boolean = false
    ): Bitmap {
        return drawCustomWatermark(source, config, metadata, context, recycleSource)
    }

    // -------------------------------------------------------------------------
    // 6. MINIMAL SIGNATURE (Elegant photographer watermark)
    // -------------------------------------------------------------------------
    private fun drawMinimalSignature(
        source: Bitmap,
        config: WatermarkConfig,
        metadata: RealCameraMetadata,
        recycleSource: Boolean = false
    ): Bitmap {
        val output = obtainMutableTarget(source, recycleSource)
        val canvas = Canvas(output)
        val width = output.width.toFloat()
        val height = output.height.toFloat()
        val shortSide = minOf(width, height)
        val scale = config.fontSizeScale.coerceIn(0.7f, 2.0f)
        val margin = shortSide * 0.042f * config.marginScale.coerceIn(0.5f, 2.0f)

        val baseColor = parseColorSafely(config.textColorHex, Color.WHITE)
        val primarySize = (shortSide * 0.032f * scale).coerceAtLeast(8f)
        val sigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = applyColorOpacity(baseColor, config.textOpacity)
            textSize = primarySize
            typeface = resolveTypeface(config.font, config.isBold)
            letterSpacing = config.letterSpacing
            if (config.hasShadow) {
                setShadowLayer((primarySize * 0.18f).coerceAtLeast(1.5f), 1f, 1f, applyColorOpacity(Color.BLACK, config.textOpacity, 0.7f))
            }
        }
        val subPaint = Paint(sigPaint).apply {
            textSize = primarySize * 0.68f
            typeface = resolveTypeface(config.font, false)
            color = applyColorOpacity(baseColor, config.textOpacity, 0.80f)
        }

        val text = config.customText.ifBlank { "by StuDO" }
        val dateYear = metadata.dateTimeFormatted?.take(4)?.takeIf { it.isNotBlank() }
        val date = if (config.showDateTime && dateYear != null) " · $dateYear" else ""
        val maxContentW = (width - margin * 2f).coerceAtLeast(24f)
        val combined = ellipsizeToFit("$text$date", sigPaint, maxContentW)
        val safeSub = if (config.secondaryText.isNotBlank()) ellipsizeToFit(config.secondaryText, subPaint, maxContentW) else ""

        val primaryW = sigPaint.measureText(combined)
        val subW = if (safeSub.isNotBlank()) subPaint.measureText(safeSub) else 0f
        val contentW = maxOf(primaryW, subW).coerceAtLeast(1f)
        val primaryH = sigPaint.descent() - sigPaint.ascent()
        val gap = if (safeSub.isNotBlank()) primarySize * 0.22f * config.lineSpacing.coerceIn(0.9f, 1.8f) else 0f
        val subH = if (safeSub.isNotBlank()) (subPaint.descent() - subPaint.ascent()) else 0f
        val contentH = (primaryH + gap + subH).coerceAtLeast(1f)

        val (calcX, calcY) = calculateAnchoredPosition(
            config.position, width, height, margin, contentW, contentH,
            config.customOffsetX, config.customOffsetY
        )
        val boxX = calcX.coerceIn(margin, (width - contentW - margin).coerceAtLeast(margin))
        val boxY = calcY.coerceIn(margin, (height - contentH - margin).coerceAtLeast(margin))

        val effectiveAlign = when (config.textAlignment) {
            WatermarkTextAlignment.CENTER -> Paint.Align.CENTER
            WatermarkTextAlignment.END -> Paint.Align.RIGHT
            WatermarkTextAlignment.START -> when (config.position) {
                WatermarkPosition.BOTTOM_RIGHT, WatermarkPosition.TOP_RIGHT -> Paint.Align.RIGHT
                WatermarkPosition.BOTTOM_CENTER -> Paint.Align.CENTER
                else -> Paint.Align.LEFT
            }
        }
        val anchorX = when (effectiveAlign) {
            Paint.Align.LEFT -> boxX
            Paint.Align.CENTER -> boxX + contentW / 2f
            Paint.Align.RIGHT -> boxX + contentW
        }
        sigPaint.textAlign = effectiveAlign
        subPaint.textAlign = effectiveAlign

        canvas.save()
        if (kotlin.math.abs(config.rotationDegrees) > 0.2f) {
            canvas.rotate(config.rotationDegrees.coerceIn(-45f, 45f), boxX + contentW / 2f, boxY + contentH / 2f)
        }
        canvas.drawText(combined, anchorX, boxY - sigPaint.ascent(), sigPaint)
        if (safeSub.isNotBlank()) {
            canvas.drawText(safeSub, anchorX, boxY + primaryH + gap - subPaint.ascent(), subPaint)
        }
        canvas.restore()

        return output
    }

    // -------------------------------------------------------------------------
    // 7. CLASSIC METADATA STRIP (Single-line compact camera metadata bar)
    // -------------------------------------------------------------------------
    private fun drawMetadataStrip(
        source: Bitmap,
        config: WatermarkConfig,
        metadata: RealCameraMetadata,
        context: Context?,
        recycleSource: Boolean = false
    ): Bitmap {
        val width = source.width
        val height = source.height
        val shortSide = minOf(width, height).toFloat()
        val scale = config.fontSizeScale.coerceIn(0.7f, 2.0f)
        val barHeight = (shortSide * 0.088f * scale).roundToInt().coerceAtLeast(22)

        val output = createBitmapOomSafe(width, height + barHeight)
        val canvas = Canvas(output)
        canvas.drawColor(parseColorSafely(config.backgroundColorHex, Color.parseColor("#0A0B0E")))
        canvas.drawBitmap(source, 0f, 0f, null)
        if (recycleSource && !source.isRecycled) source.recycle()

        val barTop = height.toFloat()
        val centerY = barTop + barHeight / 2f
        val padding = width * 0.035f
        val logoGap = proportionalLogoGap(shortSide, config.logoSpacingDp)

        val logoSize = ((barHeight * 0.50f) * config.logoScale).roundToInt().coerceIn(14, 700)
        val logoBitmap = if (config.logoType == WatermarkLogoType.NONE) null else {
            getOrCreateOpaqueLogo(context, config.logoType, config.customLogoUri, logoSize, metadata.manufacturer)
        }
        val logoOnEnd = config.logoAlignment == WatermarkLogoAlignment.END
        var leftStartX = padding
        var rightEndX = width - padding
        if (logoBitmap != null && !logoBitmap.isRecycled) {
            val logoY = centerY - logoBitmap.height / 2f
            if (logoOnEnd) {
                val logoX = rightEndX - logoBitmap.width
                canvas.drawBitmap(logoBitmap, logoX, logoY, createLogoAlphaPaint(config.logoOpacity))
                rightEndX = logoX - logoGap
            } else {
                canvas.drawBitmap(logoBitmap, leftStartX, logoY, createLogoAlphaPaint(config.logoOpacity))
                leftStartX += logoBitmap.width + logoGap
            }
        }

        val textColor = applyColorOpacity(
            parseColorSafely(config.textColorHex, Color.parseColor("#E0E0E0")),
            config.textOpacity
        )
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = (barHeight * 0.32f).coerceAtLeast(6f)
            typeface = resolveTypeface(config.font, config.isBold)
            letterSpacing = config.letterSpacing
        }
        val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = applyColorOpacity(Color.parseColor("#FFB74D"), config.textOpacity)
            textSize = (barHeight * 0.27f).coerceAtLeast(5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }

        val rightParts = mutableListOf<String>()
        if (config.showDeviceModel && !metadata.deviceModel.isNullOrBlank()) {
            rightParts.add(metadata.deviceModel)
        }
        val exif = metadata.formatAvailableExposureTriangle(config)
        if (exif.isNotBlank()) rightParts.add(exif)
        if (config.showDateTime && !metadata.dateTimeFormatted.isNullOrBlank()) {
            rightParts.add(metadata.dateTimeFormatted)
        }
        val rightText = rightParts.joinToString("  ·  ")

        val innerAvailW = (rightEndX - leftStartX).coerceAtLeast(24f)
        val colGap = (barHeight * 0.22f).coerceAtLeast(6f)
        val safeRight = ellipsizeToFit(rightText, monoPaint, innerAvailW * 0.62f)
        val usedRightW = if (safeRight.isNotBlank()) monoPaint.measureText(safeRight) else 0f
        val leftMaxW = (innerAvailW - (if (usedRightW > 0f) usedRightW + colGap else 0f)).coerceAtLeast(24f)
        val customText = ellipsizeToFit(config.customText.ifBlank { "STU•D•O" }, textPaint, leftMaxW)

        val leftBaselineY = centerY - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(customText, leftStartX, leftBaselineY, textPaint)

        if (safeRight.isNotBlank()) {
            val rightBaselineY = centerY - ((monoPaint.descent() + monoPaint.ascent()) / 2f)
            canvas.drawText(safeRight, rightEndX, rightBaselineY, monoPaint)
        }

        return output
    }

    // -------------------------------------------------------------------------
    // 8. FULL CUSTOM WATERMARK (Shared engine for Studio Brand & Custom Design)
    // -------------------------------------------------------------------------
    private fun drawCustomWatermark(
        source: Bitmap,
        config: WatermarkConfig,
        metadata: RealCameraMetadata,
        context: Context?,
        recycleSource: Boolean = false
    ): Bitmap {
        val output = obtainMutableTarget(source, recycleSource)
        val canvas = Canvas(output)
        val width = output.width.toFloat()
        val height = output.height.toFloat()
        val shortSide = minOf(width, height)
        val scale = config.fontSizeScale.coerceIn(0.6f, 2.2f)

        val text = config.customText.ifBlank { "STU•D•O" }
        val logoSize = ((shortSide * 0.062f) * config.logoScale).roundToInt().coerceIn(16, 700)
        val logoBitmap = if (config.logoType == WatermarkLogoType.NONE) null else {
            getOrCreateOpaqueLogo(context, config.logoType, config.customLogoUri, logoSize, metadata.manufacturer)
        }

        val textSizePx = (shortSide * 0.032f * scale).coerceAtLeast(8f)
        val baseColor = parseColorSafely(config.textColorHex, Color.WHITE)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = applyColorOpacity(baseColor, config.textOpacity)
            textSize = textSizePx
            typeface = resolveTypeface(config.font, config.isBold)
            letterSpacing = config.letterSpacing
            if (config.hasShadow) {
                setShadowLayer(
                    (textSizePx * 0.22f).coerceAtLeast(1.5f),
                    (textSizePx * 0.04f).coerceAtLeast(0.5f),
                    (textSizePx * 0.04f).coerceAtLeast(0.5f),
                    applyColorOpacity(Color.BLACK, config.textOpacity, 0.6f)
                )
            }
        }

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = applyColorOpacity(baseColor, config.textOpacity, 0.84f)
            textSize = textSizePx * 0.72f
            typeface = resolveTypeface(config.font, false)
            letterSpacing = config.letterSpacing * 0.7f
            if (config.hasShadow) {
                setShadowLayer(
                    (textSizePx * 0.16f).coerceAtLeast(1f),
                    (textSizePx * 0.03f).coerceAtLeast(0.5f),
                    (textSizePx * 0.03f).coerceAtLeast(0.5f),
                    applyColorOpacity(Color.BLACK, config.textOpacity, 0.55f)
                )
            }
        }

        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = applyColorOpacity(Color.parseColor("#FFB74D"), config.textOpacity)
            textSize = textSizePx * 0.66f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            if (config.hasShadow) {
                setShadowLayer(
                    (textSizePx * 0.14f).coerceAtLeast(1f),
                    (textSizePx * 0.03f).coerceAtLeast(0.5f),
                    (textSizePx * 0.03f).coerceAtLeast(0.5f),
                    applyColorOpacity(Color.BLACK, config.textOpacity, 0.55f)
                )
            }
        }

        val margin = shortSide * 0.04f * config.marginScale.coerceIn(0.5f, 2.0f)
        val pad = proportionalPadding(shortSide, config.paddingDp) * 0.72f
        val logoGap = proportionalLogoGap(shortSide, config.logoSpacingDp)
        val maxBoxW = (width - margin * 2f).coerceAtLeast(48f)
        val maxInnerW = (maxBoxW - pad * 2f).coerceAtLeast(24f)

        val metaLinesRaw = buildList {
            val exposure = metadata.formatAvailableExposureTriangle(config)
            if (exposure.isNotBlank()) add(exposure)
            val devAndRes = metadata.formatDeviceAndResolution(config)
            if (devAndRes.isNotBlank()) add(devAndRes)
            if (config.showDateTime && !metadata.dateTimeFormatted.isNullOrBlank()) {
                add(metadata.dateTimeFormatted)
            }
        }

        val logoW = logoBitmap?.width?.toFloat() ?: 0f
        val logoH = logoBitmap?.height?.toFloat() ?: 0f
        val isLogoTop = logoBitmap != null && config.logoAlignment == WatermarkLogoAlignment.TOP
        val isLogoSide = logoBitmap != null && !isLogoTop
        val sideTextAvailW = if (isLogoSide) (maxInnerW - logoW - logoGap).coerceAtLeast(24f) else maxInnerW

        val safeTitle = ellipsizeToFit(text, textPaint, sideTextAvailW)
        val safeSub = if (config.secondaryText.isNotBlank()) ellipsizeToFit(config.secondaryText, subPaint, sideTextAvailW) else ""
        val safeMetaLines = metaLinesRaw.map { ellipsizeToFit(it, metaPaint, maxInnerW) }.filter { it.isNotBlank() }

        val titleW = textPaint.measureText(safeTitle)
        val subW = if (safeSub.isNotBlank()) subPaint.measureText(safeSub) else 0f
        val titleBlockW = maxOf(titleW, subW)
        val headerW = when {
            isLogoTop -> maxOf(logoW, titleBlockW)
            isLogoSide -> logoW + logoGap + titleBlockW
            else -> titleBlockW
        }
        val maxMetaW = safeMetaLines.maxOfOrNull { metaPaint.measureText(it) } ?: 0f
        val contentWidth = maxOf(headerW, maxMetaW).coerceAtMost(maxInnerW)

        val lineMul = config.lineSpacing.coerceIn(0.9f, 1.8f)
        val titleH = textPaint.descent() - textPaint.ascent()
        val subGap = if (safeSub.isNotBlank()) subPaint.textSize * 0.30f * lineMul else 0f
        val subH = if (safeSub.isNotBlank()) subPaint.descent() - subPaint.ascent() else 0f
        val textStackH = titleH + subGap + subH

        val headerH = when {
            isLogoTop -> logoH + logoGap + textStackH
            isLogoSide -> maxOf(textStackH, logoH)
            else -> textStackH
        }
        val metaLineStep = (metaPaint.descent() - metaPaint.ascent()) + metaPaint.textSize * 0.28f * lineMul
        val metaTopGap = if (safeMetaLines.isNotEmpty()) metaPaint.textSize * 0.40f * lineMul else 0f
        val contentHeight = (headerH + metaTopGap + safeMetaLines.size * metaLineStep).coerceAtLeast(1f)

        val boxW = (contentWidth + pad * 2f).coerceAtMost(maxBoxW)
        val boxH = (contentHeight + pad * 2f).coerceAtMost((height - margin * 2f).coerceAtLeast(24f))

        val (calcX, calcY) = calculateAnchoredPosition(
            config.position, width, height, margin, boxW, boxH,
            config.customOffsetX, config.customOffsetY
        )
        val boxX = calcX.coerceIn(margin, (width - boxW - margin).coerceAtLeast(margin))
        val boxY = calcY.coerceIn(margin, (height - boxH - margin).coerceAtLeast(margin))

        canvas.save()
        if (kotlin.math.abs(config.rotationDegrees) > 0.2f) {
            canvas.rotate(config.rotationDegrees.coerceIn(-45f, 45f), boxX + boxW / 2f, boxY + boxH / 2f)
        }

        if (config.backgroundOpacity > 0.04f) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = parseColorSafely(config.backgroundColorHex, Color.BLACK)
                alpha = (config.backgroundOpacity.coerceIn(0f, 1f) * 255).roundToInt()
            }
            val bgRect = RectF(boxX, boxY, boxX + boxW, boxY + boxH)
            val rad = (shortSide * (config.cornerRadiusDp.coerceIn(0, 24) / 24f) * 0.045f)
            canvas.drawRoundRect(bgRect, rad, rad, bgPaint)

            if (config.borderThicknessDp > 0) {
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    alpha = 90
                    style = Paint.Style.STROKE
                    strokeWidth = (shortSide * 0.0022f * config.borderThicknessDp).coerceAtLeast(1f)
                }
                canvas.drawRoundRect(bgRect, rad, rad, borderPaint)
            }
        }

        val innerX = boxX + pad
        val innerY = boxY + pad
        val innerRight = boxX + boxW - pad

        val paintAlign = when (config.textAlignment) {
            WatermarkTextAlignment.CENTER -> Paint.Align.CENTER
            WatermarkTextAlignment.END -> Paint.Align.RIGHT
            WatermarkTextAlignment.START -> Paint.Align.LEFT
        }
        textPaint.textAlign = paintAlign
        subPaint.textAlign = paintAlign
        metaPaint.textAlign = paintAlign

        var textTopY = innerY
        var textRegionLeft = innerX
        var textRegionRight = innerRight

        if (logoBitmap != null && !logoBitmap.isRecycled) {
            val logoPaint = createLogoAlphaPaint(config.logoOpacity)
            when (config.logoAlignment) {
                WatermarkLogoAlignment.TOP -> {
                    val logoX = when (config.textAlignment) {
                        WatermarkTextAlignment.CENTER -> innerX + (contentWidth - logoW) / 2f
                        WatermarkTextAlignment.END -> innerRight - logoW
                        WatermarkTextAlignment.START -> innerX
                    }
                    canvas.drawBitmap(logoBitmap, logoX, innerY, logoPaint)
                    textTopY = innerY + logoH + logoGap
                }
                WatermarkLogoAlignment.END -> {
                    val logoX = innerRight - logoW
                    val logoY = innerY + (headerH - logoH) / 2f
                    canvas.drawBitmap(logoBitmap, logoX, logoY, logoPaint)
                    textRegionRight = logoX - logoGap
                    textTopY = innerY + (headerH - textStackH) / 2f
                }
                WatermarkLogoAlignment.START -> {
                    val logoY = innerY + (headerH - logoH) / 2f
                    canvas.drawBitmap(logoBitmap, innerX, logoY, logoPaint)
                    textRegionLeft = innerX + logoW + logoGap
                    textTopY = innerY + (headerH - textStackH) / 2f
                }
            }
        }

        val headerAnchorX = when (paintAlign) {
            Paint.Align.LEFT -> textRegionLeft
            Paint.Align.CENTER -> (textRegionLeft + textRegionRight) / 2f
            Paint.Align.RIGHT -> textRegionRight
        }
        canvas.drawText(safeTitle, headerAnchorX, textTopY - textPaint.ascent(), textPaint)
        if (safeSub.isNotBlank()) {
            canvas.drawText(safeSub, headerAnchorX, textTopY + titleH + subGap - subPaint.ascent(), subPaint)
        }

        if (safeMetaLines.isNotEmpty()) {
            val metaAnchorX = when (paintAlign) {
                Paint.Align.LEFT -> innerX
                Paint.Align.CENTER -> (innerX + innerRight) / 2f
                Paint.Align.RIGHT -> innerRight
            }
            var curMetaTop = innerY + headerH + metaTopGap
            safeMetaLines.forEach { line ->
                canvas.drawText(line, metaAnchorX, curMetaTop - metaPaint.ascent(), metaPaint)
                curMetaTop += metaLineStep
            }
        }

        canvas.restore()
        return output
    }

    private fun calculateAnchoredPosition(
        position: WatermarkPosition,
        canvasW: Float,
        canvasH: Float,
        margin: Float,
        elementW: Float,
        elementH: Float,
        customOffsetX: Float,
        customOffsetY: Float
    ): Pair<Float, Float> {
        return when (position) {
            WatermarkPosition.BOTTOM_RIGHT -> (canvasW - elementW - margin) to (canvasH - elementH - margin)
            WatermarkPosition.BOTTOM_LEFT -> margin to (canvasH - elementH - margin)
            WatermarkPosition.BOTTOM_CENTER -> ((canvasW - elementW) / 2f) to (canvasH - elementH - margin)
            WatermarkPosition.TOP_LEFT -> margin to margin
            WatermarkPosition.TOP_RIGHT -> (canvasW - elementW - margin) to margin
            WatermarkPosition.CUSTOM_COORDS -> {
                val maxX = (canvasW - elementW - margin).coerceAtLeast(margin)
                val maxY = (canvasH - elementH - margin).coerceAtLeast(margin)
                val x = (margin + (maxX - margin) * customOffsetX.coerceIn(0f, 1f)).coerceIn(margin, maxX)
                val y = (margin + (maxY - margin) * customOffsetY.coerceIn(0f, 1f)).coerceIn(margin, maxY)
                x to y
            }
            WatermarkPosition.FRAME_BORDER -> (canvasW - elementW - margin) to (canvasH - elementH - margin)
        }
    }

    private val sampleCache = mutableMapOf<Pair<Int, Int>, Bitmap>()
    private val blurredBgCache = LruCache<String, Bitmap>(6)

    /**
     * Returns an immutable/read-only cached sample photo directly without copying,
     * for internal read-only watermark rendering pipelines. Callers MUST NOT recycle it.
     */
    internal fun getReadOnlySamplePhoto(width: Int = 1200, height: Int = 800): Bitmap {
        val key = Pair(width, height)
        synchronized(sampleCache) {
            val existing = sampleCache[key]
            if (existing != null && !existing.isRecycled) {
                return existing
            }
            val newBmp = createRealisticSamplePhoto(width, height)
            sampleCache[key] = newBmp
            return newBmp
        }
    }

    /**
     * Retrieves a cached realistic photographic sample photo for watermark previews,
     * avoiding expensive canvas drawing, multi-stop LinearGradients, and trigonometric calculations
     * on every configuration change. Returns a mutable copy safe for external callers.
     */
    fun getCachedSamplePhoto(width: Int = 1200, height: Int = 800): Bitmap {
        return getReadOnlySamplePhoto(width, height).copy(Bitmap.Config.ARGB_8888, true)
    }

    fun clearSampleCache() {
        synchronized(sampleCache) {
            sampleCache.values.forEach { if (!it.isRecycled) it.recycle() }
            sampleCache.clear()
        }
        synchronized(logoCache) {
            logoCache.evictAll()
        }
        synchronized(blurredBgCache) {
            blurredBgCache.evictAll()
        }
    }

    /**
     * Generates a realistic high-definition photographic background for the Watermark Studio preview.
     */
    fun createRealisticSamplePhoto(width: Int = 1200, height: Int = 800): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val skyPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height * 0.65f,
                intArrayOf(
                    Color.parseColor("#1B2A4A"),
                    Color.parseColor("#4A3560"),
                    Color.parseColor("#C25450"),
                    Color.parseColor("#F4A261"),
                    Color.parseColor("#E9C46A")
                ),
                floatArrayOf(0.0f, 0.3f, 0.6f, 0.85f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height * 0.65f, skyPaint)

        val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFF3B0")
            alpha = 220
        }
        canvas.drawCircle(width * 0.68f, height * 0.45f, width * 0.055f, sunPaint)

        val mountainPath = Path().apply {
            moveTo(0f, height * 0.65f)
            lineTo(width * 0.15f, height * 0.50f)
            lineTo(width * 0.35f, height * 0.58f)
            lineTo(width * 0.55f, height * 0.42f)
            lineTo(width * 0.72f, height * 0.52f)
            lineTo(width * 0.88f, height * 0.46f)
            lineTo(width.toFloat(), height * 0.60f)
            lineTo(width.toFloat(), height.toFloat())
            lineTo(0f, height.toFloat())
            close()
        }
        val mountainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, height * 0.42f, 0f, height.toFloat(),
                Color.parseColor("#2A1E35"),
                Color.parseColor("#121118"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(mountainPath, mountainPaint)

        val ridgePath = Path().apply {
            moveTo(0f, height * 0.68f)
            lineTo(width * 0.28f, height * 0.62f)
            lineTo(width * 0.60f, height * 0.70f)
            lineTo(width * 0.82f, height * 0.64f)
            lineTo(width.toFloat(), height * 0.72f)
            lineTo(width.toFloat(), height.toFloat())
            lineTo(0f, height.toFloat())
            close()
        }
        val ridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#181924")
        }
        canvas.drawPath(ridgePath, ridgePaint)

        val waterPaint = Paint().apply {
            shader = LinearGradient(
                0f, height * 0.72f, 0f, height.toFloat(),
                Color.parseColor("#1A1F2C"),
                Color.parseColor("#0C0E14"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, height * 0.72f, width.toFloat(), height.toFloat(), waterPaint)

        val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44F4A261")
            strokeWidth = 2.5f
        }
        for (i in 0..15) {
            val y = height * (0.75f + i * 0.015f)
            val w = width * (0.12f + (15 - i) * 0.008f)
            val startX = width * 0.68f - w / 2f
            canvas.drawLine(startX, y, startX + w, y, shimmerPaint)
        }

        return bmp
    }

    // -------------------------------------------------------------------------
    // PREMIUM GLASS CAMERA WATERMARK TEMPLATE
    // -------------------------------------------------------------------------
    private fun drawGlassFrame(
        source: Bitmap,
        config: WatermarkConfig,
        metadata: RealCameraMetadata,
        context: Context?,
        recycleSource: Boolean = false
    ): Bitmap {
        val width = source.width
        val height = source.height
        val shortSide = minOf(width, height).toFloat()
        val scale = config.fontSizeScale.coerceIn(0.7f, 2.0f)

        if (config.position != WatermarkPosition.FRAME_BORDER) {
            return drawFloatingGlassCapsule(source, config, metadata, context, recycleSource)
        }

        // Expand canvas cleanly around the photo so the original subject is never cropped or distorted
        val marginSide = (shortSide * 0.056f).roundToInt().coerceAtLeast(4)
        val marginTop = (shortSide * 0.048f).roundToInt().coerceAtLeast(4)
        val marginBottom = (shortSide * 0.175f * scale).roundToInt().coerceAtLeast(16)
        val outW = width + marginSide * 2
        val outH = height + marginTop + marginBottom

        val outerRadius = (shortSide * 0.095f).coerceAtLeast(4f)
        val innerRadius = (shortSide * 0.068f).coerceAtLeast(3f)

        val blurredSmall = createFastBlurredBitmap(source, blurRadius = 14)
        val output = createBitmapOomSafe(outW, outH)
        val canvas = Canvas(output)
        val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val fullRect = RectF(0f, 0f, outW.toFloat(), outH.toFloat())

        val outerPath = Path().apply {
            addRoundRect(fullRect, outerRadius, outerRadius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(outerPath)

        canvas.drawBitmap(blurredSmall, null, fullRect, blurPaint)

        val glassOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, outH.toFloat(),
                intArrayOf(
                    Color.argb((config.backgroundOpacity * 75).toInt().coerceIn(0, 255), 255, 255, 255),
                    Color.argb((config.backgroundOpacity * 115).toInt().coerceIn(0, 255), 255, 255, 255)
                ),
                null,
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.FILL
        }
        canvas.drawRect(fullRect, glassOverlayPaint)

        val innerRect = RectF(
            marginSide.toFloat(),
            marginTop.toFloat(),
            (marginSide + width).toFloat(),
            (marginTop + height).toFloat()
        )
        val innerPath = Path().apply {
            addRoundRect(innerRect, innerRadius, innerRadius, Path.Direction.CW)
        }

        canvas.save()
        canvas.clipPath(innerPath)
        canvas.drawBitmap(source, marginSide.toFloat(), marginTop.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        if (recycleSource && !source.isRecycled) source.recycle()

        val innerVignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (marginSide * 0.22f).coerceAtLeast(1f)
            color = Color.argb(45, 0, 0, 0)
        }
        canvas.drawRoundRect(innerRect, innerRadius, innerRadius, innerVignettePaint)
        canvas.restore()

        val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (shortSide * 0.003f).coerceAtLeast(1f)
            shader = LinearGradient(
                innerRect.left, innerRect.top, innerRect.right, innerRect.bottom,
                Color.argb(130, 255, 255, 255),
                Color.argb(45, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(innerRect, innerRadius, innerRadius, innerRimPaint)

        val outerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (shortSide * 0.004f).coerceAtLeast(1f)
            shader = LinearGradient(
                0f, 0f, outW.toFloat(), outH.toFloat(),
                Color.argb(160, 255, 255, 255),
                Color.argb(60, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(fullRect, outerRadius, outerRadius, outerRimPaint)

        val capsuleCenterY = marginTop + height + (marginBottom / 2f)
        val capsuleWidth = width.toFloat() * 0.96f
        val capsuleHeight = marginBottom * 0.62f
        val capsuleLeft = (outW - capsuleWidth) / 2f
        val capsuleTop = capsuleCenterY - (capsuleHeight / 2f)
        val capsuleRect = RectF(capsuleLeft, capsuleTop, capsuleLeft + capsuleWidth, capsuleTop + capsuleHeight)

        drawGlassCapsuleCard(canvas, capsuleRect, config, metadata, context)

        canvas.restore()
        return output
    }

    private fun drawFloatingGlassCapsule(
        source: Bitmap,
        config: WatermarkConfig,
        metadata: RealCameraMetadata,
        context: Context?,
        recycleSource: Boolean = false
    ): Bitmap {
        val width = source.width
        val height = source.height
        val shortSide = minOf(width, height).toFloat()
        val output = obtainMutableTarget(source, recycleSource)
        val canvas = Canvas(output)

        val margin = shortSide * 0.045f * config.marginScale.coerceIn(0.5f, 2.0f)
        val pillWidth = (width - margin * 2f).coerceAtLeast(64f) * 0.92f
        val pillHeight = (shortSide * 0.105f * config.fontSizeScale.coerceIn(0.7f, 1.8f)).coerceAtLeast(24f)

        val rect = when (config.position) {
            WatermarkPosition.BOTTOM_CENTER -> {
                val left = (width - pillWidth) / 2f
                val top = height - pillHeight - margin
                RectF(left, top, left + pillWidth, top + pillHeight)
            }
            WatermarkPosition.BOTTOM_LEFT -> {
                val left = margin
                val top = height - pillHeight - margin
                RectF(left, top, left + pillWidth, top + pillHeight)
            }
            WatermarkPosition.BOTTOM_RIGHT -> {
                val left = width - pillWidth - margin
                val top = height - pillHeight - margin
                RectF(left, top, left + pillWidth, top + pillHeight)
            }
            WatermarkPosition.TOP_LEFT -> {
                val left = margin
                val top = margin
                RectF(left, top, left + pillWidth, top + pillHeight)
            }
            WatermarkPosition.TOP_RIGHT -> {
                val left = width - pillWidth - margin
                val top = margin
                RectF(left, top, left + pillWidth, top + pillHeight)
            }
            WatermarkPosition.CUSTOM_COORDS -> {
                val maxLeft = (width - pillWidth - margin).coerceAtLeast(margin)
                val maxTop = (height - pillHeight - margin).coerceAtLeast(margin)
                val left = (margin + (maxLeft - margin) * config.customOffsetX.coerceIn(0f, 1f)).coerceIn(margin, maxLeft)
                val top = (margin + (maxTop - margin) * config.customOffsetY.coerceIn(0f, 1f)).coerceIn(margin, maxTop)
                RectF(left, top, left + pillWidth, top + pillHeight)
            }
            else -> {
                val left = (width - pillWidth) / 2f
                val top = height - pillHeight - margin
                RectF(left, top, left + pillWidth, top + pillHeight)
            }
        }

        canvas.save()
        if (kotlin.math.abs(config.rotationDegrees) > 0.2f) {
            canvas.rotate(config.rotationDegrees.coerceIn(-45f, 45f), rect.centerX(), rect.centerY())
        }
        drawGlassCapsuleCard(canvas, rect, config, metadata, context)
        canvas.restore()
        return output
    }

    private fun drawGlassCapsuleCard(
        canvas: Canvas,
        rect: RectF,
        config: WatermarkConfig,
        metadata: RealCameraMetadata,
        context: Context?
    ) {
        val pillH = rect.height()
        val pillRadius = pillH * 0.28f

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 0, 0, 0)
            style = Paint.Style.FILL
            setShadowLayer((pillH * 0.15f).coerceAtLeast(1f), 0f, (pillH * 0.05f).coerceAtLeast(0.5f), Color.argb(60, 0, 0, 0))
        }
        canvas.drawRoundRect(rect, pillRadius, pillRadius, shadowPaint)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                intArrayOf(
                    Color.argb((config.backgroundOpacity * 110).toInt().coerceIn(30, 240), 255, 255, 255),
                    Color.argb((config.backgroundOpacity * 50).toInt().coerceIn(15, 200), 255, 255, 255)
                ),
                null,
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, pillRadius, pillRadius, fillPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (pillH * 0.035f).coerceIn(1f, 5f)
            shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                Color.argb(210, 255, 255, 255),
                Color.argb(80, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, pillRadius, pillRadius, borderPaint)

        val centerY = rect.centerY()
        val padX = rect.width() * 0.048f

        val rawMfr = if (config.showManufacturer) {
            metadata.manufacturer?.takeIf { it.isNotBlank() }?.lowercase(Locale.US) ?: ""
        } else ""
        val rawModel = if (config.showDeviceModel) {
            val baseModel = metadata.modelOnly?.takeIf { it.isNotBlank() }
                ?: metadata.deviceModel?.takeIf { it.isNotBlank() }
                ?: ""
            if (rawMfr.isNotBlank() && baseModel.lowercase(Locale.US).startsWith(rawMfr)) {
                baseModel.substring(rawMfr.length).trim()
            } else {
                baseModel.lowercase(Locale.US)
            }
        } else ""

        val mfrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = applyColorOpacity(Color.WHITE, config.textOpacity)
            textSize = (pillH * 0.28f).coerceAtLeast(6f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.04f
        }
        val modelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = applyColorOpacity(Color.WHITE, config.textOpacity, 0.78f)
            textSize = (pillH * 0.18f).coerceAtLeast(5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            letterSpacing = 0.02f
        }

        val exifText = metadata.formatAvailableExposureTriangle(config)
        val dateText = if (config.showDateTime) metadata.dateTimeFormatted.orEmpty() else ""

        val exifPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = applyColorOpacity(Color.WHITE, config.textOpacity)
            textSize = (pillH * 0.23f).coerceAtLeast(5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = applyColorOpacity(Color.WHITE, config.textOpacity, 0.72f)
            textSize = (pillH * 0.17f).coerceAtLeast(4f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        val innerAvailW = (rect.width() - padX * 2f).coerceAtLeast(24f)
        val logoSize = ((pillH * 0.52f) * config.logoScale).roundToInt().coerceIn(14, 700)
        val logoBmp = if (config.logoType == WatermarkLogoType.NONE) null else {
            if (config.logoType == WatermarkLogoType.CUSTOM && !config.customLogoUri.isNullOrBlank()) {
                getOrCreateOpaqueLogo(context, WatermarkLogoType.CUSTOM, config.customLogoUri, logoSize, metadata.manufacturer)
            } else {
                val makeForLogo = metadata.manufacturer?.takeIf { it.isNotBlank() } ?: config.customText
                getOrCreateOpaqueLogo(context, WatermarkLogoType.DEVICE_MANUFACTURER, null, logoSize, makeForLogo)
            }
        }
        val logoW = if (logoBmp != null && !logoBmp.isRecycled) logoBmp.width.toFloat() else 0f
        val gap = pillH * 0.16f

        val rightLimitW = innerAvailW * 0.54f
        val safeExif = ellipsizeToFit(exifText, exifPaint, rightLimitW)
        val safeDate = ellipsizeToFit(dateText, datePaint, rightLimitW)
        val exifW = if (safeExif.isNotBlank()) exifPaint.measureText(safeExif) else 0f
        val dateW = if (safeDate.isNotBlank()) datePaint.measureText(safeDate) else 0f
        val rightTextW = maxOf(exifW, dateW)

        val rightX = rect.right - padX
        val leftX = rect.left + padX
        val rightTextStartX = rightX - rightTextW
        val dividerX = if (rightTextW > 0f) rightTextStartX - gap else rightX
        val logoEndBoundX = if (rightTextW > 0f) dividerX - gap else rightX
        val logoX = logoEndBoundX - logoW

        if (logoBmp != null && !logoBmp.isRecycled) {
            val logoY = centerY - (logoBmp.height / 2f)
            canvas.drawBitmap(logoBmp, logoX, logoY, createLogoAlphaPaint(config.logoOpacity))
        }

        if (rightTextW > 0f && (logoBmp != null || rawMfr.isNotBlank() || rawModel.isNotBlank())) {
            val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(90, 255, 255, 255)
                strokeWidth = (pillH * 0.025f).coerceIn(1f, 3f)
            }
            canvas.drawLine(dividerX, centerY - pillH * 0.26f, dividerX, centerY + pillH * 0.26f, divPaint)
        }

        val leftMaxW = ((if (logoBmp != null) logoX - gap else dividerX - gap) - leftX).coerceAtLeast(16f)
        val safeMfr = ellipsizeToFit(rawMfr, mfrPaint, leftMaxW)
        val safeModel = ellipsizeToFit(rawModel, modelPaint, leftMaxW)

        if (safeMfr.isNotBlank() && safeModel.isNotBlank()) {
            canvas.drawText(safeMfr, leftX, centerY - pillH * 0.06f, mfrPaint)
            canvas.drawText(safeModel, leftX, centerY + pillH * 0.28f, modelPaint)
        } else if (safeMfr.isNotBlank()) {
            val textY = centerY - ((mfrPaint.descent() + mfrPaint.ascent()) / 2f)
            canvas.drawText(safeMfr, leftX, textY, mfrPaint)
        } else if (safeModel.isNotBlank()) {
            val textY = centerY - ((modelPaint.descent() + modelPaint.ascent()) / 2f)
            canvas.drawText(safeModel, leftX, textY, modelPaint)
        }

        if (safeExif.isNotBlank() && safeDate.isNotBlank()) {
            canvas.drawText(safeExif, rightTextStartX, centerY - pillH * 0.06f, exifPaint)
            canvas.drawText(safeDate, rightTextStartX, centerY + pillH * 0.28f, datePaint)
        } else if (safeExif.isNotBlank()) {
            val textY = centerY - ((exifPaint.descent() + exifPaint.ascent()) / 2f)
            canvas.drawText(safeExif, rightTextStartX, textY, exifPaint)
        } else if (safeDate.isNotBlank()) {
            val textY = centerY - ((datePaint.descent() + datePaint.ascent()) / 2f)
            canvas.drawText(safeDate, rightTextStartX, textY, datePaint)
        }
    }

    private fun createFastBlurredBitmap(source: Bitmap, blurRadius: Int = 12): Bitmap {
        val clampedRadius = blurRadius.coerceIn(4, 25)
        val cacheKey = "${System.identityHashCode(source)}|${source.width}x${source.height}|${source.generationId}|$clampedRadius"
        synchronized(blurredBgCache) {
            blurredBgCache.get(cacheKey)?.let { cached ->
                if (!cached.isRecycled) return cached
                blurredBgCache.remove(cacheKey)
            }
        }
        val downW = (source.width / 6).coerceIn(60, 240)
        val downH = (source.height / 6).coerceIn(60, 240)
        val small = Bitmap.createScaledBitmap(source, downW, downH, true)
        boxBlur(small, clampedRadius)
        synchronized(blurredBgCache) {
            blurredBgCache.put(cacheKey, small)
        }
        return small
    }

    private fun boxBlur(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val r = radius.coerceAtLeast(1)
        val wm = w - 1
        val hm = h - 1
        val div = 2 * r + 1

        val rAcc = IntArray(w * h)
        val gAcc = IntArray(w * h)
        val bAcc = IntArray(w * h)

        var yi = 0
        for (y in 0 until h) {
            var totalR = 0
            var totalG = 0
            var totalB = 0
            for (i in -r..r) {
                val p = pix[yi + i.coerceIn(0, wm)]
                totalR += (p shr 16) and 0xff
                totalG += (p shr 8) and 0xff
                totalB += p and 0xff
            }
            for (x in 0 until w) {
                rAcc[yi + x] = totalR / div
                gAcc[yi + x] = totalG / div
                bAcc[yi + x] = totalB / div

                val pLeft = pix[yi + (x - r).coerceIn(0, wm)]
                val pRight = pix[yi + (x + r + 1).coerceIn(0, wm)]
                totalR += ((pRight shr 16) and 0xff) - ((pLeft shr 16) and 0xff)
                totalG += ((pRight shr 8) and 0xff) - ((pLeft shr 8) and 0xff)
                totalB += (pRight and 0xff) - (pLeft and 0xff)
            }
            yi += w
        }

        for (x in 0 until w) {
            var totalR = 0
            var totalG = 0
            var totalB = 0
            for (i in -r..r) {
                val idx = i.coerceIn(0, hm) * w + x
                totalR += rAcc[idx]
                totalG += gAcc[idx]
                totalB += bAcc[idx]
            }
            for (y in 0 until h) {
                val idx = y * w + x
                val a = (pix[idx] ushr 24) and 0xff
                pix[idx] = (a shl 24) or ((totalR / div) shl 16) or ((totalG / div) shl 8) or (totalB / div)

                val yLeft = (y - r).coerceIn(0, hm) * w + x
                val yRight = (y + r + 1).coerceIn(0, hm) * w + x
                totalR += rAcc[yRight] - rAcc[yLeft]
                totalG += gAcc[yRight] - gAcc[yLeft]
                totalB += bAcc[yRight] - bAcc[yLeft]
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    }

    /**
     * Exports a watermarked photo to MediaStore at full resolution as a separate copy,
     * preserving authentic EXIF metadata and keeping original photos untouched.
     */
    suspend fun exportWatermarkedPhoto(
        context: Context,
        sourceUri: Uri,
        config: WatermarkConfig
    ): Uri? = withContext(Dispatchers.IO) {
        var rawBmp: Bitmap? = null
        var uprightBmp: Bitmap? = null
        var stamped: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(sourceUri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val origW = bounds.outWidth
            val origH = bounds.outHeight
            if (origW <= 0 || origH <= 0) return@withContext null

            val detectedMime = bounds.outMimeType ?: context.contentResolver.getType(sourceUri)
            val isPng = detectedMime.equals("image/png", ignoreCase = true) ||
                sourceUri.toString().lowercase(Locale.US).endsWith(".png")

            var orientation = ExifInterface.ORIENTATION_NORMAL
            if (!isPng) {
                try {
                    context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                        orientation = ExifInterface(stream).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    }
                } catch (_: Exception) {}
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = 1
                inScaled = false
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            // Decode full-resolution image, clearing caches safely if heap is tight on huge photos
            rawBmp = try {
                context.contentResolver.openInputStream(sourceUri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOpts)
                }
            } catch (_: OutOfMemoryError) {
                clearSampleCache()
                System.gc()
                context.contentResolver.openInputStream(sourceUri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOpts)
                }
            } ?: return@withContext null

            val orientMatrix = android.graphics.Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> orientMatrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> orientMatrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> orientMatrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> orientMatrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> orientMatrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    orientMatrix.postRotate(90f)
                    orientMatrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    orientMatrix.postRotate(270f)
                    orientMatrix.postScale(-1f, 1f)
                }
            }
            uprightBmp = if (!orientMatrix.isIdentity) {
                val oriented = try {
                    Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, orientMatrix, true)
                } catch (_: OutOfMemoryError) {
                    clearSampleCache()
                    System.gc()
                    Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, orientMatrix, true)
                }
                if (oriented != rawBmp) rawBmp.recycle()
                oriented
            } else {
                rawBmp
            }

            val meta = CameraMetadataUtil.fromUri(context, sourceUri, uprightBmp.width, uprightBmp.height)

            stamped = applyWatermark(uprightBmp, config, meta, context, recycleSource = true)
            if (stamped != uprightBmp && !uprightBmp.isRecycled) uprightBmp.recycle()

            return@withContext exportWatermarkedBitmap(context, stamped, config, meta, sourceUri, isPng = isPng)
        } catch (_: Throwable) {
            null
        } finally {
            try {
                if (stamped != null && !stamped.isRecycled) stamped.recycle()
                if (uprightBmp != null && uprightBmp != stamped && !uprightBmp.isRecycled) uprightBmp.recycle()
                if (rawBmp != null && rawBmp != uprightBmp && rawBmp != stamped && !rawBmp.isRecycled) rawBmp.recycle()
            } catch (_: Throwable) {}
            clearSampleCache()
        }
    }

    /**
     * Saves a watermarked bitmap to MediaStore as a separate copy with EXIF metadata preservation (for JPEG)
     * or lossless alpha transparency preservation (for PNG).
     */
    @JvmOverloads
    suspend fun exportWatermarkedBitmap(
        context: Context,
        bitmap: Bitmap,
        config: WatermarkConfig,
        metadata: RealCameraMetadata,
        sourceUriForExif: Uri? = null,
        isPng: Boolean = false
    ): Uri? = withContext(Dispatchers.IO) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val styleTag = config.style.tag.lowercase(Locale.US)
        val extension = if (isPng) "png" else "jpg"
        val mimeType = if (isPng) "image/png" else "image/jpeg"
        val displayName = "StuDO_${styleTag}_$timeStamp.$extension"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StuDO_Watermarked")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        var savedUri: Uri? = null
        try {
            savedUri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return@withContext null

            // Single high-quality compression pass (lossless PNG or JPEG quality 100)
            context.contentResolver.openOutputStream(savedUri)?.use { outStream ->
                val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                val ok = bitmap.compress(format, 100, outStream)
                outStream.flush()
                if (!ok) throw IllegalStateException("${format.name} compression failed for $savedUri")
            } ?: throw IllegalStateException("Failed to open output stream for $savedUri")

            // Preserve authentic EXIF metadata on JPEG without re-compressing pixel data
            if (!isPng) {
                try {
                    val pfd = context.contentResolver.openFileDescriptor(savedUri, "rw")
                    pfd?.use { fd ->
                        val destExif = ExifInterface(fd.fileDescriptor)
                        if (sourceUriForExif != null) {
                            context.contentResolver.openInputStream(sourceUriForExif)?.use { origStream ->
                                val srcExif = ExifInterface(origStream)
                                val tagsToCopy = arrayOf(
                                    ExifInterface.TAG_DATETIME,
                                    ExifInterface.TAG_DATETIME_ORIGINAL,
                                    ExifInterface.TAG_DATETIME_DIGITIZED,
                                    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                                    ExifInterface.TAG_MAKE,
                                    ExifInterface.TAG_MODEL,
                                    "LensMake",
                                    "LensModel",
                                    ExifInterface.TAG_FOCAL_LENGTH,
                                    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                                    ExifInterface.TAG_F_NUMBER,
                                    ExifInterface.TAG_APERTURE_VALUE,
                                    ExifInterface.TAG_EXPOSURE_TIME,
                                    ExifInterface.TAG_SHUTTER_SPEED_VALUE,
                                    ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
                                    ExifInterface.TAG_ISO_SPEED_RATINGS,
                                    "PhotographicSensitivity",
                                    ExifInterface.TAG_WHITE_BALANCE,
                                    ExifInterface.TAG_FLASH,
                                    ExifInterface.TAG_COLOR_SPACE
                                )
                                for (tag in tagsToCopy) {
                                    val v = srcExif.getAttribute(tag)
                                    if (!v.isNullOrBlank()) destExif.setAttribute(tag, v)
                                }
                            }
                        } else {
                            metadata.dateTimeFormatted?.let { destExif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, it) }
                            metadata.manufacturer?.let { destExif.setAttribute(ExifInterface.TAG_MAKE, it) }
                            metadata.modelOnly?.let { destExif.setAttribute(ExifInterface.TAG_MODEL, it) }
                        }
                        destExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                        destExif.saveAttributes()
                    }
                } catch (_: Exception) {}
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(savedUri, contentValues, null, null)
            }

            return@withContext savedUri
        } catch (_: Throwable) {
            if (savedUri != null) {
                try {
                    context.contentResolver.delete(savedUri, null, null)
                } catch (_: Exception) {}
            }
            return@withContext null
        }
    }
}
