package com.androiduse.core

import com.androiduse.protocol.UiPoint

/**
 * Optional visual feedback overlay shown on-device during agent capture/actions.
 */
interface ActionOverlayFeedback {
    /**
     * Temporarily hides any active overlay before taking a screenshot so AI agent
     * images remain completely clean.
     */
    suspend fun beforeScreenshot()

    fun showCaptured()
    fun showTap(point: UiPoint, longPress: Boolean)
    fun showSwipe(start: UiPoint, end: UiPoint)
    fun showStatus(label: String)
    fun clear()
}

object NoOpActionOverlayFeedback : ActionOverlayFeedback {
    override suspend fun beforeScreenshot() = Unit
    override fun showCaptured() = Unit
    override fun showTap(point: UiPoint, longPress: Boolean) = Unit
    override fun showSwipe(start: UiPoint, end: UiPoint) = Unit
    override fun showStatus(label: String) = Unit
    override fun clear() = Unit
}
