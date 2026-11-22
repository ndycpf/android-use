package com.androiduse.core

import com.androiduse.protocol.UiBounds
import com.androiduse.protocol.UiMark
import com.androiduse.protocol.UiNode
import com.androiduse.protocol.UiTreeResponse
import kotlin.math.max
import kotlin.math.min

/**
 * Selects visible, interactive UI elements from a normalized [UiTreeResponse] and assigns
 * compact 1-based Set-of-Mark (`markId`) indices in reading order.
 */
object SetOfMarkSelector {

    const val DEFAULT_MAX_MARKS: Int = 100
    private const val MIN_VISIBLE_DIMENSION_PX: Int = 12
    private const val MAX_CONTAINER_SCREEN_RATIO: Double = 0.85
    private const val OVERLAP_DEDUP_IOU_THRESHOLD: Double = 0.85
    private const val READING_ORDER_ROW_BAND_PX: Int = 24

    fun select(
        tree: UiTreeResponse,
        displayWidth: Int,
        displayHeight: Int,
        maxMarks: Int = DEFAULT_MAX_MARKS,
    ): List<UiMark> {
        if (displayWidth <= 0 || displayHeight <= 0 || maxMarks <= 0) {
            return emptyList()
        }

        val screenArea = displayWidth.toDouble() * displayHeight.toDouble()
        val candidates = mutableListOf<Candidate>()

        fun visit(node: UiNode, depth: Int) {
            if (isMarkable(node)) {
                val clipped = node.bounds.intersect(displayWidth, displayHeight)
                if (clipped != null &&
                    clipped.width >= MIN_VISIBLE_DIMENSION_PX &&
                    clipped.height >= MIN_VISIBLE_DIMENSION_PX
                ) {
                    val areaRatio = (clipped.width.toDouble() * clipped.height.toDouble()) / screenArea
                    val isFullScreenWrapper = areaRatio >= MAX_CONTAINER_SCREEN_RATIO && node.children.isNotEmpty()
                    if (!isFullScreenWrapper) {
                        val inheritedText = node.text ?: firstDescendantText(node)
                        val inheritedDesc = node.contentDescription ?: firstDescendantDescription(node)
                        candidates += Candidate(
                            node = node,
                            clippedBounds = clipped,
                            depth = depth,
                            text = inheritedText,
                            contentDescription = inheritedDesc,
                        )
                    }
                }
            }
            for (child in node.children) {
                visit(child, depth + 1)
            }
        }

        for (root in tree.roots) {
            visit(root, depth = 0)
        }

        val deduped = deduplicateOverlapping(candidates)

        val sorted = deduped.sortedWith(
            compareBy<Candidate>(
                { it.clippedBounds.top / READING_ORDER_ROW_BAND_PX },
                { it.clippedBounds.left },
                { it.clippedBounds.top },
            ),
        )

        return sorted.take(maxMarks).mapIndexed { index, candidate ->
            val node = candidate.node
            UiMark(
                markId = index + 1,
                nodeId = node.nodeId,
                text = candidate.text,
                contentDescription = candidate.contentDescription,
                hintText = node.hintText,
                resourceId = node.resourceId,
                className = node.className,
                bounds = candidate.clippedBounds,
                center = candidate.clippedBounds.center(),
                clickable = node.clickable,
                longClickable = node.longClickable,
                editable = node.editable,
                scrollable = node.scrollable,
                checkable = node.checkable,
                checked = node.checked,
            )
        }
    }

    private fun isMarkable(node: UiNode): Boolean {
        if (!node.visibleToUser || !node.enabled) return false
        return node.clickable ||
            node.longClickable ||
            node.editable ||
            node.checkable
    }

    private fun deduplicateOverlapping(candidates: List<Candidate>): List<Candidate> {
        val kept = mutableListOf<Candidate>()
        for (candidate in candidates) {
            val duplicateIdx = kept.indexOfFirst { existing ->
                computeIoU(existing.clippedBounds, candidate.clippedBounds) >= OVERLAP_DEDUP_IOU_THRESHOLD
            }
            if (duplicateIdx == -1) {
                kept += candidate
            } else {
                val existing = kept[duplicateIdx]
                if (preferenceScore(candidate) > preferenceScore(existing)) {
                    kept[duplicateIdx] = candidate
                }
            }
        }
        return kept
    }

    private fun preferenceScore(candidate: Candidate): Int {
        var score = candidate.depth * 2
        if (!candidate.text.isNullOrBlank()) score += 20
        if (!candidate.contentDescription.isNullOrBlank()) score += 15
        if (!candidate.node.resourceId.isNullOrBlank()) score += 5
        if (candidate.node.editable) score += 10
        if (candidate.node.clickable) score += 8
        return score
    }

    private fun computeIoU(a: UiBounds, b: UiBounds): Double {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interW = (interRight - interLeft).coerceAtLeast(0)
        val interH = (interBottom - interTop).coerceAtLeast(0)
        val interArea = interW.toDouble() * interH.toDouble()
        if (interArea <= 0.0) return 0.0
        val areaA = a.width.toDouble() * a.height.toDouble()
        val areaB = b.width.toDouble() * b.height.toDouble()
        val union = areaA + areaB - interArea
        return if (union <= 0.0) 0.0 else interArea / union
    }

    private fun firstDescendantText(node: UiNode): String? {
        for (child in node.children) {
            if (!child.text.isNullOrBlank()) return child.text
            firstDescendantText(child)?.let { return it }
        }
        return null
    }

    private fun firstDescendantDescription(node: UiNode): String? {
        for (child in node.children) {
            if (!child.contentDescription.isNullOrBlank()) return child.contentDescription
            firstDescendantDescription(child)?.let { return it }
        }
        return null
    }

    private data class Candidate(
        val node: UiNode,
        val clippedBounds: UiBounds,
        val depth: Int,
        val text: String?,
        val contentDescription: String?,
    )
}
