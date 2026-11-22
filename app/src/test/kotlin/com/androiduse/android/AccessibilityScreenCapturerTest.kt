package com.androiduse.android

import android.accessibilityservice.AccessibilityService
import androidx.test.core.app.ApplicationProvider
import com.androiduse.core.AccessibilityServiceRegistry
import com.androiduse.core.AndroidUseException
import com.androiduse.protocol.ErrorCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityScreenCapturerTest {

    @Test
    fun `capture fails with ACCESSIBILITY_NOT_CONNECTED when service is absent`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val capturer = AccessibilityScreenCapturer(
            context = context,
            accessibilityRegistry = AccessibilityServiceRegistry(),
        )

        try {
            capturer.capture()
            fail("Expected AndroidUseException")
        } catch (e: AndroidUseException) {
            assertEquals(ErrorCode.ACCESSIBILITY_NOT_CONNECTED, e.code)
            assertEquals(503, e.httpStatus)
            assertTrue(e.retryable)
        }
    }

    @Test
    fun `mapScreenshotError translates Android error codes accurately`() {
        val rateLimited = AccessibilityScreenCapturer.mapScreenshotError(
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT,
        )
        assertEquals(ErrorCode.SCREENSHOT_RATE_LIMITED, rateLimited.code)
        assertEquals(429, rateLimited.httpStatus)
        assertTrue(rateLimited.retryable)

        val noAccess = AccessibilityScreenCapturer.mapScreenshotError(
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS,
        )
        assertEquals(ErrorCode.ACCESSIBILITY_NOT_CONNECTED, noAccess.code)
        assertEquals(503, noAccess.httpStatus)

        val internal = AccessibilityScreenCapturer.mapScreenshotError(
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR,
        )
        assertEquals(ErrorCode.SCREENSHOT_FAILED, internal.code)
        assertEquals(500, internal.httpStatus)
        assertTrue(internal.retryable)
    }
}
