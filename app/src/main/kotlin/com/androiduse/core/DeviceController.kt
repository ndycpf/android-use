package com.androiduse.core

import com.androiduse.protocol.DeviceInfoResponse
import com.androiduse.protocol.HealthResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface DeviceInfoProvider {
    fun getDeviceInfo(): DeviceInfoResponse
}

/**
 * Transport-agnostic entry point for all Android Computer Use operations.
 * HTTP v1, MCP, and future in-app agent loops all delegate to this interface.
 */
interface DeviceController {
    suspend fun getHealth(): HealthResponse
    suspend fun getDeviceInfo(): DeviceInfoResponse
}

class DefaultDeviceController(
    private val accessibilityRegistry: AccessibilityServiceRegistry,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val serverVersion: String,
) : DeviceController {

    private val actionMutex = Mutex()

    override suspend fun getHealth(): HealthResponse {
        return HealthResponse(
            status = "ok",
            accessibilityConnected = accessibilityRegistry.isConnected.value,
            version = serverVersion,
        )
    }

    override suspend fun getDeviceInfo(): DeviceInfoResponse {
        return deviceInfoProvider.getDeviceInfo()
    }

    /**
     * Serializes mutating device actions so concurrent agent calls never interleave.
     */
    internal suspend fun <T> withActionLock(block: suspend () -> T): T {
        return actionMutex.withLock { block() }
    }
}
