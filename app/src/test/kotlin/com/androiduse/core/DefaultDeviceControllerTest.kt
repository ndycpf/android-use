package com.androiduse.core

import com.androiduse.protocol.DeviceInfoResponse
import com.androiduse.protocol.ImageFormat
import com.androiduse.protocol.UiTreeResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultDeviceControllerTest {

    @Test
    fun `getHealth, getDeviceInfo, captureScreenshot, getUiTree, and getScreenState delegate accurately`() = runTest {
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
        val fakeTree = UiTreeResponse(
            packageName = "com.example.app",
            windowCount = 1,
            nodeCount = 0,
            truncated = false,
            timestampMs = 43L,
            roots = emptyList(),
        )
        var capturedOptions: CaptureOptions? = null
        var inspectedOptions: UiTreeOptions? = null
        var captureCount = 0
        val controller = DefaultDeviceController(
            accessibilityRegistry = registry,
            deviceInfoProvider = object : DeviceInfoProvider {
                override fun getDeviceInfo(): DeviceInfoResponse = fakeInfo
            },
            screenCapturer = object : ScreenCapturer {
                override suspend fun capture(options: CaptureOptions): CaptureResult {
                    captureCount++
                    capturedOptions = options
                    return fakeCapture
                }
            },
            uiTreeInspector = object : UiTreeInspector {
                override suspend fun inspect(options: UiTreeOptions): UiTreeResponse {
                    inspectedOptions = options
                    return fakeTree
                }
            },
            serverVersion = "0.1.0",
            clockMs = { 99L },
        )

        val health = controller.getHealth()
        assertEquals("ok", health.status)
        assertFalse(health.accessibilityConnected)
        assertEquals("0.1.0", health.version)
        assertEquals(fakeInfo, controller.getDeviceInfo())

        val requestedCapture = CaptureOptions(format = ImageFormat.PNG, quality = 90, maxDimension = 1200)
        assertEquals(fakeCapture, controller.captureScreenshot(requestedCapture))
        assertEquals(requestedCapture, capturedOptions)

        val requestedTree = UiTreeOptions(maxDepth = 15, maxNodes = 100)
        assertEquals(fakeTree, controller.getUiTree(requestedTree))
        assertEquals(requestedTree, inspectedOptions)

        val stateWithShot = controller.getScreenState(
            captureOptions = requestedCapture,
            treeOptions = requestedTree,
            includeScreenshot = true,
        )
        assertEquals(fakeInfo, stateWithShot.device)
        assertEquals(fakeTree, stateWithShot.tree)
        assertNotNull(stateWithShot.screenshot)
        assertEquals(99L, stateWithShot.timestampMs)
        assertEquals(2, captureCount)

        val stateWithoutShot = controller.getScreenState(includeScreenshot = false)
        assertNull(stateWithoutShot.screenshot)
        assertEquals(2, captureCount)
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
            uiTreeInspector = object : UiTreeInspector {
                override suspend fun inspect(options: UiTreeOptions): UiTreeResponse = error("not used")
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
