package com.androiduse.server.http

import com.androiduse.core.AndroidUseException
import com.androiduse.core.DeviceController
import com.androiduse.protocol.ApiError
import com.androiduse.protocol.ErrorCode
import com.androiduse.protocol.ErrorResponse
import com.androiduse.protocol.ProtocolJson
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
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
        }
    }
}
