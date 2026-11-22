package com.androiduse.android

import androidx.test.core.app.ApplicationProvider
import com.androiduse.core.AccessibilityServiceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDeviceInfoProviderTest {

    @Test
    fun `reads display bounds, density, rotation, and SDK version from Application context`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val registry = AccessibilityServiceRegistry()
        val provider = AndroidDeviceInfoProvider(
            context = context,
            accessibilityRegistry = registry,
            serverVersion = "0.1.0",
        )

        val info = provider.getDeviceInfo()

        assertTrue(info.displayWidth > 0)
        assertTrue(info.displayHeight > 0)
        assertTrue(info.densityDpi > 0)
        assertTrue(info.rotation in setOf(0, 90, 180, 270))
        assertEquals(34, info.sdkInt)
        assertEquals("0.1.0", info.serverVersion)
        assertFalse(info.accessibilityConnected)
    }
}
