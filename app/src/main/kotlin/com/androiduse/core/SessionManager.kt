package com.androiduse.core

import com.androiduse.protocol.StartSessionRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class ActiveSession(
    val sessionId: String,
    val label: String?,
    val startedAtMs: Long,
    val lastActivityMs: Long,
    val idleTimeoutMs: Long,
    val keepAwake: Boolean,
)

/**
 * Thread-safe manager for the active android-use control session (ADR-0006).
 * Enforces idle timeout expiration and notifies listeners (such as the visual overlay
 * and screen keep-awake window) immediately whenever a session starts or ends.
 */
class SessionManager(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val _state = MutableStateFlow<ActiveSession?>(null)
    val state: StateFlow<ActiveSession?> = _state.asStateFlow()

    @Volatile
    private var stateChangeListener: ((ActiveSession?) -> Unit)? = null

    fun setStateChangeListener(listener: ((ActiveSession?) -> Unit)?) {
        stateChangeListener = listener
    }

    fun startSession(request: StartSessionRequest = StartSessionRequest()): ActiveSession {
        if (request.idleTimeoutMs !in MIN_IDLE_TIMEOUT_MS..MAX_IDLE_TIMEOUT_MS) {
            throw AndroidUseException.invalidRequest(
                "idleTimeoutMs must be between $MIN_IDLE_TIMEOUT_MS and $MAX_IDLE_TIMEOUT_MS ms (got ${request.idleTimeoutMs}).",
            )
        }

        val now = clockMs()
        val cleanLabel = request.label?.trim()?.takeIf { it.isNotEmpty() }
        val session = ActiveSession(
            sessionId = idGenerator(),
            label = cleanLabel,
            startedAtMs = now,
            lastActivityMs = now,
            idleTimeoutMs = request.idleTimeoutMs,
            keepAwake = request.keepAwake,
        )
        synchronized(lock) {
            _state.value = session
        }
        stateChangeListener?.invoke(session)
        return session
    }

    fun endSession(): Boolean {
        val ended = synchronized(lock) {
            val hadSession = _state.value != null
            _state.value = null
            hadSession
        }
        if (ended) {
            stateChangeListener?.invoke(null)
        }
        return ended
    }

    fun currentSession(): ActiveSession? {
        val expired = synchronized(lock) {
            val current = _state.value ?: return null
            if (isExpired(current, clockMs())) {
                _state.value = null
                true
            } else {
                return current
            }
        }
        if (expired) {
            stateChangeListener?.invoke(null)
        }
        return null
    }

    fun requireActiveSessionAndTouch(): ActiveSession {
        var expired = false
        val updated = synchronized(lock) {
            val current = _state.value
            val now = clockMs()
            when {
                current == null -> null
                isExpired(current, now) -> {
                    _state.value = null
                    expired = true
                    null
                }

                else -> {
                    val touched = current.copy(lastActivityMs = now)
                    _state.value = touched
                    touched
                }
            }
        }
        if (expired) {
            stateChangeListener?.invoke(null)
        }
        return updated ?: throw AndroidUseException.sessionNotActive()
    }

    /**
     * Checks whether the active session has exceeded its idle timeout and terminates it if so.
     * Returns true if an active session expired and was ended.
     */
    fun checkIdleTimeout(): Boolean {
        val expired = synchronized(lock) {
            val current = _state.value ?: return false
            if (isExpired(current, clockMs())) {
                _state.value = null
                true
            } else {
                false
            }
        }
        if (expired) {
            stateChangeListener?.invoke(null)
        }
        return expired
    }

    fun reset() {
        synchronized(lock) {
            _state.value = null
        }
        stateChangeListener = null
    }

    private fun isExpired(session: ActiveSession, nowMs: Long): Boolean {
        return (nowMs - session.lastActivityMs) >= session.idleTimeoutMs
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS: Long = 600_000L
        const val MIN_IDLE_TIMEOUT_MS: Long = 10_000L
        const val MAX_IDLE_TIMEOUT_MS: Long = 3_600_000L

        val default: SessionManager = SessionManager()
    }
}
