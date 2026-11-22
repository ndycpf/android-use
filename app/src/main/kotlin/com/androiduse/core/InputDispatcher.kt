package com.androiduse.core

import com.androiduse.protocol.GlobalKey
import com.androiduse.protocol.UiPoint

/**
 * Abstraction for injecting pointer gestures and global navigation actions.
 */
interface InputDispatcher {
    suspend fun tap(point: UiPoint, durationMs: Long)
    suspend fun longPress(point: UiPoint, durationMs: Long)
    suspend fun swipe(start: UiPoint, end: UiPoint, durationMs: Long)
    suspend fun pressGlobalKey(key: GlobalKey)
}
