package com.androiduse.core

import com.androiduse.protocol.UiBounds
import com.androiduse.protocol.UiTreeResponse

data class UiTreeOptions(
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    val maxNodes: Int = DEFAULT_MAX_NODES,
    val includeInvisible: Boolean = false,
    val pruneEmptyContainers: Boolean = true,
    val includeAllWindows: Boolean = false,
) {
    fun validate(): UiTreeOptions {
        if (maxDepth !in 1..MAX_ALLOWED_DEPTH) {
            throw AndroidUseException.invalidRequest(
                "maxDepth must be between 1 and $MAX_ALLOWED_DEPTH (got $maxDepth).",
            )
        }
        if (maxNodes !in 1..MAX_ALLOWED_NODES) {
            throw AndroidUseException.invalidRequest(
                "maxNodes must be between 1 and $MAX_ALLOWED_NODES (got $maxNodes).",
            )
        }
        return this
    }

    companion object {
        const val DEFAULT_MAX_DEPTH: Int = 30
        const val DEFAULT_MAX_NODES: Int = 400
        const val MAX_ALLOWED_DEPTH: Int = 100
        const val MAX_ALLOWED_NODES: Int = 2000
    }
}

/**
 * Platform-independent snapshot of a raw Android [android.view.accessibility.AccessibilityNodeInfo].
 */
data class RawUiNode(
    val packageName: String? = null,
    val className: String? = null,
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val hintText: String? = null,
    val bounds: UiBounds = UiBounds(0, 0, 0, 0),
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val visibleToUser: Boolean = true,
    val children: List<RawUiNode> = emptyList(),
)

interface UiTreeInspector {
    suspend fun inspect(options: UiTreeOptions = UiTreeOptions()): UiTreeResponse
}
