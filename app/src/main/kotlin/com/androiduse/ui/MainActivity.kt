package com.androiduse.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import com.androiduse.R
import com.androiduse.core.AccessibilityServiceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    internal var registry: AccessibilityServiceRegistry = AccessibilityServiceRegistry.default

    private lateinit var statusValueText: TextView
    private var uiScope: CoroutineScope? = null
    private var observeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusValueText = findViewById(R.id.statusValueText)
        val openSettingsButton = findViewById<Button>(R.id.openSettingsButton)
        openSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        renderStatus(registry.isConnected.value)
    }

    override fun onStart() {
        super.onStart()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        uiScope = scope
        observeJob = scope.launch {
            registry.isConnected.collect { connected ->
                renderStatus(connected)
            }
        }
    }

    override fun onStop() {
        observeJob?.cancel()
        observeJob = null
        uiScope = null
        super.onStop()
    }

    private fun renderStatus(connected: Boolean) {
        val textRes = if (connected) R.string.status_connected else R.string.status_disconnected
        statusValueText.setText(textRes)
    }
}
