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
    }
}
