package com.androiduse.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.androiduse.R
import com.androiduse.android.AndroidUseAccessibilityService
import com.androiduse.core.AccessibilityServiceRegistry
import com.androiduse.core.ServerStateRegistry
import com.androiduse.core.SessionManager
import com.androiduse.protocol.StartSessionRequest
import com.androiduse.server.http.ServerForegroundService
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        SessionManager.default.reset()
    }

    private fun grantNotificationPermission() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `displays disconnected then connected status when accessibility service attaches`() {
        grantNotificationPermission()
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
        grantNotificationPermission()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val button = activity.findViewById<Button>(R.id.openSettingsButton)

        button.performClick()

        val startedIntent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, startedIntent.action)
    }

    @Test
    fun `toggle server button updates state display and sends start and stop service intents`() {
        grantNotificationPermission()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val serverStatusText = activity.findViewById<TextView>(R.id.serverStatusValueText)
        val toggleButton = activity.findViewById<Button>(R.id.toggleServerButton)
        val permissionBanner = activity.findViewById<View>(R.id.notificationPermissionContainer)

        assertEquals(View.GONE, permissionBanner.visibility)
        assertEquals(activity.getString(R.string.server_status_stopped), serverStatusText.text.toString())
        assertEquals(activity.getString(R.string.start_server), toggleButton.text.toString())

        toggleButton.performClick()
        val startIntent = shadowOf(activity).nextStartedService
        assertEquals(ServerForegroundService.ACTION_START, startIntent.action)
        assertEquals("127.0.0.1", startIntent.getStringExtra(ServerForegroundService.EXTRA_HOST))
        assertNull(startIntent.getStringExtra(ServerForegroundService.EXTRA_AUTH_TOKEN))

        ServerStateRegistry.default.markRunning("127.0.0.1", 8765, authRequired = false)
        assertEquals(
            activity.getString(R.string.server_status_running, "127.0.0.1:8765"),
            serverStatusText.text.toString(),
        )
        assertEquals(activity.getString(R.string.stop_server), toggleButton.text.toString())

        toggleButton.performClick()
        val stopIntent = shadowOf(activity).nextStartedService
        assertEquals(ServerForegroundService.ACTION_STOP, stopIntent.action)
    }

    @Test
    fun `requests POST_NOTIFICATIONS when missing and starts server after permission result`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val permissionBanner = activity.findViewById<View>(R.id.notificationPermissionContainer)
        val toggleButton = activity.findViewById<Button>(R.id.toggleServerButton)

        assertEquals(View.VISIBLE, permissionBanner.visibility)

        toggleButton.performClick()
        val request = shadowOf(activity).lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions)
        assertNull(shadowOf(activity).nextStartedService)

        grantNotificationPermission()
        activity.onRequestPermissionsResult(
            request.requestCode,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )

        assertEquals(View.GONE, permissionBanner.visibility)
        val startIntent = shadowOf(activity).nextStartedService
        assertEquals(ServerForegroundService.ACTION_START, startIntent.action)
    }

    @Test
    fun `tailscale bind mode sends detected tailscale host and Bearer auth token`() {
        grantNotificationPermission()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.tailscaleIpDetector = { "100.64.10.20" }
        controller.setup()

        val tailscaleRadio = activity.findViewById<RadioButton>(R.id.bindModeTailscale)
        val authContainer = activity.findViewById<View>(R.id.authTokenContainer)
        val toggleButton = activity.findViewById<Button>(R.id.toggleServerButton)

        assertTrue(tailscaleRadio.text.toString().contains("100.64.10.20"))
        tailscaleRadio.isChecked = true
        assertEquals(View.VISIBLE, authContainer.visibility)

        toggleButton.performClick()
        val startIntent = shadowOf(activity).nextStartedService
        assertEquals(ServerForegroundService.ACTION_START, startIntent.action)
        assertEquals("100.64.10.20", startIntent.getStringExtra(ServerForegroundService.EXTRA_HOST))
        assertFalse(startIntent.getStringExtra(ServerForegroundService.EXTRA_AUTH_TOKEN).isNullOrBlank())
    }

    @Test
    fun `displays active session status and allows ending session from UI`() {
        grantNotificationPermission()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val sessionText = activity.findViewById<TextView>(R.id.sessionStatusValueText)
        val endButton = activity.findViewById<Button>(R.id.endSessionButton)

        assertEquals(activity.getString(R.string.session_status_inactive), sessionText.text.toString())
        assertFalse(endButton.isEnabled)

        SessionManager.default.startSession(StartSessionRequest(label = "my-agent", keepAwake = true))
        assertTrue(sessionText.text.toString().contains("my-agent"))
        assertTrue(endButton.isEnabled)

        endButton.performClick()
        assertNull(SessionManager.default.currentSession())
        assertFalse(endButton.isEnabled)
    }
}
