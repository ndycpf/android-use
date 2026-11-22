package com.androiduse.core

import com.androiduse.protocol.ActionResponse
import com.androiduse.protocol.AppsListResponse
import com.androiduse.protocol.ClearTextRequest
import com.androiduse.protocol.DeviceInfoResponse
import com.androiduse.protocol.HealthResponse
import com.androiduse.protocol.ImeActionRequest
import com.androiduse.protocol.KeyRequest
import com.androiduse.protocol.LaunchAppRequest
import com.androiduse.protocol.LaunchAppResponse
import com.androiduse.protocol.LongPressRequest
import com.androiduse.protocol.ScreenStateResponse
import com.androiduse.protocol.SwipeRequest
import com.androiduse.protocol.TapRequest
import com.androiduse.protocol.TextActionResponse
import com.androiduse.protocol.TypeTextRequest
import com.androiduse.protocol.UiTreeResponse
import com.androiduse.protocol.WaitRequest
import com.androiduse.protocol.WaitResponse
import kotlinx.coroutines.delay
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
    suspend fun typeText(request: TypeTextRequest): TextActionResponse
    suspend fun clearText(request: ClearTextRequest = ClearTextRequest()): TextActionResponse
    suspend fun performImeAction(request: ImeActionRequest = ImeActionRequest()): TextActionResponse
    suspend fun listApps(query: String? = null): AppsListResponse
    suspend fun launchApp(request: LaunchAppRequest): LaunchAppResponse
    suspend fun waitFor(request: WaitRequest): WaitResponse
}

class DefaultDeviceController(
    private val accessibilityRegistry: AccessibilityServiceRegistry,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val screenCapturer: ScreenCapturer,
    private val uiTreeInspector: UiTreeInspector,
    private val inputDispatcher: InputDispatcher,
    private val textInputController: TextInputController,
    private val appController: AppController,
    private val overlayFeedback: ActionOverlayFeedback = NoOpActionOverlayFeedback,
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
        overlayFeedback.beforeScreenshot()
        val result = screenCapturer.capture(options)
        overlayFeedback.showCaptured()
        return result
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
            overlayFeedback.showTap(point = point, longPress = false)
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
            overlayFeedback.showTap(point = point, longPress = true)
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
            overlayFeedback.showSwipe(start = start, end = end)
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
            overlayFeedback.showStatus("Key: ${request.key.name}")
            inputDispatcher.pressGlobalKey(request.key)
            ActionResponse(
                ok = true,
                action = "key",
                durationMs = 0L,
                timestampMs = clockMs(),
            )
        }
    }

    override suspend fun typeText(request: TypeTextRequest): TextActionResponse {
        return withActionLock {
            overlayFeedback.showStatus("Typing...")
            val result = textInputController.typeText(request)
            TextActionResponse(
                ok = true,
                action = "type",
                method = result.method,
                submitted = result.submitted,
                timestampMs = clockMs(),
            )
        }
    }

    override suspend fun clearText(request: ClearTextRequest): TextActionResponse {
        return withActionLock {
            overlayFeedback.showStatus("Clear text")
            val result = textInputController.clearText()
            TextActionResponse(
                ok = true,
                action = "clear",
                method = result.method,
                submitted = false,
                timestampMs = clockMs(),
            )
        }
    }

    override suspend fun performImeAction(request: ImeActionRequest): TextActionResponse {
        return withActionLock {
            overlayFeedback.showStatus("IME: ${request.action}")
            val result = textInputController.performImeAction(request)
            TextActionResponse(
                ok = true,
                action = "ime",
                method = result.method,
                submitted = result.submitted,
                timestampMs = clockMs(),
            )
        }
    }

    override suspend fun listApps(query: String?): AppsListResponse {
        return appController.listLaunchableApps(query)
    }

    override suspend fun launchApp(request: LaunchAppRequest): LaunchAppResponse {
        val pkg = request.packageName?.trim()?.takeIf { it.isNotEmpty() }
        val uri = request.uri?.trim()?.takeIf { it.isNotEmpty() }
        if (pkg == null && uri == null) {
            throw AndroidUseException.invalidRequest("Either 'packageName' or 'uri' must be provided.")
        }
        return withActionLock {
            overlayFeedback.showStatus("Launch: ${pkg ?: uri}")
            appController.launchApp(LaunchAppRequest(packageName = pkg, uri = uri))
            LaunchAppResponse(
                ok = true,
                packageName = pkg,
                uri = uri,
                timestampMs = clockMs(),
            )
        }
    }

    override suspend fun waitFor(request: WaitRequest): WaitResponse {
        val hasText = !request.text.isNullOrEmpty()
        val hasResId = !request.resourceId.isNullOrEmpty()
        val hasCondition = hasText || hasResId

        if (!hasCondition && request.durationMs == null) {
            throw AndroidUseException.invalidRequest(
                "Provide 'durationMs' for a sleep wait, or 'text'/'resourceId' for a condition wait.",
            )
        }

        val startMs = clockMs()

        if (!hasCondition) {
            val sleepMs = request.durationMs!!
            validateDuration(sleepMs, minMs = 0L, maxMs = MAX_SLEEP_MS, name = "durationMs")
            if (sleepMs > 0L) {
                delay(sleepMs)
            }
            val now = clockMs()
            return WaitResponse(
                ok = true,
                matched = true,
                elapsedMs = (now - startMs).coerceAtLeast(0L),
                matchedNode = null,
                timestampMs = now,
            )
        }

        validateDuration(request.timeoutMs, minMs = 100L, maxMs = MAX_WAIT_TIMEOUT_MS, name = "timeoutMs")
        validateDuration(request.pollIntervalMs, minMs = 50L, maxMs = MAX_POLL_INTERVAL_MS, name = "pollIntervalMs")

        while (true) {
            val tree = uiTreeInspector.inspect(UiTreeOptions())
            val matched = UiTreeMatcher.findFirstMatch(
                roots = tree.roots,
                text = request.text,
                resourceId = request.resourceId,
            )
            val satisfied = if (request.gone) matched == null else matched != null
            val now = clockMs()
            val elapsed = (now - startMs).coerceAtLeast(0L)

            if (satisfied) {
                return WaitResponse(
                    ok = true,
                    matched = true,
                    elapsedMs = elapsed,
                    matchedNode = if (request.gone) null else matched,
                    timestampMs = now,
                )
            }

            if (elapsed >= request.timeoutMs) {
                val targetDesc = buildString {
                    if (hasText) append("text='${request.text}'")
                    if (hasText && hasResId) append(" and ")
                    if (hasResId) append("resourceId='${request.resourceId}'")
                }
                val verb = if (request.gone) "disappear" else "appear"
                throw AndroidUseException.waitTimeout(
                    "Timed out after ${request.timeoutMs}ms waiting for $targetDesc to $verb.",
                )
            }

            val remaining = (request.timeoutMs - elapsed).coerceAtLeast(1L)
            delay(minOf(request.pollIntervalMs, remaining))
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

    companion object {
        private const val MAX_SLEEP_MS: Long = 30_000L
        private const val MAX_WAIT_TIMEOUT_MS: Long = 60_000L
        private const val MAX_POLL_INTERVAL_MS: Long = 2_000L
    }
}
