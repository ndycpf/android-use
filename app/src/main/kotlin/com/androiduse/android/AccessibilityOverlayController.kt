package com.androiduse.android

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import com.androiduse.core.AccessibilityServiceRegistry
import com.androiduse.core.ActionOverlayFeedback
import com.androiduse.protocol.UiPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Draws non-touchable visual feedback (tap markers, swipe vectors, status pill, and
 * brief screen border highlight) using [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY].
 *
 * Requires zero extra permissions beyond AccessibilityService, never intercepts touch input,
 * and hides itself before [beforeScreenshot] so screenshots sent to AI agents remain clean.
 */
class AccessibilityOverlayController(
    private val accessibilityRegistry: AccessibilityServiceRegistry,
    private val isEnabledProvider: () -> Boolean,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : ActionOverlayFeedback {

    private var attachedService: AccessibilityService? = null
    private var overlayView: OverlayCanvasView? = null
    private var clearRunnable: Runnable? = null

    override suspend fun beforeScreenshot() {
        val wasVisible = withContext(mainDispatcher) {
            val view = overlayView ?: return@withContext false
            val active = view.hasActiveOverlay()
            cancelScheduledClear()
            if (active) {
                view.clearState()
                view.visibility = View.GONE
            }
            active
        }
        if (wasVisible) {
            // Allow one compositor frame (~32ms) so the overlay is completely gone before capture.
            delay(SCREENSHOT_HIDE_SETTLE_MS)
        }
    }

    override fun showCaptured() {
        postOverlayUpdate(
            OverlayState(
                label = "android-use · Captured",
                borderColor = COLOR_CAPTURE,
            ),
            durationMs = CAPTURE_FLASH_MS,
        )
    }

    override fun showTap(point: UiPoint, longPress: Boolean) {
        postOverlayUpdate(
            OverlayState(
                label = if (longPress) "android-use · Long press" else "android-use · Tap",
                borderColor = if (longPress) COLOR_LONG_PRESS else COLOR_TAP,
                tapPoint = point,
                accentColor = if (longPress) COLOR_LONG_PRESS else COLOR_TAP,
            ),
            durationMs = if (longPress) LONG_PRESS_DISPLAY_MS else ACTION_DISPLAY_MS,
        )
    }

    override fun showSwipe(start: UiPoint, end: UiPoint) {
        postOverlayUpdate(
            OverlayState(
                label = "android-use · Swipe",
                borderColor = COLOR_SWIPE,
                swipeStart = start,
                swipeEnd = end,
                accentColor = COLOR_SWIPE,
            ),
            durationMs = ACTION_DISPLAY_MS,
        )
    }

    override fun showStatus(label: String) {
        postOverlayUpdate(
            OverlayState(
                label = "android-use · $label",
                borderColor = COLOR_TAP,
            ),
            durationMs = ACTION_DISPLAY_MS,
        )
    }

    override fun clear() {
        mainHandler.post {
            cancelScheduledClear()
            detachView()
        }
    }

    private fun postOverlayUpdate(state: OverlayState, durationMs: Long) {
        if (!isEnabledProvider()) return
        mainHandler.post {
            if (!isEnabledProvider()) {
                detachView()
                return@post
            }
            val view = ensureAttachedView() ?: return@post
            cancelScheduledClear()
            view.visibility = View.VISIBLE
            view.updateState(state)
            val runnable = Runnable {
                view.clearState()
                view.visibility = View.GONE
            }
            clearRunnable = runnable
            mainHandler.postDelayed(runnable, durationMs)
        }
    }

    private fun ensureAttachedView(): OverlayCanvasView? {
        val service = accessibilityRegistry.currentService() ?: run {
            detachView()
            return null
        }

        if (attachedService === service && overlayView != null) {
            return overlayView
        }

        detachView()

        val wm = service.getSystemService(WindowManager::class.java) ?: return null
        val view = OverlayCanvasView(service)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )

        return runCatching {
            wm.addView(view, params)
            attachedService = service
            overlayView = view
            view
        }.getOrNull()
    }

    private fun detachView() {
        val view = overlayView
        val service = attachedService
        overlayView = null
        attachedService = null
        if (view != null && service != null) {
            runCatching {
                service.getSystemService(WindowManager::class.java)?.removeViewImmediate(view)
            }
        }
    }

    private fun cancelScheduledClear() {
        clearRunnable?.let { mainHandler.removeCallbacks(it) }
        clearRunnable = null
    }

    private data class OverlayState(
        val label: String,
        val borderColor: Int,
        val accentColor: Int = borderColor,
        val tapPoint: UiPoint? = null,
        val swipeStart: UiPoint? = null,
        val swipeEnd: UiPoint? = null,
    )

    private class OverlayCanvasView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private var state: OverlayState? = null

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * density
        }

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * density
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * density
            strokeCap = Paint.Cap.ROUND
        }

        private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(215, 20, 24, 30)
        }

        private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * density
            textAlign = Paint.Align.CENTER
        }

        private val pillRect = RectF()

        fun hasActiveOverlay(): Boolean = state != null && visibility == VISIBLE

        fun updateState(newState: OverlayState) {
            state = newState
            invalidate()
        }

        fun clearState() {
            state = null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val current = state ?: return

            // Subtle screen border indicator.
            val halfStroke = borderPaint.strokeWidth / 2f
            borderPaint.color = Color.argb(
                180,
                Color.red(current.borderColor),
                Color.green(current.borderColor),
                Color.blue(current.borderColor),
            )
            canvas.drawRect(
                halfStroke,
                halfStroke,
                width - halfStroke,
                height - halfStroke,
                borderPaint,
            )

            // Tap / long-press marker ring.
            current.tapPoint?.let { pt ->
                val cx = pt.x.toFloat()
                val cy = pt.y.toFloat()
                ringPaint.color = current.accentColor
                fillPaint.color = Color.argb(
                    90,
                    Color.red(current.accentColor),
                    Color.green(current.accentColor),
                    Color.blue(current.accentColor),
                )
                canvas.drawCircle(cx, cy, 24f * density, fillPaint)
                canvas.drawCircle(cx, cy, 24f * density, ringPaint)
                fillPaint.color = current.accentColor
                canvas.drawCircle(cx, cy, 5f * density, fillPaint)
            }

            // Swipe vector path.
            val start = current.swipeStart
            val end = current.swipeEnd
            if (start != null && end != null) {
                linePaint.color = current.accentColor
                canvas.drawLine(
                    start.x.toFloat(),
                    start.y.toFloat(),
                    end.x.toFloat(),
                    end.y.toFloat(),
                    linePaint,
                )
                fillPaint.color = current.accentColor
                canvas.drawCircle(start.x.toFloat(), start.y.toFloat(), 7f * density, fillPaint)
                ringPaint.color = current.accentColor
                canvas.drawCircle(end.x.toFloat(), end.y.toFloat(), 11f * density, ringPaint)
            }

            // Top-center status pill positioned below the status bar.
            val textWidth = pillTextPaint.measureText(current.label)
            val padH = 14f * density
            val padV = 7f * density
            val pillW = textWidth + padH * 2f
            val pillH = pillTextPaint.textSize + padV * 2f
            val topY = 48f * density
            val leftX = (width - pillW) / 2f
            pillRect.set(leftX, topY, leftX + pillW, topY + pillH)
            canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, pillBgPaint)

            val baselineY = pillRect.centerY() - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2f
            canvas.drawText(current.label, pillRect.centerX(), baselineY, pillTextPaint)
        }
    }

    companion object {
        private const val SCREENSHOT_HIDE_SETTLE_MS: Long = 32L
        private const val CAPTURE_FLASH_MS: Long = 450L
        private const val ACTION_DISPLAY_MS: Long = 700L
        private const val LONG_PRESS_DISPLAY_MS: Long = 900L

        private val COLOR_TAP = Color.rgb(0, 212, 255)
        private val COLOR_LONG_PRESS = Color.rgb(255, 179, 0)
        private val COLOR_SWIPE = Color.rgb(124, 77, 255)
        private val COLOR_CAPTURE = Color.rgb(0, 230, 118)
    }
}
