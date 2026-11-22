package com.androiduse.core

import com.androiduse.protocol.UiNode
import com.androiduse.protocol.UiTreeResponse
import java.security.MessageDigest

/**
 * Computes a deterministic, jitter-tolerant fingerprint for a [UiTreeResponse]
 * so screen transition waits can verify genuine visible UI changes.
 */
object UiTreeFingerprint {

    private const val BOUNDS_BUCKET_PX: Int = 8
    private const val MAX_NODES_HASHED: Int = 250

    fun compute(tree: UiTreeResponse): String {
        val sb = StringBuilder(1024)
        sb.append("pkg=").append(tree.packageName.orEmpty()).append('\n')

        var count = 0
        fun visit(node: UiNode) {
            if (count >= MAX_NODES_HASHED) return
            if (!node.visibleToUser) return
            count++

            sb.append(node.resourceId.orEmpty()).append('|')
                .append(node.text.orEmpty()).append('|')
                .append(node.contentDescription.orEmpty()).append('|')
                .append(node.hintText.orEmpty()).append('|')
                .append(if (node.clickable) 'C' else '-')
                .append(if (node.editable) 'E' else '-')
                .append(if (node.checkable) 'K' else '-')
                .append(if (node.checked) '1' else '0')
                .append(if (node.selected) 'S' else '-')
                .append('|')
                .append(node.bounds.left / BOUNDS_BUCKET_PX).append(',')
                .append(node.bounds.top / BOUNDS_BUCKET_PX).append(',')
                .append(node.bounds.right / BOUNDS_BUCKET_PX).append(',')
                .append(node.bounds.bottom / BOUNDS_BUCKET_PX)
                .append('\n')

            for (child in node.children) {
                visit(child)
                if (count >= MAX_NODES_HASHED) return
            }
        }

        for (root in tree.roots) {
            visit(root)
            if (count >= MAX_NODES_HASHED) break
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
