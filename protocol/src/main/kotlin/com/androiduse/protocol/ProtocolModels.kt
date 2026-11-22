package com.androiduse.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Shared JSON configuration for all android-use protocol messages.
 */
object ProtocolJson {
    val instance: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}

@Serializable
enum class ErrorCode {
    ACCESSIBILITY_NOT_CONNECTED,
    SESSION_NOT_ACTIVE,
    UNAUTHORIZED,
    INVALID_REQUEST,
    SCREENSHOT_FAILED,
    SCREENSHOT_RATE_LIMITED,
    NO_FOCUSED_INPUT,
    ELEMENT_NOT_FOUND,
    APP_NOT_FOUND,
    WAIT_TIMEOUT,
    ACTION_FAILED,
    UNSUPPORTED_OPERATION,
    INTERNAL_ERROR,
}

@Serializable
enum class ImageFormat(val mimeType: String) {
    @SerialName("jpeg")
    JPEG("image/jpeg"),

    @SerialName("png")
    PNG("image/png"),
}

@Serializable
enum class CoordinateSpace {
    @SerialName("pixel")
    PIXEL,

    @SerialName("normalized")
    NORMALIZED,

    @SerialName("image")
    IMAGE,
}

@Serializable
enum class GlobalKey {
    BACK,
    HOME,
    RECENTS,
    NOTIFICATIONS,
    QUICK_SETTINGS,
    DISMISS_NOTIFICATION_SHADE,
}

@Serializable
data class ApiError(
    val code: ErrorCode,
    val message: String,
    val retryable: Boolean = false,
)

@Serializable
data class ErrorResponse(
    val error: ApiError,
)

@Serializable
data class HealthResponse(
    val status: String,
    val accessibilityConnected: Boolean,
    val version: String,
    val sessionActive: Boolean = false,
)

@Serializable
data class DeviceInfoResponse(
    val displayWidth: Int,
    val displayHeight: Int,
    val densityDpi: Int,
    val rotation: Int,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val serverVersion: String,
    val accessibilityConnected: Boolean,
    val foregroundPackage: String? = null,
    val screenOn: Boolean = true,
    val deviceLocked: Boolean = false,
    val sessionActive: Boolean = false,
)

@Serializable
data class ScreenshotResponse(
    val format: ImageFormat,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val scale: Double,
    val rotation: Int,
    val timestampMs: Long,
    val dataBase64: String,
    val marks: List<UiMark>? = null,
)

@Serializable
data class UiPoint(
    val x: Int,
    val y: Int,
)

@Serializable
data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int
        get() = (right - left).coerceAtLeast(0)

    val height: Int
        get() = (bottom - top).coerceAtLeast(0)

    fun center(): UiPoint = UiPoint(
        x = (left + right) / 2,
        y = (top + bottom) / 2,
    )

    /**
     * Clips these bounds to the `[0, displayWidth] x [0, displayHeight]` screen viewport.
     * Returns `null` if the bounds do not intersect the visible screen area.
     */
    fun intersect(displayWidth: Int, displayHeight: Int): UiBounds? {
        if (displayWidth <= 0 || displayHeight <= 0) return null
        val clippedLeft = left.coerceIn(0, displayWidth)
        val clippedTop = top.coerceIn(0, displayHeight)
        val clippedRight = right.coerceIn(0, displayWidth)
        val clippedBottom = bottom.coerceIn(0, displayHeight)
        if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) return null
        return UiBounds(
            left = clippedLeft,
            top = clippedTop,
            right = clippedRight,
            bottom = clippedBottom,
        )
    }
}

@Serializable
data class UiMark(
    val markId: Int,
    val nodeId: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val hintText: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val bounds: UiBounds,
    val center: UiPoint,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
)

@Serializable
data class MarksResponse(
    val count: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val timestampMs: Long,
    val marks: List<UiMark>,
)

@Serializable
data class UiNode(
    val nodeId: String,
    val packageName: String? = null,
    val className: String? = null,
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val hintText: String? = null,
    val bounds: UiBounds,
    val center: UiPoint,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val visibleToUser: Boolean = true,
    val children: List<UiNode> = emptyList(),
)

@Serializable
data class UiTreeResponse(
    val packageName: String? = null,
    val windowCount: Int,
    val nodeCount: Int,
    val truncated: Boolean = false,
    val timestampMs: Long,
    val roots: List<UiNode>,
)

