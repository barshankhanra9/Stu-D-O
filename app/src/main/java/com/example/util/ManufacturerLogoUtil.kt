package com.example.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Locale

/**
 * Renders high-fidelity vector manufacturer logos for Android watermarks.
 * Dynamically renders the official logo for Motorola (e.g. Moto G34), Google, Samsung,
 * Xiaomi, Apple, OnePlus, Sony, or StuDO without third-party web dependencies.
 */
object ManufacturerLogoUtil {

    fun createManufacturerLogo(manufacturer: String?, targetSize: Int): Bitmap {
        val size = targetSize.coerceIn(24, 800)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val make = (manufacturer ?: "").lowercase(Locale.US).trim()

        when {
            make.contains("motorola") || make.contains("moto") || make.contains("lenovo") -> {
                drawMotorolaLogo(canvas, size)
            }
            make.contains("google") || make.contains("pixel") -> {
                drawGoogleLogo(canvas, size)
            }
            make.contains("samsung") -> {
                drawSamsungLogo(canvas, size)
            }
            make.contains("apple") || make.contains("iphone") -> {
                drawAppleLogo(canvas, size)
            }
            make.contains("xiaomi") || make.contains("redmi") || make.contains("poco") -> {
                drawXiaomiLogo(canvas, size)
            }
            make.contains("oneplus") -> {
                drawOnePlusLogo(canvas, size)
            }
            make.contains("sony") -> {
                drawSonyLogo(canvas, size)
            }
            else -> {
                drawGenericCameraLogo(canvas, size)
            }
        }

        return bmp
    }

    /**
     * Authentic Motorola Batwing "M" Emblem for Motorola / Moto devices.
     */
    private fun drawMotorolaLogo(canvas: Canvas, size: Int) {
        val cx = size / 2f
        val cy = size / 2f
        val r = size * 0.46f

        // Official Motorola Cobalt/Midnight Blue Circular Disc
        val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#001489")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, r, discPaint)

        // Authentic symmetrical Batwing "M" geometry
        val mPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        val batwingPath = Path().apply {
            // Start at left outer wing tip
            moveTo(cx - r * 0.52f, cy + r * 0.44f)
            // Sweep up left outer edge to sharp left peak
            quadTo(
                cx - r * 0.42f, cy - r * 0.06f,
                cx - r * 0.29f, cy - r * 0.56f
            )
            // Crisp straight inner V down to center valley
            lineTo(cx, cy + r * 0.05f)
            // Crisp straight inner V up to sharp right peak
            lineTo(cx + r * 0.29f, cy - r * 0.56f)
            // Sweep down right outer edge to right outer wing tip
            quadTo(
                cx + r * 0.42f, cy - r * 0.06f,
                cx + r * 0.52f, cy + r * 0.44f
            )
            // Right batwing underside arch curving up and into central bottom wedge
            cubicTo(
                cx + r * 0.42f, cy - r * 0.12f,
                cx + r * 0.14f, cy - r * 0.16f,
                cx, cy + r * 0.24f
            )
            // Left batwing underside arch mirroring back to left wing tip
            cubicTo(
                cx - r * 0.14f, cy - r * 0.16f,
                cx - r * 0.42f, cy - r * 0.12f,
                cx - r * 0.52f, cy + r * 0.44f
            )
            close()
        }

        canvas.drawPath(batwingPath, mPaint)
    }

