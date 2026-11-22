package com.androiduse.protocol

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolModelsTest {
    private val json = ProtocolJson.instance

    @Test
    fun `HealthResponse serializes and deserializes with defaults preserved`() {
        val original = HealthResponse(
            status = "ok",
            accessibilityConnected = true,
            version = "0.1.0",
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<HealthResponse>(encoded)

        assertEquals(original, decoded)
        assertTrue(encoded.contains("\"accessibilityConnected\":true"))
    }

    @Test
    fun `ErrorResponse serializes deterministic structured error envelope`() {
        val original = ErrorResponse(
            error = ApiError(
                code = ErrorCode.ACCESSIBILITY_NOT_CONNECTED,
                message = "AndroidUseAccessibilityService is not connected.",
                retryable = true,
            ),
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ErrorResponse>(encoded)

        assertEquals(original, decoded)
        assertTrue(encoded.contains("\"code\":\"ACCESSIBILITY_NOT_CONNECTED\""))
        assertTrue(encoded.contains("\"retryable\":true"))
    }

    @Test
    fun `DeviceInfoResponse omits null foregroundPackage and round-trips`() {
        val original = DeviceInfoResponse(
            displayWidth = 1080,
            displayHeight = 2400,
            densityDpi = 420,
            rotation = 0,
            sdkInt = 35,
            manufacturer = "Google",
            model = "Pixel 9",
            serverVersion = "0.1.0",
            accessibilityConnected = true,
            foregroundPackage = null,
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<DeviceInfoResponse>(encoded)

        assertEquals(original, decoded)
        assertFalse(encoded.contains("foregroundPackage"))
    }

    @Test
    fun `ProtocolJson ignores unknown fields for forward compatibility`() {
        val payload = """
            {
              "status": "ok",
              "accessibilityConnected": false,
              "version": "0.1.0",
              "futureField": "ignored"
            }
        """.trimIndent()

        val decoded = json.decodeFromString<HealthResponse>(payload)

        assertEquals("ok", decoded.status)
        assertEquals(false, decoded.accessibilityConnected)
        assertEquals("0.1.0", decoded.version)
    }
}
