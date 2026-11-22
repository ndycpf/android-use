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
import com.androiduse.android.AccessibilityInputDispatcher
import com.androiduse.android.AccessibilityOverlayController
import com.androiduse.android.AccessibilityScreenCapturer
import com.androiduse.android.AccessibilityTextInputController
import com.androiduse.android.AccessibilityUiTreeInspector
import com.androiduse.android.AndroidAppController
import com.androiduse.android.AndroidDeviceInfoProvider
import com.androiduse.android.AndroidDevicePowerController
import com.androiduse.android.ServerPreferences
import com.androiduse.core.AccessibilityServiceRegistry
import com.androiduse.core.ActiveSession
import com.androiduse.core.DefaultDeviceController
import com.androiduse.core.NetworkAddressHelper
import com.androiduse.core.ResilientScreenCapturer
import com.androiduse.core.ServerState
import com.androiduse.core.ServerStateRegistry
import com.androiduse.core.SessionManager
import com.androiduse.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the embedded local HTTP server, session idle timeout watcher,
 * persistent status/stop notification, and persistent session overlay lifecycle.
 */
class ServerForegroundService : Service() {

    internal var serverStateRegistry: ServerStateRegistry = ServerStateRegistry.default
    internal var accessibilityRegistry: AccessibilityServiceRegistry = AccessibilityServiceRegistry.default
    internal var sessionManager: SessionManager = SessionManager.default

