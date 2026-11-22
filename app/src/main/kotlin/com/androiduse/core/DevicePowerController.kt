package com.androiduse.core

data class PowerState(
    val screenOn: Boolean,
    val deviceLocked: Boolean,
)

/**
 * Queries display interactive / keyguard lock state and requests best-effort screen wake.
 */
interface DevicePowerController {
    fun getPowerState(): PowerState
    fun wakeScreen(): PowerState
}

class StaticDevicePowerController(
    var state: PowerState = PowerState(screenOn = true, deviceLocked = false),
) : DevicePowerController {
    var wakeCalls: Int = 0
        private set

    override fun getPowerState(): PowerState = state

    override fun wakeScreen(): PowerState {
        wakeCalls++
        state = state.copy(screenOn = true)
        return state
    }
}
