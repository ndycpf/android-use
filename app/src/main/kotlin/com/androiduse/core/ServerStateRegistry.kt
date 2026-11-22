package com.androiduse.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerState(
    val running: Boolean = false,
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val lastError: String? = null,
) {
    val endpoint: String
        get() = "$host:$port"

    companion object {
        const val DEFAULT_HOST: String = "127.0.0.1"
        const val DEFAULT_PORT: Int = 8765
    }
}

class ServerStateRegistry {
    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    fun markRunning(host: String, port: Int) {
        _state.value = ServerState(
            running = true,
            host = host,
            port = port,
            lastError = null,
        )
    }

    fun markStopped(error: String? = null) {
        val current = _state.value
        _state.value = current.copy(
            running = false,
            lastError = error,
        )
    }

    fun reset() {
        _state.value = ServerState()
    }

    companion object {
        val default: ServerStateRegistry = ServerStateRegistry()
    }
}
