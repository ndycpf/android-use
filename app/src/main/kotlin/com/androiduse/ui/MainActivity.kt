package com.androiduse.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.androiduse.R
import com.androiduse.android.ServerPreferences
import com.androiduse.core.AccessibilityServiceRegistry
import com.androiduse.core.BindMode
import com.androiduse.core.NetworkAddressHelper
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
    internal var tailscaleIpDetector: () -> String? = { NetworkAddressHelper.detectTailscaleIpv4() }
    internal var lanIpDetector: () -> String? = { NetworkAddressHelper.detectLanIpv4() }

    private lateinit var prefs: ServerPreferences
    private lateinit var statusValueText: TextView
    private lateinit var serverStatusValueText: TextView
    private lateinit var bindModeGroup: RadioGroup
    private lateinit var bindModeLoopback: RadioButton
    private lateinit var bindModeTailscale: RadioButton
    private lateinit var bindModeAll: RadioButton
    private lateinit var toggleServerButton: Button
    private lateinit var authTokenContainer: View
    private lateinit var authTokenValueText: TextView
    private lateinit var rotateTokenButton: Button
    private lateinit var connectionHintText: TextView
    private lateinit var copyCommandButton: Button

    private var selectedBindMode: BindMode = BindMode.LOOPBACK
    private var currentAuthToken: String = ""
    private var detectedTailscaleIp: String? = null
    private var detectedLanIp: String? = null
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

        prefs = ServerPreferences(this)
        selectedBindMode = prefs.getBindMode()
        currentAuthToken = prefs.getOrCreateAuthToken()

        statusValueText = findViewById(R.id.statusValueText)
        serverStatusValueText = findViewById(R.id.serverStatusValueText)
        bindModeGroup = findViewById(R.id.bindModeGroup)
        bindModeLoopback = findViewById(R.id.bindModeLoopback)
        bindModeTailscale = findViewById(R.id.bindModeTailscale)
        bindModeAll = findViewById(R.id.bindModeAll)
        toggleServerButton = findViewById(R.id.toggleServerButton)
        authTokenContainer = findViewById(R.id.authTokenContainer)
        authTokenValueText = findViewById(R.id.authTokenValueText)
        rotateTokenButton = findViewById(R.id.rotateTokenButton)
        connectionHintText = findViewById(R.id.connectionHintText)
        copyCommandButton = findViewById(R.id.copyCommandButton)

        findViewById<Button>(R.id.openSettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        checkBindModeRadio(selectedBindMode)
        bindModeGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedBindMode = when (checkedId) {
                R.id.bindModeTailscale -> BindMode.TAILSCALE
                R.id.bindModeAll -> BindMode.ALL_INTERFACES
                else -> BindMode.LOOPBACK
            }
            prefs.setBindMode(selectedBindMode)
            refreshConnectionDetails(serverStateRegistry.state.value)
        }

        rotateTokenButton.setOnClickListener {
            currentAuthToken = prefs.rotateAuthToken()
            refreshConnectionDetails(serverStateRegistry.state.value)
        }

        copyCommandButton.setOnClickListener {
            val command = buildPrimaryCurlCommand(serverStateRegistry.state.value)
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("android-use curl", command))
            Toast.makeText(this, R.string.copied_toast, Toast.LENGTH_SHORT).show()
        }

        val overlaySwitch = findViewById<android.widget.Switch>(R.id.overlaySwitch)
        overlaySwitch.isChecked = prefs.isOverlayEnabled()
        overlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setOverlayEnabled(isChecked)
        }

        toggleServerButton.setOnClickListener {
            if (serverStateRegistry.state.value.running) {
                startService(ServerForegroundService.stopIntent(this))
            } else {
                startServerForSelectedMode()
            }
        }

        refreshDetectedIps()
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

    override fun onResume() {
        super.onResume()
        refreshDetectedIps()
        refreshConnectionDetails(serverStateRegistry.state.value)
    }

    override fun onStop() {
        uiScope?.cancel()
        uiScope = null
        super.onStop()
    }

    private fun startServerForSelectedMode() {
        refreshDetectedIps()
        val (host, token) = when (selectedBindMode) {
            BindMode.LOOPBACK -> ServerState.DEFAULT_HOST to null
            BindMode.TAILSCALE -> {
                val tsIp = detectedTailscaleIp
                if (tsIp == null) {
                    serverStateRegistry.markStopped(getString(R.string.tailscale_not_found_error))
                    return
                }
                tsIp to currentAuthToken
            }

            BindMode.ALL_INTERFACES -> "0.0.0.0" to currentAuthToken
        }

        startForegroundService(
            ServerForegroundService.startIntent(
                context = this,
                host = host,
                port = ServerState.DEFAULT_PORT,
                authToken = token,
            ),
        )
    }

    private fun checkBindModeRadio(mode: BindMode) {
        val radioId = when (mode) {
            BindMode.LOOPBACK -> R.id.bindModeLoopback
            BindMode.TAILSCALE -> R.id.bindModeTailscale
            BindMode.ALL_INTERFACES -> R.id.bindModeAll
        }
        bindModeGroup.check(radioId)
    }

    private fun refreshDetectedIps() {
        detectedTailscaleIp = tailscaleIpDetector()
        detectedLanIp = lanIpDetector()
        bindModeTailscale.text = if (detectedTailscaleIp != null) {
            getString(R.string.bind_mode_tailscale, detectedTailscaleIp)
        } else {
            getString(R.string.bind_mode_tailscale_unavailable)
        }
    }

    private fun renderAccessibilityStatus(connected: Boolean) {
        val textRes = if (connected) R.string.status_connected else R.string.status_disconnected
        statusValueText.setText(textRes)
    }

    private fun renderServerState(state: ServerState) {
        serverStatusValueText.text = when {
            state.running && state.authRequired ->
                getString(R.string.server_status_running_auth, state.endpoint)
            state.running ->
                getString(R.string.server_status_running, state.endpoint)
            state.lastError != null ->
                getString(R.string.server_status_error, state.lastError)
            else ->
                getString(R.string.server_status_stopped)
        }
        toggleServerButton.setText(
            if (state.running) R.string.stop_server else R.string.start_server,
        )

        val editable = !state.running
        bindModeLoopback.isEnabled = editable
        bindModeTailscale.isEnabled = editable
        bindModeAll.isEnabled = editable
        rotateTokenButton.isEnabled = editable

        refreshConnectionDetails(state)
    }

    private fun refreshConnectionDetails(state: ServerState) {
        val needsAuth = if (state.running) {
            state.authRequired
        } else {
            selectedBindMode != BindMode.LOOPBACK
        }
        authTokenContainer.visibility = if (needsAuth) View.VISIBLE else View.GONE
        authTokenValueText.text = currentAuthToken
        connectionHintText.text = buildConnectionHint(state)
    }

    private fun resolveClientHost(state: ServerState): String {
        if (state.running) {
            if (state.host != "0.0.0.0") return state.host
            return detectedTailscaleIp ?: detectedLanIp ?: "<device-ip>"
        }
        return when (selectedBindMode) {
            BindMode.LOOPBACK -> ServerState.DEFAULT_HOST
            BindMode.TAILSCALE -> detectedTailscaleIp ?: "<tailscale-ip>"
            BindMode.ALL_INTERFACES -> detectedTailscaleIp ?: detectedLanIp ?: "<device-ip>"
        }
    }

    private fun buildPrimaryCurlCommand(state: ServerState): String {
        val host = resolveClientHost(state)
        val port = state.port
        val needsAuth = if (state.running) state.authRequired else selectedBindMode != BindMode.LOOPBACK
        return if (needsAuth) {
            "curl -H \"Authorization: Bearer $currentAuthToken\" \"http://$host:$port/v1/screen/screenshot?encoding=binary\" -o screen.jpg"
        } else {
            "curl \"http://$host:$port/v1/screen/screenshot?encoding=binary\" -o screen.jpg"
        }
    }

    private fun buildConnectionHint(state: ServerState): String {
        val host = resolveClientHost(state)
        val port = state.port
        val needsAuth = if (state.running) state.authRequired else selectedBindMode != BindMode.LOOPBACK
        return if (needsAuth) {
            """
            curl -H "Authorization: Bearer $currentAuthToken" http://$host:$port/v1/health
            curl -H "Authorization: Bearer $currentAuthToken" "http://$host:$port/v1/screen/screenshot?encoding=binary" -o screen.jpg
            """.trimIndent()
        } else {
            """
            adb forward tcp:$port tcp:$port
            curl http://$host:$port/v1/health
            curl "http://$host:$port/v1/screen/screenshot?encoding=binary" -o screen.jpg
            """.trimIndent()
        }
    }
}
