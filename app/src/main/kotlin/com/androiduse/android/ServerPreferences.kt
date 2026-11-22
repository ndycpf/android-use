package com.androiduse.android

import android.content.Context
import com.androiduse.core.BindMode
import java.security.SecureRandom
import java.util.Base64

/**
 * Persists server bind mode and the random Bearer token used whenever the server
 * binds to a non-loopback address (Tailscale or LAN).
 */
class ServerPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBindMode(): BindMode {
        val raw = prefs.getString(KEY_BIND_MODE, BindMode.LOOPBACK.name)
        return runCatching { BindMode.valueOf(raw ?: BindMode.LOOPBACK.name) }
            .getOrDefault(BindMode.LOOPBACK)
    }

    fun setBindMode(mode: BindMode) {
        prefs.edit().putString(KEY_BIND_MODE, mode.name).apply()
    }

    fun getOrCreateAuthToken(): String {
        val existing = prefs.getString(KEY_AUTH_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        return rotateAuthToken()
    }

    fun rotateAuthToken(): String {
        val token = generateToken()
        prefs.edit().putString(KEY_AUTH_TOKEN, token).apply()
        return token
    }

    companion object {
        private const val PREFS_NAME = "android_use_server_prefs"
        private const val KEY_BIND_MODE = "bind_mode"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private val secureRandom = SecureRandom()

        fun generateToken(): String {
            val bytes = ByteArray(24)
            secureRandom.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
