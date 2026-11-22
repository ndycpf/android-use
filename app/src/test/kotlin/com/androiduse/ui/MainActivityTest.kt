package com.androiduse.ui

import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import com.androiduse.R
import com.androiduse.android.AndroidUseAccessibilityService
import com.androiduse.core.AccessibilityServiceRegistry
import com.androiduse.core.ServerStateRegistry
import com.androiduse.server.http.ServerForegroundService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {

    @After
    fun tearDown() {
        AccessibilityServiceRegistry.default.clear()
        ServerStateRegistry.default.reset()
    }

    @Test
    fun `displays disconnected then connected status when accessibility service attaches`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val statusText = activity.findViewById<TextView>(R.id.statusValueText)

        assertEquals(activity.getString(R.string.status_disconnected), statusText.text.toString())

        val service = Robolectric.buildService(AndroidUseAccessibilityService::class.java).create().get()
        AccessibilityServiceRegistry.default.attach(service)

        assertEquals(activity.getString(R.string.status_connected), statusText.text.toString())

        AccessibilityServiceRegistry.default.detach(service)
        assertEquals(activity.getString(R.string.status_disconnected), statusText.text.toString())
    }

    @Test
    fun `clicking open settings button launches accessibility settings intent`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val button = activity.findViewById<Button>(R.id.openSettingsButton)

        button.performClick()

        val startedIntent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, startedIntent.action)
    }

    @Test
    fun `toggle server button updates state display and sends start and stop service intents`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val serverStatusText = activity.findViewById<TextView>(R.id.serverStatusValueText)
        val toggleButton = activity.findViewById<Button>(R.id.toggleServerButton)

        assertEquals(activity.getString(R.string.server_status_stopped), serverStatusText.text.toString())
        assertEquals(activity.getString(R.string.start_server), toggleButton.text.toString())

        toggleButton.performClick()
        val startIntent = shadowOf(activity).nextStartedService
        assertEquals(ServerForegroundService.ACTION_START, startIntent.action)

        ServerStateRegistry.default.markRunning("127.0.0.1", 8765)
        assertEquals(
            activity.getString(R.string.server_status_running, "127.0.0.1:8765"),
            serverStatusText.text.toString(),
        )
        assertEquals(activity.getString(R.string.stop_server), toggleButton.text.toString())

        toggleButton.performClick()
        val stopIntent = shadowOf(activity).nextStartedService
        assertEquals(ServerForegroundService.ACTION_STOP, stopIntent.action)
    }
}