    private var httpServer: LocalHttpServer? = null
    private var overlayController: AccessibilityOverlayController? = null
    private var serviceScope: CoroutineScope? = null
    private var idleWatcherJob: Job? = null
    private var activeHost: String = ServerState.DEFAULT_HOST
    private var activePort: Int = ServerState.DEFAULT_PORT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServerAndSelf()
                return START_NOT_STICKY
            }

            ACTION_END_SESSION -> {
                sessionManager.endSession()
                overlayController?.setSessionActive(active = false)
                refreshNotification(session = null)
                return START_STICKY
            }

            ACTION_RESTORE_NOTIFICATION -> {
                if (httpServer != null) {
                    refreshNotification(session = sessionManager.currentSession())
                }
                return START_STICKY
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
        teardownServer()
        if (serverStateRegistry.state.value.running) {
            serverStateRegistry.markStopped()
        }
        super.onDestroy()
    }

    private fun startServer(host: String, port: Int, explicitToken: String?) {
        if (httpServer != null) {
            refreshNotification(session = sessionManager.currentSession())
            return
        }

        activeHost = host
        activePort = port

        ensureNotificationChannel()
        val notification = buildNotification(
            host = host,
            port = port,
            session = sessionManager.currentSession(),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val prefs = ServerPreferences(applicationContext)
        val authToken = if (NetworkAddressHelper.isLoopbackHost(host)) {
            null
        } else {
            explicitToken?.takeIf { it.isNotBlank() }
                ?: prefs.getOrCreateAuthToken()
        }

        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: DEFAULT_VERSION
        }.getOrDefault(DEFAULT_VERSION)

        val deviceInfoProvider = AndroidDeviceInfoProvider(
            context = applicationContext,
            accessibilityRegistry = accessibilityRegistry,
            serverVersion = versionName,
        )
        val powerController = AndroidDevicePowerController(applicationContext)
        val screenCapturer = ResilientScreenCapturer(
            delegate = AccessibilityScreenCapturer(
                context = applicationContext,
                accessibilityRegistry = accessibilityRegistry,
            ),
        )
        val uiTreeInspector = AccessibilityUiTreeInspector(
            accessibilityRegistry = accessibilityRegistry,
        )
        val inputDispatcher = AccessibilityInputDispatcher(
            accessibilityRegistry = accessibilityRegistry,
        )
        val textInputController = AccessibilityTextInputController(
            context = applicationContext,
            accessibilityRegistry = accessibilityRegistry,
        )
        val appController = AndroidAppController(
            context = applicationContext,
        )
        val overlay = AccessibilityOverlayController(
            accessibilityRegistry = accessibilityRegistry,
        )
        overlayController = overlay

        sessionManager.setStateChangeListener { activeSession ->
            overlay.setSessionActive(
                active = activeSession != null,
                label = activeSession?.label,
                keepAwake = activeSession?.keepAwake ?: false,
            )
            refreshNotification(session = activeSession)
        }

        val controller = DefaultDeviceController(
            accessibilityRegistry = accessibilityRegistry,
            deviceInfoProvider = deviceInfoProvider,
            screenCapturer = screenCapturer,
            uiTreeInspector = uiTreeInspector,
            inputDispatcher = inputDispatcher,
            textInputController = textInputController,
            appController = appController,
            sessionManager = sessionManager,
            powerController = powerController,
            overlayFeedback = overlay,
            screenshotAnnotator = com.androiduse.android.AndroidScreenshotAnnotator(),
            uiChangeTracker = com.androiduse.core.UiChangeTracker.default,
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
            startIdleTimeoutWatcher()
            serverStateRegistry.markRunning(
                host = host,
                port = port,
                authRequired = authToken != null,
            )
        } catch (e: Throwable) {
            teardownServer()
            serverStateRegistry.markStopped(e.message ?: "Failed to start local HTTP server.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startIdleTimeoutWatcher() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        serviceScope = scope
        idleWatcherJob = scope.launch {
            while (isActive) {
                delay(IDLE_CHECK_INTERVAL_MS)
                sessionManager.checkIdleTimeout()
            }
        }
    }

    private fun teardownServer() {
        idleWatcherJob?.cancel()
        idleWatcherJob = null
        serviceScope?.cancel()
        serviceScope = null
        sessionManager.endSession()
        sessionManager.setStateChangeListener(null)
        overlayController?.clear()
        overlayController = null
        httpServer?.stop()
        httpServer = null
    }

    private fun stopServerAndSelf() {
        teardownServer()
        serverStateRegistry.markStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refreshNotification(session: ActiveSession?) {
        if (httpServer == null) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(host = activeHost, port = activePort, session = session),
        )
    }

    internal fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.server_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    internal fun buildNotification(
        host: String,
        port: Int,
        session: ActiveSession? = null,
    ): Notification {
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            pendingFlags,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            pendingFlags,
        )

        val restoreIntent = PendingIntent.getService(
            this,
            3,
            restoreNotificationIntent(this),
            pendingFlags,
        )

        val endpoint = "$host:$port"
        val title = if (session != null) {
            getString(R.string.server_notification_session_title)
        } else {
            getString(R.string.server_notification_title)
        }
        val contentText = if (session != null) {
            val label = session.label ?: getString(R.string.session_default_label)
            getString(R.string.server_notification_session_text, label, endpoint)
        } else {
            getString(R.string.server_notification_text, endpoint)
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(NOTIFICATION_ACCENT_COLOR)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent)
            .setDeleteIntent(restoreIntent)

        if (session != null) {
            val endSessionIntent = PendingIntent.getService(
                this,
                2,
                endSessionIntent(this),
                pendingFlags,
            )
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.end_session),
                    endSessionIntent,
                ).build(),
            )
        }

        builder.addAction(
            Notification.Action.Builder(
                null,
                getString(R.string.stop_server),
                stopIntent,
            ).build(),
        )

        return builder.build()
    }

    companion object {
        const val ACTION_START: String = "com.androiduse.action.START_SERVER"
        const val ACTION_STOP: String = "com.androiduse.action.STOP_SERVER"
        const val ACTION_END_SESSION: String = "com.androiduse.action.END_SESSION"
        const val ACTION_RESTORE_NOTIFICATION: String = "com.androiduse.action.RESTORE_NOTIFICATION"
        const val EXTRA_HOST: String = "host"
        const val EXTRA_PORT: String = "port"
        const val EXTRA_AUTH_TOKEN: String = "auth_token"

        internal const val CHANNEL_ID: String = "android_use_server_status"
        internal const val NOTIFICATION_ID: Int = 1001
        private const val NOTIFICATION_ACCENT_COLOR: Int = 0xFF0EA5E9.toInt()
        private const val DEFAULT_VERSION: String = "0.1.0"
        private const val IDLE_CHECK_INTERVAL_MS: Long = 5_000L

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

        fun endSessionIntent(context: Context): Intent =
            Intent(context, ServerForegroundService::class.java).apply {
                action = ACTION_END_SESSION
            }

        fun restoreNotificationIntent(context: Context): Intent =
            Intent(context, ServerForegroundService::class.java).apply {
                action = ACTION_RESTORE_NOTIFICATION
            }
    }
}
