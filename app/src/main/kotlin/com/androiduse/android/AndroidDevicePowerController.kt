package com.androiduse.android

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.androiduse.core.DevicePowerController
import com.androiduse.core.PowerState

/**
 * Queries display interactive / keyguard lock state and triggers best-effort wake-from-sleep
 * via a short-lived [PowerManager.WakeLock] (ADR-0006).
 *
 * Note: Waking the screen turns on the display, but Android's security model prevents non-root
 * apps from bypassing PIN, pattern, password, or biometric keyguard locks.
 */
class AndroidDevicePowerController(
    private val context: Context,
) : DevicePowerController {

    override fun getPowerState(): PowerState {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        val screenOn = powerManager?.isInteractive ?: true
        val locked = keyguardManager?.isKeyguardLocked ?: false
        return PowerState(screenOn = screenOn, deviceLocked = locked)
    }

    override fun wakeScreen(): PowerState {
        val powerManager = context.getSystemService(PowerManager::class.java)
        if (powerManager != null && !powerManager.isInteractive) {
            runCatching {
                @Suppress("DEPRECATION")
                val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE
                val wakeLock = powerManager.newWakeLock(flags, WAKE_LOCK_TAG)
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
        }
        return getPowerState()
    }

    companion object {
        private const val WAKE_LOCK_TAG = "android-use:wake-screen"
        private const val WAKE_LOCK_TIMEOUT_MS = 3_000L
    }
}
