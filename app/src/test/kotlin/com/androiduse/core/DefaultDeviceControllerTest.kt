package com.androiduse.core

import com.androiduse.protocol.DeviceInfoResponse
import com.androiduse.protocol.ImageFormat
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DefaultDeviceControllerTest {

    @Test
    fun `getHealth, getDeviceInfo, and captureScreenshot delegate accurately`() = runTest {
        val registry = AccessibilityServiceRegistry()
        val fakeInfo = DeviceInfoResponse(
            displayWidth = 1080,
            displayHeight = 2400,
            densityDpi = 420,
            rotation = 0,
            sdkInt = 35,
            manufacturer = "Google",
            model = "Pixel",
            serverVersion = "0.1.0",
            accessibilityConnected = false,
        )
        val fakeCapture = CaptureResult(
            bytes = byteArrayOf(10, 20, 30),
            format = ImageFormat.PNG,
            mimeType = ImageFormat.PNG.mimeType,
            width = 540,
            height = 1200,
            displayWidth = 1080,
            displayHeight = 2400,
            scale = 0.5,
            rotation = 0,
            timestampMs = 42L,
        )
        var capturedOptions: CaptureOptions? = null
        val controller = DefaultDeviceController(
            accessibilityRegistry = registry,
            deviceInfoProvider = object : DeviceInfoProvider {
                override fun getDeviceInfo(): DeviceInfoResponse = fakeInfo
            },
            screenCapturer = object : ScreenCapturer {
                override suspend fun capture(options: CaptureOptions): CaptureResult {
                    capturedOptions = options
                    return fakeCapture
                }
            },
            serverVersion = "0.1.0",
        )

        val health = controller.getHealth()
        assertEquals("ok", health.status)
        assertFalse(health.accessibilityConnected)
        assertEquals("0.1.0", health.version)
        assertEquals(fakeInfo, controller.getDeviceInfo())

        val requested = CaptureOptions(format = ImageFormat.PNG, quality = 90, maxDimension = 1200)
        assertEquals(fakeCapture, controller.captureScreenshot(requested))
        assertEquals(requested, capturedOptions)
    }

    @Test
    fun `withActionLock serializes overlapping actions without interleaving`() = runTest {
        val controller = DefaultDeviceController(
            accessibilityRegistry = AccessibilityServiceRegistry(),
            deviceInfoProvider = object : DeviceInfoProvider {
                override fun getDeviceInfo(): DeviceInfoResponse = error("not used")
            },
            screenCapturer = object : ScreenCapturer {
                override suspend fun capture(options: CaptureOptions): CaptureResult = error("not used")
            },
            serverVersion = "0.1.0",
        )
        val events = mutableListOf<String>()

        val first = async {
            controller.withActionLock {
                events += "first-start"
                delay(50)
                events += "first-end"
            }
        }
        val second = async {
            delay(10)
            controller.withActionLock {
                events += "second-start"
                events += "second-end"
            }
        }

        first.await()
        second.await()

        assertEquals(
            listOf("first-start", "first-end", "second-start", "second-end"),
            events,
        )
    }
}
