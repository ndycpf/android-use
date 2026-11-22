package com.androiduse.android

import android.content.Intent
import com.androiduse.core.AccessibilityServiceRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidUseAccessibilityServiceTest {

    @After
    fun tearDown() {
        AccessibilityServiceRegistry.default.clear()
    }

    @Test
    fun `service lifecycle attaches on connect and detaches on unbind`() {
        val registry = AccessibilityServiceRegistry()
        val controller = Robolectric.buildService(AndroidUseAccessibilityService::class.java).create()
        val service = controller.get()
        service.registry = registry

        val onServiceConnected = AndroidUseAccessibilityService::class.java
            .getDeclaredMethod("onServiceConnected")
        onServiceConnected.isAccessible = true
        onServiceConnected.invoke(service)

        assertTrue(registry.isConnected.value)
        assertSame(service, registry.currentService())

        service.onUnbind(Intent())

        assertFalse(registry.isConnected.value)
        assertNull(registry.currentService())
    }
}
