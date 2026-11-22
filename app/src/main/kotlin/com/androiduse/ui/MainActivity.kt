package com.androiduse.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.androiduse.R
import com.androiduse.core.AccessibilityServiceRegistry
import com.androiduse.core.ServerState
import com.androiduse.core.ServerStateRegistry
import com.androiduse.server.http.ServerForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    internal var registry: AccessibilityServiceRegistry = AccessibilityServiceRegistry.default
    internal var serverStateRegistry: ServerStateRegistry = ServerStateRegistry.default

    private lateinit var statusValueText: TextView
    private lateinit var serverStatusValueText: TextView
    private lateinit var toggleServerButton: Button
    private var uiScope: CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val rootScrollView = findViewById<View>(R.id.rootScrollView)
        ViewCompat.setOnApplyWindowInsetsListener(rootScrollView) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        statusValueText = findViewById(R.id.statusValueText)
        serverStatusValueText = findViewById(R.id.serverStatusValueText)
        toggleServerButton = findViewById(R.id.toggleServerButton)

        val openSettingsButton = findViewById<Button>(R.id.openSettingsButton)
        openSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        toggleServerButton.setOnClickListener {
            if (serverStateRegistry.state.value.running) {
                startService(ServerForegroundService.stopIntent(this))
            } else {
                startForegroundService(ServerForegroundService.startIntent(this))
            }
        }

        renderAccessibilityStatus(registry.isConnected.value)
        renderServerState(serverStateRegistry.state.value)
    }

    override fun onStart() {
        super.onStart()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        uiScope = scope
        scope.launch {
            registry.isConnected.collect { connected ->
                renderAccessibilityStatus(connected)
            }
        }
        scope.launch {
            serverStateRegistry.state.collect { state ->
                renderServerState(state)
            }
        }
    }

    override fun onStop() {
        uiScope?.cancel()
        uiScope = null
        super.onStop()
    }

    private fun renderAccessibilityStatus(connected: Boolean) {
        val textRes = if (connected) R.string.status_connected else R.string.status_disconnected
        statusValueText.setText(textRes)
    }

    private fun renderServerState(state: ServerState) {
        serverStatusValueText.text = when {
            state.running -> getString(R.string.server_status_running, state.endpoint)
            state.lastError != null -> getString(R.string.server_status_error, state.lastError)
            else -> getString(R.string.server_status_stopped)
        }
        toggleServerButton.setText(
            if (state.running) R.string.stop_server else R.string.start_server,
        )
    }
}
