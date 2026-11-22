package com.androiduse.server.http

import com.androiduse.core.DeviceController
import com.androiduse.core.ServerState
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer

/**
 * Thin lifecycle wrapper around the embedded Ktor CIO server.
 * Defaults to loopback-only binding (`127.0.0.1:8765`) for safe `adb forward` access.
 */
class LocalHttpServer(
    private val controller: DeviceController,
    val host: String = ServerState.DEFAULT_HOST,
    val port: Int = ServerState.DEFAULT_PORT,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Synchronized
    fun start() {
        if (server != null) return
        val instance = embeddedServer(
            factory = CIO,
            port = port,
            host = host,
        ) {
            configureAndroidUseHttp(controller)
        }
        instance.start(wait = false)
        server = instance
    }

    @Synchronized
    fun stop(gracePeriodMillis: Long = 500, timeoutMillis: Long = 1_500) {
        server?.stop(gracePeriodMillis, timeoutMillis)
        server = null
    }
}
