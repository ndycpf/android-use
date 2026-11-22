package com.androiduse.server.http

import com.androiduse.core.AndroidUseException
import com.androiduse.core.CaptureOptions
import com.androiduse.core.CaptureResult
import com.androiduse.core.DeviceController
import com.androiduse.protocol.DeviceInfoResponse
import com.androiduse.protocol.ErrorCode
import com.androiduse.protocol.ErrorResponse
import com.androiduse.protocol.HealthResponse
import com.androiduse.protocol.ImageFormat
import com.androiduse.protocol.ProtocolJson
import com.androiduse.protocol.ScreenshotResponse
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

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
    fun `GET v1 screen screenshot returns base64 JSON by default`() = testApplication {
        val rawBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val controller = FakeDeviceController(
            screenshotResult = CaptureResult(
                bytes = rawBytes,
                format = ImageFormat.JPEG,
                mimeType = "image/jpeg",
                width = 576,
                height = 1280,
                displayWidth = 1080,
                displayHeight = 2400,
                scale = 1280.0 / 2400.0,
                rotation = 0,
                timestampMs = 123_456L,
            ),
        )
        application {
            configureAndroidUseHttp(controller)
        }

        val response = client.get("/v1/screen/screenshot")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())

        val body = json.decodeFromString<ScreenshotResponse>(response.bodyAsText())
        assertEquals(ImageFormat.JPEG, body.format)
        assertEquals("image/jpeg", body.mimeType)
        assertEquals(576, body.width)
        assertEquals(1280, body.height)
        assertEquals(1080, body.displayWidth)
        assertEquals(2400, body.displayHeight)
        assertEquals(123_456L, body.timestampMs)
        assertArrayEquals(rawBytes, Base64.getDecoder().decode(body.dataBase64))
        assertEquals(
            CaptureOptions(
                format = ImageFormat.JPEG,
                quality = 80,
                maxDimension = 1280,
                allowCachedMs = 0L,
            ),
            controller.lastCaptureOptions,
        )
    }

    @Test
    fun `GET v1 screen screenshot supports binary encoding, png, and native maxDimension=0`() = testApplication {
        val rawBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val controller = FakeDeviceController(
            screenshotResult = CaptureResult(
                bytes = rawBytes,
                format = ImageFormat.PNG,
                mimeType = "image/png",
                width = 1080,
                height = 2400,
                displayWidth = 1080,
                displayHeight = 2400,
                scale = 1.0,
                rotation = 90,
                timestampMs = 999L,
            ),
        )
        application {
            configureAndroidUseHttp(controller)
        }

        val response = client.get(
            "/v1/screen/screenshot?encoding=binary&format=png&quality=95&maxDimension=0&allowCachedMs=250",
        )
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Image.PNG, response.contentType()?.withoutParameters())
        assertArrayEquals(rawBytes, response.bodyAsBytes())
        assertEquals("1080", response.headers["X-Image-Width"])
        assertEquals("2400", response.headers["X-Image-Height"])
        assertEquals("1080", response.headers["X-Display-Width"])
        assertEquals("2400", response.headers["X-Display-Height"])
        assertEquals("1.0", response.headers["X-Image-Scale"])
        assertEquals("90", response.headers["X-Display-Rotation"])
        assertEquals("999", response.headers["X-Capture-Timestamp-Ms"])

        val opts = controller.lastCaptureOptions!!
        assertEquals(ImageFormat.PNG, opts.format)
        assertEquals(95, opts.quality)
        assertNull(opts.maxDimension)
        assertEquals(250L, opts.allowCachedMs)
    }

    @Test
    fun `GET v1 screen screenshot rejects invalid query parameters with 400 INVALID_REQUEST`() = testApplication {
        application {
            configureAndroidUseHttp(FakeDeviceController())
        }

        val badFormat = client.get("/v1/screen/screenshot?format=webp")
        assertEquals(HttpStatusCode.BadRequest, badFormat.status)
        assertEquals(
            ErrorCode.INVALID_REQUEST,
            json.decodeFromString<ErrorResponse>(badFormat.bodyAsText()).error.code,
        )

        val badQuality = client.get("/v1/screen/screenshot?quality=150")
        assertEquals(HttpStatusCode.BadRequest, badQuality.status)
        assertEquals(
            ErrorCode.INVALID_REQUEST,
            json.decodeFromString<ErrorResponse>(badQuality.bodyAsText()).error.code,
        )

        val badEncoding = client.get("/v1/screen/screenshot?encoding=hex")
        assertEquals(HttpStatusCode.BadRequest, badEncoding.status)
        assertEquals(
            ErrorCode.INVALID_REQUEST,
            json.decodeFromString<ErrorResponse>(badEncoding.bodyAsText()).error.code,
        )

        val badMaxDim = client.get("/v1/screen/screenshot?maxDimension=10")
        assertEquals(HttpStatusCode.BadRequest, badMaxDim.status)
        assertEquals(
            ErrorCode.INVALID_REQUEST,
            json.decodeFromString<ErrorResponse>(badMaxDim.bodyAsText()).error.code,
        )
    }

    @Test
    fun `GET v1 screen screenshot maps rate-limit exception to 429`() = testApplication {
        val controller = FakeDeviceController(
            screenshotError = AndroidUseException.screenshotRateLimited(),
        )
        application {
            configureAndroidUseHttp(controller)
        }

        val response = client.get("/v1/screen/screenshot")
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        val body = json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals(ErrorCode.SCREENSHOT_RATE_LIMITED, body.error.code)
        assertTrue(body.error.retryable)
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
        private val screenshotResult: CaptureResult = CaptureResult(
            bytes = byteArrayOf(1, 2, 3),
            format = ImageFormat.JPEG,
            mimeType = "image/jpeg",
            width = 576,
            height = 1280,
            displayWidth = 1080,
            displayHeight = 2400,
            scale = 1280.0 / 2400.0,
            rotation = 0,
            timestampMs = 1L,
        ),
        private val screenshotError: Throwable? = null,
    ) : DeviceController {
        var lastCaptureOptions: CaptureOptions? = null

        override suspend fun getHealth(): HealthResponse = health

        override suspend fun getDeviceInfo(): DeviceInfoResponse {
            deviceInfoError?.let { throw it }
            return deviceInfo
        }

        override suspend fun captureScreenshot(options: CaptureOptions): CaptureResult {
            lastCaptureOptions = options
            screenshotError?.let { throw it }
            return screenshotResult
        }
    }
}
