package io.devtech.integration.debug

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DebugModelsTest {

    @Test
    fun `DebugTrace generates unique 8-char ids`() {
        val ids = (1..100).map { DebugTrace(operationId = "test", method = "GET", path = "/test", request = TraceData()).id }.toSet()
        assertEquals(100, ids.size) // all unique
        ids.forEach { assertEquals(8, it.length) }
    }

    @Test
    fun `Breakpoint defaults to BEFORE_HANDLER`() {
        val bp = Breakpoint(operationId = "echo")
        assertEquals(BreakPhase.BEFORE_HANDLER, bp.phase)
        assertTrue(bp.enabled)
        assertNull(bp.condition)
    }

    @Test
    fun `DebugSession has CompletableFuture for signaling`() {
        val session = DebugSession(
            traceId = "t1",
            operationId = "echo",
            breakpointId = "bp1",
            phase = BreakPhase.BEFORE_HANDLER,
        )
        assertFalse(session.resumeSignal.isDone)

        session.resumeSignal.complete(SessionAction.Resume)
        assertTrue(session.resumeSignal.isDone)
        assertInstanceOf(SessionAction.Resume::class.java, session.resumeSignal.get())
    }

    @Test
    fun `SessionAction sealed variants`() {
        val resume: SessionAction = SessionAction.Resume
        val modify: SessionAction = SessionAction.ModifyAndResume(mapOf("key" to "value"))
        val abort: SessionAction = SessionAction.Abort

        assertInstanceOf(SessionAction.Resume::class.java, resume)
        assertInstanceOf(SessionAction.ModifyAndResume::class.java, modify)
        assertEquals("value", (modify as SessionAction.ModifyAndResume).newBody["key"])
        assertInstanceOf(SessionAction.Abort::class.java, abort)
    }

    @Test
    fun `DebugConfig defaults`() {
        val config = DebugConfig()
        assertFalse(config.enabled)
        assertEquals(100, config.traceHistorySize)
        assertEquals(300_000, config.sessionTimeoutMs)
    }

    @Test
    fun `TraceData defaults to empty`() {
        val data = TraceData()
        assertTrue(data.body.isEmpty())
        assertTrue(data.headers.isEmpty())
        assertNull(data.statusCode)
    }

    @Test
    fun `TraceStatus values`() {
        assertEquals(5, TraceStatus.entries.size)
        assertNotNull(TraceStatus.valueOf("IN_PROGRESS"))
        assertNotNull(TraceStatus.valueOf("COMPLETED"))
        assertNotNull(TraceStatus.valueOf("ERROR"))
        assertNotNull(TraceStatus.valueOf("PAUSED"))
        assertNotNull(TraceStatus.valueOf("ABORTED"))
    }
}
