package com.androiduse.core

import com.androiduse.protocol.CoordinateSpace
import com.androiduse.protocol.ErrorCode
import com.androiduse.protocol.UiPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CoordinateMapperTest {

    @Test
    fun `resolves pixel coordinates and clamps exact outer edge`() {
        assertEquals(
            UiPoint(540, 1260),
            CoordinateMapper.resolve(540.0, 1260.0, CoordinateSpace.PIXEL, 1080, 2520),
        )
        assertEquals(
            UiPoint(1079, 2519),
            CoordinateMapper.resolve(1080.0, 2520.0, CoordinateSpace.PIXEL, 1080, 2520),
        )
    }

    @Test
    fun `resolves normalized coordinates across portrait and landscape`() {
        assertEquals(
            UiPoint(0, 0),
            CoordinateMapper.resolve(0.0, 0.0, CoordinateSpace.NORMALIZED, 1080, 2400),
        )
        assertEquals(
            UiPoint(540, 1200),
            CoordinateMapper.resolve(0.5, 0.5, CoordinateSpace.NORMALIZED, 1080, 2400),
        )
        assertEquals(
            UiPoint(2399, 1079),
            CoordinateMapper.resolve(1.0, 1.0, CoordinateSpace.NORMALIZED, 2400, 1080),
        )
    }

    @Test
    fun `scales image coordinates from downscaled screenshot back to native display`() {
        // 1080x2400 downscaled 2x to 540x1200
        val resolved = CoordinateMapper.resolve(
            x = 270.0,
            y = 600.0,
            space = CoordinateSpace.IMAGE,
            displayWidth = 1080,
            displayHeight = 2400,
            imageWidth = 540,
            imageHeight = 1200,
        )
        assertEquals(UiPoint(540, 1200), resolved)
    }

    @Test
    fun `rejects out-of-bounds, non-finite, and missing image dimension inputs with INVALID_REQUEST`() {
        assertInvalidRequest {
            CoordinateMapper.resolve(-1.0, 100.0, CoordinateSpace.PIXEL, 1080, 2400)
        }
        assertInvalidRequest {
            CoordinateMapper.resolve(1081.0, 100.0, CoordinateSpace.PIXEL, 1080, 2400)
        }
        assertInvalidRequest {
            CoordinateMapper.resolve(1.1, 0.5, CoordinateSpace.NORMALIZED, 1080, 2400)
        }
        assertInvalidRequest {
            CoordinateMapper.resolve(Double.NaN, 0.5, CoordinateSpace.NORMALIZED, 1080, 2400)
        }
        assertInvalidRequest {
            CoordinateMapper.resolve(100.0, 100.0, CoordinateSpace.IMAGE, 1080, 2400)
        }
        assertInvalidRequest {
            CoordinateMapper.resolve(600.0, 100.0, CoordinateSpace.IMAGE, 1080, 2400, 540, 1200)
        }
    }

    private fun assertInvalidRequest(block: () -> Unit) {
        try {
            block()
            fail("Expected AndroidUseException(INVALID_REQUEST)")
        } catch (e: AndroidUseException) {
            assertEquals(ErrorCode.INVALID_REQUEST, e.code)
            assertEquals(400, e.httpStatus)
        }
    }
}
