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

    override fun onServiceConnected() {
        super.onServiceConnected()
        registry.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Populated in later phases (UI state tracking and wait conditions).
    }

    override fun onInterrupt() {
        // No continuous feedback channels to interrupt.
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
