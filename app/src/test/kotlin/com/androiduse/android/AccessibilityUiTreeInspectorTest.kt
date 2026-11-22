package com.androiduse.android

import com.androiduse.core.AccessibilityServiceRegistry
import com.androiduse.core.AndroidUseException
import com.androiduse.protocol.ErrorCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccessibilityUiTreeInspectorTest {

    @Test
    fun `inspect fails with ACCESSIBILITY_NOT_CONNECTED when service is absent`() = runTest {
        val inspector = AccessibilityUiTreeInspector(
            accessibilityRegistry = AccessibilityServiceRegistry(),
        )

        try {
            inspector.inspect()
            fail("Expected AndroidUseException")
        } catch (e: AndroidUseException) {
            assertEquals(ErrorCode.ACCESSIBILITY_NOT_CONNECTED, e.code)
            assertEquals(503, e.httpStatus)
            assertTrue(e.retryable)
        }
    }
}
