package com.androiduse.server.http

import android.app.Notification
import android.app.NotificationManager
import com.androiduse.R
import com.androiduse.core.ActiveSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class ServerForegroundServiceTest {

    @Test
    fun `ensureNotificationChannel creates silent default-importance channel`() {
        val service = Robolectric.buildService(ServerForegroundService::class.java).create().get()
        service.ensureNotificationChannel()

        val manager = service.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(ServerForegroundService.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
    }

    @Test
    fun `buildNotification includes ongoing Stop Server action and swipe-restore deleteIntent`() {
        val service = Robolectric.buildService(ServerForegroundService::class.java).create().get()
        val notification = service.buildNotification("100.64.1.2", 8765, session = null)

        assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertEquals(R.drawable.ic_notification, notification.smallIcon.resId)
        assertEquals(
            service.getString(R.string.server_notification_title),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            service.getString(R.string.server_notification_text, "100.64.1.2:8765"),
            notification.extras.getString(Notification.EXTRA_TEXT),
        )

        val actions = notification.actions
        assertEquals(1, actions.size)
        assertEquals(service.getString(R.string.stop_server), actions[0].title.toString())
        assertEquals(
            ServerForegroundService.ACTION_STOP,
            shadowOf(actions[0].actionIntent).savedIntent.action,
        )

        assertNotNull(notification.deleteIntent)
        assertEquals(
            ServerForegroundService.ACTION_RESTORE_NOTIFICATION,
            shadowOf(notification.deleteIntent).savedIntent.action,
        )
    }

    @Test
    fun `buildNotification shows End Session and Stop Server actions when session is active`() {
        val service = Robolectric.buildService(ServerForegroundService::class.java).create().get()
        val session = ActiveSession(
            sessionId = "s-1",
            label = "codex-task",
            startedAtMs = 1_000L,
            lastActivityMs = 1_000L,
            idleTimeoutMs = 600_000L,
            keepAwake = true,
        )

        val notification = service.buildNotification("100.64.1.2", 8765, session = session)
        assertEquals(
            service.getString(R.string.server_notification_session_title),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertTrue(
            notification.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains("codex-task"),
        )

        val actions = notification.actions
        assertEquals(2, actions.size)
        assertEquals(service.getString(R.string.end_session), actions[0].title.toString())
        assertEquals(
            ServerForegroundService.ACTION_END_SESSION,
            shadowOf(actions[0].actionIntent).savedIntent.action,
        )
        assertEquals(service.getString(R.string.stop_server), actions[1].title.toString())
        assertEquals(
            ServerForegroundService.ACTION_STOP,
            shadowOf(actions[1].actionIntent).savedIntent.action,
        )
    }
}
