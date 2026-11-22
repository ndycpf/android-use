package com.androiduse.core

import com.androiduse.protocol.ActionResponse
import com.androiduse.protocol.DeviceInfoResponse
import com.androiduse.protocol.HealthResponse
import com.androiduse.protocol.KeyRequest
import com.androiduse.protocol.LongPressRequest
import com.androiduse.protocol.ScreenStateResponse
import com.androiduse.protocol.SwipeRequest
import com.androiduse.protocol.TapRequest
import com.androiduse.protocol.UiTreeResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface DeviceInfoProvider {
    fun getDeviceInfo(): DeviceInfoResponse
}

/**
 * Transport-agnostic entry point for all Android Computer Use operations.
 * HTTP v1, MCP, and future in-app agent loops all delegate to this interface.
 */
interface DeviceController {
    suspend fun getHealth(): HealthResponse
    suspend fun getDeviceInfo(): DeviceInfoResponse
    suspend fun captureScreenshot(options: CaptureOptions = CaptureOptions()): CaptureResult
    suspend fun getUiTree(options: UiTreeOptions = UiTreeOptions()): UiTreeResponse
    suspend fun getScreenState(
        captureOptions: CaptureOptions = CaptureOptions(),
        treeOptions: UiTreeOptions = UiTreeOptions(),
        includeScreenshot: Boolean = true,
    ): ScreenStateResponse
    suspend fun tap(request: TapRequest): ActionResponse
    suspend fun longPress(request: LongPressRequest): ActionResponse
    suspend fun swipe(request: SwipeRequest): ActionResponse
    suspend fun pressKey(request: KeyRequest): ActionResponse
}

class DefaultDeviceController(
    private val accessibilityRegistry: AccessibilityServiceRegistry,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val screenCapturer: ScreenCapturer,
    private val uiTreeInspector: UiTreeInspector,
    private val inputDispatcher: InputDispatcher,
    private val serverVersion: String,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : DeviceController {

    private val actionMutex = Mutex()

    override suspend fun getHealth(): HealthResponse {
        return HealthResponse(
            status = "ok",
            accessibilityConnected = accessibilityRegistry.isConnected.value,
            version = serverVersion,
        )
    }

    override suspend fun getDeviceInfo(): DeviceInfoResponse {
        return deviceInfoProvider.getDeviceInfo()
    }

    override suspend fun captureScreenshot(options: CaptureOptions): CaptureResult {
        return screenCapturer.capture(options)
    }

    override suspend fun getUiTree(options: UiTreeOptions): UiTreeResponse {
        return uiTreeInspector.inspect(options)
    }

    override suspend fun getScreenState(
        captureOptions: CaptureOptions,
        treeOptions: UiTreeOptions,
        includeScreenshot: Boolean,
    ): ScreenStateResponse {
        val device = getDeviceInfo()
        val tree = getUiTree(treeOptions)
        val screenshot = if (includeScreenshot) {
            captureScreenshot(captureOptions).toBase64Response()
        } else {
            null
        }
        return ScreenStateResponse(
            device = device,
            screenshot = screenshot,
            tree = tree,
            timestampMs = clockMs(),
        )
    }

    override suspend fun tap(request: TapRequest): ActionResponse {
        validateDuration(request.durationMs, minMs = 10L, maxMs = 500L, name = "tap durationMs")
        return withActionLock {
            val info = deviceInfoProvider.getDeviceInfo()
            val point = CoordinateMapper.resolve(
                x = request.x,
                y = request.y,
                space = request.coordinateSpace,
                displayWidth = info.displayWidth,
                displayHeight = info.displayHeight,
                imageWidth = request.imageWidth,
                imageHeight = request.imageHeight,
            )
            inputDispatcher.tap(point = point, durationMs = request.durationMs)
            ActionResponse(
                ok = true,
                action = "tap",
                resolvedPoint = point,
                durationMs = request.durationMs,
                timestampMs = clockMs(),
            )
        }
    }

    override suspend fun longPress(request: LongPressRequest): ActionResponse {
        validateDuration(request.durationMs, minMs = 200L, maxMs = 5_000L, name = "long-press durationMs")
        return withActionLock {
            val info = deviceInfoProvider.getDeviceInfo()
            val point = CoordinateMapper.resolve(
                x = request.x,
                y = request.y,
                space = request.coordinateSpace,
                displayWidth = info.displayWidth,
                displayHeight = info.displayHeight,
                imageWidth = request.imageWidth,
                imageHeight = request.imageHeight,
            )
            inputDispatcher.longPress(point = point, durationMs = request.durationMs)
            ActionResponse(
                ok = true,
                action = "long_press",
                resolvedPoint = point,
                durationMs = request.durationMs,
                timestampMs = clockMs(),
            )
        }
    }

    override suspend fun swipe(request: SwipeRequest): ActionResponse {
        validateDuration(request.durationMs, minMs = 50L, maxMs = 5_000L, name = "swipe durationMs")
        return withActionLock {
            val info = deviceInfoProvider.getDeviceInfo()
            val start = CoordinateMapper.resolve(
                x = request.startX,
                y = request.startY,
                space = request.coordinateSpace,
                displayWidth = info.displayWidth,
                displayHeight = info.displayHeight,
                imageWidth = request.imageWidth,
                imageHeight = request.imageHeight,
            )
            val end = CoordinateMapper.resolve(
                x = request.endX,
                y = request.endY,
                space = request.coordinateSpace,
                displayWidth = info.displayWidth,
                displayHeight = info.displayHeight,
                imageWidth = request.imageWidth,
                imageHeight = request.imageHeight,
            )
            inputDispatcher.swipe(start = start, end = end, durationMs = request.durationMs)
            ActionResponse(
                ok = true,
                action = "swipe",
                resolvedPoint = start,
                resolvedEndPoint = end,
                durationMs = request.durationMs,
                timestampMs = clockMs(),
            )
        }
    }

    override suspend fun pressKey(request: KeyRequest): ActionResponse {
        return withActionLock {
            inputDispatcher.pressGlobalKey(request.key)
            ActionResponse(
                ok = true,
                action = "key",
                durationMs = 0L,
                timestampMs = clockMs(),
            )
        }
    }

    private fun validateDuration(value: Long, minMs: Long, maxMs: Long, name: String) {
        if (value !in minMs..maxMs) {
            throw AndroidUseException.invalidRequest(
                "$name must be between $minMs and $maxMs ms (got $value).",
            )
        }
    }

    /**
     * Serializes mutating device actions so concurrent agent calls never interleave.
     */
    internal suspend fun <T> withActionLock(block: suspend () -> T): T {
        return actionMutex.withLock { block() }
    }
}
