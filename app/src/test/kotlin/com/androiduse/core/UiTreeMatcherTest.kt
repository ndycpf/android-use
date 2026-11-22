package com.androiduse.core

import com.androiduse.protocol.UiBounds
import com.androiduse.protocol.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiTreeMatcherTest {

    private val sampleTree = listOf(
        UiNode(
            nodeId = "n1",
            className = "android.widget.LinearLayout",
            bounds = UiBounds(0, 0, 1080, 2400),
            center = UiBounds(0, 0, 1080, 2400).center(),
            children = listOf(
                UiNode(
                    nodeId = "n2",
                    resourceId = "com.google.android.gm:id/compose_button",
                    text = "作成",
                    contentDescription = "作成",
                    bounds = UiBounds(777, 2292, 1017, 2439),
                    center = UiBounds(777, 2292, 1017, 2439).center(),
                    clickable = true,
                ),
                UiNode(
                    nodeId = "n3",
                    resourceId = "com.google.android.gm:id/open_search",
                    hintText = "Search in mail",
                    bounds = UiBounds(158, 121, 914, 268),
                    center = UiBounds(158, 121, 914, 268).center(),
                    editable = true,
                ),
            ),
        ),
    )

    @Test
    fun `matches text, hintText, and resourceId case-insensitively`() {
        assertEquals("n2", UiTreeMatcher.findFirstMatch(sampleTree, text = "作成")?.nodeId)
        assertEquals("n3", UiTreeMatcher.findFirstMatch(sampleTree, text = "search in")?.nodeId)
        assertEquals("n2", UiTreeMatcher.findFirstMatch(sampleTree, resourceId = "compose_button")?.nodeId)
        assertEquals(
            "n2",
            UiTreeMatcher.findFirstMatch(sampleTree, text = "作成", resourceId = "compose_button")?.nodeId,
        )
    }

    @Test
    fun `returns null when condition does not match`() {
        assertNull(UiTreeMatcher.findFirstMatch(sampleTree, text = "not-present"))
        assertNull(UiTreeMatcher.findFirstMatch(sampleTree, text = "作成", resourceId = "open_search"))
    }
}
