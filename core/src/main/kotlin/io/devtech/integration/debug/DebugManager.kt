package io.devtech.integration.debug

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit

/**
 * Manages debug state: breakpoints, active sessions (paused requests), and trace history.
 *
 * Thread-safe — multiple request threads can pause/resume concurrently.
 * The debug API routes delegate to this manager.
 */
class DebugManager(private val config: DebugConfig) {

    private val log = LoggerFactory.getLogger("debug")

    // Trace history — bounded ring buffer (newest first)
    private val traces = ConcurrentLinkedDeque<DebugTrace>()

    // Breakpoints by ID
    private val breakpoints = ConcurrentHashMap<String, Breakpoint>()

    // Active debug sessions (paused requests) by session ID
    private val sessions = ConcurrentHashMap<String, DebugSession>()

    // --- Traces ---

    fun addTrace(trace: DebugTrace) {
        traces.addFirst(trace)
        while (traces.size > config.traceHistorySize) {
            traces.removeLast()
        }
    }

    fun getTrace(traceId: String): DebugTrace? = traces.find { it.id == traceId }

    fun listTraces(limit: Int = 20): List<DebugTrace> = traces.take(limit)

    fun clearTraces() = traces.clear()

    // --- Breakpoints ---

    fun addBreakpoint(breakpoint: Breakpoint): Breakpoint {
        breakpoints[breakpoint.id] = breakpoint
        log.info("Breakpoint {} added on operation '{}' ({})", breakpoint.id, breakpoint.operationId, breakpoint.phase)
        return breakpoint
    }

    fun removeBreakpoint(breakpointId: String): Boolean {
        val removed = breakpoints.remove(breakpointId)
        if (removed != null) log.info("Breakpoint {} removed", breakpointId)
        return removed != null
    }

    fun listBreakpoints(): List<Breakpoint> = breakpoints.values.toList()

    fun clearBreakpoints() {
        breakpoints.clear()
        log.info("All breakpoints cleared")
    }

    /**
     * Check if any active breakpoint matches this operation + phase.
     * Returns the matching breakpoint, or null if execution should continue.
     */
    fun checkBreakpoint(operationId: String, phase: BreakPhase): Breakpoint? {
        return breakpoints.values.firstOrNull {
            it.enabled && it.operationId == operationId && it.phase == phase
        }
    }

    // --- Sessions (paused requests) ---

    /**
     * Pause the current request at a breakpoint.
     * Creates a debug session and blocks the calling thread until resumed.
     *
     * @return the action to take (resume, modify+resume, abort)
     */
    fun pauseAtBreakpoint(
        traceId: String,
        operationId: String,
        breakpoint: Breakpoint,
        currentData: Map<String, Any?>,
    ): SessionAction {
        val session = DebugSession(
            traceId = traceId,
            operationId = operationId,
            breakpointId = breakpoint.id,
            phase = breakpoint.phase,
            currentData = currentData.toMutableMap(),
        )
        sessions[session.id] = session

        log.info("Request paused at breakpoint {} — session {} (operation: {}, phase: {})",
            breakpoint.id, session.id, operationId, breakpoint.phase)

        // Update the trace status
        getTrace(traceId)?.status = TraceStatus.PAUSED

        return try {
            // Block the request thread until someone calls resume/abort
            val action = session.resumeSignal.get(config.sessionTimeoutMs, TimeUnit.MILLISECONDS)
            sessions.remove(session.id)
            log.info("Session {} resumed with action: {}", session.id, action::class.simpleName)
            action
        } catch (e: java.util.concurrent.TimeoutException) {
            sessions.remove(session.id)
            log.warn("Session {} timed out after {}ms — aborting", session.id, config.sessionTimeoutMs)
            SessionAction.Abort
        }
    }

    fun getSession(sessionId: String): DebugSession? = sessions[sessionId]

    fun listSessions(): List<DebugSession> = sessions.values.toList()

    /**
     * Resume a paused session. Unblocks the request thread.
     */
    fun resumeSession(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        session.resumeSignal.complete(SessionAction.Resume)
        return true
    }

    /**
     * Modify the request data and resume.
     */
    fun modifyAndResumeSession(sessionId: String, newBody: Map<String, Any?>): Boolean {
        val session = sessions[sessionId] ?: return false
        session.resumeSignal.complete(SessionAction.ModifyAndResume(newBody))
        return true
    }

    /**
     * Abort a paused session. The request gets a 499 response.
     */
    fun abortSession(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        session.resumeSignal.complete(SessionAction.Abort)
        return true
    }

    /**
     * Summary stats for the /debug endpoint.
     */
    fun stats(): Map<String, Any> = mapOf(
        "traces" to traces.size,
        "breakpoints" to breakpoints.size,
        "activeSessions" to sessions.size,
        "config" to mapOf(
            "traceHistorySize" to config.traceHistorySize,
            "sessionTimeoutMs" to config.sessionTimeoutMs,
        ),
    )
}