@Serializable
data class ScreenStateResponse(
    val device: DeviceInfoResponse,
    val screenshot: ScreenshotResponse? = null,
    val tree: UiTreeResponse,
    val marks: List<UiMark>? = null,
    val timestampMs: Long,
)

@Serializable
data class TapRequest(
    val x: Double,
    val y: Double,
    val coordinateSpace: CoordinateSpace = CoordinateSpace.PIXEL,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val durationMs: Long = 50L,
)

@Serializable
data class TapElementRequest(
    val markId: Int? = null,
    val nodeId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val exact: Boolean = false,
    val index: Int = 0,
    val clickableOnly: Boolean = false,
    val longPress: Boolean = false,
    val durationMs: Long? = null,
)

@Serializable
data class LongPressRequest(
    val x: Double,
    val y: Double,
    val coordinateSpace: CoordinateSpace = CoordinateSpace.PIXEL,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val durationMs: Long = 600L,
)

@Serializable
data class SwipeRequest(
    val startX: Double,
    val startY: Double,
    val endX: Double,
    val endY: Double,
    val coordinateSpace: CoordinateSpace = CoordinateSpace.PIXEL,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val durationMs: Long = 300L,
)

@Serializable
data class KeyRequest(
    val key: GlobalKey,
)

@Serializable
data class ActionResponse(
    val ok: Boolean,
    val action: String,
    val resolvedPoint: UiPoint? = null,
    val resolvedEndPoint: UiPoint? = null,
    val matchedNode: UiNode? = null,
    val matchedMarkId: Int? = null,
    val durationMs: Long,
    val timestampMs: Long,
)

@Serializable
data class TypeTextRequest(
    val text: String,
    val clearFirst: Boolean = false,
    val submit: Boolean = false,
    val useClipboardFallback: Boolean = true,
)

@Serializable
data class ClearTextRequest(
    val useClipboardFallback: Boolean = false,
)

@Serializable
data class ImeActionRequest(
    val action: String = "enter",
)

@Serializable
data class TextActionResponse(
    val ok: Boolean,
    val action: String,
    val method: String? = null,
    val submitted: Boolean = false,
    val timestampMs: Long,
)

@Serializable
data class AppInfo(
    val packageName: String,
    val label: String,
)

@Serializable
data class AppsListResponse(
    val count: Int,
    val apps: List<AppInfo>,
)

@Serializable
data class LaunchAppRequest(
    val packageName: String? = null,
    val uri: String? = null,
)

@Serializable
data class LaunchAppResponse(
    val ok: Boolean,
    val packageName: String? = null,
    val uri: String? = null,
    val timestampMs: Long,
)

@Serializable
data class WaitRequest(
    val durationMs: Long? = null,
    val text: String? = null,
    val resourceId: String? = null,
    val packageName: String? = null,
    val waitForChange: Boolean = false,
    val waitForIdle: Boolean = false,
    val baselineHash: String? = null,
    val quietPeriodMs: Long = 150L,
    val includeMarks: Boolean = false,
    val failOnTimeout: Boolean = true,
    val gone: Boolean = false,
    val timeoutMs: Long = 10_000L,
    val pollIntervalMs: Long = 200L,
)

@Serializable
data class WaitResponse(
    val ok: Boolean,
    val matched: Boolean,
    val elapsedMs: Long,
    val matchedNode: UiNode? = null,
    val screenChanged: Boolean? = null,
    val foregroundPackage: String? = null,
    val screenHash: String? = null,
    val marks: List<UiMark>? = null,
    val timestampMs: Long,
)

@Serializable
data class StartSessionRequest(
    val label: String? = null,
    val keepAwake: Boolean = true,
    val wakeScreen: Boolean = true,
    val idleTimeoutMs: Long = 600_000L,
)

@Serializable
data class SessionStatusResponse(
    val active: Boolean,
    val sessionId: String? = null,
    val label: String? = null,
    val startedAtMs: Long? = null,
    val lastActivityMs: Long? = null,
    val idleTimeoutMs: Long? = null,
    val keepAwake: Boolean = false,
    val screenOn: Boolean = true,
    val deviceLocked: Boolean = false,
)

@Serializable
data class WakeResponse(
    val ok: Boolean,
    val screenOn: Boolean,
    val deviceLocked: Boolean,
    val timestampMs: Long,
)
