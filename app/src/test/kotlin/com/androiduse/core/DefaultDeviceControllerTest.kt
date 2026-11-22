package com.androiduse.core

import com.androiduse.protocol.DeviceInfoResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DefaultDeviceControllerTest {

    @Test
    fun `getHealth reflects registry state and server version`() = runTest {
        val registry = AccessibilityServiceRegistry()
        val fakeInfo = DeviceInfoResponse(
            displayWidth = 1080,
            displayHeight = 2400,
            densityDpi = 420,
            rotation = 0,
            sdkInt = 35,
            manufacturer = "Google",
            model = "Pixel",
            serverVersion = "0.1.0",
            accessibilityConnected = false,
        )
        val controller = DefaultDeviceController(
            accessibilityRegistry = registry,
            deviceInfoProvider = object : DeviceInfoProvider {
                override fun getDeviceInfo(): DeviceInfoResponse = fakeInfo
            },
            serverVersion = "0.1.0",
        )

        val health = controller.getHealth()
        assertEquals("ok", health.status)
        assertFalse(health.accessibilityConnected)
        assertEquals("0.1.0", health.version)
        assertEquals(fakeInfo, controller.getDeviceInfo())
    }

    @Test
    fun `withActionLock serializes overlapping actions without interleaving`() = runTest {
        val controller = DefaultDeviceController(
            accessibilityRegistry = AccessibilityServiceRegistry(),
            deviceInfoProvider = object : DeviceInfoProvider {
                override fun getDeviceInfo(): DeviceInfoResponse {
                    error("not used")
                }
            },
            serverVersion = "0.1.0",
        )
        val events = mutableListOf<String>()

        val first = async {
            controller.withActionLock {
                events += "first-start"
                delay(50)
                events += "first-end"
            }
        }
        val second = async {
            delay(10)
            controller.withActionLock {
                events += "second-start"
                events += "second-end"
            }
        }

        first.await()
        second.await()

        assertEquals(
            listOf("first-start", "first-end", "second-start", "second-end"),
            events,
        )
    }
}
