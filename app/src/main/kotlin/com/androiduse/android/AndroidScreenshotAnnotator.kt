package com.androiduse.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.androiduse.core.AndroidUseException
import com.androiduse.core.CaptureResult
import com.androiduse.core.ScreenshotAnnotator
import com.androiduse.protocol.ImageFormat
import com.androiduse.protocol.UiMark
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Draws Set-of-Mark (SoM) colored bounding boxes and numbered pill badges onto a screenshot.
 */
class AndroidScreenshotAnnotator(
    private val quality: Int = DEFAULT_REENCODE_QUALITY,
) : ScreenshotAnnotator {

    override fun annotate(capture: CaptureResult, marks: List<UiMark>): CaptureResult {
        if (marks.isEmpty()) {
            return capture.copy(marks = emptyList())
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(capture.bytes, 0, capture.bytes.size, decodeOptions)
            ?: throw AndroidUseException.screenshotFailed("Failed to decode screenshot for Set-of-Mark annotation.")

        val mutableBitmap = if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) {
            decoded
        } else {
            val copy = decoded.copy(Bitmap.Config.ARGB_8888, true)
            decoded.recycle()
            copy ?: throw AndroidUseException.screenshotFailed("Failed to allocate mutable bitmap for annotation.")
        }

        try {
            drawMarks(
                bitmap = mutableBitmap,
                marks = marks,
                displayWidth = capture.displayWidth,
                displayHeight = capture.displayHeight,
            )

            val compressFormat = when (capture.format) {
                ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                ImageFormat.PNG -> Bitmap.CompressFormat.PNG
            }
            val outputStream = ByteArrayOutputStream()
            val ok = mutableBitmap.compress(compressFormat, quality, outputStream)
            if (!ok) {
                throw AndroidUseException.screenshotFailed("Failed to encode annotated screenshot.")
            }

            return capture.copy(
                bytes = outputStream.toByteArray(),
                marks = marks,
            )
        } finally {
            if (!mutableBitmap.isRecycled) {
                mutableBitmap.recycle()
            }
        }
    }

    private fun drawMarks(
        bitmap: Bitmap,
        marks: List<UiMark>,
        displayWidth: Int,
        displayHeight: Int,
    ) {
        if (displayWidth <= 0 || displayHeight <= 0) return

        val canvas = Canvas(bitmap)
        val imgW = bitmap.width.toFloat()
        val imgH = bitmap.height.toFloat()
        val scaleX = imgW / displayWidth.toFloat()
        val scaleY = imgH / displayHeight.toFloat()

        // Scale stroke and label typography proportionally to the output image resolution.
        val minDim = min(imgW, imgH)
        val strokePx = max(2f, minDim * 0.0035f)
        val textSizePx = max(14f, minDim * 0.024f)
        val padH = textSizePx * 0.34f
        val padV = textSizePx * 0.16f
        val cornerRadius = textSizePx * 0.25f

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
        }
        val boxOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx + 2f
            color = Color.argb(180, 255, 255, 255)
        }
        val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.2f, strokePx * 0.6f)
            color = Color.WHITE
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = textSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val fontMetrics = textPaint.fontMetrics
        val badgeHeight = (fontMetrics.descent - fontMetrics.ascent) + padV * 2f

        val placedBadges = mutableListOf<RectF>()

        // Pass 1: draw all bounding boxes so badges always sit cleanly on top.
        for (mark in marks) {
            val color = PALETTE[(mark.markId - 1).mod(PALETTE.size)]
            val rect = RectF(
                (mark.bounds.left * scaleX).coerceIn(0f, imgW),
                (mark.bounds.top * scaleY).coerceIn(0f, imgH),
                (mark.bounds.right * scaleX).coerceIn(0f, imgW),
                (mark.bounds.bottom * scaleY).coerceIn(0f, imgH),
            )
            boxPaint.color = color
            canvas.drawRect(rect, boxOutlinePaint)
            canvas.drawRect(rect, boxPaint)
        }

        // Pass 2: draw high-contrast numbered pill badges with simple collision nudging.
        for (mark in marks) {
            val color = PALETTE[(mark.markId - 1).mod(PALETTE.size)]
            val label = mark.markId.toString()
            val textWidth = textPaint.measureText(label)
            val badgeWidth = max(badgeHeight, textWidth + padH * 2f)

            val boxLeft = (mark.bounds.left * scaleX).coerceIn(0f, imgW)
            val boxTop = (mark.bounds.top * scaleY).coerceIn(0f, imgH)
            val boxRight = (mark.bounds.right * scaleX).coerceIn(0f, imgW)
            val boxBottom = (mark.bounds.bottom * scaleY).coerceIn(0f, imgH)

            val badgeRect = placeBadge(
                boxLeft = boxLeft,
                boxTop = boxTop,
                boxRight = boxRight,
                boxBottom = boxBottom,
                badgeWidth = badgeWidth,
                badgeHeight = badgeHeight,
                imgW = imgW,
                imgH = imgH,
                placed = placedBadges,
            )
            placedBadges += badgeRect

            badgeFillPaint.color = color
            canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, badgeFillPaint)
            canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, badgeBorderPaint)

            val textCenterX = badgeRect.centerX()
            val textBaselineY = badgeRect.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2f
            canvas.drawText(label, textCenterX, textBaselineY, textPaint)
        }
    }

    private fun placeBadge(
        boxLeft: Float,
        boxTop: Float,
        boxRight: Float,
        boxBottom: Float,
        badgeWidth: Float,
        badgeHeight: Float,
        imgW: Float,
        imgH: Float,
        placed: List<RectF>,
    ): RectF {
        val candidates = listOf(
            // Top-left corner (default)
            boxLeft to boxTop,
            // Top-right corner
            (boxRight - badgeWidth) to boxTop,
            // Bottom-left corner
            boxLeft to (boxBottom - badgeHeight),
            // Centered horizontally along top edge
            ((boxLeft + boxRight - badgeWidth) / 2f) to boxTop,
            // Nudged right of top-left
            (boxLeft + badgeWidth + 2f) to boxTop,
            // Nudged below top-left
            boxLeft to (boxTop + badgeHeight + 2f),
        )

        var fallback: RectF? = null
        for ((rawX, rawY) in candidates) {
            val x = rawX.coerceIn(0f, max(0f, imgW - badgeWidth))
            val y = rawY.coerceIn(0f, max(0f, imgH - badgeHeight))
            val rect = RectF(x, y, x + badgeWidth, y + badgeHeight)
            if (fallback == null) {
                fallback = rect
            }
            if (placed.none { existing -> RectF.intersects(existing, rect) }) {
                return rect
            }
        }
        return fallback ?: RectF(0f, 0f, badgeWidth, badgeHeight)
    }

    companion object {
        private const val DEFAULT_REENCODE_QUALITY: Int = 85

        // High-contrast, distinct palette for bounding boxes and numbered badges.
        private val PALETTE: IntArray = intArrayOf(
            Color.rgb(225, 29, 72),   // Crimson
            Color.rgb(37, 99, 235),   // Royal Blue
            Color.rgb(5, 150, 105),   // Emerald
            Color.rgb(217, 119, 6),   // Amber
            Color.rgb(124, 58, 237),  // Violet
            Color.rgb(8, 145, 178),   // Cyan
            Color.rgb(192, 38, 211),  // Fuchsia
            Color.rgb(51, 65, 85),    // Slate
        )
    }
}
