package com.androiduse.android

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.androiduse.core.AccessibilityServiceRegistry
import com.androiduse.core.AndroidUseException
import com.androiduse.core.RawUiNode
import com.androiduse.core.UiTreeInspector
import com.androiduse.core.UiTreeNormalizer
import com.androiduse.core.UiTreeOptions
import com.androiduse.protocol.UiBounds
import com.androiduse.protocol.UiTreeResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Inspects the active Android accessibility window(s) and produces a normalized [UiTreeResponse].
 */
class AccessibilityUiTreeInspector(
    private val accessibilityRegistry: AccessibilityServiceRegistry,
    private val inspectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : UiTreeInspector {

    override suspend fun inspect(options: UiTreeOptions): UiTreeResponse {
        options.validate()
        val service = accessibilityRegistry.currentService()
            ?: throw AndroidUseException.accessibilityNotConnected()

        return withContext(inspectionDispatcher) {
            val rootNodeInfos = collectWindowRoots(service, options.includeAllWindows)
            var visitedRawNodes = 0
            var preTruncated = false
            val rect = Rect()

            fun snapshot(info: AccessibilityNodeInfo, rawDepth: Int): RawUiNode? {
                if (visitedRawNodes >= MAX_RAW_NODES) {
                    preTruncated = true
                    return null
                }
                visitedRawNodes++

                info.getBoundsInScreen(rect)
                val bounds = UiBounds(
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom,
                )

                val children = mutableListOf<RawUiNode>()
                val childCount = info.childCount
                if (rawDepth < MAX_RAW_DEPTH) {
                    for (i in 0 until childCount) {
                        val childInfo = info.getChild(i) ?: continue
                        val childSnapshot = snapshot(childInfo, rawDepth + 1)
                        if (childSnapshot != null) {
                            children += childSnapshot
                        } else {
                            break
                        }
                    }
                } else if (childCount > 0) {
                    preTruncated = true
                }

                return RawUiNode(
                    packageName = info.packageName?.toString(),
                    className = info.className?.toString(),
                    resourceId = info.viewIdResourceName,
                    text = info.text?.toString(),
                    contentDescription = info.contentDescription?.toString(),
                    hintText = info.hintText?.toString(),
                    bounds = bounds,
                    clickable = info.isClickable,
                    longClickable = info.isLongClickable,
                    editable = info.isEditable,
                    scrollable = info.isScrollable,
                    checkable = info.isCheckable,
                    checked = info.isChecked,
                    focused = info.isFocused,
                    enabled = info.isEnabled,
                    selected = info.isSelected,
                    visibleToUser = info.isVisibleToUser,
                    children = children,
                )
            }

            val rawRoots = rootNodeInfos.mapNotNull { root -> snapshot(root, rawDepth = 0) }
            UiTreeNormalizer.normalize(
                rawRoots = rawRoots,
                options = options,
                timestampMs = clockMs(),
                windowCount = rootNodeInfos.size,
                preTruncated = preTruncated,
            )
        }
    }

    private fun collectWindowRoots(
        service: android.accessibilityservice.AccessibilityService,
        includeAllWindows: Boolean,
    ): List<AccessibilityNodeInfo> {
        if (!includeAllWindows) {
            return listOfNotNull(service.rootInActiveWindow)
        }

        val windows = service.windows.orEmpty()
        if (windows.isEmpty()) {
            return listOfNotNull(service.rootInActiveWindow)
        }

        // Prioritize the active window first, then application/IME/system windows by layer.
        val sortedWindows = windows
            .filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    it.type == AccessibilityWindowInfo.TYPE_SYSTEM
            }
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { it.isActive }
                    .thenByDescending { it.layer },
            )

        val roots = sortedWindows.mapNotNull { window -> window.root }
        return roots.ifEmpty { listOfNotNull(service.rootInActiveWindow) }
    }

    companion object {
        private const val MAX_RAW_NODES: Int = 4_000
        private const val MAX_RAW_DEPTH: Int = 80
    }
}
