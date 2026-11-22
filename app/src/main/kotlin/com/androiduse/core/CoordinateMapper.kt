package com.androiduse.core

import com.androiduse.protocol.CoordinateSpace
import com.androiduse.protocol.UiPoint
import kotlin.math.roundToInt

/**
 * Translates coordinates from [CoordinateSpace] (`pixel`, `normalized`, or `image`)
 * into valid native display pixel coordinates within `[0, displayWidth - 1] x [0, displayHeight - 1]`.
 */
object CoordinateMapper {

    fun resolve(
        x: Double,
        y: Double,
        space: CoordinateSpace,
        displayWidth: Int,
        displayHeight: Int,
        imageWidth: Int? = null,
        imageHeight: Int? = null,
    ): UiPoint {
        if (displayWidth <= 0 || displayHeight <= 0) {
            throw AndroidUseException.actionFailed(
                "Invalid display dimensions: ${displayWidth}x$displayHeight.",
            )
        }
        if (!x.isFinite() || !y.isFinite()) {
            throw AndroidUseException.invalidRequest("Coordinates must be finite numbers (got x=$x, y=$y).")
        }

        val maxX = displayWidth - 1
        val maxY = displayHeight - 1

        return when (space) {
            CoordinateSpace.PIXEL -> {
                if (x < 0.0 || x > displayWidth.toDouble() || y < 0.0 || y > displayHeight.toDouble()) {
                    throw AndroidUseException.invalidRequest(
                        "Pixel coordinates ($x, $y) are outside display bounds ${displayWidth}x$displayHeight.",
                    )
                }
                UiPoint(
                    x = x.roundToInt().coerceIn(0, maxX),
                    y = y.roundToInt().coerceIn(0, maxY),
                )
            }

            CoordinateSpace.NORMALIZED -> {
                if (x < 0.0 || x > 1.0 || y < 0.0 || y > 1.0) {
                    throw AndroidUseException.invalidRequest(
                        "Normalized coordinates ($x, $y) must be within [0.0, 1.0].",
                    )
                }
                UiPoint(
                    x = (x * maxX).roundToInt().coerceIn(0, maxX),
                    y = (y * maxY).roundToInt().coerceIn(0, maxY),
                )
            }

            CoordinateSpace.IMAGE -> {
                if (imageWidth == null || imageWidth <= 0 || imageHeight == null || imageHeight <= 0) {
                    throw AndroidUseException.invalidRequest(
                        "imageWidth and imageHeight (> 0) are required when coordinateSpace is 'image'.",
                    )
                }
                if (x < 0.0 || x > imageWidth.toDouble() || y < 0.0 || y > imageHeight.toDouble()) {
                    throw AndroidUseException.invalidRequest(
                        "Image coordinates ($x, $y) are outside image bounds ${imageWidth}x$imageHeight.",
                    )
                }
                val scaledX = (x * displayWidth.toDouble() / imageWidth.toDouble()).roundToInt()
                val scaledY = (y * displayHeight.toDouble() / imageHeight.toDouble()).roundToInt()
                UiPoint(
                    x = scaledX.coerceIn(0, maxX),
                    y = scaledY.coerceIn(0, maxY),
                )
            }
        }
    }
}
