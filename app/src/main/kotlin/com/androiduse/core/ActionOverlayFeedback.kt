package com.androiduse.core

import com.androiduse.protocol.UiPoint

/**
 * Visual feedback overlay shown continuously during an active session (ADR-0006),
 * with transient markers for individual actions and clean suppression during screenshots.
 */
interface ActionOverlayFeedback {
    /**
     * Attaches or detaches the persistent session overlay and controls screen keep-awake.
     */
    fun setSessionActive(active: Boolean, label: String? = null, keepAwake: Boolean = true)

    /**
     * Temporarily suppresses drawing before taking a screenshot so AI agent images remain
     * clean while keeping the window attached (and `FLAG_KEEP_SCREEN_ON` uninterrupted).
     */
    suspend fun beforeScreenshot()

    fun showCaptured()
    fun showTap(point: UiPoint, longPress: Boolean)
    fun showSwipe(start: UiPoint, end: UiPoint)
    fun showStatus(label: String)
    fun clear()
}

object NoOpActionOverlayFeedback : ActionOverlayFeedback {
    override fun setSessionActive(active: Boolean, label: String?, keepAwake: Boolean) = Unit
    override suspend fun beforeScreenshot() = Unit
    override fun showCaptured() = Unit
    override fun showTap(point: UiPoint, longPress: Boolean) = Unit
    override fun showSwipe(start: UiPoint, end: UiPoint) = Unit
    override fun showStatus(label: String) = Unit
    override fun clear() = Unit
}
