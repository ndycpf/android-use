package com.androiduse.core

import com.androiduse.protocol.UiNode

/**
 * Pure tree search helper for `POST /v1/wait` and `POST /v1/actions/tap-element`.
 */
object UiTreeMatcher {

    fun findFirstMatch(
        roots: List<UiNode>,
        text: String? = null,
        resourceId: String? = null,
    ): UiNode? = findAllMatches(
        roots = roots,
        text = text,
        resourceId = resourceId,
    ).firstOrNull()

    fun findByNodeId(
        roots: List<UiNode>,
        nodeId: String,
    ): UiNode? {
        val targetId = nodeId.trim()
        if (targetId.isEmpty()) return null

        fun search(node: UiNode): UiNode? {
            if (node.nodeId == targetId) return node
            for (child in node.children) {
                search(child)?.let { return it }
            }
            return null
        }

        for (root in roots) {
            search(root)?.let { return it }
        }
        return null
    }

    fun findAllMatches(
        roots: List<UiNode>,
        text: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null,
        exact: Boolean = false,
        clickableOnly: Boolean = false,
    ): List<UiNode> {
        val textQuery = text?.takeIf { it.isNotEmpty() }
        val descQuery = contentDescription?.takeIf { it.isNotEmpty() }
        val resIdQuery = resourceId?.takeIf { it.isNotEmpty() }
        if (textQuery == null && descQuery == null && resIdQuery == null) {
            return emptyList()
        }

        val matches = mutableListOf<UiNode>()

        fun search(node: UiNode) {
            if (matchesNode(node, textQuery, descQuery, resIdQuery, exact, clickableOnly)) {
                matches += node
            }
            for (child in node.children) {
                search(child)
            }
        }

        for (root in roots) {
            search(root)
        }
        return matches
    }

    private fun matchesNode(
        node: UiNode,
        textQuery: String?,
        descQuery: String?,
        resIdQuery: String?,
        exact: Boolean,
        clickableOnly: Boolean,
    ): Boolean {
        if (clickableOnly && !isInteractive(node)) {
            return false
        }

        val textOk = textQuery == null ||
            matchesString(node.text, textQuery, exact) ||
            matchesString(node.contentDescription, textQuery, exact) ||
            matchesString(node.hintText, textQuery, exact)

        val descOk = descQuery == null ||
            matchesString(node.contentDescription, descQuery, exact)

        val resIdOk = resIdQuery == null ||
            matchesString(node.resourceId, resIdQuery, exact)

        return textOk && descOk && resIdOk
    }

    private fun matchesString(value: String?, query: String, exact: Boolean): Boolean {
        if (value == null) return false
        return if (exact) {
            value.equals(query, ignoreCase = true)
        } else {
            value.contains(query, ignoreCase = true)
        }
    }

    private fun isInteractive(node: UiNode): Boolean =
        node.clickable || node.longClickable || node.editable || node.checkable
}
