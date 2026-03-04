package io.devtech.integration.debug

import java.time.Instant
import java.util.UUID

/**
 * Configuration for debug mode.
 */
data class DebugConfig(
    val enabled: Boolean = false,
    val traceHistorySize: Int = 100,
    val sessionTimeoutMs: Long = 300_000, // 5 minutes
)

/**
 * A full execution trace for one request.
 */
data class DebugTrace(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    val operationId: String,
    val method: String,
    val path: String,
    val timestamp: Instant = Instant.now(),
    val request: TraceData,
    var response: TraceData? = null,
    var durationMs: Double = 0.0,
    var status: TraceStatus = TraceStatus.IN_PROGRESS,
    var error: String? = null,
)

enum class TraceStatus { IN_PROGRESS, COMPLETED, ERROR, PAUSED, ABORTED }

/**
 * Snapshot of data at a point in the execution.
 */
data class TraceData(
    val body: Map<String, Any?> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val statusCode: Int? = null,
    val timestamp: Instant = Instant.now(),
)

/**
 * A breakpoint — pauses execution when hit.
 */
data class Breakpoint(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    val operationId: String,
    val phase: BreakPhase = BreakPhase.BEFORE_HANDLER,
    val condition: String? = null, // future: expression-based conditional breakpoints
    val enabled: Boolean = true,
)

enum class BreakPhase { BEFORE_HANDLER, AFTER_HANDLER }

/**
 * An active debug session — a request that is currently paused at a breakpoint.
 * The request thread is blocked on [resumeSignal].
 */
data class DebugSession(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    val traceId: String,
    val operationId: String,
    val breakpointId: String,
    val phase: BreakPhase,
    val pausedAt: Instant = Instant.now(),
    val currentData: MutableMap<String, Any?> = mutableMapOf(),
) {
    @Transient
    val resumeSignal: java.util.concurrent.CompletableFuture<SessionAction> =
        java.util.concurrent.CompletableFuture()
}

/**
 * What to do when resuming a paused session.
 */
sealed class SessionAction {
    data object Resume : SessionAction()
    data class ModifyAndResume(val newBody: Map<String, Any?>) : SessionAction()
    data object Abort : SessionAction()
}
