package com.androiduse.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageScalerTest {

    @Test
    fun `portrait display downscales proportionally to maxDimension`() {
        val scaled = ImageScaler.computeScaledSize(
            sourceWidth = 1080,
            sourceHeight = 2400,
            maxDimension = 1280,
        )

        assertEquals(576, scaled.width)
        assertEquals(1280, scaled.height)
        assertEquals(1280.0 / 2400.0, scaled.scale, 1e-6)
    }

    @Test
    fun `landscape display downscales along width`() {
        val scaled = ImageScaler.computeScaledSize(
            sourceWidth = 2400,
            sourceHeight = 1080,
            maxDimension = 1200,
        )

        assertEquals(1200, scaled.width)
        assertEquals(540, scaled.height)
        assertEquals(0.5, scaled.scale, 1e-6)
    }

    @Test
    fun `never upscales when image is smaller than maxDimension`() {
        val scaled = ImageScaler.computeScaledSize(
            sourceWidth = 720,
            sourceHeight = 1200,
            maxDimension = 1280,
        )

        assertEquals(720, scaled.width)
        assertEquals(1200, scaled.height)
        assertEquals(1.0, scaled.scale, 1e-6)
    }

    @Test
    fun `null maxDimension preserves native dimensions`() {
        val scaled = ImageScaler.computeScaledSize(
            sourceWidth = 1440,
            sourceHeight = 3120,
            maxDimension = null,
        )

        assertEquals(1440, scaled.width)
        assertEquals(3120, scaled.height)
        assertEquals(1.0, scaled.scale, 1e-6)
    }
}
