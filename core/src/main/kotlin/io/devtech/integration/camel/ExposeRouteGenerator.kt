package io.devtech.integration.camel

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.devtech.integration.debug.*
import io.devtech.integration.model.*
import org.apache.camel.Exchange
import org.apache.camel.builder.RouteBuilder
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Generates Camel routes from an [Integration] model.
 *
 * Every route automatically gets:
 * - End-to-end trace ID (MDC + X-Trace-Id header)
 * - Structured logging (request in, response out, errors)
 * - Micrometer metrics (call count, duration, errors per operation)
 * - Debug interception (traces, breakpoints, pause/resume) when debug mode is enabled
 *
 * No per-flow code needed — it's all infrastructure.
 */
class ExposeRouteGenerator(
    private val integration: Integration,
    private val debugManager: DebugManager? = null,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) {
    // Logger named after the integration — shows as "echo-service" in logs
    // Named `logger` to avoid being shadowed by RouteBuilder's protected `log` field
    private val logger = LoggerFactory.getLogger(integration.name)

    fun generate(): RouteBuilder = object : RouteBuilder() {
        override fun configure() {
            val expose = integration.expose ?: return
            val spec = expose.spec

            // Configure REST with Undertow
            restConfiguration()
                .component("undertow")
                .host(expose.host)
                .port(expose.port)
                .bindingMode(org.apache.camel.model.rest.RestBindingMode.off)
                .dataFormatProperty("prettyPrint", "true")

            // Serve the OpenAPI spec via REST DSL
            rest().get("/openapi.json")
                .produces("application/yaml")
                .to("direct:openapi-spec")

            from("direct:openapi-spec")
                .routeId("openapi-spec")
                .process { exchange ->
                    val specContent = Files.readString(Path.of(spec.path))
                    exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/yaml")
                    exchange.message.body = specContent
                }

            // Serve Prometheus metrics via REST DSL
            rest().get("/metrics")
                .produces("text/plain")
                .to("direct:metrics")

            from("direct:metrics")
                .routeId("metrics")
                .process { exchange ->
                    val registry = MetricsRegistry.registry
                    exchange.message.setHeader(Exchange.CONTENT_TYPE, "text/plain; version=0.0.4")
                    exchange.message.body = registry.scrape()
                }

            // Debug API routes (only when debug mode is enabled)
            if (debugManager != null) {
                configureDebugRoutes()
            }

            // Generate a route for each operation that has an implementation
            for (impl in integration.implementations) {
                val operation = spec.operations[impl.operationId] ?: continue

                // REST DSL endpoint -> direct route
                val restDef = when (operation.method) {
                    HttpMethod.GET -> rest().get(operation.path)
                    HttpMethod.POST -> rest().post(operation.path)
                    HttpMethod.PUT -> rest().put(operation.path)
                    HttpMethod.PATCH -> rest().patch(operation.path)
                    HttpMethod.DELETE -> rest().delete(operation.path)
                }
                restDef
                    .consumes("application/json")
                    .produces("application/json")
                    .to("direct:${impl.operationId}")

                configureImplementationRoute(impl, operation)
            }
        }

        /**
         * Configures a Camel route for a single implementation, applying:
         * - Error handling via Camel's onException (with optional retry/backoff)
         * - Circuit breaker via Camel's circuitBreaker() with Resilience4j (if configured)
         * - Handler execution with tracing, logging, metrics, and debug support
         */
        private fun configureImplementationRoute(impl: Implementation, operation: Operation) {
            val retryConfig = impl.errorConfig?.retry
            val cbConfig = impl.errorConfig?.circuitBreaker

            // Start the route with error handling
            val route = from("direct:${impl.operationId}")
                .routeId("impl-${impl.operationId}")
                // Camel onException: handles errors and optionally retries
                .onException(Exception::class.java)
                    .maximumRedeliveries(retryConfig?.let { it.maxAttempts - 1 } ?: 0)
                    .redeliveryDelay(retryConfig?.delayMs ?: 0)
                    .useExponentialBackOff()
                    .backOffMultiplier(retryConfig?.backoffMultiplier ?: 2.0)
                    .maximumRedeliveryDelay(retryConfig?.maxDelayMs ?: 60_000)
                    .handled(true)
                    .process { exchange -> handleError(exchange, impl.operationId, operation) }
                .end()

            // Circuit breaker wrapping (Resilience4j via Camel) or direct handler
            if (cbConfig != null) {
                route.circuitBreaker()
                    .resilience4jConfiguration()
                        .slidingWindowSize(cbConfig.slidingWindowSize)
                        .minimumNumberOfCalls(cbConfig.minimumCalls)
                        .failureRateThreshold(cbConfig.failureRateThreshold)
                        .waitDurationInOpenState((cbConfig.waitDurationInOpenStateMs / 1000).coerceAtLeast(1).toInt())
                        .permittedNumberOfCallsInHalfOpenState(cbConfig.permittedCallsInHalfOpen)
                    .end()
                    .process { exchange -> executeHandler(exchange, impl, operation) }
                .onFallback()
                    .process { exchange -> handleCircuitBreakerFallback(exchange, impl.operationId) }
                .endCircuitBreaker()
            } else {
                route.process { exchange -> executeHandler(exchange, impl, operation) }
            }
        }

        /**
         * Execute the handler with tracing, logging, metrics, and debug support.
         * Exceptions propagate to Camel's onException for retry and error handling.
         */
        private fun executeHandler(
            exchange: Exchange,
            impl: Implementation,
            operation: Operation,
        ) {
            val handler = impl.handler ?: return
            val opId = impl.operationId

            // Reuse trace context across retries (same logical request)
            val traceId = (exchange.getProperty("traceId") as? String)
                ?: generateTraceId().also { exchange.setProperty("traceId", it) }
            val startTime = (exchange.getProperty("startTime") as? Long)
                ?: System.nanoTime().also { exchange.setProperty("startTime", it) }

            MDC.put("traceId", traceId)
            CamelContextHolder.set(exchange.context)
            try {
                var requestContext = buildRequestContext(exchange, operation)

                // --- Debug: create trace (only on first attempt) ---
                val trace = if (exchange.getProperty("debugTrace") == null) {
                    debugManager?.let { dm ->
                        val t = DebugTrace(
                            id = traceId,
                            operationId = opId,
                            method = operation.method.name,
                            path = operation.path,
                            request = TraceData(
                                body = requestContext.body,
                                headers = requestContext.headers.redactSecrets(),
                            ),
                        )
                        dm.addTrace(t)
                        exchange.setProperty("debugTrace", t)
                        t
                    }
                } else {
                    exchange.getProperty("debugTrace") as? DebugTrace
                }

                // --- Debug: check BEFORE_HANDLER breakpoint ---
                if (debugManager != null && trace != null) {
                    val bp = debugManager.checkBreakpoint(opId, BreakPhase.BEFORE_HANDLER)
                    if (bp != null) {
                        logger.info("{} {} — paused at BEFORE_HANDLER breakpoint {}", operation.method, operation.path, bp.id)
                        val action = debugManager.pauseAtBreakpoint(trace.id, opId, bp, requestContext.body)
                        when (action) {
                            is SessionAction.Resume -> {
                                // continue as-is
                            }
                            is SessionAction.ModifyAndResume -> {
                                requestContext = requestContext.copy(body = action.newBody)
                                logger.info("{} {} — request body modified by debug session", operation.method, operation.path)
                            }
                            is SessionAction.Abort -> {
                                trace.status = TraceStatus.ABORTED
                                trace.durationMs = (System.nanoTime() - startTime) / 1_000_000.0
                                logger.info("{} {} — aborted by debug session", operation.method, operation.path)
                                exchange.message.setHeader(Exchange.HTTP_RESPONSE_CODE, 499)
                                exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
                                exchange.message.setHeader("X-Trace-Id", traceId)
                                exchange.message.body = mapper.writeValueAsString(mapOf("error" to "Request aborted by debug session"))
                                return
                            }
                        }
                    }
                }

                // Execute handler — exceptions propagate for Camel's retry/error handling
                val response = handler(requestContext)
                val durationMs = (System.nanoTime() - startTime) / 1_000_000.0

                // --- Debug: check AFTER_HANDLER breakpoint ---
                if (debugManager != null && trace != null) {
                    trace.response = TraceData(
                        body = when (response.body) {
                            is Map<*, *> -> @Suppress("UNCHECKED_CAST") (response.body as Map<String, Any?>)
                            else -> mapOf("_raw" to response.body)
                        },
                        headers = response.headers.redactSecrets(),
                        statusCode = response.statusCode,
                    )

                    val bp = debugManager.checkBreakpoint(opId, BreakPhase.AFTER_HANDLER)
                    if (bp != null) {
                        logger.info("{} {} — paused at AFTER_HANDLER breakpoint {}", operation.method, operation.path, bp.id)
                        val responseBody = when (response.body) {
                            is Map<*, *> -> @Suppress("UNCHECKED_CAST") (response.body as Map<String, Any?>)
                            else -> mapOf("_raw" to response.body)
                        }
                        debugManager.pauseAtBreakpoint(trace.id, opId, bp, responseBody)
                    }
                }

                // --- Debug: complete trace ---
                trace?.let {
                    it.durationMs = durationMs
                    it.status = TraceStatus.COMPLETED
                }

                // Set response
                exchange.message.setHeader(Exchange.HTTP_RESPONSE_CODE, response.statusCode)
                exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
                exchange.message.setHeader("X-Trace-Id", traceId)
                for (entry in response.headers.entries) {
                    exchange.message.setHeader(entry.key, entry.value)
                }
                exchange.message.body = when (response.body) {
                    is String -> response.body
                    null -> ""
                    else -> mapper.writeValueAsString(response.body)
                }
            } finally {
                CamelContextHolder.clear()
                MDC.remove("traceId")
            }
        }

        /**
         * Camel onException handler — formats 500 error response after retries are exhausted.
         * Called by Camel's error handler when handled(true) is set.
         */
        private fun handleError(
            exchange: Exchange,
            opId: String,
            operation: Operation,
        ) {
            val cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception::class.java)
            val startTime = (exchange.getProperty("startTime") as? Long) ?: System.nanoTime()
            val traceId = (exchange.getProperty("traceId") as? String) ?: generateTraceId()
            val durationMs = (System.nanoTime() - startTime) / 1_000_000.0

            MDC.put("traceId", traceId)
            try {
                logger.error("{} {} — ERROR in {}ms: {}", operation.method, operation.path, String.format("%.1f", durationMs), cause?.message, cause)

                // Debug: error trace
                val trace = exchange.getProperty("debugTrace") as? DebugTrace
                trace?.let {
                    it.durationMs = durationMs
                    it.status = TraceStatus.ERROR
                    it.error = cause?.message
                }

                exchange.message.setHeader(Exchange.HTTP_RESPONSE_CODE, 500)
                exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
                exchange.message.setHeader("X-Trace-Id", traceId)
                exchange.message.body = mapper.writeValueAsString(mapOf(
                    "error" to (cause?.message ?: "Internal server error"),
                ))
            } finally {
                MDC.remove("traceId")
            }
        }

        /**
         * Circuit breaker fallback — returns 503 when circuit is OPEN.
         */
        private fun handleCircuitBreakerFallback(
            exchange: Exchange,
            opId: String,
        ) {
            val traceId = (exchange.getProperty("traceId") as? String) ?: generateTraceId()

            logger.warn("Circuit breaker OPEN for operation '{}' — request rejected", opId)

            exchange.message.setHeader(Exchange.HTTP_RESPONSE_CODE, 503)
            exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
            exchange.message.setHeader("X-Trace-Id", traceId)
            exchange.message.body = mapper.writeValueAsString(mapOf(
                "error" to "Service temporarily unavailable — circuit breaker open for '$opId'",
            ))
        }

        /**
         * Debug API routes — only registered when debug mode is active.
         */
        private fun configureDebugRoutes() {
            val dm = debugManager ?: return

            // GET /debug — overview
            rest().get("/debug")
                .produces("application/json")
                .to("direct:debug-overview")

            from("direct:debug-overview")
                .routeId("debug-overview")
                .process { exchange ->
                    exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
                    exchange.message.body = mapper.writeValueAsString(dm.stats())
                }

            // GET /debug/traces — list recent traces
            rest().get("/debug/traces")
                .produces("application/json")
                .to("direct:debug-traces")

            from("direct:debug-traces")
                .routeId("debug-traces")
                .process { exchange ->
                    val limit = exchange.message.getHeader("limit", "20", String::class.java).toIntOrNull() ?: 20
                    val traces = dm.listTraces(limit).map { traceToMap(it) }
                    exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
                    exchange.message.body = mapper.writeValueAsString(traces)
                }

            // GET /debug/traces/{id} — single trace detail
            rest().get("/debug/traces/{traceId}")
                .produces("application/json")
                .to("direct:debug-trace-detail")

            from("direct:debug-trace-detail")
                .routeId("debug-trace-detail")
                .process { exchange ->
                    val traceId = exchange.message.getHeader("traceId", String::class.java)
                    val trace = dm.getTrace(traceId)
                    exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
                    if (trace != null) {
                        exchange.message.body = mapper.writeValueAsString(traceToMap(trace))
                    } else {
                        exchange.message.setHeader(Exchange.HTTP_RESPONSE_CODE, 404)
                        exchange.message.body = mapper.writeValueAsString(mapOf("error" to "Trace not found"))
                    }
                }

            // DELETE /debug/traces — clear all traces
            rest().delete("/debug/traces")
                .produces("application/json")
                .to("direct:debug-traces-clear")

            from("direct:debug-traces-clear")
                .routeId("debug-traces-clear")
                .process { exchange ->
                    dm.clearTraces()
                    exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
                    exchange.message.body = mapper.writeValueAsString(mapOf("cleared" to true))
                }

            // GET /debug/breakpoints — list all breakpoints
            rest().get("/debug/breakpoints")
                .produces("application/json")
                .to("direct:debug-breakpoints-list")

            from("direct:debug-breakpoints-list")
                .routeId("debug-breakpoints-list")
                .process { exchange ->
                    exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
                    exchange.message.body = mapper.writeValueAsString(dm.listBreakpoints())
                }

            // POST /debug/breakpoints — add a breakpoint
            rest().post("/debug/breakpoints")
                .consumes("application/json")
                .produces("application/json")
                .to("direct:debug-breakpoints-add")

            from("direct:debug-breakpoints-add")
                .routeId("debug-breakpoints-add")
                .process { exchange ->
                    val bodyStr = exchange.message.getBody(String::class.java) ?: "{}"
                    @Suppress("UNCHECKED_CAST")
                    val body = mapper.readValue(bodyStr, Map::class.java) as Map<String, Any?>
                    val operationId = body["operationId"] as? String
                    if (operationId == null) {
                        exchange.message.setHeader(Exchange.HTTP_RESPONSE_CODE, 400)
                        exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
                        exchange.message.body = mapper.writeValueAsString(mapOf("error" to "operationId is required"))
                        return@process
                    }
                    val phase = when (body["phase"] as? String) {
                        "AFTER_HANDLER" -> BreakPhase.AFTER_HANDLER
                        else -> BreakPhase.BEFORE_HANDLER
                    }
                    val bp = dm.addBreakpoint(Breakpoint(operationId = operationId, phase = phase))
                    exchange.message.setHeader(Exchange.HTTP_RESPONSE_CODE, 201)
                    exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
                    exchange.message.body = mapper.writeValueAsString(bp)
                }

            // DELETE /debug/breakpoints/{id} — remove a breakpoint
            rest().delete("/debug/breakpoints/{breakpointId}")
                .produces("application/json")
                .to("direct:debug-breakpoints-remove")

            from("direct:debug-breakpoints-remove")
                .routeId("debug-breakpoints-remove")
                .process { exchange ->
                    val bpId = exchange.message.getHeader("breakpointId", String::class.java)
                    val removed = dm.removeBreakpoint(bpId)
                    exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
                    if (removed) {
                        exchange.message.body = mapper.writeValueAsString(mapOf("removed" to true))
                    } else {
                        exchange.message.setHeader(Exchange.HTTP_RESPONSE_CODE, 404)
                        exchange.message.body = mapper.writeValueAsString(mapOf("error" to "Breakpoint not found"))
                    }
                }

            rest().post("/debug/breakpoints/clear")
                .produces("application/json")
                .to("direct:debug-breakpoints-clear")

            from("direct:debug-breakpoints-clear")
                .routeId("debug-breakpoints-clear")
                .process { exchange ->
                    dm.clearBreakpoints()
                    exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
                    exchange.message.body = mapper.writeValueAsString(mapOf("cleared" to true))
                }

            // GET /debug/sessions — list active (paused) sessions
            rest().get("/debug/sessions")
                .produces("application/json")
                .to("direct:debug-sessions-list")

            from("direct:debug-sessions-list")
                .routeId("debug-sessions-list")
                .process { exchange ->
                    val sessions = dm.listSessions().map { sessionToMap(it) }
                    exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
                    exchange.message.body = mapper.writeValueAsString(sessions)
                }

            // GET /debug/sessions/{id} — inspect a paused session
            rest().get("/debug/sessions/{sessionId}")
                .produces("application/json")
                .to("direct:debug-session-detail")

            from("direct:debug-session-detail")
                .routeId("debug-session-detail")
                .process { exchange ->
                    val sessionId = exchange.message.getHeader("sessionId", String::class.java)
                    val session = dm.getSession(sessionId)
                    exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
                    if (session != null) {
                        exchange.message.body = mapper.writeValueAsString(sessionToMap(session))
                    } else {
                        exchange.message.setHeader(Exchange.HTTP_RESPONSE_CODE, 404)
                        exchange.message.body = mapper.writeValueAsString(mapOf("error" to "Session not found"))
                    }
                }

            // POST /debug/sessions/{id}/resume
            rest().post("/debug/sessions/{sessionId}/resume")
                .produces("application/json")
                .to("direct:debug-session-resume")

            from("direct:debug-session-resume")
                .routeId("debug-session-resume")
                .process { exchange ->
                    val sessionId = exchange.message.getHeader("sessionId", String::class.java)
                    val resumed = dm.resumeSession(sessionId)
                    exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
                    if (resumed) {
                        exchange.message.body = mapper.writeValueAsString(mapOf("resumed" to true))
                    } else {
                        exchange.message.setHeader(Exchange.HTTP_RESPONSE_CODE, 404)
                        exchange.message.body = mapper.writeValueAsString(mapOf("error" to "Session not found or already completed"))
                    }
                }

            // POST /debug/sessions/{id}/modify
            rest().post("/debug/sessions/{sessionId}/modify")
                .consumes("application/json")
                .produces("application/json")
                .to("direct:debug-session-modify")

            from("direct:debug-session-modify")
                .routeId("debug-session-modify")
                .process { exchange ->
                    val sessionId = exchange.message.getHeader("sessionId", String::class.java)
                    val bodyStr = exchange.message.getBody(String::class.java) ?: "{}"
                    @Suppress("UNCHECKED_CAST")
                    val newBody = mapper.readValue(bodyStr, Map::class.java) as Map<String, Any?>
                    val modified = dm.modifyAndResumeSession(sessionId, newBody)
                    exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
                    if (modified) {
                        exchange.message.body = mapper.writeValueAsString(mapOf("modified" to true, "resumed" to true))
                    } else {
                        exchange.message.setHeader(Exchange.HTTP_RESPONSE_CODE, 404)
                        exchange.message.body = mapper.writeValueAsString(mapOf("error" to "Session not found or already completed"))
                    }
                }

            // POST /debug/sessions/{id}/abort
            rest().post("/debug/sessions/{sessionId}/abort")
                .produces("application/json")
                .to("direct:debug-session-abort")

            from("direct:debug-session-abort")
                .routeId("debug-session-abort")
                .process { exchange ->
                    val sessionId = exchange.message.getHeader("sessionId", String::class.java)
                    val aborted = dm.abortSession(sessionId)
                    exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
                    if (aborted) {
                        exchange.message.body = mapper.writeValueAsString(mapOf("aborted" to true))
                    } else {
                        exchange.message.setHeader(Exchange.HTTP_RESPONSE_CODE, 404)
                        exchange.message.body = mapper.writeValueAsString(mapOf("error" to "Session not found or already completed"))
                    }
                }
        }
    }

    private fun traceToMap(trace: DebugTrace): Map<String, Any?> = mapOf(
        "id" to trace.id,
        "operationId" to trace.operationId,
        "method" to trace.method,
        "path" to trace.path,
        "timestamp" to trace.timestamp.toString(),
        "status" to trace.status.name,
        "durationMs" to trace.durationMs,
        "request" to mapOf(
            "body" to trace.request.body,
            "headers" to trace.request.headers.redactSecrets(),
        ),
        "response" to trace.response?.let { r ->
            mapOf(
                "body" to r.body,
                "headers" to r.headers.redactSecrets(),
                "statusCode" to r.statusCode,
            )
        },
        "error" to trace.error,
    )

    private fun sessionToMap(session: DebugSession): Map<String, Any?> = mapOf(
        "id" to session.id,
        "traceId" to session.traceId,
        "operationId" to session.operationId,
        "breakpointId" to session.breakpointId,
        "phase" to session.phase.name,
        "pausedAt" to session.pausedAt.toString(),
        "currentData" to session.currentData,
    )

    @Suppress("UNCHECKED_CAST")
    private fun buildRequestContext(exchange: Exchange, operation: Operation): RequestContext {
        val bodyStr = exchange.message.getBody(String::class.java) ?: ""
        val body: Map<String, Any?> = if (bodyStr.isNotBlank()) {
            try {
                mapper.readValue(bodyStr, Map::class.java) as Map<String, Any?>
            } catch (_: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }

        val pathParams = mutableMapOf<String, String>()
        for (param in operation.parameters.filter { it.location == ParameterLocation.PATH }) {
            val value = exchange.message.getHeader(param.name, String::class.java)
            if (value != null) pathParams[param.name] = value
        }

        val queryParams = mutableMapOf<String, String>()
        for (param in operation.parameters.filter { it.location == ParameterLocation.QUERY }) {
            val value = exchange.message.getHeader(param.name, String::class.java)
            if (value != null) queryParams[param.name] = value
        }

        return RequestContext(
            body = body,
            pathParams = pathParams,
            queryParams = queryParams,
        )
    }

    companion object {
        fun generateTraceId(): String = UUID.randomUUID().toString().substring(0, 8)
    }
}
