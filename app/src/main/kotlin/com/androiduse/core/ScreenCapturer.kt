package com.androiduse.core

import com.androiduse.protocol.ImageFormat
import com.androiduse.protocol.ScreenshotResponse
import com.androiduse.protocol.UiMark
import java.util.Base64

data class CaptureOptions(
    val format: ImageFormat = ImageFormat.JPEG,
    val quality: Int = DEFAULT_QUALITY,
    val maxDimension: Int? = DEFAULT_MAX_DIMENSION,
    val allowCachedMs: Long = 0L,
    val annotate: Boolean = false,
) {
    fun validate(): CaptureOptions {
        if (quality !in 1..100) {
            throw AndroidUseException.invalidRequest("quality must be between 1 and 100 (got $quality).")
        }
        if (maxDimension != null && maxDimension < MIN_DIMENSION) {
            throw AndroidUseException.invalidRequest(
                "maxDimension must be at least $MIN_DIMENSION or 0/none for native size (got $maxDimension).",
            )
        }
        if (allowCachedMs < 0L) {
            throw AndroidUseException.invalidRequest(
                "allowCachedMs must be non-negative (got $allowCachedMs).",
            )
        }
        return this
    }

    companion object {
        const val DEFAULT_QUALITY: Int = 80
        const val DEFAULT_MAX_DIMENSION: Int = 1280
        const val MIN_DIMENSION: Int = 64
    }
}

data class CaptureResult(
    val bytes: ByteArray,
    val format: ImageFormat,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val scale: Double,
    val rotation: Int,
    val timestampMs: Long,
    val marks: List<UiMark>? = null,
) {
    fun toBase64Response(): ScreenshotResponse = ScreenshotResponse(
        format = format,
        mimeType = mimeType,
        width = width,
        height = height,
        displayWidth = displayWidth,
        displayHeight = displayHeight,
        scale = scale,
        rotation = rotation,
        timestampMs = timestampMs,
        dataBase64 = Base64.getEncoder().encodeToString(bytes),
        marks = marks,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptureResult) return false
        return bytes.contentEquals(other.bytes) &&
            format == other.format &&
            mimeType == other.mimeType &&
            width == other.width &&
            height == other.height &&
            displayWidth == other.displayWidth &&
            displayHeight == other.displayHeight &&
            scale == other.scale &&
            rotation == other.rotation &&
            timestampMs == other.timestampMs &&
            marks == other.marks
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + displayWidth
        result = 31 * result + displayHeight
        result = 31 * result + scale.hashCode()
        result = 31 * result + rotation
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + (marks?.hashCode() ?: 0)
        return result
    }
}

/**
 * Renders Set-of-Mark (SoM) bounding boxes and numbered badges onto a captured screenshot.
 */
interface ScreenshotAnnotator {
    fun annotate(capture: CaptureResult, marks: List<UiMark>): CaptureResult
}

object NoOpScreenshotAnnotator : ScreenshotAnnotator {
    override fun annotate(capture: CaptureResult, marks: List<UiMark>): CaptureResult =
        capture.copy(marks = marks)
}

/**
 * Abstraction for screen capture backends (ADR-0003).
 * Phase 2 implements [com.androiduse.android.AccessibilityScreenCapturer];
 * Phase 8 may add an optional MediaProjection implementation behind the same contract.
 */
interface ScreenCapturer {
    suspend fun capture(options: CaptureOptions = CaptureOptions()): CaptureResult
}
