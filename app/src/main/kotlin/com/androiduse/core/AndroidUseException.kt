package com.androiduse.core

import com.androiduse.protocol.ErrorCode

/**
 * Domain exception carrying a structured [ErrorCode] and suggested HTTP status code
 * so transport adapters (HTTP, MCP, in-app agent) report consistent errors.
 */
class AndroidUseException(
    val code: ErrorCode,
    override val message: String,
    val httpStatus: Int = 500,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    companion object {
        fun accessibilityNotConnected(): AndroidUseException = AndroidUseException(
            code = ErrorCode.ACCESSIBILITY_NOT_CONNECTED,
            message = "AndroidUseAccessibilityService is not enabled or not connected.",
            httpStatus = 503,
            retryable = true,
        )

        fun invalidRequest(message: String): AndroidUseException = AndroidUseException(
            code = ErrorCode.INVALID_REQUEST,
            message = message,
            httpStatus = 400,
            retryable = false,
        )

        fun unauthorized(
            message: String = "Missing or invalid Bearer token.",
        ): AndroidUseException = AndroidUseException(
            code = ErrorCode.UNAUTHORIZED,
            message = message,
            httpStatus = 401,
            retryable = false,
        )

        fun screenshotRateLimited(
            message: String = "Screen capture rate limit exceeded; retry shortly.",
        ): AndroidUseException = AndroidUseException(
            code = ErrorCode.SCREENSHOT_RATE_LIMITED,
            message = message,
            httpStatus = 429,
            retryable = true,
        )

        fun screenshotFailed(
            message: String,
            retryable: Boolean = false,
            cause: Throwable? = null,
        ): AndroidUseException = AndroidUseException(
            code = ErrorCode.SCREENSHOT_FAILED,
            message = message,
            httpStatus = 500,
            retryable = retryable,
            cause = cause,
        )

        fun actionFailed(
            message: String,
            retryable: Boolean = false,
            cause: Throwable? = null,
        ): AndroidUseException = AndroidUseException(
            code = ErrorCode.ACTION_FAILED,
            message = message,
            httpStatus = 500,
            retryable = retryable,
            cause = cause,
        )

        fun noFocusedInput(
            message: String = "No focused editable input field found. Tap an input field first.",
        ): AndroidUseException = AndroidUseException(
            code = ErrorCode.NO_FOCUSED_INPUT,
            message = message,
            httpStatus = 409,
            retryable = true,
        )
    }
}
