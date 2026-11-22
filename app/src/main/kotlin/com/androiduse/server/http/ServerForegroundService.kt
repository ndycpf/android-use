package com.androiduse.server.http

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.androiduse.R
import com.androiduse.android.AccessibilityScreenCapturer
import com.androiduse.android.AndroidDeviceInfoProvider
import com.androiduse.android.ServerPreferences
import com.androiduse.core.AccessibilityServiceRegistry
import com.androiduse.core.DefaultDeviceController
import com.androiduse.core.NetworkAddressHelper
import com.androiduse.core.ResilientScreenCapturer
import com.androiduse.core.ServerState
import com.androiduse.core.ServerStateRegistry
import com.androiduse.ui.MainActivity

/**
 * Foreground service hosting the embedded local HTTP server so AI agents can keep
 * sending requests while target Android apps are in the foreground.
 */
class ServerForegroundService : Service() {

    internal var serverStateRegistry: ServerStateRegistry = ServerStateRegistry.default
    internal var accessibilityRegistry: AccessibilityServiceRegistry = AccessibilityServiceRegistry.default

    private var httpServer: LocalHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServerAndSelf()
                return START_NOT_STICKY
            }

            else -> {
                val host = intent?.getStringExtra(EXTRA_HOST) ?: ServerState.DEFAULT_HOST
                val port = intent?.getIntExtra(EXTRA_PORT, ServerState.DEFAULT_PORT)
                    ?: ServerState.DEFAULT_PORT
                val explicitToken = intent?.getStringExtra(EXTRA_AUTH_TOKEN)
                startServer(host = host, port = port, explicitToken = explicitToken)
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        httpServer?.stop()
        httpServer = null
        if (serverStateRegistry.state.value.running) {
            serverStateRegistry.markStopped()
        }
        super.onDestroy()
    }

    private fun startServer(host: String, port: Int, explicitToken: String?) {
        if (httpServer != null) return

        ensureNotificationChannel()
        val notification = buildNotification(host, port)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val authToken = if (NetworkAddressHelper.isLoopbackHost(host)) {
            null
        } else {
            explicitToken?.takeIf { it.isNotBlank() }
                ?: ServerPreferences(applicationContext).getOrCreateAuthToken()
        }

        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: DEFAULT_VERSION
        }.getOrDefault(DEFAULT_VERSION)

        val deviceInfoProvider = AndroidDeviceInfoProvider(
            context = applicationContext,
            accessibilityRegistry = accessibilityRegistry,
            serverVersion = versionName,
        )
        val screenCapturer = ResilientScreenCapturer(
            delegate = AccessibilityScreenCapturer(
                context = applicationContext,
                accessibilityRegistry = accessibilityRegistry,
            ),
        )
        val uiTreeInspector = com.androiduse.android.AccessibilityUiTreeInspector(
            accessibilityRegistry = accessibilityRegistry,
        )
        val inputDispatcher = com.androiduse.android.AccessibilityInputDispatcher(
            accessibilityRegistry = accessibilityRegistry,
        )
        val controller = DefaultDeviceController(
            accessibilityRegistry = accessibilityRegistry,
            deviceInfoProvider = deviceInfoProvider,
            screenCapturer = screenCapturer,
            uiTreeInspector = uiTreeInspector,
            inputDispatcher = inputDispatcher,
            serverVersion = versionName,
        )

        val server = LocalHttpServer(
            controller = controller,
            host = host,
            port = port,
            authToken = authToken,
        )
        try {
            server.start()
            httpServer = server
            serverStateRegistry.markRunning(
                host = host,
                port = port,
                authRequired = authToken != null,
            )
        } catch (e: Throwable) {
            server.stop()
            serverStateRegistry.markStopped(e.message ?: "Failed to start local HTTP server.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopServerAndSelf() {
        httpServer?.stop()
        httpServer = null
        serverStateRegistry.markStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.server_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(host: String, port: Int): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.server_notification_title))
            .setContentText(getString(R.string.server_notification_text, "$host:$port"))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.stop_server),
                    stopIntent,
                ).build(),
            )
            .build()
    }

    companion object {
        const val ACTION_START: String = "com.androiduse.action.START_SERVER"
        const val ACTION_STOP: String = "com.androiduse.action.STOP_SERVER"
        const val EXTRA_HOST: String = "host"
        const val EXTRA_PORT: String = "port"
        const val EXTRA_AUTH_TOKEN: String = "auth_token"

        private const val CHANNEL_ID: String = "android_use_server"
        private const val NOTIFICATION_ID: Int = 1001
        private const val DEFAULT_VERSION: String = "0.1.0"

        fun startIntent(
            context: Context,
            host: String = ServerState.DEFAULT_HOST,
            port: Int = ServerState.DEFAULT_PORT,
            authToken: String? = null,
        ): Intent = Intent(context, ServerForegroundService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_HOST, host)
            putExtra(EXTRA_PORT, port)
            if (authToken != null) {
                putExtra(EXTRA_AUTH_TOKEN, authToken)
            }
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, ServerForegroundService::class.java).apply {
                action = ACTION_STOP
            }
    }
}
