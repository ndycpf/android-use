package com.androiduse.core

import android.accessibilityservice.AccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * Thread-safe registry tracking the currently connected [AccessibilityService] instance.
 *
 * Uses a [WeakReference] combined with explicit [detach] on unbind/destroy to prevent
 * leaking service instances while exposing observable connection state to core and UI layers.
 */
class AccessibilityServiceRegistry {
    private val lock = Any()
    private var serviceRef: WeakReference<AccessibilityService>? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun attach(service: AccessibilityService) {
        synchronized(lock) {
            serviceRef = WeakReference(service)
            _isConnected.value = true
        }
    }

    fun detach(service: AccessibilityService) {
        synchronized(lock) {
            val current = serviceRef?.get()
            if (current == null || current === service) {
                serviceRef = null
                _isConnected.value = false
            }
        }
    }

    fun currentService(): AccessibilityService? {
        synchronized(lock) {
            val service = serviceRef?.get()
            if (service == null && _isConnected.value) {
                serviceRef = null
                _isConnected.value = false
            }
            return service
        }
    }

    fun clear() {
        synchronized(lock) {
            serviceRef = null
            _isConnected.value = false
        }
    }

    companion object {
        val default: AccessibilityServiceRegistry = AccessibilityServiceRegistry()
    }
}
