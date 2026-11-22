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
    fun `ScreenshotResponse serializes lowercase image format and metadata`() {
        val original = ScreenshotResponse(
            format = ImageFormat.JPEG,
            mimeType = "image/jpeg",
            width = 576,
            height = 1280,
            displayWidth = 1080,
            displayHeight = 2400,
            scale = 1280.0 / 2400.0,
            rotation = 0,
            timestampMs = 1_700_000_000_000L,
            dataBase64 = "ZmFrZQ==",
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ScreenshotResponse>(encoded)

        assertEquals(original, decoded)
        assertTrue(encoded.contains("\"format\":\"jpeg\""))
        assertTrue(encoded.contains("\"mimeType\":\"image/jpeg\""))
    }

    @Test
    fun `UiTreeResponse and ScreenStateResponse round-trip cleanly`() {
        val bounds = UiBounds(left = 100, top = 200, right = 300, bottom = 280)
        val button = UiNode(
            nodeId = "n1",
            packageName = "com.example.app",
            className = "android.widget.Button",
            resourceId = "com.example.app:id/submit",
            text = "Submit",
            bounds = bounds,
            center = bounds.center(),
            clickable = true,
        )
        val tree = UiTreeResponse(
            packageName = "com.example.app",
            windowCount = 1,
            nodeCount = 1,
            truncated = false,
            timestampMs = 10_000L,
            roots = listOf(button),
        )
        val state = ScreenStateResponse(
            device = DeviceInfoResponse(
                displayWidth = 1080,
                displayHeight = 2400,
                densityDpi = 420,
                rotation = 0,
                sdkInt = 35,
                manufacturer = "Google",
                model = "Pixel 9",
                serverVersion = "0.1.0",
                accessibilityConnected = true,
                foregroundPackage = "com.example.app",
            ),
            screenshot = null,
            tree = tree,
            timestampMs = 10_000L,
        )

        val encoded = json.encodeToString(state)
        val decoded = json.decodeFromString<ScreenStateResponse>(encoded)

        assertEquals(state, decoded)
        assertEquals(UiPoint(200, 240), decoded.tree.roots.first().center)
        assertFalse(encoded.contains("\"screenshot\""))
    }

    @Test
    fun `action requests and ActionResponse serialize cleanly with defaults`() {
        val tap = json.decodeFromString<TapRequest>("""{"x":897,"y":2365}""")
        assertEquals(897.0, tap.x, 1e-6)
        assertEquals(2365.0, tap.y, 1e-6)
        assertEquals(CoordinateSpace.PIXEL, tap.coordinateSpace)
        assertEquals(50L, tap.durationMs)

        val swipe = SwipeRequest(
            startX = 0.5,
            startY = 0.8,
            endX = 0.5,
            endY = 0.2,
            coordinateSpace = CoordinateSpace.NORMALIZED,
            durationMs = 350L,
        )
        val swipeJson = json.encodeToString(swipe)
        assertTrue(swipeJson.contains("\"coordinateSpace\":\"normalized\""))
        assertEquals(swipe, json.decodeFromString<SwipeRequest>(swipeJson))

        val key = KeyRequest(key = GlobalKey.HOME)
        assertEquals(key, json.decodeFromString<KeyRequest>(json.encodeToString(key)))

        val resp = ActionResponse(
            ok = true,
            action = "tap",
            resolvedPoint = UiPoint(897, 2365),
            durationMs = 50L,
            timestampMs = 5_000L,
        )
        assertEquals(resp, json.decodeFromString<ActionResponse>(json.encodeToString(resp)))
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
