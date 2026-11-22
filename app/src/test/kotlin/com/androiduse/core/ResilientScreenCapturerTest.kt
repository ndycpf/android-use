package com.androiduse.core

import com.androiduse.protocol.ErrorCode
import com.androiduse.protocol.ImageFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ResilientScreenCapturerTest {

    @Test
    fun `retries bounded times when backend reports SCREENSHOT_RATE_LIMITED`() = runTest {
        var attempts = 0
        val expected = sampleResult(timestampMs = 1_000L)
        val capturer = ResilientScreenCapturer(
            delegate = object : ScreenCapturer {
                override suspend fun capture(options: CaptureOptions): CaptureResult {
                    attempts++
                    if (attempts < 3) {
                        throw AndroidUseException.screenshotRateLimited()
                    }
                    return expected
                }
            },
            maxRetries = 2,
            retryDelayMs = 100L,
            clockMs = { 1_000L },
        )

        val actual = capturer.capture()
        assertEquals(3, attempts)
        assertSame(expected, actual)
    }

    @Test
    fun `propagates SCREENSHOT_RATE_LIMITED after exhausting retries`() = runTest {
        var attempts = 0
        val capturer = ResilientScreenCapturer(
            delegate = object : ScreenCapturer {
                override suspend fun capture(options: CaptureOptions): CaptureResult {
                    attempts++
                    throw AndroidUseException.screenshotRateLimited()
                }
            },
            maxRetries = 2,
            retryDelayMs = 100L,
        )

        try {
            capturer.capture()
            fail("Expected AndroidUseException")
        } catch (e: AndroidUseException) {
            assertEquals(ErrorCode.SCREENSHOT_RATE_LIMITED, e.code)
            assertEquals(429, e.httpStatus)
            assertEquals(3, attempts)
        }
    }

    @Test
    fun `returns cached frame when allowCachedMs covers frame age and options match`() = runTest {
        var now = 10_000L
        var calls = 0
        val capturer = ResilientScreenCapturer(
            delegate = object : ScreenCapturer {
                override suspend fun capture(options: CaptureOptions): CaptureResult {
                    calls++
                    return sampleResult(format = options.format, timestampMs = now)
                }
            },
            clockMs = { now },
        )

        val first = capturer.capture(CaptureOptions(format = ImageFormat.JPEG, allowCachedMs = 500L))
        now = 10_300L
        val cached = capturer.capture(CaptureOptions(format = ImageFormat.JPEG, allowCachedMs = 500L))
        assertEquals(1, calls)
        assertSame(first, cached)

        // Different format must bypass cache.
        capturer.capture(CaptureOptions(format = ImageFormat.PNG, allowCachedMs = 500L))
        assertEquals(2, calls)

        // Expired frame must bypass cache.
        now = 11_000L
        capturer.capture(CaptureOptions(format = ImageFormat.PNG, allowCachedMs = 500L))
        assertEquals(3, calls)
    }

    private fun sampleResult(
        format: ImageFormat = ImageFormat.JPEG,
        timestampMs: Long = 1_000L,
    ): CaptureResult = CaptureResult(
        bytes = byteArrayOf(1, 2, 3),
        format = format,
        mimeType = format.mimeType,
        width = 576,
        height = 1280,
        displayWidth = 1080,
        displayHeight = 2400,
        scale = 1280.0 / 2400.0,
        rotation = 0,
        timestampMs = timestampMs,
    )
}