    /**
     * Iconic 4-Color Google "G" Logo (Matching user reference image).
     */
    private fun drawGoogleLogo(canvas: Canvas, size: Int) {
        val cx = size / 2f
        val cy = size / 2f
        val outerR = size * 0.44f
        val innerR = size * 0.23f
        val thickness = outerR - innerR
        val strokeMid = (outerR + innerR) / 2f

        val outerOval = RectF(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        val strokeOval = RectF(cx - strokeMid, cy - strokeMid, cx + strokeMid, cy + strokeMid)

        fun arcPaint(colorInt: Int): Paint {
            return Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorInt
                style = Paint.Style.STROKE
                strokeWidth = thickness
                strokeCap = Paint.Cap.BUTT
            }
        }

        // 1. Red (Top Arc: 220° to 315°)
        canvas.drawArc(strokeOval, 220f, 95f, false, arcPaint(Color.parseColor("#EA4335")))

        // 2. Yellow (Left Arc: 135° to 220°)
        canvas.drawArc(strokeOval, 135f, 85f, false, arcPaint(Color.parseColor("#FBBC05")))

        // 3. Green (Bottom Arc: 45° to 135°)
        canvas.drawArc(strokeOval, 45f, 90f, false, arcPaint(Color.parseColor("#34A853")))

        // 4. Blue (Right Arc: -45° to 45°)
        canvas.drawArc(strokeOval, -45f, 90f, false, arcPaint(Color.parseColor("#4285F4")))

        // 5. Blue Horizontal Bar
        val blueBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4285F4")
            style = Paint.Style.FILL
        }
        val barTop = cy - (thickness / 2f)
        val barBottom = cy + (thickness / 2f)
        val barLeft = cx - 1f
        val barRight = cx + outerR
        canvas.drawRect(barLeft, barTop, barRight, barBottom, blueBarPaint)
    }

    /**
     * Samsung Iconic Ellipse Logo.
     */
    private fun drawSamsungLogo(canvas: Canvas, size: Int) {
        val cx = size / 2f
        val cy = size / 2f
        val rx = size * 0.46f
        val ry = size * 0.32f

        canvas.save()
        canvas.rotate(-12f, cx, cy)

        val ellipsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1428A0") // Samsung Blue
            style = Paint.Style.FILL
        }
        canvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), ellipsePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.22f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
        }
        val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText("SAMSUNG", cx, textY, textPaint)
        canvas.restore()
    }

    /**
     * Apple Silhouette Logo.
     */
    private fun drawAppleLogo(canvas: Canvas, size: Int) {
        val cx = size / 2f
        val cy = size / 2f + size * 0.05f
        val r = size * 0.32f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        // Apple body: overlapping smooth lobes
        val bodyPath = Path()
        bodyPath.addCircle(cx - r * 0.42f, cy, r * 0.85f, Path.Direction.CW)
        bodyPath.addCircle(cx + r * 0.42f, cy, r * 0.85f, Path.Direction.CW)
        bodyPath.addCircle(cx, cy - r * 0.25f, r * 0.72f, Path.Direction.CW)

        // Apple bite cutout on right side
        val bitePath = Path().apply {
            addCircle(cx + r * 0.95f, cy - r * 0.25f, r * 0.52f, Path.Direction.CW)
        }
        bodyPath.op(bitePath, Path.Op.DIFFERENCE)
        canvas.drawPath(bodyPath, paint)

        // Floating leaf
        val leafPath = Path().apply {
            moveTo(cx, cy - r * 1.15f)
            quadTo(cx + r * 0.45f, cy - r * 1.35f, cx + r * 0.38f, cy - r * 0.82f)
            quadTo(cx, cy - r * 0.75f, cx, cy - r * 1.15f)
        }
        canvas.drawPath(leafPath, paint)
    }

    /**
     * Xiaomi Orange Rounded Square "mi" Logo.
     */
    private fun drawXiaomiLogo(canvas: Canvas, size: Int) {
        val cx = size / 2f
        val cy = size / 2f
        val r = size * 0.44f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF6900") // Xiaomi Orange
            style = Paint.Style.FILL
        }
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawRoundRect(rect, r * 0.48f, r * 0.48f, bgPaint)

        // Clean white "mi" text emblem
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.36f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
        }
        val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText("mi", cx, textY, textPaint)
    }

    /**
     * OnePlus Red Square Logo.
     */
    private fun drawOnePlusLogo(canvas: Canvas, size: Int) {
        val cx = size / 2f
        val cy = size / 2f
        val r = size * 0.42f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EB0028") // OnePlus Red
            style = Paint.Style.FILL
        }
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawRoundRect(rect, r * 0.22f, r * 0.22f, bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.38f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
        }
        val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText("1+", cx, textY, textPaint)
    }

    /**
     * Sony Geometric Wordmark.
     */
    private fun drawSonyLogo(canvas: Canvas, size: Int) {
        val cx = size / 2f
        val cy = size / 2f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.28f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
        }
        val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText("SONY", cx, textY, textPaint)
    }

    /**
     * Generic StuDO Optical Camera Lens Emblem for other devices.
     */
    private fun drawGenericCameraLogo(canvas: Canvas, size: Int) {
        val cx = size / 2f
        val cy = size / 2f
        val r = size * 0.44f

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD54F")
            style = Paint.Style.STROKE
            strokeWidth = size * 0.08f
        }
        canvas.drawCircle(cx, cy, r * 0.85f, ringPaint)

        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, r * 0.5f, corePaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, r * 0.2f, dotPaint)
    }
}
