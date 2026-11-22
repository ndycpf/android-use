package com.androiduse.core

import com.androiduse.protocol.UiBounds
import com.androiduse.protocol.UiNode
import com.androiduse.protocol.UiTreeResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiChangeTrackerTest {

    @Test
    fun `awaitEventSince wakes up immediately when an accessibility event arrives`() = runTest {
        val tracker = UiChangeTracker(clockMs = { testScheduler.currentTime })
        val initialSeq = tracker.currentSequence()

        val waiter = async {
            tracker.awaitEventSince(sinceSequence = initialSeq, timeoutMs = 5_000L)
        }
        delay(40L)
        tracker.recordEvent(
            eventType = 32,
            packageName = "com.google.android.apps.maps",
            isWindowStateChange = true,
        )

        val event = waiter.await()
        assertNotNull(event)
        assertEquals("com.google.android.apps.maps", event!!.packageName)
        assertTrue(event.isWindowStateChange)
        // Woke right around 40ms instead of sleeping the full 5000ms timeout.
        assertTrue("Expected fast wakeup, elapsed=${testScheduler.currentTime}ms", testScheduler.currentTime < 200L)
    }

    @Test
    fun `awaitQuiet waits for event burst to stop then returns once quiet period elapses`() = runTest {
        val tracker = UiChangeTracker(clockMs = { testScheduler.currentTime })

        val quietJob = async {
            tracker.awaitQuiet(quietPeriodMs = 150L, maxWaitMs = 2_000L)
        }
        // Emit two events 50ms apart, then stop.
        delay(50L)
        tracker.recordEvent(eventType = 2048, packageName = "com.example.app")
        delay(50L)
        tracker.recordEvent(eventType = 2048, packageName = "com.example.app")

        val settled = quietJob.await()
        assertTrue(settled)
        // Last event at 100ms + 150ms quiet = ~250ms total.
        assertTrue("Expected settle around 250ms, actual=${testScheduler.currentTime}ms", testScheduler.currentTime in 240L..350L)
    }

    @Test
    fun `UiTreeFingerprint ignores 1px jitter but changes on text or checked mutations`() {
        fun tree(text: String, checked: Boolean, top: Int) = UiTreeResponse(
            packageName = "com.android.settings",
            windowCount = 1,
            nodeCount = 1,
            timestampMs = 1L,
            roots = listOf(
                UiNode(
                    nodeId = "n1",
                    text = text,
                    bounds = UiBounds(10, top, 300, top + 80),
                    center = UiBounds(10, top, 300, top + 80).center(),
                    clickable = true,
                    checkable = true,
                    checked = checked,
                ),
            ),
        )

        val h1 = UiTreeFingerprint.compute(tree("Wi-Fi", checked = false, top = 100))
        val hJitter = UiTreeFingerprint.compute(tree("Wi-Fi", checked = false, top = 101))
        val hChecked = UiTreeFingerprint.compute(tree("Wi-Fi", checked = true, top = 100))
        val hText = UiTreeFingerprint.compute(tree("Bluetooth", checked = false, top = 100))

        assertEquals(h1, hJitter)
        assertNotEquals(h1, hChecked)
        assertNotEquals(h1, hText)
    }
}
