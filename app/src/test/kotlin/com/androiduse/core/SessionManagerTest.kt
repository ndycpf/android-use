package com.androiduse.core

import com.androiduse.protocol.ErrorCode
import com.androiduse.protocol.StartSessionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SessionManagerTest {

    @Test
    fun `start, touch, idle expiry, and end notify listeners accurately`() {
        var now = 1_000L
        val listenerEvents = mutableListOf<String>()
        val manager = SessionManager(
            clockMs = { now },
            idGenerator = { "sess-1" },
        )
        manager.setStateChangeListener { active ->
            listenerEvents += if (active != null) "started:${active.sessionId}" else "ended"
        }

        // No session initially -> throws 409 SESSION_NOT_ACTIVE.
        try {
            manager.requireActiveSessionAndTouch()
            fail("Expected SESSION_NOT_ACTIVE")
        } catch (e: AndroidUseException) {
            assertEquals(ErrorCode.SESSION_NOT_ACTIVE, e.code)
            assertEquals(409, e.httpStatus)
        }

        val started = manager.startSession(
            StartSessionRequest(label = "demo", keepAwake = true, idleTimeoutMs = 60_000L),
        )
        assertEquals("sess-1", started.sessionId)
        assertEquals("demo", started.label)
        assertNotNull(manager.currentSession())

        // Touch at +40s extends expiry window to 101_000L.
        now = 41_000L
        val touched = manager.requireActiveSessionAndTouch()
        assertEquals(41_000L, touched.lastActivityMs)

        // At +90s (49s since last touch) still active.
        now = 90_000L
        assertFalse(manager.checkIdleTimeout())
        assertNotNull(manager.currentSession())

        // At +101s (60s since last touch) expires automatically.
        now = 101_000L
        assertTrue(manager.checkIdleTimeout())
        assertNull(manager.currentSession())
        assertEquals(listOf("started:sess-1", "ended"), listenerEvents)
    }

    @Test
    fun `rejects out-of-range idleTimeoutMs with 400 INVALID_REQUEST`() {
        val manager = SessionManager()
        try {
            manager.startSession(StartSessionRequest(idleTimeoutMs = 500L))
            fail("Expected INVALID_REQUEST")
        } catch (e: AndroidUseException) {
            assertEquals(ErrorCode.INVALID_REQUEST, e.code)
            assertEquals(400, e.httpStatus)
        }
    }
}
