package com.androiduse.core

import com.androiduse.android.AndroidUseAccessibilityService
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
class AccessibilityServiceRegistryTest {

    @Test
    fun `attach and detach update connection state accurately`() {
        val registry = AccessibilityServiceRegistry()
        val serviceA = Robolectric.buildService(AndroidUseAccessibilityService::class.java).create().get()
        val serviceB = Robolectric.buildService(AndroidUseAccessibilityService::class.java).create().get()

        assertFalse(registry.isConnected.value)
        assertNull(registry.currentService())

        registry.attach(serviceA)
        assertTrue(registry.isConnected.value)
        assertSame(serviceA, registry.currentService())

        // Detaching a different instance must not disconnect the active instance.
        registry.detach(serviceB)
        assertTrue(registry.isConnected.value)
        assertSame(serviceA, registry.currentService())

        registry.detach(serviceA)
        assertFalse(registry.isConnected.value)
        assertNull(registry.currentService())
    }
}
