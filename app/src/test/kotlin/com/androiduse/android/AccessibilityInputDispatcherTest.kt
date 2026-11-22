package com.androiduse.android

import android.accessibilityservice.AccessibilityService
import com.androiduse.core.AccessibilityServiceRegistry
import com.androiduse.core.AndroidUseException
import com.androiduse.protocol.ErrorCode
import com.androiduse.protocol.GlobalKey
import com.androiduse.protocol.UiPoint
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
class AccessibilityInputDispatcherTest {

    @Test
    fun `actions fail with ACCESSIBILITY_NOT_CONNECTED when service is absent`() = runTest {
        val dispatcher = AccessibilityInputDispatcher(
            accessibilityRegistry = AccessibilityServiceRegistry(),
        )

        try {
            dispatcher.tap(UiPoint(100, 200), durationMs = 50L)
            fail("Expected AndroidUseException")
        } catch (e: AndroidUseException) {
            assertEquals(ErrorCode.ACCESSIBILITY_NOT_CONNECTED, e.code)
            assertEquals(503, e.httpStatus)
            assertTrue(e.retryable)
        }

        try {
            dispatcher.pressGlobalKey(GlobalKey.HOME)
            fail("Expected AndroidUseException")
        } catch (e: AndroidUseException) {
            assertEquals(ErrorCode.ACCESSIBILITY_NOT_CONNECTED, e.code)
            assertEquals(503, e.httpStatus)
        }
    }

    @Test
    fun `mapGlobalAction covers all GlobalKey values accurately`() {
        assertEquals(
            AccessibilityService.GLOBAL_ACTION_BACK,
            AccessibilityInputDispatcher.mapGlobalAction(GlobalKey.BACK),
        )
        assertEquals(
            AccessibilityService.GLOBAL_ACTION_HOME,
            AccessibilityInputDispatcher.mapGlobalAction(GlobalKey.HOME),
        )
        assertEquals(
            AccessibilityService.GLOBAL_ACTION_RECENTS,
            AccessibilityInputDispatcher.mapGlobalAction(GlobalKey.RECENTS),
        )
        assertEquals(
            AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
            AccessibilityInputDispatcher.mapGlobalAction(GlobalKey.NOTIFICATIONS),
        )
        assertEquals(
            AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
            AccessibilityInputDispatcher.mapGlobalAction(GlobalKey.QUICK_SETTINGS),
        )
        assertEquals(
            AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE,
            AccessibilityInputDispatcher.mapGlobalAction(GlobalKey.DISMISS_NOTIFICATION_SHADE),
        )
    }
}
