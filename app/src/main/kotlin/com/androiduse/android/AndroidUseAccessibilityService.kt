package com.androiduse.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.androiduse.core.AccessibilityServiceRegistry

/**
 * Skeleton AccessibilityService providing the foundation for screen capture,
 * UI tree inspection, gesture injection, and text input in subsequent phases.
 */
class AndroidUseAccessibilityService : AccessibilityService() {

    internal var registry: AccessibilityServiceRegistry = AccessibilityServiceRegistry.default
    internal var changeTracker: com.androiduse.core.UiChangeTracker = com.androiduse.core.UiChangeTracker.default

    override fun onServiceConnected() {
        super.onServiceConnected()
        registry.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        val pkg = event.packageName?.toString()
        // Ignore noisy status-bar clock/battery content ticks from SystemUI,
        // while still forwarding window-state changes (notification shade, dialogs, permission prompts).
        if (pkg == SYSTEM_UI_PACKAGE && type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }
        changeTracker.recordEvent(
            eventType = type,
            packageName = pkg,
            className = event.className?.toString(),
            isWindowStateChange = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        )
    }

    override fun onInterrupt() {
        // No continuous feedback channels to interrupt.
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE: String = "com.android.systemui"
    }

    override fun onUnbind(intent: Intent?): Boolean {
        registry.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        registry.detach(this)
        super.onDestroy()
    }
}
