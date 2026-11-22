package com.androiduse.core

import com.androiduse.protocol.UiBounds
import com.androiduse.protocol.UiPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeNormalizerTest {

    @Test
    fun `prunes empty non-interactive containers and lifts meaningful children with dense IDs`() {
        val raw = RawUiNode(
            packageName = "com.example.shop",
            className = "android.widget.FrameLayout",
            bounds = UiBounds(0, 0, 1080, 2400),
            children = listOf(
                RawUiNode(
                    className = "android.widget.LinearLayout",
                    bounds = UiBounds(0, 0, 1080, 500),
                    children = listOf(
                        RawUiNode(
                            className = "android.widget.TextView",
                            text = "Search",
                            bounds = UiBounds(100, 100, 500, 200),
                            clickable = true,
                        ),
                        RawUiNode(
                            className = "android.view.View",
                            bounds = UiBounds(0, 200, 1080, 202),
                        ),
                    ),
                ),
                RawUiNode(
                    className = "android.widget.Button",
                    contentDescription = "Checkout",
                    bounds = UiBounds(200, 2000, 880, 2200),
                    clickable = true,
                ),
            ),
        )

        val response = UiTreeNormalizer.normalize(
            rawRoots = listOf(raw),
            options = UiTreeOptions(pruneEmptyContainers = true),
            timestampMs = 1_234L,
        )

        assertEquals("com.example.shop", response.packageName)
        assertEquals(1, response.windowCount)
        assertEquals(2, response.nodeCount)
        assertFalse(response.truncated)
        assertEquals(2, response.roots.size)

        val first = response.roots[0]
        assertEquals("n1", first.nodeId)
        assertEquals("Search", first.text)
        assertEquals(UiPoint(300, 150), first.center)
        assertTrue(first.clickable)

        val second = response.roots[1]
        assertEquals("n2", second.nodeId)
        assertEquals("Checkout", second.contentDescription)
        assertEquals(UiPoint(540, 2100), second.center)
    }

    @Test
    fun `preserves empty layout wrappers when pruneEmptyContainers is false`() {
        val raw = RawUiNode(
            packageName = "com.example.app",
            className = "android.widget.FrameLayout",
            bounds = UiBounds(0, 0, 1080, 2400),
            children = listOf(
                RawUiNode(
                    className = "android.widget.TextView",
                    text = "Hello",
                    bounds = UiBounds(10, 20, 110, 60),
                ),
            ),
        )

        val response = UiTreeNormalizer.normalize(
            rawRoots = listOf(raw),
            options = UiTreeOptions(pruneEmptyContainers = false),
            timestampMs = 50L,
        )

        assertEquals(2, response.nodeCount)
        assertEquals(1, response.roots.size)
        assertEquals("n1", response.roots[0].nodeId)
        assertEquals("android.widget.FrameLayout", response.roots[0].className)
        assertEquals("n2", response.roots[0].children[0].nodeId)
        assertEquals("Hello", response.roots[0].children[0].text)
    }

    @Test
    fun `filters invisible nodes unless includeInvisible is true`() {
        val raw = RawUiNode(
            packageName = "com.example.app",
            bounds = UiBounds(0, 0, 1080, 2400),
            children = listOf(
                RawUiNode(
                    text = "Visible",
                    bounds = UiBounds(0, 0, 100, 100),
                    visibleToUser = true,
                ),
                RawUiNode(
                    text = "Hidden",
                    bounds = UiBounds(0, 100, 100, 200),
                    visibleToUser = false,
                ),
            ),
        )

        val defaultResponse = UiTreeNormalizer.normalize(
            rawRoots = listOf(raw),
            options = UiTreeOptions(includeInvisible = false),
            timestampMs = 1L,
        )
        assertEquals(1, defaultResponse.nodeCount)
        assertEquals("Visible", defaultResponse.roots[0].text)

        val withInvisible = UiTreeNormalizer.normalize(
            rawRoots = listOf(raw),
            options = UiTreeOptions(includeInvisible = true),
            timestampMs = 1L,
        )
        assertEquals(2, withInvisible.nodeCount)
        assertEquals(listOf("Visible", "Hidden"), withInvisible.roots.map { it.text })
    }

    @Test
    fun `enforces maxNodes and maxDepth caps and sets truncated flag`() {
        val manyButtons = (1..5).map { idx ->
            RawUiNode(
                text = "Button $idx",
                bounds = UiBounds(0, idx * 100, 200, idx * 100 + 80),
                clickable = true,
            )
        }
        val cappedNodes = UiTreeNormalizer.normalize(
            rawRoots = manyButtons,
            options = UiTreeOptions(maxNodes = 3),
            timestampMs = 1L,
        )
        assertEquals(3, cappedNodes.nodeCount)
        assertTrue(cappedNodes.truncated)
        assertEquals(listOf("n1", "n2", "n3"), cappedNodes.roots.map { it.nodeId })

        val deepTree = RawUiNode(
            text = "Level 0",
            bounds = UiBounds(0, 0, 500, 500),
            children = listOf(
                RawUiNode(
                    text = "Level 1",
                    bounds = UiBounds(0, 0, 400, 400),
                    children = listOf(
                        RawUiNode(
                            text = "Level 2",
                            bounds = UiBounds(0, 0, 300, 300),
                        ),
                    ),
                ),
            ),
        )
        val cappedDepth = UiTreeNormalizer.normalize(
            rawRoots = listOf(deepTree),
            options = UiTreeOptions(maxDepth = 1),
            timestampMs = 1L,
        )
        assertEquals(2, cappedDepth.nodeCount)
        assertTrue(cappedDepth.truncated)
        assertTrue(cappedDepth.roots[0].children[0].children.isEmpty())
    }
}
