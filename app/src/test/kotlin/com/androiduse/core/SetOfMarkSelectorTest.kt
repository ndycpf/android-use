package com.androiduse.core

import com.androiduse.protocol.UiBounds
import com.androiduse.protocol.UiNode
import com.androiduse.protocol.UiPoint
import com.androiduse.protocol.UiTreeResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetOfMarkSelectorTest {

    @Test
    fun `selects interactive visible nodes in reading order and clips bounds to viewport`() {
        val tree = UiTreeResponse(
            packageName = "com.example.app",
            windowCount = 1,
            nodeCount = 6,
            timestampMs = 1L,
            roots = listOf(
                // Full-screen clickable container with children should be skipped.
                node(
                    id = "n1",
                    bounds = UiBounds(0, 0, 1080, 2400),
                    clickable = true,
                    children = listOf(
                        // Bottom button (listed first in tree, should sort last in reading order).
                        node(
                            id = "n2",
                            text = "Confirm",
                            bounds = UiBounds(100, 2200, 980, 2500), // partially off-screen bottom
                            clickable = true,
                        ),
                        // Top-right icon button.
                        node(
                            id = "n3",
                            desc = "Settings",
                            bounds = UiBounds(900, 100, 1040, 220),
                            clickable = true,
                        ),
                        // Top-left clickable row wrapping a non-clickable TextView.
                        node(
                            id = "n4",
                            bounds = UiBounds(40, 105, 500, 215),
                            clickable = true,
                            children = listOf(
                                node(
                                    id = "n5",
                                    text = "Inbox",
                                    bounds = UiBounds(60, 120, 300, 200),
                                    clickable = false,
                                ),
                            ),
                        ),
                        // Invisible or off-screen nodes should be excluded.
                        node(
                            id = "n6",
                            text = "Hidden",
                            bounds = UiBounds(100, 400, 300, 500),
                            clickable = true,
                            visible = false,
                        ),
                        node(
                            id = "n7",
                            text = "OffScreen",
                            bounds = UiBounds(100, 2500, 300, 2700),
                            clickable = true,
                        ),
                        // Tiny 4x4 divider should be ignored.
                        node(
                            id = "n8",
                            bounds = UiBounds(10, 300, 14, 304),
                            clickable = true,
                        ),
                    ),
                ),
            ),
        )

        val marks = SetOfMarkSelector.select(tree, displayWidth = 1080, displayHeight = 2400)
        assertEquals(3, marks.size)

        // Mark #1: top-left row (inherits child text "Inbox")
        assertEquals(1, marks[0].markId)
        assertEquals("n4", marks[0].nodeId)
        assertEquals("Inbox", marks[0].text)

        // Mark #2: top-right Settings icon
        assertEquals(2, marks[1].markId)
        assertEquals("n3", marks[1].nodeId)
        assertEquals("Settings", marks[1].contentDescription)

        // Mark #3: bottom Confirm button clipped to [2200..2400], center y = 2300
        assertEquals(3, marks[2].markId)
        assertEquals("n2", marks[2].nodeId)
        assertEquals(UiBounds(100, 2200, 980, 2400), marks[2].bounds)
        assertEquals(UiPoint(540, 2300), marks[2].center)
    }

    @Test
    fun `deduplicates overlapping clickable parent and child keeping the labeled node`() {
        val tree = UiTreeResponse(
            packageName = "com.example.app",
            windowCount = 1,
            nodeCount = 2,
            timestampMs = 1L,
            roots = listOf(
                node(
                    id = "n1",
                    bounds = UiBounds(100, 500, 400, 620),
                    clickable = true,
                    children = listOf(
                        node(
                            id = "n2",
                            text = "Play",
                            bounds = UiBounds(102, 502, 398, 618),
                            clickable = true,
                        ),
                    ),
                ),
            ),
        )

        val marks = SetOfMarkSelector.select(tree, displayWidth = 1080, displayHeight = 2400)
        assertEquals(1, marks.size)
        assertEquals("n2", marks[0].nodeId)
        assertEquals("Play", marks[0].text)
        assertTrue(marks[0].clickable)
    }

    private fun node(
        id: String,
        text: String? = null,
        desc: String? = null,
        bounds: UiBounds,
        clickable: Boolean = false,
        visible: Boolean = true,
        children: List<UiNode> = emptyList(),
    ): UiNode = UiNode(
        nodeId = id,
        text = text,
        contentDescription = desc,
        bounds = bounds,
        center = bounds.center(),
        clickable = clickable,
        visibleToUser = visible,
        children = children,
    )
}
