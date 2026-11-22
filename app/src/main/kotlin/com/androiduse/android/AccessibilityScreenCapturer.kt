package com.androiduse.android

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import com.androiduse.core.AccessibilityServiceRegistry
import com.androiduse.core.AndroidUseException
import com.androiduse.core.CaptureOptions
import com.androiduse.core.CaptureResult
import com.androiduse.core.ImageScaler
import com.androiduse.core.ScreenCapturer
import com.androiduse.protocol.ImageFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Default [ScreenCapturer] backend backed by [AccessibilityService.takeScreenshot] (API 30+).
 * Requires no per-session MediaProjection consent dialog (ADR-0003).
 */
class AccessibilityScreenCapturer(
    private val context: Context,
    private val accessibilityRegistry: AccessibilityServiceRegistry,
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : ScreenCapturer {

    override suspend fun capture(options: CaptureOptions): CaptureResult {
        options.validate()
        val service = accessibilityRegistry.currentService()
            ?: throw AndroidUseException.accessibilityNotConnected()

        val screenshotResult = takeRawScreenshot(service)
        return withContext(processingDispatcher) {
            encodeScreenshot(screenshotResult, options)
        }
    }

    private suspend fun takeRawScreenshot(
        service: AccessibilityService,
    ): AccessibilityService.ScreenshotResult = suspendCancellableCoroutine { continuation ->
        val executor = processingDispatcher.asExecutor()
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        if (continuation.isActive) {
                            continuation.resume(screenshot)
                        } else {
                            screenshot.hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(mapScreenshotError(errorCode))
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            if (continuation.isActive) {
                continuation.resumeWithException(
                    AndroidUseException.screenshotFailed(
                        message = "Failed to invoke takeScreenshot: ${t.message}",
                        cause = t,
                    ),
                )
            }
        }
    }

    private fun encodeScreenshot(
        screenshot: AccessibilityService.ScreenshotResult,
        options: CaptureOptions,
    ): CaptureResult {
        val hardwareBuffer = screenshot.hardwareBuffer
        val colorSpace = screenshot.colorSpace

        var hardwareBitmap: Bitmap? = null
        var softwareBitmap: Bitmap? = null
        var outputBitmap: Bitmap? = null

        try {
            hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                ?: throw AndroidUseException.screenshotFailed(
                    message = "Bitmap.wrapHardwareBuffer returned null.",
                    retryable = true,
                )

            softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw AndroidUseException.screenshotFailed(
                    message = "Failed to copy hardware screenshot bitmap to software memory.",
                    retryable = true,
                )

            // Release hardware resources immediately after software copy.
            hardwareBitmap.recycle()
            hardwareBitmap = null

            val displayWidth = softwareBitmap.width
            val displayHeight = softwareBitmap.height
            val scaled = ImageScaler.computeScaledSize(
                sourceWidth = displayWidth,
                sourceHeight = displayHeight,
                maxDimension = options.maxDimension,
            )

            outputBitmap = if (scaled.width != displayWidth || scaled.height != displayHeight) {
                Bitmap.createScaledBitmap(softwareBitmap, scaled.width, scaled.height, true)
            } else {
                softwareBitmap
            }

            val compressFormat = when (options.format) {
                ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                ImageFormat.PNG -> Bitmap.CompressFormat.PNG
            }
            val outputStream = ByteArrayOutputStream()
            val encoded = outputBitmap.compress(compressFormat, options.quality, outputStream)
            if (!encoded) {
                throw AndroidUseException.screenshotFailed(
                    message = "Failed to compress screenshot as ${options.format.name}.",
                )
            }

            return CaptureResult(
                bytes = outputStream.toByteArray(),
                format = options.format,
                mimeType = options.format.mimeType,
                width = outputBitmap.width,
                height = outputBitmap.height,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                scale = scaled.scale,
                rotation = currentDisplayRotationDegrees(),
                timestampMs = clockMs(),
            )
        } finally {
            if (outputBitmap != null && outputBitmap !== softwareBitmap && !outputBitmap.isRecycled) {
                outputBitmap.recycle()
            }
            if (softwareBitmap != null && !softwareBitmap.isRecycled) {
                softwareBitmap.recycle()
            }
            if (hardwareBitmap != null && !hardwareBitmap.isRecycled) {
                hardwareBitmap.recycle()
            }
            hardwareBuffer.close()
        }
    }

    private fun currentDisplayRotationDegrees(): Int {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val rotationEnum = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: Surface.ROTATION_0
        return when (rotationEnum) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    internal companion object {
        fun mapScreenshotError(errorCode: Int): AndroidUseException = when (errorCode) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                AndroidUseException.screenshotRateLimited()

            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                AndroidUseException.accessibilityNotConnected()

            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
                AndroidUseException.screenshotFailed(
                    message = "takeScreenshot failed: invalid display (errorCode=$errorCode).",
                    retryable = false,
                )

            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ->
                AndroidUseException.screenshotFailed(
                    message = "takeScreenshot failed: invalid window (errorCode=$errorCode).",
                    retryable = true,
                )

            else ->
                AndroidUseException.screenshotFailed(
                    message = "takeScreenshot failed with errorCode=$errorCode.",
                    retryable = errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR,
                )
        }
    }
}
