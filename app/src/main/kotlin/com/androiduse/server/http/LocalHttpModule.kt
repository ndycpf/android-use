package com.androiduse.server.http

import com.androiduse.core.AndroidUseException
import com.androiduse.core.CaptureOptions
import com.androiduse.core.DeviceController
import com.androiduse.protocol.ApiError
import com.androiduse.protocol.ErrorCode
import com.androiduse.protocol.ErrorResponse
import com.androiduse.protocol.ImageFormat
import com.androiduse.protocol.ProtocolJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureAndroidUseHttp(controller: DeviceController) {
    install(ContentNegotiation) {
        json(ProtocolJson.instance)
    }

    install(StatusPages) {
        exception<AndroidUseException> { call, cause ->
            call.respond(
                status = HttpStatusCode.fromValue(cause.httpStatus),
                message = ErrorResponse(
                    error = ApiError(
                        code = cause.code,
                        message = cause.message,
                        retryable = cause.retryable,
                    ),
                ),
            )
        }

        exception<BadRequestException> { call, cause ->
            call.respond(
                status = HttpStatusCode.BadRequest,
                message = ErrorResponse(
                    error = ApiError(
                        code = ErrorCode.INVALID_REQUEST,
                        message = cause.message ?: "Invalid request payload.",
                        retryable = false,
                    ),
                ),
            )
        }

        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                status = HttpStatusCode.BadRequest,
                message = ErrorResponse(
                    error = ApiError(
                        code = ErrorCode.INVALID_REQUEST,
                        message = cause.message ?: "Invalid argument.",
                        retryable = false,
                    ),
                ),
            )
        }

        exception<Throwable> { call, cause ->
            call.respond(
                status = HttpStatusCode.InternalServerError,
                message = ErrorResponse(
                    error = ApiError(
                        code = ErrorCode.INTERNAL_ERROR,
                        message = cause.message ?: "Internal server error.",
                        retryable = false,
                    ),
                ),
            )
        }
    }

    routing {
        route("/v1") {
            get("/health") {
                call.respond(controller.getHealth())
            }

            get("/device/info") {
                call.respond(controller.getDeviceInfo())
            }

            get("/screen/screenshot") {
                val (options, encoding) = parseScreenshotQuery(call.request.queryParameters)
                val result = controller.captureScreenshot(options)
                when (encoding) {
                    ScreenshotEncoding.BASE64 -> {
                        call.respond(result.toBase64Response())
                    }

                    ScreenshotEncoding.BINARY -> {
                        call.response.header("X-Image-Width", result.width.toString())
                        call.response.header("X-Image-Height", result.height.toString())
                        call.response.header("X-Display-Width", result.displayWidth.toString())
                        call.response.header("X-Display-Height", result.displayHeight.toString())
                        call.response.header("X-Image-Scale", result.scale.toString())
                        call.response.header("X-Display-Rotation", result.rotation.toString())
                        call.response.header("X-Capture-Timestamp-Ms", result.timestampMs.toString())
                        call.respondBytes(
                            bytes = result.bytes,
                            contentType = ContentType.parse(result.mimeType),
                            status = HttpStatusCode.OK,
                        )
                    }
                }
            }
        }
    }
}

private enum class ScreenshotEncoding {
    BASE64,
    BINARY,
}

private fun parseScreenshotQuery(params: Parameters): Pair<CaptureOptions, ScreenshotEncoding> {
    val format = when (val raw = params["format"]?.lowercase()) {
        null, "", "jpeg", "jpg" -> ImageFormat.JPEG
        "png" -> ImageFormat.PNG
        else -> throw AndroidUseException.invalidRequest(
            "Unsupported format '$raw'. Expected 'jpeg' or 'png'.",
        )
    }

    val quality = params["quality"]?.let { raw ->
        raw.toIntOrNull()
            ?: throw AndroidUseException.invalidRequest("Invalid quality '$raw'. Expected integer 1..100.")
    } ?: CaptureOptions.DEFAULT_QUALITY

    val maxDimension = when (val raw = params["maxDimension"]?.lowercase()) {
        null, "" -> CaptureOptions.DEFAULT_MAX_DIMENSION
        "0", "none", "native" -> null
        else -> raw.toIntOrNull()
            ?: throw AndroidUseException.invalidRequest(
                "Invalid maxDimension '$raw'. Expected integer >= ${CaptureOptions.MIN_DIMENSION} or 0/none.",
            )
    }

    val allowCachedMs = params["allowCachedMs"]?.let { raw ->
        raw.toLongOrNull()
            ?: throw AndroidUseException.invalidRequest(
                "Invalid allowCachedMs '$raw'. Expected non-negative integer.",
            )
    } ?: 0L

    val encoding = when (val raw = params["encoding"]?.lowercase()) {
        null, "", "base64" -> ScreenshotEncoding.BASE64
        "binary" -> ScreenshotEncoding.BINARY
        else -> throw AndroidUseException.invalidRequest(
            "Unsupported encoding '$raw'. Expected 'base64' or 'binary'.",
        )
    }

    val options = CaptureOptions(
        format = format,
        quality = quality,
        maxDimension = maxDimension,
        allowCachedMs = allowCachedMs,
    ).validate()

    return options to encoding
}
