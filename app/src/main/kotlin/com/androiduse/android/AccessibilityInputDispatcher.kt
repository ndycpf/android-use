package com.androiduse.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import com.androiduse.core.AccessibilityServiceRegistry
import com.androiduse.core.AndroidUseException
import com.androiduse.core.InputDispatcher
import com.androiduse.protocol.GlobalKey
import com.androiduse.protocol.UiPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [InputDispatcher] implementation backed by [AccessibilityService.dispatchGesture]
 * and [AccessibilityService.performGlobalAction].
 */
class AccessibilityInputDispatcher(
    private val accessibilityRegistry: AccessibilityServiceRegistry,
    private val callbackHandler: Handler = Handler(Looper.getMainLooper()),
) : InputDispatcher {

    override suspend fun tap(point: UiPoint, durationMs: Long) {
        val path = Path().apply {
            moveTo(point.x.toFloat(), point.y.toFloat())
        }
        dispatchSingleStroke(path = path, durationMs = durationMs, label = "tap")
    }

    override suspend fun longPress(point: UiPoint, durationMs: Long) {
        val path = Path().apply {
            moveTo(point.x.toFloat(), point.y.toFloat())
        }
        dispatchSingleStroke(path = path, durationMs = durationMs, label = "long_press")
    }

    override suspend fun swipe(start: UiPoint, end: UiPoint, durationMs: Long) {
        val path = Path().apply {
            moveTo(start.x.toFloat(), start.y.toFloat())
            lineTo(end.x.toFloat(), end.y.toFloat())
        }
        dispatchSingleStroke(path = path, durationMs = durationMs, label = "swipe")
    }

    override suspend fun pressGlobalKey(key: GlobalKey) {
        val service = requireService()
        val globalAction = mapGlobalAction(key)
        val ok = service.performGlobalAction(globalAction)
        if (!ok) {
            throw AndroidUseException.actionFailed(
                message = "performGlobalAction($key) returned false.",
                retryable = true,
            )
        }
    }

    private suspend fun dispatchSingleStroke(path: Path, durationMs: Long, label: String) {
        val service = requireService()
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        suspendCancellableCoroutine { continuation ->
            val accepted = try {
                service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) {
                                continuation.resume(Unit)
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    AndroidUseException.actionFailed(
                                        message = "Gesture '$label' was cancelled by Android.",
                                        retryable = true,
                                    ),
                                )
                            }
                        }
                    },
                    callbackHandler,
                )
            } catch (t: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        AndroidUseException.actionFailed(
                            message = "Failed to dispatch '$label' gesture: ${t.message}",
                            cause = t,
                        ),
                    )
                }
                return@suspendCancellableCoroutine
            }

            if (!accepted && continuation.isActive) {
                continuation.resumeWithException(
                    AndroidUseException.actionFailed(
                        message = "AccessibilityService rejected '$label' gesture dispatch.",
                        retryable = true,
                    ),
                )
            }
        }
    }

    private fun requireService(): AccessibilityService =
        accessibilityRegistry.currentService()
            ?: throw AndroidUseException.accessibilityNotConnected()

    internal companion object {
        fun mapGlobalAction(key: GlobalKey): Int = when (key) {
            GlobalKey.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            GlobalKey.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            GlobalKey.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            GlobalKey.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            GlobalKey.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            GlobalKey.DISMISS_NOTIFICATION_SHADE ->
                AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
        }
    }
}
