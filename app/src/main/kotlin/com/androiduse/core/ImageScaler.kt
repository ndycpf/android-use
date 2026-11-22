package com.androiduse.core

import kotlin.math.max
import kotlin.math.roundToInt

data class ScaledSize(
    val width: Int,
    val height: Int,
    val scale: Double,
)

/**
 * Pure aspect-ratio-preserving downscale calculator.
 * Never upscales images smaller than or equal to [maxDimension].
 */
object ImageScaler {
    fun computeScaledSize(
        sourceWidth: Int,
        sourceHeight: Int,
        maxDimension: Int?,
    ): ScaledSize {
        require(sourceWidth > 0 && sourceHeight > 0) {
            "Source dimensions must be positive: ${sourceWidth}x$sourceHeight"
        }

        val longestEdge = max(sourceWidth, sourceHeight)
        if (maxDimension == null || longestEdge <= maxDimension) {
            return ScaledSize(width = sourceWidth, height = sourceHeight, scale = 1.0)
        }

        val scale = maxDimension.toDouble() / longestEdge.toDouble()
        val targetWidth = max(1, (sourceWidth * scale).roundToInt())
        val targetHeight = max(1, (sourceHeight * scale).roundToInt())
        return ScaledSize(width = targetWidth, height = targetHeight, scale = scale)
    }
}
