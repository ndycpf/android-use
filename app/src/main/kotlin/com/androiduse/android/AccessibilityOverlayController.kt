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
 * Session-driven non-touchable visual overlay using [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY].
 *
 * - Stays continuously visible while an AI session is active so the user always knows
 *   an agent is operating the device.
 * - Sets [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] while `keepAwake` is enabled,
 *   preventing automatic display sleep during the session with zero dangerous permissions.
 * - Briefly shows tap markers, swipe vectors, and action labels before reverting to the
 *   persistent session indicator.
 * - Suppresses drawing (without detaching the window) during [beforeScreenshot] so screenshots
 *   sent to AI agents remain completely clean while keeping `FLAG_KEEP_SCREEN_ON` uninterrupted.
 */
class AccessibilityOverlayController(
    private val accessibilityRegistry: AccessibilityServiceRegistry,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : ActionOverlayFeedback {

    private var attachedService: AccessibilityService? = null
    private var attachedKeepAwake: Boolean = false
    private var overlayView: OverlayCanvasView? = null
    private var revertRunnable: Runnable? = null

    private var sessionActive: Boolean = false
    private var sessionLabel: String? = null
    private var sessionKeepAwake: Boolean = true

    override fun setSessionActive(active: Boolean, label: String?, keepAwake: Boolean) {
        mainHandler.post {
            sessionActive = active
            sessionLabel = label
            sessionKeepAwake = keepAwake
            cancelScheduledRevert()

            if (!active) {
                detachView()
                return@post
            }

            val view = ensureAttachedView(keepAwake) ?: return@post
            view.setSuppressDrawing(false)
            view.updateState(idleSessionState())
        }
    }

    override suspend fun beforeScreenshot() {
        val wasDrawing = withContext(mainDispatcher) {
            val view = overlayView ?: return@withContext false
            val drawing = view.isDrawingVisible()
            cancelScheduledRevert()
            if (drawing) {
                view.setSuppressDrawing(true)
            }
            drawing
        }
        if (wasDrawing) {
            // Allow one compositor frame (~32ms) so transparent canvas is committed before capture.
            delay(SCREENSHOT_HIDE_SETTLE_MS)
        }
    }

    override fun showCaptured() {
        postTransientState(
            state = OverlayState(
                label = "android-use · Captured",
                borderColor = COLOR_CAPTURE,
            ),
            durationMs = CAPTURE_FLASH_MS,
        )
    }

    override fun showTap(point: UiPoint, longPress: Boolean) {
        postTransientState(
            state = OverlayState(
                label = if (longPress) "android-use · Long press" else "android-use · Tap",
                borderColor = if (longPress) COLOR_LONG_PRESS else COLOR_TAP,
                tapPoint = point,
                accentColor = if (longPress) COLOR_LONG_PRESS else COLOR_TAP,
            ),
            durationMs = if (longPress) LONG_PRESS_DISPLAY_MS else ACTION_DISPLAY_MS,
        )
    }

    override fun showSwipe(start: UiPoint, end: UiPoint) {
        postTransientState(
            state = OverlayState(
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
        postTransientState(
            state = OverlayState(
                label = "android-use · $label",
                borderColor = COLOR_TAP,
            ),
            durationMs = ACTION_DISPLAY_MS,
        )
    }

    override fun clear() {
        mainHandler.post {
            sessionActive = false
            sessionLabel = null
            cancelScheduledRevert()
            detachView()
        }
    }

    private fun postTransientState(state: OverlayState, durationMs: Long) {
        mainHandler.post {
            if (!sessionActive) return@post
            val view = ensureAttachedView(sessionKeepAwake) ?: return@post
            cancelScheduledRevert()
            view.setSuppressDrawing(false)
            view.updateState(state)
            val runnable = Runnable {
                if (sessionActive) {
                    view.setSuppressDrawing(false)
                    view.updateState(idleSessionState())
                } else {
                    detachView()
                }
            }
            revertRunnable = runnable
            mainHandler.postDelayed(runnable, durationMs)
        }
    }

    private fun idleSessionState(): OverlayState {
        val text = if (!sessionLabel.isNullOrBlank()) {
            "android-use · $sessionLabel"
        } else {
            "android-use · Session active"
        }
        return OverlayState(
            label = text,
            borderColor = COLOR_SESSION,
        )
    }

    private fun ensureAttachedView(keepAwake: Boolean): OverlayCanvasView? {
        val service = accessibilityRegistry.currentService() ?: run {
            detachView()
            return null
        }

        val existingView = overlayView
        if (attachedService === service && existingView != null) {
            if (attachedKeepAwake != keepAwake) {
                updateWindowFlags(service, existingView, keepAwake)
            }
            return existingView
        }

        detachView()

        val wm = service.getSystemService(WindowManager::class.java) ?: return null
        val view = OverlayCanvasView(service)
        val params = buildLayoutParams(keepAwake)

        return runCatching {
            wm.addView(view, params)
            attachedService = service
            attachedKeepAwake = keepAwake
            overlayView = view
            view
        }.getOrNull()
    }

    private fun updateWindowFlags(
        service: AccessibilityService,
        view: OverlayCanvasView,
        keepAwake: Boolean,
    ) {
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        runCatching {
            wm.updateViewLayout(view, buildLayoutParams(keepAwake))
            attachedKeepAwake = keepAwake
        }
    }

    private fun buildLayoutParams(keepAwake: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (keepAwake) {
            flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        )
    }

    private fun detachView() {
        val view = overlayView
        val service = attachedService
        overlayView = null
        attachedService = null
        attachedKeepAwake = false
        if (view != null && service != null) {
            runCatching {
                service.getSystemService(WindowManager::class.java)?.removeViewImmediate(view)
            }
        }
    }

    private fun cancelScheduledRevert() {
        revertRunnable?.let { mainHandler.removeCallbacks(it) }
        revertRunnable = null
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
        private var suppressDrawing: Boolean = false

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

        fun isDrawingVisible(): Boolean = state != null && !suppressDrawing && visibility == VISIBLE

        fun setSuppressDrawing(suppress: Boolean) {
            suppressDrawing = suppress
            invalidate()
        }

        fun updateState(newState: OverlayState) {
            state = newState
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (suppressDrawing) return
            val current = state ?: return

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

        private val COLOR_SESSION = Color.rgb(56, 189, 248)
        private val COLOR_TAP = Color.rgb(0, 212, 255)
        private val COLOR_LONG_PRESS = Color.rgb(255, 179, 0)
        private val COLOR_SWIPE = Color.rgb(124, 77, 255)
        private val COLOR_CAPTURE = Color.rgb(0, 230, 118)
    }
}
