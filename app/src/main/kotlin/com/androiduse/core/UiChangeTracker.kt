package com.androiduse.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

data class UiChangeEvent(
    val sequence: Long,
    val timestampMs: Long,
    val eventType: Int,
    val packageName: String?,
    val className: String?,
    val isWindowStateChange: Boolean,
)

/**
 * Lightweight, coroutine-friendly event bus fed by [com.androiduse.android.AndroidUseAccessibilityService].
 *
 * Allows `POST /v1/wait` to wake up immediately on real Android UI transitions instead of
 * wasting time on fixed sleeps, and to wait for a short quiet period once a burst of layout
 * updates settles.
 */
class UiChangeTracker(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val sequenceCounter = AtomicLong(0L)
    @Volatile
    private var lastEvent: UiChangeEvent? = null

    private val events = MutableSharedFlow<UiChangeEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    fun currentSequence(): Long = sequenceCounter.get()

    fun latestEvent(): UiChangeEvent? = lastEvent

    fun recordEvent(
        eventType: Int,
        packageName: String?,
        className: String? = null,
        isWindowStateChange: Boolean = false,
        timestampMs: Long = clockMs(),
    ): UiChangeEvent {
        val seq = sequenceCounter.incrementAndGet()
        val event = UiChangeEvent(
            sequence = seq,
            timestampMs = timestampMs,
            eventType = eventType,
            packageName = packageName?.takeIf { it.isNotBlank() },
            className = className?.takeIf { it.isNotBlank() },
            isWindowStateChange = isWindowStateChange,
        )
        lastEvent = event
        events.tryEmit(event)
        return event
    }

    /**
     * Suspends until an event with `sequence > sinceSequence` arrives or [timeoutMs] elapses.
     */
    suspend fun awaitEventSince(sinceSequence: Long, timeoutMs: Long): UiChangeEvent? {
        if (timeoutMs <= 0L) {
            val existing = lastEvent
            return if (existing != null && existing.sequence > sinceSequence) existing else null
        }

        val current = lastEvent
        if (current != null && current.sequence > sinceSequence) {
            return current
        }

        return withTimeoutOrNull(timeoutMs) {
            // Re-check inside collector window to avoid missed-signal races.
            val check = lastEvent
            if (check != null && check.sequence > sinceSequence) {
                check
            } else {
                events.first { it.sequence > sinceSequence }
            }
        }
    }

    /**
     * Waits until no new accessibility events arrive for [quietPeriodMs], capped by [maxWaitMs]
     * so continuous animations (progress spinners, videos, timers) never block forever.
     */
    suspend fun awaitQuiet(quietPeriodMs: Long, maxWaitMs: Long): Boolean {
        if (quietPeriodMs <= 0L || maxWaitMs <= 0L) return true
        val startMs = clockMs()
        val deadlineMs = startMs + maxWaitMs
        var seq = currentSequence()
        var eventsSeen = 0

        while (true) {
            val remainingMs = deadlineMs - clockMs()
            if (remainingMs <= 0L || eventsSeen >= MAX_QUIET_EVENT_BURST) return false

            val waitWindow = minOf(quietPeriodMs, remainingMs)
            val nextEvent = awaitEventSince(sinceSequence = seq, timeoutMs = waitWindow)
            if (nextEvent == null) {
                return waitWindow >= quietPeriodMs
            }
            seq = nextEvent.sequence
            eventsSeen++
        }
    }

    fun reset() {
        lastEvent = null
        sequenceCounter.set(0L)
    }

    companion object {
        private const val MAX_QUIET_EVENT_BURST: Int = 64
        val default: UiChangeTracker = UiChangeTracker()
    }
}
