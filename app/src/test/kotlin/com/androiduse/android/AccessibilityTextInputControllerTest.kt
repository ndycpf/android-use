package com.androiduse.android

import androidx.test.core.app.ApplicationProvider
import com.androiduse.core.AccessibilityServiceRegistry
import com.androiduse.core.AndroidUseException
import com.androiduse.protocol.ErrorCode
import com.androiduse.protocol.ImeActionRequest
import com.androiduse.protocol.TypeTextRequest
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
class AccessibilityTextInputControllerTest {

    @Test
    fun `typeText and clearText fail with ACCESSIBILITY_NOT_CONNECTED when service is absent`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val controller = AccessibilityTextInputController(
            context = context,
            accessibilityRegistry = AccessibilityServiceRegistry(),
        )

        try {
            controller.typeText(TypeTextRequest(text = "hello"))
            fail("Expected AndroidUseException")
        } catch (e: AndroidUseException) {
            assertEquals(ErrorCode.ACCESSIBILITY_NOT_CONNECTED, e.code)
            assertEquals(503, e.httpStatus)
            assertTrue(e.retryable)
        }

        try {
            controller.clearText()
            fail("Expected AndroidUseException")
        } catch (e: AndroidUseException) {
            assertEquals(ErrorCode.ACCESSIBILITY_NOT_CONNECTED, e.code)
            assertEquals(503, e.httpStatus)
        }
    }

    @Test
    fun `performImeAction rejects unsupported action name with 400 INVALID_REQUEST`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val controller = AccessibilityTextInputController(
            context = context,
            accessibilityRegistry = AccessibilityServiceRegistry(),
        )

        try {
            controller.performImeAction(ImeActionRequest(action = "unknown_action"))
            fail("Expected AndroidUseException")
        } catch (e: AndroidUseException) {
            assertEquals(ErrorCode.INVALID_REQUEST, e.code)
            assertEquals(400, e.httpStatus)
        }
    }
}
