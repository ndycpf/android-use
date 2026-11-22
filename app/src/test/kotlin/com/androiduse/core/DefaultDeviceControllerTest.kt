package com.androiduse.core

import com.androiduse.protocol.CoordinateSpace
import com.androiduse.protocol.DeviceInfoResponse
import com.androiduse.protocol.ErrorCode
import com.androiduse.protocol.GlobalKey
import com.androiduse.protocol.ImageFormat
import com.androiduse.protocol.KeyRequest
import com.androiduse.protocol.LongPressRequest
import com.androiduse.protocol.SwipeRequest
import com.androiduse.protocol.TapRequest
import com.androiduse.protocol.UiPoint
import com.androiduse.protocol.UiTreeResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DefaultDeviceControllerTest {

    @Test
    fun `observation methods delegate accurately`() = runTest {
        val registry = AccessibilityServiceRegistry()
        val fakeInfo = sampleDeviceInfo()
        val fakeCapture = sampleCapture()
        val fakeTree = sampleTree()
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
            inputDispatcher = RecordingInputDispatcher(),
            textInputController = RecordingTextInputController(),
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
    fun `tap, longPress, swipe, pressKey, and text actions delegate cleanly`() = runTest {
        val dispatcher = RecordingInputDispatcher()
        val textController = RecordingTextInputController()
        val controller = DefaultDeviceController(
            accessibilityRegistry = AccessibilityServiceRegistry(),
            deviceInfoProvider = object : DeviceInfoProvider {
                override fun getDeviceInfo(): DeviceInfoResponse = sampleDeviceInfo()
            },
            screenCapturer = object : ScreenCapturer {
                override suspend fun capture(options: CaptureOptions): CaptureResult = sampleCapture()
            },
            uiTreeInspector = object : UiTreeInspector {
                override suspend fun inspect(options: UiTreeOptions): UiTreeResponse = sampleTree()
            },
            inputDispatcher = dispatcher,
            textInputController = textController,
            serverVersion = "0.1.0",
            clockMs = { 500L },
        )

        val tapResp = controller.tap(
            TapRequest(
                x = 270.0,
                y = 600.0,
                coordinateSpace = CoordinateSpace.IMAGE,
                imageWidth = 540,
                imageHeight = 1200,
                durationMs = 60L,
            ),
        )
        assertTrue(tapResp.ok)
        assertEquals("tap", tapResp.action)
        assertEquals(UiPoint(540, 1200), tapResp.resolvedPoint)

        val longResp = controller.longPress(
            LongPressRequest(x = 0.5, y = 0.5, coordinateSpace = CoordinateSpace.NORMALIZED, durationMs = 700L),
        )
        assertEquals("long_press", longResp.action)
        assertEquals(UiPoint(540, 1200), longResp.resolvedPoint)

        val swipeResp = controller.swipe(
            SwipeRequest(startX = 500.0, startY = 1800.0, endX = 500.0, endY = 600.0, durationMs = 300L),
        )
        assertEquals("swipe", swipeResp.action)
        assertEquals(UiPoint(500, 1800), swipeResp.resolvedPoint)
        assertEquals(UiPoint(500, 600), swipeResp.resolvedEndPoint)

        val keyResp = controller.pressKey(KeyRequest(GlobalKey.BACK))
        assertEquals("key", keyResp.action)

        val typeResp = controller.typeText(
            com.androiduse.protocol.TypeTextRequest(text = "hello", clearFirst = true, submit = true),
        )
        assertTrue(typeResp.ok)
        assertEquals("type", typeResp.action)
        assertEquals("set_text", typeResp.method)
        assertTrue(typeResp.submitted)

        val clearResp = controller.clearText()
        assertEquals("clear", clearResp.action)

        val imeResp = controller.performImeAction(com.androiduse.protocol.ImeActionRequest("enter"))
        assertEquals("ime", imeResp.action)
        assertTrue(imeResp.submitted)

        assertEquals(
            listOf(
                "tap:(540,1200):60",
                "longPress:(540,1200):700",
                "swipe:(500,1800)->(500,600):300",
                "key:BACK",
            ),
            dispatcher.events,
        )
        assertEquals(
            listOf("type:hello:clear=true:submit=true", "clear", "ime:enter"),
            textController.events,
        )

        try {
            controller.tap(TapRequest(x = 10.0, y = 10.0, durationMs = 5L))
            fail("Expected invalid duration error")
        } catch (e: AndroidUseException) {
            assertEquals(ErrorCode.INVALID_REQUEST, e.code)
        }
    }

    @Test
    fun `withActionLock serializes overlapping actions without interleaving`() = runTest {
        val controller = DefaultDeviceController(
            accessibilityRegistry = AccessibilityServiceRegistry(),
            deviceInfoProvider = object : DeviceInfoProvider {
                override fun getDeviceInfo(): DeviceInfoResponse = sampleDeviceInfo()
            },
            screenCapturer = object : ScreenCapturer {
                override suspend fun capture(options: CaptureOptions): CaptureResult = sampleCapture()
            },
            uiTreeInspector = object : UiTreeInspector {
                override suspend fun inspect(options: UiTreeOptions): UiTreeResponse = sampleTree()
            },
            inputDispatcher = RecordingInputDispatcher(),
            textInputController = RecordingTextInputController(),
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

    private fun sampleDeviceInfo() = DeviceInfoResponse(
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

    private fun sampleCapture() = CaptureResult(
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

    private fun sampleTree() = UiTreeResponse(
        packageName = "com.example.app",
        windowCount = 1,
        nodeCount = 0,
        truncated = false,
        timestampMs = 43L,
        roots = emptyList(),
    )

    private class RecordingInputDispatcher : InputDispatcher {
        val events = mutableListOf<String>()

        override suspend fun tap(point: UiPoint, durationMs: Long) {
            events += "tap:(${point.x},${point.y}):$durationMs"
        }

        override suspend fun longPress(point: UiPoint, durationMs: Long) {
            events += "longPress:(${point.x},${point.y}):$durationMs"
        }

        override suspend fun swipe(start: UiPoint, end: UiPoint, durationMs: Long) {
            events += "swipe:(${start.x},${start.y})->(${end.x},${end.y}):$durationMs"
        }

        override suspend fun pressGlobalKey(key: GlobalKey) {
            events += "key:$key"
        }
    }

    private class RecordingTextInputController : TextInputController {
        val events = mutableListOf<String>()

        override suspend fun typeText(request: com.androiduse.protocol.TypeTextRequest): TextInputResult {
            events += "type:${request.text}:clear=${request.clearFirst}:submit=${request.submit}"
            return TextInputResult(method = "set_text", submitted = request.submit)
        }

        override suspend fun clearText(): TextInputResult {
            events += "clear"
            return TextInputResult(method = "set_text", submitted = false)
        }

        override suspend fun performImeAction(request: com.androiduse.protocol.ImeActionRequest): TextInputResult {
            events += "ime:${request.action}"
            return TextInputResult(method = "ime_enter", submitted = true)
        }
    }
}
