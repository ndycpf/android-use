package com.androiduse.server.http

import com.androiduse.core.AndroidUseException
import com.androiduse.core.DeviceController
import com.androiduse.protocol.DeviceInfoResponse
import com.androiduse.protocol.ErrorCode
import com.androiduse.protocol.ErrorResponse
import com.androiduse.protocol.HealthResponse
import com.androiduse.protocol.ProtocolJson
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHttpRoutesTest {

    private val json = ProtocolJson.instance

    @Test
    fun `GET v1 health returns JSON health status`() = testApplication {
        val controller = FakeDeviceController(
            health = HealthResponse(
                status = "ok",
                accessibilityConnected = true,
                version = "0.1.0",
            ),
        )
        application {
            configureAndroidUseHttp(controller)
        }

        val response = client.get("/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())

        val body = json.decodeFromString<HealthResponse>(response.bodyAsText())
        assertEquals("ok", body.status)
        assertTrue(body.accessibilityConnected)
        assertEquals("0.1.0", body.version)
    }

    @Test
    fun `GET v1 device info returns JSON device metadata`() = testApplication {
        val expected = DeviceInfoResponse(
            displayWidth = 1080,
            displayHeight = 2400,
            densityDpi = 420,
            rotation = 90,
            sdkInt = 35,
            manufacturer = "Google",
            model = "Pixel 9",
            serverVersion = "0.1.0",
            accessibilityConnected = true,
            foregroundPackage = "com.android.settings",
        )
        val controller = FakeDeviceController(deviceInfo = expected)
        application {
            configureAndroidUseHttp(controller)
        }

        val response = client.get("/v1/device/info")
        assertEquals(HttpStatusCode.OK, response.status)

        val actual = json.decodeFromString<DeviceInfoResponse>(response.bodyAsText())
        assertEquals(expected, actual)
    }

    @Test
    fun `AndroidUseException maps to structured ErrorResponse with matching status`() = testApplication {
        val controller = FakeDeviceController(
            deviceInfoError = AndroidUseException.accessibilityNotConnected(),
        )
        application {
            configureAndroidUseHttp(controller)
        }

        val response = client.get("/v1/device/info")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)

        val body = json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals(ErrorCode.ACCESSIBILITY_NOT_CONNECTED, body.error.code)
        assertTrue(body.error.retryable)
    }

    @Test
    fun `IllegalArgumentException maps to 400 INVALID_REQUEST`() = testApplication {
        val controller = FakeDeviceController(
            deviceInfoError = IllegalArgumentException("bad parameter"),
        )
        application {
            configureAndroidUseHttp(controller)
        }

        val response = client.get("/v1/device/info")
        assertEquals(HttpStatusCode.BadRequest, response.status)

        val body = json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals(ErrorCode.INVALID_REQUEST, body.error.code)
        assertEquals("bad parameter", body.error.message)
    }

    @Test
    fun `unexpected runtime exception maps to 500 INTERNAL_ERROR`() = testApplication {
        val controller = FakeDeviceController(
            deviceInfoError = IllegalStateException("boom"),
        )
        application {
            configureAndroidUseHttp(controller)
        }

        val response = client.get("/v1/device/info")
        assertEquals(HttpStatusCode.InternalServerError, response.status)

        val body = json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals(ErrorCode.INTERNAL_ERROR, body.error.code)
        assertEquals("boom", body.error.message)
    }

    private class FakeDeviceController(
        private val health: HealthResponse = HealthResponse("ok", false, "0.1.0"),
        private val deviceInfo: DeviceInfoResponse = DeviceInfoResponse(
            displayWidth = 1080,
            displayHeight = 2400,
            densityDpi = 420,
            rotation = 0,
            sdkInt = 35,
            manufacturer = "Test",
            model = "Device",
            serverVersion = "0.1.0",
            accessibilityConnected = false,
        ),
        private val deviceInfoError: Throwable? = null,
    ) : DeviceController {
        override suspend fun getHealth(): HealthResponse = health

        override suspend fun getDeviceInfo(): DeviceInfoResponse {
            deviceInfoError?.let { throw it }
            return deviceInfo
        }
    }
}
