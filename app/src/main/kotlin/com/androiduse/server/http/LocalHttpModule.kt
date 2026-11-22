package com.androiduse.server.http

import com.androiduse.core.AndroidUseException
import com.androiduse.core.CaptureOptions
import com.androiduse.core.DeviceController
import com.androiduse.core.UiTreeOptions
import com.androiduse.protocol.ApiError
import com.androiduse.protocol.ErrorCode
import com.androiduse.protocol.ErrorResponse
import com.androiduse.protocol.ImageFormat
import com.androiduse.protocol.KeyRequest
import com.androiduse.protocol.LongPressRequest
import com.androiduse.protocol.ProtocolJson
import com.androiduse.protocol.SwipeRequest
import com.androiduse.protocol.TapRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import java.security.MessageDigest

fun Application.configureAndroidUseHttp(
    controller: DeviceController,
    authToken: String? = null,
) {
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
                        message = cause.cause?.message ?: cause.message ?: "Invalid request payload.",
                        retryable = false,
                    ),
                ),
            )
        }

        exception<SerializationException> { call, cause ->
            call.respond(
                status = HttpStatusCode.BadRequest,
                message = ErrorResponse(
                    error = ApiError(
                        code = ErrorCode.INVALID_REQUEST,
                        message = cause.message ?: "Malformed JSON payload.",
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

    if (!authToken.isNullOrEmpty()) {
        val expectedBytes = authToken.toByteArray(Charsets.UTF_8)
        val bearerAuthPlugin = createApplicationPlugin("AndroidUseBearerAuth") {
            onCall { call ->
                val header = call.request.header(HttpHeaders.Authorization)
                val provided = header
                    ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
                    ?.substring(BEARER_PREFIX.length)
                    ?.trim()
                val providedBytes = provided?.toByteArray(Charsets.UTF_8)
                if (providedBytes == null || !MessageDigest.isEqual(expectedBytes, providedBytes)) {
                    throw AndroidUseException.unauthorized()
                }
            }
        }
        install(bearerAuthPlugin)
    }

    routing {
        route("/v1") {
            get("/health") {
                call.respond(controller.getHealth())
            }

            get("/device/info") {
                call.respond(controller.getDeviceInfo())
            }

            post("/device/wake") {
                call.respond(controller.wakeDevice())
            }

            route("/session") {
                get {
                    call.respond(controller.getSession())
                }

                post("/start") {
                    val raw = call.receiveText().trim()
                    val request = if (raw.isEmpty()) {
                        com.androiduse.protocol.StartSessionRequest()
                    } else {
                        ProtocolJson.instance.decodeFromString<com.androiduse.protocol.StartSessionRequest>(raw)
                    }
                    call.respond(controller.startSession(request))
                }

                post("/end") {
                    call.respond(controller.endSession())
                }
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
                        result.marks?.let { marks ->
                            call.response.header("X-Mark-Count", marks.size.toString())
                        }
                        call.respondBytes(
                            bytes = result.bytes,
                            contentType = ContentType.parse(result.mimeType),
                            status = HttpStatusCode.OK,
                        )
                    }
                }
            }

            get("/screen/tree") {
                val treeOptions = parseUiTreeQuery(call.request.queryParameters)
                call.respond(controller.getUiTree(treeOptions))
            }

            get("/screen/marks") {
                val refresh = parseBooleanParam(call.request.queryParameters, "refresh", default = false)
                call.respond(controller.getMarks(refresh = refresh))
            }

            get("/screen/state") {
                val params = call.request.queryParameters
                val includeScreenshot = parseBooleanParam(params, "includeScreenshot", default = true)
                val (captureOptions, _) = parseScreenshotQuery(params)
                val treeOptions = parseUiTreeQuery(params)
                call.respond(
                    controller.getScreenState(
                        captureOptions = captureOptions,
                        treeOptions = treeOptions,
                        includeScreenshot = includeScreenshot,
                    ),
                )
            }

            route("/actions") {
                post("/tap") {
                    val request = call.receive<TapRequest>()
                    call.respond(controller.tap(request))
                }

                post("/tap-element") {
                    val request = call.receive<com.androiduse.protocol.TapElementRequest>()
                    call.respond(controller.tapElement(request))
                }

                post("/long-press") {
                    val request = call.receive<LongPressRequest>()
                    call.respond(controller.longPress(request))
                }

                post("/swipe") {
                    val request = call.receive<SwipeRequest>()
                    call.respond(controller.swipe(request))
                }

                post("/key") {
                    val request = call.receive<KeyRequest>()
                    call.respond(controller.pressKey(request))
                }

                post("/type") {
                    val request = call.receive<com.androiduse.protocol.TypeTextRequest>()
                    call.respond(controller.typeText(request))
                }

                post("/clear") {
                    val raw = call.receiveText().trim()
                    val request = if (raw.isEmpty()) {
                        com.androiduse.protocol.ClearTextRequest()
                    } else {
                        ProtocolJson.instance.decodeFromString<com.androiduse.protocol.ClearTextRequest>(raw)
                    }
                    call.respond(controller.clearText(request))
                }

                post("/ime") {
                    val raw = call.receiveText().trim()
                    val request = if (raw.isEmpty()) {
                        com.androiduse.protocol.ImeActionRequest()
                    } else {
                        ProtocolJson.instance.decodeFromString<com.androiduse.protocol.ImeActionRequest>(raw)
                    }
                    call.respond(controller.performImeAction(request))
                }
            }

            route("/apps") {
                get {
                    val query = call.request.queryParameters["q"]
                    call.respond(controller.listApps(query))
                }

                post("/launch") {
                    val request = call.receive<com.androiduse.protocol.LaunchAppRequest>()
                    call.respond(controller.launchApp(request))
                }
            }

            post("/wait") {
                val request = call.receive<com.androiduse.protocol.WaitRequest>()
                call.respond(controller.waitFor(request))
            }
        }
    }
}

private const val BEARER_PREFIX: String = "Bearer "

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

    val annotate = parseBooleanParam(params, "annotate", default = false)

    val options = CaptureOptions(
        format = format,
        quality = quality,
        maxDimension = maxDimension,
        allowCachedMs = allowCachedMs,
        annotate = annotate,
    ).validate()

    return options to encoding
}

