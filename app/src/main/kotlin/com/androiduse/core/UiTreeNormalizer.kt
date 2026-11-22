package com.androiduse.core

import com.androiduse.protocol.UiNode
import com.androiduse.protocol.UiTreeResponse

/**
 * Normalizes and prunes raw accessibility node snapshots into a compact, AI-friendly
 * [UiTreeResponse] with deterministic pre-order node IDs (`n1`, `n2`, ...).
 */
object UiTreeNormalizer {

    fun normalize(
        rawRoots: List<RawUiNode>,
        options: UiTreeOptions = UiTreeOptions(),
        timestampMs: Long,
        windowCount: Int = rawRoots.size,
        preTruncated: Boolean = false,
    ): UiTreeResponse {
        options.validate()

        var truncated = preTruncated
        val prunedRoots = mutableListOf<IntermediateNode>()

        fun prune(node: RawUiNode, depth: Int): List<IntermediateNode> {
            if (!options.includeInvisible && !node.visibleToUser) {
                return emptyList()
            }

            val keptChildren = if (depth < options.maxDepth) {
                node.children.flatMap { child -> prune(child, depth + 1) }
            } else {
                if (node.children.isNotEmpty()) {
                    truncated = true
                }
                emptyList()
            }

            if (!options.pruneEmptyContainers) {
                return listOf(IntermediateNode(node, keptChildren))
            }

            val meaningful = isMeaningful(node)
            val hasArea = node.bounds.width > 0 && node.bounds.height > 0

            return when {
                meaningful && (hasArea || keptChildren.isNotEmpty()) ->
                    listOf(IntermediateNode(node, keptChildren))

                keptChildren.isNotEmpty() ->
                    // Collapse empty non-interactive layout wrappers by lifting their children.
                    keptChildren

                else ->
                    emptyList()
            }
        }

        for (root in rawRoots) {
            prunedRoots += prune(root, depth = 0)
        }

        // Second pass: assign dense pre-order IDs and enforce maxNodes budget.
        var assignedCount = 0
        fun materialize(node: IntermediateNode): UiNode? {
            if (assignedCount >= options.maxNodes) {
                truncated = true
                return null
            }
            assignedCount++
            val id = "n$assignedCount"
            val children = mutableListOf<UiNode>()
            for (child in node.children) {
                val materialized = materialize(child)
                if (materialized != null) {
                    children += materialized
                } else {
                    break
                }
            }
            val raw = node.raw
            return UiNode(
                nodeId = id,
                packageName = raw.packageName?.takeIf { it.isNotBlank() },
                className = raw.className?.takeIf { it.isNotBlank() },
                resourceId = raw.resourceId?.takeIf { it.isNotBlank() },
                text = raw.text?.takeIf { it.isNotBlank() },
                contentDescription = raw.contentDescription?.takeIf { it.isNotBlank() },
                hintText = raw.hintText?.takeIf { it.isNotBlank() },
                bounds = raw.bounds,
                center = raw.bounds.center(),
                clickable = raw.clickable,
                longClickable = raw.longClickable,
                editable = raw.editable,
                scrollable = raw.scrollable,
                checkable = raw.checkable,
                checked = raw.checked,
                focused = raw.focused,
                enabled = raw.enabled,
                selected = raw.selected,
                visibleToUser = raw.visibleToUser,
                children = children,
            )
        }

        val finalRoots = mutableListOf<UiNode>()
        for (root in prunedRoots) {
            val materialized = materialize(root)
            if (materialized != null) {
                finalRoots += materialized
            } else {
                break
            }
        }

        val activePackage = rawRoots.firstOrNull()?.packageName?.takeIf { it.isNotBlank() }
            ?: finalRoots.firstOrNull()?.packageName

        return UiTreeResponse(
            packageName = activePackage,
            windowCount = windowCount,
            nodeCount = assignedCount,
            truncated = truncated,
            timestampMs = timestampMs,
            roots = finalRoots,
        )
    }

    private fun isMeaningful(node: RawUiNode): Boolean {
        return !node.text.isNullOrBlank() ||
            !node.contentDescription.isNullOrBlank() ||
            !node.hintText.isNullOrBlank() ||
            !node.resourceId.isNullOrBlank() ||
            node.clickable ||
            node.longClickable ||
            node.editable ||
            node.scrollable ||
            node.checkable ||
            node.focused
    }

    private data class IntermediateNode(
        val raw: RawUiNode,
        val children: List<IntermediateNode>,
    )
}
