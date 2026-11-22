package com.androiduse.core

import com.androiduse.protocol.UiNode

/**
 * Pure tree search helper for `POST /v1/wait` condition matching.
 */
object UiTreeMatcher {

    fun findFirstMatch(
        roots: List<UiNode>,
        text: String? = null,
        resourceId: String? = null,
    ): UiNode? {
        val textQuery = text?.takeIf { it.isNotEmpty() }
        val resIdQuery = resourceId?.takeIf { it.isNotEmpty() }
        if (textQuery == null && resIdQuery == null) return null

        fun search(node: UiNode): UiNode? {
            if (matches(node, textQuery, resIdQuery)) {
                return node
            }
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

    private fun matches(node: UiNode, textQuery: String?, resIdQuery: String?): Boolean {
        val textOk = textQuery == null ||
            node.text?.contains(textQuery, ignoreCase = true) == true ||
            node.contentDescription?.contains(textQuery, ignoreCase = true) == true ||
            node.hintText?.contains(textQuery, ignoreCase = true) == true

        val resIdOk = resIdQuery == null ||
            node.resourceId?.contains(resIdQuery, ignoreCase = true) == true

        return textOk && resIdOk
    }
}
