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
import com.androiduse.protocol.MarksResponse
import com.androiduse.protocol.ScreenStateResponse
import com.androiduse.protocol.SessionStatusResponse
import com.androiduse.protocol.StartSessionRequest
import com.androiduse.protocol.SwipeRequest
import com.androiduse.protocol.TapElementRequest
import com.androiduse.protocol.TapRequest
import com.androiduse.protocol.TextActionResponse
import com.androiduse.protocol.TypeTextRequest
import com.androiduse.protocol.UiBounds
import com.androiduse.protocol.UiMark
import com.androiduse.protocol.UiNode
import com.androiduse.protocol.UiPoint
import com.androiduse.protocol.UiTreeResponse
import com.androiduse.protocol.WaitRequest
import com.androiduse.protocol.WaitResponse
import com.androiduse.protocol.WakeResponse
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
    suspend fun getSession(): SessionStatusResponse
    suspend fun startSession(request: StartSessionRequest = StartSessionRequest()): SessionStatusResponse
    suspend fun endSession(): SessionStatusResponse
    suspend fun wakeDevice(): WakeResponse
    suspend fun captureScreenshot(options: CaptureOptions = CaptureOptions()): CaptureResult
    suspend fun getUiTree(options: UiTreeOptions = UiTreeOptions()): UiTreeResponse
    suspend fun getMarks(refresh: Boolean = false): MarksResponse
    suspend fun getScreenState(
        captureOptions: CaptureOptions = CaptureOptions(),
        treeOptions: UiTreeOptions = UiTreeOptions(),
        includeScreenshot: Boolean = true,
    ): ScreenStateResponse
    suspend fun tap(request: TapRequest): ActionResponse
    suspend fun tapElement(request: TapElementRequest): ActionResponse
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
    private val sessionManager: SessionManager = SessionManager(),
    private val powerController: DevicePowerController = StaticDevicePowerController(),
    private val overlayFeedback: ActionOverlayFeedback = NoOpActionOverlayFeedback,
    private val screenshotAnnotator: ScreenshotAnnotator = NoOpScreenshotAnnotator,
    private val uiChangeTracker: UiChangeTracker = UiChangeTracker(),
    private val serverVersion: String,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : DeviceController {

    private val actionMutex = Mutex()
    @Volatile
    private var lastTree: UiTreeResponse? = null
    @Volatile
    private var lastMarks: MarksResponse? = null
    @Volatile
    private var lastPreActionHash: String? = null
    @Volatile
    private var lastPreActionTimeMs: Long = 0L

    override suspend fun getHealth(): HealthResponse {
        val sessionActive = sessionManager.currentSession() != null
        return HealthResponse(
            status = "ok",
            accessibilityConnected = accessibilityRegistry.isConnected.value,
            version = serverVersion,
            sessionActive = sessionActive,
        )
    }

    override suspend fun getDeviceInfo(): DeviceInfoResponse {
        val power = powerController.getPowerState()
        val sessionActive = sessionManager.currentSession() != null
        return deviceInfoProvider.getDeviceInfo().copy(
            screenOn = power.screenOn,
            deviceLocked = power.deviceLocked,
            sessionActive = sessionActive,
        )
    }

    override suspend fun getSession(): SessionStatusResponse {
        return toSessionResponse(sessionManager.currentSession(), powerController.getPowerState())
    }

    override suspend fun startSession(request: StartSessionRequest): SessionStatusResponse {
        val power = if (request.wakeScreen) {
            powerController.wakeScreen()
        } else {
            powerController.getPowerState()
        }
        val session = sessionManager.startSession(request)
        overlayFeedback.setSessionActive(
            active = true,
            label = session.label,
            keepAwake = session.keepAwake,
        )
        return toSessionResponse(session, power)
    }

    override suspend fun endSession(): SessionStatusResponse {
        sessionManager.endSession()
        lastTree = null
        lastMarks = null
        lastPreActionHash = null
        lastPreActionTimeMs = 0L
        overlayFeedback.setSessionActive(active = false, label = null, keepAwake = false)
        return toSessionResponse(null, powerController.getPowerState())
    }

    override suspend fun wakeDevice(): WakeResponse {
        val power = powerController.wakeScreen()
        return WakeResponse(
            ok = true,
            screenOn = power.screenOn,
            deviceLocked = power.deviceLocked,
            timestampMs = clockMs(),
        )
    }

    override suspend fun captureScreenshot(options: CaptureOptions): CaptureResult {
        sessionManager.requireActiveSessionAndTouch()
        val raw = captureRawScreenshot(options)
        if (!options.annotate) {
            return raw
        }
        val tree = uiTreeInspector.inspect(UiTreeOptions())
        lastTree = tree
        val marks = SetOfMarkSelector.select(
            tree = tree,
            displayWidth = raw.displayWidth,
            displayHeight = raw.displayHeight,
        )
        lastMarks = MarksResponse(
            count = marks.size,
            displayWidth = raw.displayWidth,
            displayHeight = raw.displayHeight,
            timestampMs = raw.timestampMs,
            marks = marks,
        )
        return screenshotAnnotator.annotate(raw, marks)
    }

    override suspend fun getUiTree(options: UiTreeOptions): UiTreeResponse {
        sessionManager.requireActiveSessionAndTouch()
        val tree = uiTreeInspector.inspect(options)
        lastTree = tree
        return tree
    }

    override suspend fun getMarks(refresh: Boolean): MarksResponse {
        sessionManager.requireActiveSessionAndTouch()
        if (!refresh) {
            lastMarks?.let { return it }
        }
        val info = deviceInfoProvider.getDeviceInfo()
        val tree = uiTreeInspector.inspect(UiTreeOptions())
        lastTree = tree
        val marks = SetOfMarkSelector.select(
            tree = tree,
            displayWidth = info.displayWidth,
            displayHeight = info.displayHeight,
        )
        val response = MarksResponse(
            count = marks.size,
            displayWidth = info.displayWidth,
            displayHeight = info.displayHeight,
            timestampMs = clockMs(),
            marks = marks,
        )
        lastMarks = response
        return response
    }

    override suspend fun getScreenState(
        captureOptions: CaptureOptions,
        treeOptions: UiTreeOptions,
        includeScreenshot: Boolean,
    ): ScreenStateResponse {
        sessionManager.requireActiveSessionAndTouch()
        val device = getDeviceInfo()
        val tree = getUiTree(treeOptions)
        var marks: List<UiMark>? = null
        val screenshot = if (includeScreenshot) {
            val raw = captureRawScreenshot(captureOptions)
            if (captureOptions.annotate) {
                val computed = SetOfMarkSelector.select(
                    tree = tree,
                    displayWidth = raw.displayWidth,
                    displayHeight = raw.displayHeight,
                )
                marks = computed
                lastMarks = MarksResponse(
                    count = computed.size,
                    displayWidth = raw.displayWidth,
                    displayHeight = raw.displayHeight,
                    timestampMs = raw.timestampMs,
                    marks = computed,
                )
                screenshotAnnotator.annotate(raw, computed).toBase64Response()
            } else {
                raw.toBase64Response()
            }
        } else {
            if (captureOptions.annotate) {
                val computed = SetOfMarkSelector.select(
                    tree = tree,
                    displayWidth = device.displayWidth,
                    displayHeight = device.displayHeight,
                )
                marks = computed
                lastMarks = MarksResponse(
                    count = computed.size,
                    displayWidth = device.displayWidth,
                    displayHeight = device.displayHeight,
                    timestampMs = clockMs(),
                    marks = computed,
                )
            }
            null
        }
        return ScreenStateResponse(
            device = device,
            screenshot = screenshot,
            tree = tree,
            marks = marks,
            timestampMs = clockMs(),
        )
    }

    override suspend fun tap(request: TapRequest): ActionResponse {
        sessionManager.requireActiveSessionAndTouch()
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

    override suspend fun tapElement(request: TapElementRequest): ActionResponse {
        sessionManager.requireActiveSessionAndTouch()

        val hasMarkId = request.markId != null
        val nodeIdQuery = request.nodeId?.trim()?.takeIf { it.isNotEmpty() }
        val textQuery = request.text?.takeIf { it.isNotEmpty() }
        val descQuery = request.contentDescription?.takeIf { it.isNotEmpty() }
        val resIdQuery = request.resourceId?.takeIf { it.isNotEmpty() }
        val hasSelector = textQuery != null || descQuery != null || resIdQuery != null

        if (!hasMarkId && nodeIdQuery == null && !hasSelector) {
            throw AndroidUseException.invalidRequest(
                "Provide at least one target: 'markId', 'nodeId', 'text', 'contentDescription', or 'resourceId'.",
            )
        }
        if (request.index < 0) {
            throw AndroidUseException.invalidRequest("index must be >= 0 (got ${request.index}).")
        }

        val effectiveDurationMs = request.durationMs ?: if (request.longPress) 600L else 50L
        if (request.longPress) {
            validateDuration(effectiveDurationMs, minMs = 200L, maxMs = 5_000L, name = "long-press durationMs")
        } else {
            validateDuration(effectiveDurationMs, minMs = 10L, maxMs = 500L, name = "tap durationMs")
        }

        return withActionLock {
            val info = deviceInfoProvider.getDeviceInfo()
            val resolved = resolveElementTarget(
                request = request,
                nodeIdQuery = nodeIdQuery,
                textQuery = textQuery,
                descQuery = descQuery,
                resIdQuery = resIdQuery,
                displayWidth = info.displayWidth,
                displayHeight = info.displayHeight,
            )

            overlayFeedback.showTap(point = resolved.point, longPress = request.longPress)
            if (request.longPress) {
                inputDispatcher.longPress(point = resolved.point, durationMs = effectiveDurationMs)
            } else {
                inputDispatcher.tap(point = resolved.point, durationMs = effectiveDurationMs)
            }

            ActionResponse(
                ok = true,
                action = if (request.longPress) "long_press_element" else "tap_element",
                resolvedPoint = resolved.point,
                matchedNode = resolved.node,
                matchedMarkId = resolved.markId,
                durationMs = effectiveDurationMs,
                timestampMs = clockMs(),
            )
        }
    }

    private suspend fun captureRawScreenshot(options: CaptureOptions): CaptureResult {
        overlayFeedback.beforeScreenshot()
        return try {
            screenCapturer.capture(options)
        } finally {
            overlayFeedback.showCaptured()
        }
    }

    private suspend fun resolveElementTarget(
        request: TapElementRequest,
        nodeIdQuery: String?,
        textQuery: String?,
        descQuery: String?,
        resIdQuery: String?,
        displayWidth: Int,
        displayHeight: Int,
    ): ResolvedElementTarget {
        if (request.markId != null) {
            val snapshot = lastMarks ?: throw AndroidUseException.elementNotFound(
                "No Set-of-Mark snapshot available. Capture an annotated screenshot (annotate=true) or call GET /v1/screen/marks first.",
            )
            val mark = snapshot.marks.firstOrNull { it.markId == request.markId }
                ?: throw AndroidUseException.elementNotFound(
                    "Mark #${request.markId} not found in latest Set-of-Mark snapshot (available: 1..${snapshot.count}).",
                )
            val point = clippedCenterOrThrow(
                bounds = mark.bounds,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                label = "mark #${mark.markId}",
            )
            val matchedNode = lastTree?.let { UiTreeMatcher.findByNodeId(it.roots, mark.nodeId) }
            return ResolvedElementTarget(point = point, node = matchedNode, markId = mark.markId)
        }

        if (nodeIdQuery != null) {
            val tree = lastTree ?: uiTreeInspector.inspect(UiTreeOptions()).also { lastTree = it }
            val node = UiTreeMatcher.findByNodeId(tree.roots, nodeIdQuery)
                ?: throw AndroidUseException.elementNotFound(
                    "Node '$nodeIdQuery' not found in latest UI tree. Refresh /v1/screen/tree and retry.",
                )
            val point = clippedCenterOrThrow(
                bounds = node.bounds,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                label = "node '$nodeIdQuery'",
            )
            return ResolvedElementTarget(point = point, node = node, markId = null)
        }

        // Selector-based lookup ALWAYS inspects the live UI tree right before tapping to avoid stale bounds.
        val liveTree = uiTreeInspector.inspect(UiTreeOptions())
        lastTree = liveTree
        val allMatches = UiTreeMatcher.findAllMatches(
            roots = liveTree.roots,
            text = textQuery,
            contentDescription = descQuery,
            resourceId = resIdQuery,
            exact = request.exact,
            clickableOnly = request.clickableOnly,
        )
        val visibleMatches = allMatches.mapNotNull { node ->
            val clipped = node.bounds.intersect(displayWidth, displayHeight) ?: return@mapNotNull null
            node to clipped
        }

        if (visibleMatches.isEmpty()) {
            val desc = buildSelectorDescription(textQuery, descQuery, resIdQuery)
            throw AndroidUseException.elementNotFound(
                "No visible UI element matched $desc.",
            )
        }
        if (request.index >= visibleMatches.size) {
            val desc = buildSelectorDescription(textQuery, descQuery, resIdQuery)
            throw AndroidUseException.elementNotFound(
                "Matched ${visibleMatches.size} visible element(s) for $desc, but index=${request.index} is out of range.",
            )
        }

        val (node, clipped) = visibleMatches[request.index]
        val center = clipped.center()
        val point = UiPoint(
            x = center.x.coerceIn(0, (displayWidth - 1).coerceAtLeast(0)),
            y = center.y.coerceIn(0, (displayHeight - 1).coerceAtLeast(0)),
        )
        return ResolvedElementTarget(point = point, node = node, markId = null)
    }

    private fun clippedCenterOrThrow(
        bounds: UiBounds,
        displayWidth: Int,
        displayHeight: Int,
        label: String,
    ): UiPoint {
        val clipped = bounds.intersect(displayWidth, displayHeight)
            ?: throw AndroidUseException.elementNotFound(
                "Target $label is outside visible display bounds (${displayWidth}x$displayHeight).",
            )
        val center = clipped.center()
        return UiPoint(
            x = center.x.coerceIn(0, (displayWidth - 1).coerceAtLeast(0)),
            y = center.y.coerceIn(0, (displayHeight - 1).coerceAtLeast(0)),
        )
    }

    private fun buildSelectorDescription(
        text: String?,
        contentDescription: String?,
        resourceId: String?,
    ): String = buildList {
        if (text != null) add("text='$text'")
        if (contentDescription != null) add("contentDescription='$contentDescription'")
        if (resourceId != null) add("resourceId='$resourceId'")
    }.joinToString(" and ")

    private data class ResolvedElementTarget(
        val point: UiPoint,
        val node: UiNode?,
        val markId: Int?,
    )

    override suspend fun longPress(request: LongPressRequest): ActionResponse {
        sessionManager.requireActiveSessionAndTouch()
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
        sessionManager.requireActiveSessionAndTouch()
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
        sessionManager.requireActiveSessionAndTouch()
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
        sessionManager.requireActiveSessionAndTouch()
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
        sessionManager.requireActiveSessionAndTouch()
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
        sessionManager.requireActiveSessionAndTouch()
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
        sessionManager.requireActiveSessionAndTouch()
        return appController.listLaunchableApps(query)
    }

    override suspend fun launchApp(request: LaunchAppRequest): LaunchAppResponse {
        sessionManager.requireActiveSessionAndTouch()
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
        sessionManager.requireActiveSessionAndTouch()
        val hasText = !request.text.isNullOrEmpty()
        val hasResId = !request.resourceId.isNullOrEmpty()
        val targetPackage = request.packageName?.trim()?.takeIf { it.isNotEmpty() }
        val hasPackage = targetPackage != null
        val hasCondition = hasText || hasResId || hasPackage || request.waitForChange || request.waitForIdle

        if (!hasCondition && request.durationMs == null) {
            throw AndroidUseException.invalidRequest(
                "Provide 'durationMs' for a sleep wait, or 'waitForChange'/'waitForIdle'/'packageName'/'text'/'resourceId' for a condition wait.",
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
        validateDuration(request.quietPeriodMs, minMs = 0L, maxMs = MAX_QUIET_PERIOD_MS, name = "quietPeriodMs")

        // Pure waitForIdle mode: wait until UI events stay quiet for quietPeriodMs, then snapshot state.
        if (request.waitForIdle && !request.waitForChange && !hasText && !hasResId && !hasPackage) {
            val settled = uiChangeTracker.awaitQuiet(
                quietPeriodMs = request.quietPeriodMs,
                maxWaitMs = request.timeoutMs,
            )
            val tree = uiTreeInspector.inspect(UiTreeOptions())
            lastTree = tree
            val hash = UiTreeFingerprint.compute(tree)
            val marks = if (request.includeMarks) computeAndCacheMarks(tree) else null
            val now = clockMs()
            val elapsed = (now - startMs).coerceAtLeast(0L)
            if (!settled && request.failOnTimeout) {
                throw AndroidUseException.waitTimeout(
                    "Timed out after ${request.timeoutMs}ms waiting for UI to become idle (quietPeriodMs=${request.quietPeriodMs}).",
                )
            }
            return WaitResponse(
                ok = true,
                matched = settled,
                elapsedMs = elapsed,
                foregroundPackage = tree.packageName,
                screenHash = hash,
                marks = marks,
                timestampMs = now,
            )
        }

        // Resolve baseline screen hash for waitForChange.
        val baselineHash: String? = if (request.waitForChange) {
            val explicit = request.baselineHash?.trim()?.takeIf { it.isNotEmpty() }
            val recentPreAction = lastPreActionHash?.takeIf {
                val age = startMs - lastPreActionTimeMs
                age in 0L..PRE_ACTION_BASELINE_TTL_MS
            }
            explicit ?: recentPreAction ?: run {
                val initialTree = uiTreeInspector.inspect(UiTreeOptions())
                lastTree = initialTree
                UiTreeFingerprint.compute(initialTree)
            }
        } else {
            null
        }

        while (true) {
            sessionManager.requireActiveSessionAndTouch()
            val seqBeforeInspect = uiChangeTracker.currentSequence()
            val tree = uiTreeInspector.inspect(UiTreeOptions())
            lastTree = tree
            val currentHash = UiTreeFingerprint.compute(tree)

            val changeOk = if (request.waitForChange) {
                currentHash != baselineHash
            } else {
                true
            }

            val packageOk = if (hasPackage) {
                val currentPkg = tree.packageName
                val pkgMatches = currentPkg != null && currentPkg.equals(targetPackage, ignoreCase = true)
                if (request.gone) !pkgMatches else pkgMatches
            } else {
                true
            }

            val matchedNode = if (hasText || hasResId) {
                UiTreeMatcher.findFirstMatch(
                    roots = tree.roots,
                    text = request.text,
                    resourceId = request.resourceId,
                )
            } else {
                null
            }
            val nodeOk = if (hasText || hasResId) {
                if (request.gone) matchedNode == null else matchedNode != null
            } else {
                true
            }

            val satisfied = changeOk && packageOk && nodeOk
            val now = clockMs()
            val elapsed = (now - startMs).coerceAtLeast(0L)

            if (satisfied) {
                val shouldSettle = (request.waitForChange || request.waitForIdle || hasPackage) &&
                    request.quietPeriodMs > 0L
                val finalTree = if (shouldSettle) {
                    val remaining = (request.timeoutMs - elapsed).coerceAtLeast(0L)
                    if (remaining > 0L) {
                        uiChangeTracker.awaitQuiet(
                            quietPeriodMs = request.quietPeriodMs,
                            maxWaitMs = minOf(remaining, MAX_QUIET_SETTLE_CAP_MS),
                        )
                    }
                    uiTreeInspector.inspect(UiTreeOptions()).also { lastTree = it }
                } else {
                    tree
                }
                val finalHash = if (finalTree === tree) currentHash else UiTreeFingerprint.compute(finalTree)
                val finalNode = if (hasText || hasResId) {
                    if (request.gone) null else {
                        if (finalTree === tree) matchedNode
                        else UiTreeMatcher.findFirstMatch(finalTree.roots, request.text, request.resourceId)
                            ?: matchedNode
                    }
                } else {
                    null
                }
                val marks = if (request.includeMarks) computeAndCacheMarks(finalTree) else null
                val doneNow = clockMs()
                return WaitResponse(
                    ok = true,
                    matched = true,
                    elapsedMs = (doneNow - startMs).coerceAtLeast(0L),
                    matchedNode = finalNode,
                    screenChanged = if (request.waitForChange) true else null,
                    foregroundPackage = finalTree.packageName,
                    screenHash = finalHash,
                    marks = marks,
                    timestampMs = doneNow,
                )
            }

            if (elapsed >= request.timeoutMs) {
                if (!request.failOnTimeout) {
                    val marks = if (request.includeMarks) computeAndCacheMarks(tree) else null
                    return WaitResponse(
                        ok = true,
                        matched = false,
                        elapsedMs = elapsed,
                        matchedNode = null,
                        screenChanged = if (request.waitForChange) false else null,
                        foregroundPackage = tree.packageName,
                        screenHash = currentHash,
                        marks = marks,
                        timestampMs = now,
                    )
                }
                val targetDesc = buildList {
                    if (request.waitForChange) add("screen change")
                    if (hasPackage) add("packageName='$targetPackage'")
                    if (hasText) add("text='${request.text}'")
                    if (hasResId) add("resourceId='${request.resourceId}'")
                }.joinToString(" and ")
                val verb = if (request.gone && (hasText || hasResId || hasPackage)) "disappear" else "appear"
                val suffix = if (request.waitForChange && !hasText && !hasResId && !hasPackage) "" else " to $verb"
                throw AndroidUseException.waitTimeout(
                    "Timed out after ${request.timeoutMs}ms waiting for $targetDesc$suffix.",
                )
            }

            val remaining = (request.timeoutMs - elapsed).coerceAtLeast(1L)
            val waitSlice = minOf(request.pollIntervalMs, remaining)
            // Event-driven wakeup: resumes immediately when Android emits an AccessibilityEvent,
            // or falls back to waitSlice polling if the app updates without emitting one.
            uiChangeTracker.awaitEventSince(sinceSequence = seqBeforeInspect, timeoutMs = waitSlice)
        }
    }

    private fun computeAndCacheMarks(tree: UiTreeResponse): List<UiMark> {
        val info = deviceInfoProvider.getDeviceInfo()
        val marks = SetOfMarkSelector.select(
            tree = tree,
            displayWidth = info.displayWidth,
            displayHeight = info.displayHeight,
        )
        lastMarks = MarksResponse(
            count = marks.size,
            displayWidth = info.displayWidth,
            displayHeight = info.displayHeight,
            timestampMs = clockMs(),
            marks = marks,
        )
        return marks
    }

    private suspend fun recordPreActionBaseline() {
        val existingTree = lastTree ?: runCatching { uiTreeInspector.inspect(UiTreeOptions()) }.getOrNull()
        if (existingTree != null) {
            lastTree = existingTree
            lastPreActionHash = UiTreeFingerprint.compute(existingTree)
            lastPreActionTimeMs = clockMs()
        }
    }

    private fun toSessionResponse(
        session: ActiveSession?,
        power: PowerState,
    ): SessionStatusResponse = SessionStatusResponse(
        active = session != null,
        sessionId = session?.sessionId,
        label = session?.label,
        startedAtMs = session?.startedAtMs,
        lastActivityMs = session?.lastActivityMs,
        idleTimeoutMs = session?.idleTimeoutMs,
        keepAwake = session?.keepAwake ?: false,
        screenOn = power.screenOn,
        deviceLocked = power.deviceLocked,
    )

    private fun validateDuration(value: Long, minMs: Long, maxMs: Long, name: String) {
        if (value !in minMs..maxMs) {
            throw AndroidUseException.invalidRequest(
                "$name must be between $minMs and $maxMs ms (got $value).",
            )
        }
    }

    /**
     * Serializes mutating device actions so concurrent agent calls never interleave,
     * and records the pre-action screen hash so a subsequent `waitForChange` call can
     * immediately detect even fast transitions that finished before `/v1/wait` arrived.
     */
    internal suspend fun <T> withActionLock(block: suspend () -> T): T {
        return actionMutex.withLock {
            recordPreActionBaseline()
            block()
        }
    }

    companion object {
        private const val MAX_SLEEP_MS: Long = 30_000L
        private const val MAX_WAIT_TIMEOUT_MS: Long = 60_000L
        private const val MAX_POLL_INTERVAL_MS: Long = 2_000L
        private const val MAX_QUIET_PERIOD_MS: Long = 5_000L
        private const val MAX_QUIET_SETTLE_CAP_MS: Long = 1_500L
        private const val PRE_ACTION_BASELINE_TTL_MS: Long = 5_000L
    }
}
