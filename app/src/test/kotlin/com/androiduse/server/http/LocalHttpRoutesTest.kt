package com.androiduse.server.http

import com.androiduse.core.AndroidUseException
import com.androiduse.core.CaptureOptions
import com.androiduse.core.CaptureResult
import com.androiduse.core.DeviceController
import com.androiduse.core.UiTreeOptions
import com.androiduse.protocol.ActionResponse
import com.androiduse.protocol.CoordinateSpace
import com.androiduse.protocol.DeviceInfoResponse
import com.androiduse.protocol.ErrorCode
import com.androiduse.protocol.ErrorResponse
import com.androiduse.protocol.GlobalKey
import com.androiduse.protocol.HealthResponse
import com.androiduse.protocol.ImageFormat
import com.androiduse.protocol.KeyRequest
import com.androiduse.protocol.LongPressRequest
import com.androiduse.protocol.ProtocolJson
import com.androiduse.protocol.ScreenStateResponse
import com.androiduse.protocol.ScreenshotResponse
import com.androiduse.protocol.SwipeRequest
import com.androiduse.protocol.TapRequest
import com.androiduse.protocol.UiBounds
import com.androiduse.protocol.UiNode
import com.androiduse.protocol.UiPoint
import com.androiduse.protocol.UiTreeResponse
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `GET v1 screen tree returns normalized UiTreeResponse and parses query options`() = testApplication {
        val controller = FakeDeviceController()
        application {
            configureAndroidUseHttp(controller)
        }

        val response = client.get(
            "/v1/screen/tree?maxDepth=12&maxNodes=150&includeInvisible=true&prune=false&allWindows=true",
        )
        assertEquals(HttpStatusCode.OK, response.status)

        val tree = json.decodeFromString<UiTreeResponse>(response.bodyAsText())
        assertEquals("com.example.home", tree.packageName)
        assertEquals(1, tree.nodeCount)
        assertEquals("n1", tree.roots.first().nodeId)
        assertEquals(
            UiTreeOptions(
                maxDepth = 12,
                maxNodes = 150,
                includeInvisible = true,
                pruneEmptyContainers = false,
                includeAllWindows = true,
            ),
            controller.lastTreeOptions,
        )
    }

    @Test
    fun `GET v1 screen tree rejects invalid maxDepth and boolean flags with 400`() = testApplication {
        application {
            configureAndroidUseHttp(FakeDeviceController())
        }

        val badDepth = client.get("/v1/screen/tree?maxDepth=0")
        assertEquals(HttpStatusCode.BadRequest, badDepth.status)
        assertEquals(
            ErrorCode.INVALID_REQUEST,
            json.decodeFromString<ErrorResponse>(badDepth.bodyAsText()).error.code,
        )

        val badBool = client.get("/v1/screen/tree?prune=maybe")
        assertEquals(HttpStatusCode.BadRequest, badBool.status)
        assertEquals(
            ErrorCode.INVALID_REQUEST,
            json.decodeFromString<ErrorResponse>(badBool.bodyAsText()).error.code,
        )
    }

    @Test
    fun `GET v1 screen state returns combined device, tree, and optional screenshot`() = testApplication {
        val controller = FakeDeviceController()
        application {
            configureAndroidUseHttp(controller)
        }

        val withShot = client.get("/v1/screen/state?format=png&maxNodes=80")
        assertEquals(HttpStatusCode.OK, withShot.status)
        val state1 = json.decodeFromString<ScreenStateResponse>(withShot.bodyAsText())
        assertNotNull(state1.screenshot)
        assertEquals(ImageFormat.PNG, controller.lastCaptureOptions?.format)
        assertEquals(80, controller.lastTreeOptions?.maxNodes)
        assertTrue(controller.lastIncludeScreenshot == true)

        val withoutShot = client.get("/v1/screen/state?includeScreenshot=false")
        assertEquals(HttpStatusCode.OK, withoutShot.status)
        val state2 = json.decodeFromString<ScreenStateResponse>(withoutShot.bodyAsText())
        assertNull(state2.screenshot)
        assertFalse(controller.lastIncludeScreenshot!!)
    }

    @Test
    fun `POST v1 actions endpoints deserialize requests and return ActionResponse`() = testApplication {
        val controller = FakeDeviceController()
        application {
            configureAndroidUseHttp(controller)
        }

        val tapRes = client.post("/v1/actions/tap") {
            contentType(ContentType.Application.Json)
            setBody("""{"x":897,"y":2365}""")
        }
        assertEquals(HttpStatusCode.OK, tapRes.status)
        assertEquals("tap", json.decodeFromString<ActionResponse>(tapRes.bodyAsText()).action)
        assertEquals(TapRequest(x = 897.0, y = 2365.0), controller.lastTapRequest)

        val longRes = client.post("/v1/actions/long-press") {
            contentType(ContentType.Application.Json)
            setBody("""{"x":0.5,"y":0.5,"coordinateSpace":"normalized","durationMs":750}""")
        }
        assertEquals(HttpStatusCode.OK, longRes.status)
        assertEquals("long_press", json.decodeFromString<ActionResponse>(longRes.bodyAsText()).action)
        assertEquals(
            LongPressRequest(x = 0.5, y = 0.5, coordinateSpace = CoordinateSpace.NORMALIZED, durationMs = 750L),
            controller.lastLongPressRequest,
        )

        val swipeRes = client.post("/v1/actions/swipe") {
            contentType(ContentType.Application.Json)
            setBody("""{"startX":540,"startY":1800,"endX":540,"endY":600,"durationMs":300}""")
        }
        assertEquals(HttpStatusCode.OK, swipeRes.status)
        assertEquals("swipe", json.decodeFromString<ActionResponse>(swipeRes.bodyAsText()).action)
        assertEquals(
            SwipeRequest(startX = 540.0, startY = 1800.0, endX = 540.0, endY = 600.0, durationMs = 300L),
            controller.lastSwipeRequest,
        )

        val keyRes = client.post("/v1/actions/key") {
            contentType(ContentType.Application.Json)
            setBody("""{"key":"HOME"}""")
        }
        assertEquals(HttpStatusCode.OK, keyRes.status)
        assertEquals("key", json.decodeFromString<ActionResponse>(keyRes.bodyAsText()).action)
        assertEquals(KeyRequest(GlobalKey.HOME), controller.lastKeyRequest)
    }

    @Test
    fun `POST v1 actions rejects malformed JSON or invalid enum with 400 INVALID_REQUEST`() = testApplication {
        application {
            configureAndroidUseHttp(FakeDeviceController())
        }

        val badTap = client.post("/v1/actions/tap") {
            contentType(ContentType.Application.Json)
            setBody("""{"x":"not-a-number"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badTap.status)
        assertEquals(
            ErrorCode.INVALID_REQUEST,
            json.decodeFromString<ErrorResponse>(badTap.bodyAsText()).error.code,
        )

        val badKey = client.post("/v1/actions/key") {
            contentType(ContentType.Application.Json)
            setBody("""{"key":"NON_EXISTENT"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badKey.status)
        assertEquals(
            ErrorCode.INVALID_REQUEST,
            json.decodeFromString<ErrorResponse>(badKey.bodyAsText()).error.code,
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

    @Test
    fun `Bearer auth rejects missing or invalid token with 401 and allows valid token`() = testApplication {
        application {
            configureAndroidUseHttp(
                controller = FakeDeviceController(),
                authToken = "secret-token-123",
            )
        }

        val missing = client.get("/v1/health")
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals(
            ErrorCode.UNAUTHORIZED,
            json.decodeFromString<ErrorResponse>(missing.bodyAsText()).error.code,
        )

        val wrong = client.get("/v1/health") {
            headers.append("Authorization", "Bearer wrong-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)

        val valid = client.get("/v1/health") {
            headers.append("Authorization", "Bearer secret-token-123")
        }
        assertEquals(HttpStatusCode.OK, valid.status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `LocalHttpServer refuses non-loopback bind without Bearer authToken`() {
        LocalHttpServer(
            controller = FakeDeviceController(),
            host = "100.64.1.2",
            port = 8765,
            authToken = null,
        )
    }

    @Test
    fun `POST v1 actions type, clear, and ime handle payloads, empty bodies, and 409 NO_FOCUSED_INPUT`() = testApplication {
        val controller = FakeDeviceController()
        application {
            configureAndroidUseHttp(controller)
        }

        val typeRes = client.post("/v1/actions/type") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"hello world","clearFirst":true,"submit":true}""")
        }
        assertEquals(HttpStatusCode.OK, typeRes.status)
        val typeBody = json.decodeFromString<com.androiduse.protocol.TextActionResponse>(typeRes.bodyAsText())
        assertEquals("type", typeBody.action)
        assertEquals("set_text", typeBody.method)
        assertTrue(typeBody.submitted)
        assertEquals("hello world", controller.lastTypeRequest?.text)
        assertTrue(controller.lastTypeRequest?.clearFirst == true)

        val clearRes = client.post("/v1/actions/clear")
        assertEquals(HttpStatusCode.OK, clearRes.status)
        assertEquals(
            "clear",
            json.decodeFromString<com.androiduse.protocol.TextActionResponse>(clearRes.bodyAsText()).action,
        )

        val imeRes = client.post("/v1/actions/ime")
        assertEquals(HttpStatusCode.OK, imeRes.status)
        assertEquals("enter", controller.lastImeRequest?.action)

        val noFocusController = FakeDeviceController(
            typeError = AndroidUseException.noFocusedInput(),
        )
        testApplication {
            application {
                configureAndroidUseHttp(noFocusController)
            }
            val res = client.post("/v1/actions/type") {
                contentType(ContentType.Application.Json)
                setBody("""{"text":"abc"}""")
            }
            assertEquals(HttpStatusCode.Conflict, res.status)
            assertEquals(
                ErrorCode.NO_FOCUSED_INPUT,
                json.decodeFromString<ErrorResponse>(res.bodyAsText()).error.code,
            )
        }
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
        private val typeError: Throwable? = null,
        private val uiTreeResponse: UiTreeResponse = UiTreeResponse(
            packageName = "com.example.home",
            windowCount = 1,
            nodeCount = 1,
            truncated = false,
            timestampMs = 2L,
            roots = listOf(
                UiNode(
                    nodeId = "n1",
                    text = "Search",
                    bounds = UiBounds(10, 20, 210, 120),
                    center = UiBounds(10, 20, 210, 120).center(),
                    clickable = true,
                ),
            ),
        ),
    ) : DeviceController {
        var lastCaptureOptions: CaptureOptions? = null
        var lastTreeOptions: UiTreeOptions? = null
        var lastIncludeScreenshot: Boolean? = null
        var lastTapRequest: TapRequest? = null
        var lastLongPressRequest: LongPressRequest? = null
        var lastSwipeRequest: SwipeRequest? = null
        var lastKeyRequest: KeyRequest? = null
        var lastTypeRequest: com.androiduse.protocol.TypeTextRequest? = null
        var lastImeRequest: com.androiduse.protocol.ImeActionRequest? = null

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

        override suspend fun getUiTree(options: UiTreeOptions): UiTreeResponse {
            lastTreeOptions = options
            return uiTreeResponse
        }

        override suspend fun getScreenState(
            captureOptions: CaptureOptions,
            treeOptions: UiTreeOptions,
            includeScreenshot: Boolean,
        ): ScreenStateResponse {
            lastCaptureOptions = captureOptions
            lastTreeOptions = treeOptions
            lastIncludeScreenshot = includeScreenshot
            return ScreenStateResponse(
                device = deviceInfo,
                screenshot = if (includeScreenshot) screenshotResult.toBase64Response() else null,
                tree = uiTreeResponse,
                timestampMs = 3L,
            )
        }

        override suspend fun tap(request: TapRequest): ActionResponse {
            lastTapRequest = request
            return ActionResponse(
                ok = true,
                action = "tap",
                resolvedPoint = UiPoint(request.x.toInt(), request.y.toInt()),
                durationMs = request.durationMs,
                timestampMs = 4L,
            )
        }

        override suspend fun longPress(request: LongPressRequest): ActionResponse {
            lastLongPressRequest = request
            return ActionResponse(
                ok = true,
                action = "long_press",
                resolvedPoint = UiPoint(request.x.toInt(), request.y.toInt()),
                durationMs = request.durationMs,
                timestampMs = 5L,
            )
        }

        override suspend fun swipe(request: SwipeRequest): ActionResponse {
            lastSwipeRequest = request
            return ActionResponse(
                ok = true,
                action = "swipe",
                resolvedPoint = UiPoint(request.startX.toInt(), request.startY.toInt()),
                resolvedEndPoint = UiPoint(request.endX.toInt(), request.endY.toInt()),
                durationMs = request.durationMs,
                timestampMs = 6L,
            )
        }

        override suspend fun pressKey(request: KeyRequest): ActionResponse {
            lastKeyRequest = request
            return ActionResponse(
                ok = true,
                action = "key",
                durationMs = 0L,
                timestampMs = 7L,
            )
        }

        override suspend fun typeText(
            request: com.androiduse.protocol.TypeTextRequest,
        ): com.androiduse.protocol.TextActionResponse {
            lastTypeRequest = request
            typeError?.let { throw it }
            return com.androiduse.protocol.TextActionResponse(
                ok = true,
                action = "type",
                method = "set_text",
                submitted = request.submit,
                timestampMs = 8L,
            )
        }

        override suspend fun clearText(
            request: com.androiduse.protocol.ClearTextRequest,
        ): com.androiduse.protocol.TextActionResponse {
            return com.androiduse.protocol.TextActionResponse(
                ok = true,
                action = "clear",
                method = "set_text",
                submitted = false,
                timestampMs = 9L,
            )
        }

        override suspend fun performImeAction(
            request: com.androiduse.protocol.ImeActionRequest,
        ): com.androiduse.protocol.TextActionResponse {
            lastImeRequest = request
            return com.androiduse.protocol.TextActionResponse(
                ok = true,
                action = "ime",
                method = "ime_enter",
                submitted = true,
                timestampMs = 10L,
            )
        }

        override suspend fun listApps(query: String?): com.androiduse.protocol.AppsListResponse {
            return com.androiduse.protocol.AppsListResponse(
                count = 1,
                apps = listOf(com.androiduse.protocol.AppInfo("com.google.android.gm", "Gmail")),
            )
        }

        override suspend fun launchApp(
            request: com.androiduse.protocol.LaunchAppRequest,
        ): com.androiduse.protocol.LaunchAppResponse {
            return com.androiduse.protocol.LaunchAppResponse(
                ok = true,
                packageName = request.packageName,
                uri = request.uri,
                timestampMs = 11L,
            )
        }

        override suspend fun waitFor(
            request: com.androiduse.protocol.WaitRequest,
        ): com.androiduse.protocol.WaitResponse {
            return com.androiduse.protocol.WaitResponse(
                ok = true,
                matched = true,
                elapsedMs = 120L,
                matchedNode = uiTreeResponse.roots.firstOrNull(),
                timestampMs = 12L,
            )
        }
    }

    @Test
    fun `GET v1 apps, POST v1 apps launch, and POST v1 wait return expected JSON`() = testApplication {
        application {
            configureAndroidUseHttp(FakeDeviceController())
        }

        val appsRes = client.get("/v1/apps?q=gmail")
        assertEquals(HttpStatusCode.OK, appsRes.status)
        val appsBody = json.decodeFromString<com.androiduse.protocol.AppsListResponse>(appsRes.bodyAsText())
        assertEquals(1, appsBody.count)
        assertEquals("com.google.android.gm", appsBody.apps.first().packageName)

        val launchRes = client.post("/v1/apps/launch") {
            contentType(ContentType.Application.Json)
            setBody("""{"packageName":"com.google.android.gm"}""")
        }
        assertEquals(HttpStatusCode.OK, launchRes.status)
        assertTrue(json.decodeFromString<com.androiduse.protocol.LaunchAppResponse>(launchRes.bodyAsText()).ok)

        val waitRes = client.post("/v1/wait") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"Search","timeoutMs":2000}""")
        }
        assertEquals(HttpStatusCode.OK, waitRes.status)
        val waitBody = json.decodeFromString<com.androiduse.protocol.WaitResponse>(waitRes.bodyAsText())
        assertTrue(waitBody.matched)
        assertEquals("n1", waitBody.matchedNode?.nodeId)
    }
}
