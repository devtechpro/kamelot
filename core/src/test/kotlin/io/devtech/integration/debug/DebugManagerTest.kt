package io.devtech.integration.debug

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DebugManagerTest {

    private lateinit var manager: DebugManager

    @BeforeEach
    fun setup() {
        manager = DebugManager(DebugConfig(
            enabled = true,
            traceHistorySize = 5,
            sessionTimeoutMs = 2_000,
        ))
    }

    @Nested
    inner class Traces {

        @Test
        fun `addTrace and getTrace round-trip`() {
            val trace = makeTrace("t1", "echo")
            manager.addTrace(trace)

            val found = manager.getTrace("t1")
            assertNotNull(found)
            assertEquals("echo", found!!.operationId)
            assertEquals("POST", found.method)
        }

        @Test
        fun `listTraces returns newest first`() {
            manager.addTrace(makeTrace("t1", "echo"))
            manager.addTrace(makeTrace("t2", "health"))
            manager.addTrace(makeTrace("t3", "echo"))

            val traces = manager.listTraces()
            assertEquals(3, traces.size)
            assertEquals("t3", traces[0].id)
            assertEquals("t2", traces[1].id)
            assertEquals("t1", traces[2].id)
        }

        @Test
        fun `listTraces respects limit`() {
            repeat(5) { i ->
                manager.addTrace(makeTrace("t$i", "echo"))
            }

            val traces = manager.listTraces(limit = 2)
            assertEquals(2, traces.size)
        }

        @Test
        fun `trace history bounded by traceHistorySize`() {
            // config has traceHistorySize = 5
            repeat(10) { i ->
                manager.addTrace(makeTrace("t$i", "echo"))
            }

            val traces = manager.listTraces(limit = 100)
            assertEquals(5, traces.size)
            // Oldest traces should have been evicted
            assertNull(manager.getTrace("t0"))
            assertNull(manager.getTrace("t4"))
            // Newest should exist
            assertNotNull(manager.getTrace("t9"))
            assertNotNull(manager.getTrace("t5"))
        }

        @Test
        fun `clearTraces removes all`() {
            manager.addTrace(makeTrace("t1", "echo"))
            manager.addTrace(makeTrace("t2", "health"))
            manager.clearTraces()

            assertEquals(0, manager.listTraces().size)
            assertNull(manager.getTrace("t1"))
        }

        @Test
        fun `getTrace returns null for unknown id`() {
            assertNull(manager.getTrace("nonexistent"))
        }
    }

    @Nested
    inner class Breakpoints {

        @Test
        fun `addBreakpoint and listBreakpoints`() {
            val bp = manager.addBreakpoint(Breakpoint(id = "bp1", operationId = "echo"))
            assertEquals("bp1", bp.id)
            assertEquals("echo", bp.operationId)
            assertEquals(BreakPhase.BEFORE_HANDLER, bp.phase)

            val list = manager.listBreakpoints()
            assertEquals(1, list.size)
            assertEquals("bp1", list[0].id)
        }

        @Test
        fun `removeBreakpoint returns true for existing`() {
            manager.addBreakpoint(Breakpoint(id = "bp1", operationId = "echo"))
            assertTrue(manager.removeBreakpoint("bp1"))
            assertEquals(0, manager.listBreakpoints().size)
        }

        @Test
        fun `removeBreakpoint returns false for unknown`() {
            assertFalse(manager.removeBreakpoint("nonexistent"))
        }

        @Test
        fun `clearBreakpoints removes all`() {
            manager.addBreakpoint(Breakpoint(id = "bp1", operationId = "echo"))
            manager.addBreakpoint(Breakpoint(id = "bp2", operationId = "health"))
            manager.clearBreakpoints()
            assertEquals(0, manager.listBreakpoints().size)
        }

        @Test
        fun `checkBreakpoint matches operationId and phase`() {
            manager.addBreakpoint(Breakpoint(id = "bp1", operationId = "echo", phase = BreakPhase.BEFORE_HANDLER))
            manager.addBreakpoint(Breakpoint(id = "bp2", operationId = "health", phase = BreakPhase.AFTER_HANDLER))

            val match1 = manager.checkBreakpoint("echo", BreakPhase.BEFORE_HANDLER)
            assertNotNull(match1)
            assertEquals("bp1", match1!!.id)

            val match2 = manager.checkBreakpoint("health", BreakPhase.AFTER_HANDLER)
            assertNotNull(match2)
            assertEquals("bp2", match2!!.id)

            // Wrong phase — no match
            assertNull(manager.checkBreakpoint("echo", BreakPhase.AFTER_HANDLER))

            // Wrong operation — no match
            assertNull(manager.checkBreakpoint("unknown", BreakPhase.BEFORE_HANDLER))
        }

        @Test
        fun `checkBreakpoint skips disabled breakpoints`() {
            manager.addBreakpoint(Breakpoint(id = "bp1", operationId = "echo", enabled = false))
            assertNull(manager.checkBreakpoint("echo", BreakPhase.BEFORE_HANDLER))
        }
    }

    @Nested
    inner class Sessions {

        @Test
        fun `pauseAtBreakpoint blocks and resumeSession unblocks`() {
            val bp = Breakpoint(id = "bp1", operationId = "echo")
            val trace = makeTrace("t1", "echo")
            manager.addTrace(trace)

            val result = AtomicReference<SessionAction>()
            val started = CountDownLatch(1)

            // Pause on a background thread (simulates request thread)
            val thread = Thread {
                started.countDown()
                result.set(manager.pauseAtBreakpoint("t1", "echo", bp, mapOf("msg" to "hello")))
            }
            thread.start()
            assertTrue(started.await(1, TimeUnit.SECONDS))
            Thread.sleep(100) // let it enter the blocking wait

            // Should have 1 active session
            val sessions = manager.listSessions()
            assertEquals(1, sessions.size)
            assertEquals("echo", sessions[0].operationId)
            assertEquals(mapOf("msg" to "hello"), sessions[0].currentData)

            // Resume it
            assertTrue(manager.resumeSession(sessions[0].id))
            thread.join(2000)

            assertInstanceOf(SessionAction.Resume::class.java, result.get())
            assertEquals(0, manager.listSessions().size)
        }

        @Test
        fun `modifyAndResumeSession delivers modified body`() {
            val bp = Breakpoint(id = "bp1", operationId = "echo")
            manager.addTrace(makeTrace("t1", "echo"))

            val result = AtomicReference<SessionAction>()
            val started = CountDownLatch(1)

            val thread = Thread {
                started.countDown()
                result.set(manager.pauseAtBreakpoint("t1", "echo", bp, mapOf("msg" to "original")))
            }
            thread.start()
            assertTrue(started.await(1, TimeUnit.SECONDS))
            Thread.sleep(100)

            val sessions = manager.listSessions()
            assertEquals(1, sessions.size)

            val newBody = mapOf<String, Any?>("msg" to "modified")
            assertTrue(manager.modifyAndResumeSession(sessions[0].id, newBody))
            thread.join(2000)

            val action = result.get()
            assertInstanceOf(SessionAction.ModifyAndResume::class.java, action)
            assertEquals("modified", (action as SessionAction.ModifyAndResume).newBody["msg"])
        }

        @Test
        fun `abortSession delivers Abort action`() {
            val bp = Breakpoint(id = "bp1", operationId = "echo")
            manager.addTrace(makeTrace("t1", "echo"))

            val result = AtomicReference<SessionAction>()
            val started = CountDownLatch(1)

            val thread = Thread {
                started.countDown()
                result.set(manager.pauseAtBreakpoint("t1", "echo", bp, mapOf("msg" to "hello")))
            }
            thread.start()
            assertTrue(started.await(1, TimeUnit.SECONDS))
            Thread.sleep(100)

            val sessions = manager.listSessions()
            assertTrue(manager.abortSession(sessions[0].id))
            thread.join(2000)

            assertInstanceOf(SessionAction.Abort::class.java, result.get())
        }

        @Test
        fun `session times out and returns Abort`() {
            // Config has 2s timeout
            val bp = Breakpoint(id = "bp1", operationId = "echo")
            manager.addTrace(makeTrace("t1", "echo"))

            val result = AtomicReference<SessionAction>()
            val thread = Thread {
                result.set(manager.pauseAtBreakpoint("t1", "echo", bp, emptyMap()))
            }
            thread.start()
            thread.join(5000) // wait for timeout + margin

            assertInstanceOf(SessionAction.Abort::class.java, result.get())
            assertEquals(0, manager.listSessions().size)
        }

        @Test
        fun `resumeSession returns false for unknown id`() {
            assertFalse(manager.resumeSession("nonexistent"))
        }

        @Test
        fun `modifyAndResumeSession returns false for unknown id`() {
            assertFalse(manager.modifyAndResumeSession("nonexistent", emptyMap()))
        }

        @Test
        fun `abortSession returns false for unknown id`() {
            assertFalse(manager.abortSession("nonexistent"))
        }

        @Test
        fun `getSession returns session detail`() {
            val bp = Breakpoint(id = "bp1", operationId = "echo")
            manager.addTrace(makeTrace("t1", "echo"))

            val started = CountDownLatch(1)
            val thread = Thread {
                started.countDown()
                manager.pauseAtBreakpoint("t1", "echo", bp, mapOf("key" to "value"))
            }
            thread.start()
            assertTrue(started.await(1, TimeUnit.SECONDS))
            Thread.sleep(100)

            val sessions = manager.listSessions()
            val session = manager.getSession(sessions[0].id)
            assertNotNull(session)
            assertEquals("echo", session!!.operationId)
            assertEquals("bp1", session.breakpointId)
            assertEquals(BreakPhase.BEFORE_HANDLER, session.phase)
            assertEquals(mapOf("key" to "value"), session.currentData)

            // Clean up
            manager.abortSession(sessions[0].id)
            thread.join(2000)
        }

        @Test
        fun `getSession returns null for unknown id`() {
            assertNull(manager.getSession("nonexistent"))
        }

        @Test
        fun `pauseAtBreakpoint sets trace status to PAUSED`() {
            val bp = Breakpoint(id = "bp1", operationId = "echo")
            val trace = makeTrace("t1", "echo")
            manager.addTrace(trace)

            val started = CountDownLatch(1)
            val thread = Thread {
                started.countDown()
                manager.pauseAtBreakpoint("t1", "echo", bp, emptyMap())
            }
            thread.start()
            assertTrue(started.await(1, TimeUnit.SECONDS))
            Thread.sleep(100)

            assertEquals(TraceStatus.PAUSED, manager.getTrace("t1")!!.status)

            // Clean up
            manager.abortSession(manager.listSessions()[0].id)
            thread.join(2000)
        }
    }

    @Nested
    inner class Stats {

        @Test
        fun `stats returns counts`() {
            manager.addTrace(makeTrace("t1", "echo"))
            manager.addTrace(makeTrace("t2", "health"))
            manager.addBreakpoint(Breakpoint(id = "bp1", operationId = "echo"))

            val stats = manager.stats()
            assertEquals(2, stats["traces"])
            assertEquals(1, stats["breakpoints"])
            assertEquals(0, stats["activeSessions"])
        }
    }

    @Nested
    inner class ConcurrentAccess {

        @Test
        fun `multiple threads can add traces concurrently`() {
            val latch = CountDownLatch(10)
            val threads = (0 until 10).map { i ->
                Thread {
                    manager.addTrace(makeTrace("t$i", "op$i"))
                    latch.countDown()
                }
            }
            threads.forEach { it.start() }
            assertTrue(latch.await(5, TimeUnit.SECONDS))

            // Should have exactly 5 (traceHistorySize)
            val traces = manager.listTraces(limit = 100)
            assertEquals(5, traces.size)
        }

        @Test
        fun `multiple sessions can be active simultaneously`() {
            val bp = Breakpoint(id = "bp1", operationId = "echo")
            val started = CountDownLatch(3)
            val threads = (0 until 3).map { i ->
                manager.addTrace(makeTrace("t$i", "echo"))
                Thread {
                    started.countDown()
                    manager.pauseAtBreakpoint("t$i", "echo", bp, mapOf("i" to i))
                }
            }
            threads.forEach { it.start() }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            Thread.sleep(200)

            assertEquals(3, manager.listSessions().size)

            // Resume all
            manager.listSessions().forEach { manager.resumeSession(it.id) }
            threads.forEach { it.join(3000) }

            assertEquals(0, manager.listSessions().size)
        }
    }

    private fun makeTrace(id: String, operationId: String): DebugTrace = DebugTrace(
        id = id,
        operationId = operationId,
        method = "POST",
        path = "/$operationId",
        request = TraceData(body = mapOf("test" to true)),
    )
}
