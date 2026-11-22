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
    UNAUTHORIZED,
    INVALID_REQUEST,
    SCREENSHOT_FAILED,
    SCREENSHOT_RATE_LIMITED,
    NO_FOCUSED_INPUT,
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
}

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
