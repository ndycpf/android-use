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
    INVALID_REQUEST,
    SCREENSHOT_FAILED,
    SCREENSHOT_RATE_LIMITED,
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
