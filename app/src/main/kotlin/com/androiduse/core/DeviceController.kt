package com.androiduse.core

import com.androiduse.protocol.DeviceInfoResponse
import com.androiduse.protocol.HealthResponse
import com.androiduse.protocol.ScreenStateResponse
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
}

class DefaultDeviceController(
    private val accessibilityRegistry: AccessibilityServiceRegistry,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val screenCapturer: ScreenCapturer,
    private val uiTreeInspector: UiTreeInspector,
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

    /**
     * Serializes mutating device actions so concurrent agent calls never interleave.
     */
    internal suspend fun <T> withActionLock(block: suspend () -> T): T {
        return actionMutex.withLock { block() }
    }
}