private fun parseUiTreeQuery(params: Parameters): UiTreeOptions {
    val maxDepth = params["maxDepth"]?.let { raw ->
        raw.toIntOrNull()
            ?: throw AndroidUseException.invalidRequest("Invalid maxDepth '$raw'. Expected integer.")
    } ?: UiTreeOptions.DEFAULT_MAX_DEPTH

    val maxNodes = params["maxNodes"]?.let { raw ->
        raw.toIntOrNull()
            ?: throw AndroidUseException.invalidRequest("Invalid maxNodes '$raw'. Expected integer.")
    } ?: UiTreeOptions.DEFAULT_MAX_NODES

    val includeInvisible = parseBooleanParam(params, "includeInvisible", default = false)
    val pruneEmptyContainers = parseBooleanParam(params, "prune", default = true)
    val includeAllWindows = parseBooleanParam(params, "allWindows", default = false)

    return UiTreeOptions(
        maxDepth = maxDepth,
        maxNodes = maxNodes,
        includeInvisible = includeInvisible,
        pruneEmptyContainers = pruneEmptyContainers,
        includeAllWindows = includeAllWindows,
    ).validate()
}

private fun parseBooleanParam(params: Parameters, name: String, default: Boolean): Boolean {
    return when (val raw = params[name]?.lowercase()) {
        null, "" -> default
        "true", "1" -> true
        "false", "0" -> false
        else -> throw AndroidUseException.invalidRequest(
            "Invalid boolean for '$name': '$raw'. Expected 'true' or 'false'.",
        )
    }
}
