package io.devtech.integration.dsl

import io.devtech.integration.model.*
import org.slf4j.LoggerFactory

/**
 * Builder for the block form of `flow()`. Supports two modes:
 *
 * **Imperative** — pass a handler lambda via `handle()`:
 * ```kotlin
 * flow("enrich") {
 *     onError { retry { maxAttempts = 3 } }
 *     handle(enrichHandler(usersApi))
 * }
 * ```
 *
 * **Declarative** — use step methods (`call()`, `process()`, `log()`):
 * ```kotlin
 * flow("createProduct") {
 *     statusCode = 201
 *     process("enrich") { body -> body + ("created_at" to now(), "slug" to slugify(body.string("name"))) }
 *     call(db, HttpMethod.POST, "/products")
 * }
 * ```
 *
 * If `handle()` is called, it takes precedence over steps. If only steps are
 * present, a handler is auto-generated that executes them in order.
 */
@IntegrationDsl
class FlowBuilder(private val operationId: String) {
    private var handler: ((RequestContext) -> ResponseContext)? = null
    private var errorConfig: ErrorConfig? = null

    /** Collected declarative steps. */
    internal val steps = mutableListOf<Step>()

    /** Adapter refs captured by call() steps — used by IntegrationBuilder for ProducerTemplate binding. */
    private val capturedAdapterRefs = mutableListOf<AdapterRef>()

    /** Public getter for adapter refs referenced in call() steps. */
    val adapterRefs: List<AdapterRef> get() = capturedAdapterRefs.toList()

    /** Response status code for auto-generated handler (default 200). */
    var statusCode: Int = 200

    /** Wire the handler for this flow (imperative mode). */
    fun handle(handlerFn: (RequestContext) -> ResponseContext) {
        handler = handlerFn
    }

    // --- Step methods (declarative mode) ---

    /** Call an adapter endpoint. */
    fun call(adapter: AdapterRef, method: HttpMethod, path: String, block: CallConfigBuilder.() -> Unit = {}) {
        val config = CallConfigBuilder().apply(block).build()
        steps += Step.Call(adapter.name, method, path, config)
        capturedAdapterRefs += adapter
    }

    /** Process data with a Kotlin lambda. */
    fun process(name: String = "", handler: (Map<String, Any?>) -> Any?) {
        steps += Step.Process(name, handler)
    }

    /** Filter — continue only if expression evaluates to true (Camel Simple language). */
    fun filter(expression: String) {
        steps += Step.Filter(expression)
    }

    /** Log a message at the given level. */
    fun log(message: String, level: LogLevel = LogLevel.INFO) {
        steps += Step.Log(message, level)
    }

    /**
     * Respond with fields built using `set` and `to`.
     *
     * ```kotlin
     * respond {
     *     "message" to "message"        // body["message"] → response
     *     "timestamp" set now()         // computed value
     *     "source" set "my-service"     // literal
     * }
     * ```
     *
     * This is a declarative step — at runtime the builder receives the
     * accumulated body from prior steps.
     */
    fun respond(block: ResponseBuilder.() -> Unit) {
        steps += Step.Respond(statusCode, block)
    }

    /**
     * Map the body for the next step using `set` and `to`.
     *
     * ```kotlin
     * map {
     *     "productId" to "productId"
     *     "quantity" to "quantity"
     *     "status" set "pending"
     *     "createdAt" set now()
     * }
     * ```
     */
    fun map(block: ResponseBuilder.() -> Unit) {
        steps += Step.MapFields(block)
    }

    /**
     * Configure error handling — retry and/or circuit breaker.
     * Maps to Camel's onException (retry) and circuitBreaker (Resilience4j).
     */
    fun onError(block: ErrorConfigBuilder.() -> Unit) {
        errorConfig = ErrorConfigBuilder().apply(block).build()
    }

    fun build(): Implementation {
        val effectiveHandler = handler ?: if (steps.isNotEmpty()) generateHandlerFromSteps() else null
        return Implementation(
            operationId = operationId,
            steps = steps.toList(),
            handler = effectiveHandler,
            errorConfig = errorConfig,
        )
    }

    /**
     * Auto-generate a handler from declarative steps. Executes steps in order:
     * - Call → delegates to adapter backend
     * - Process → transforms data with lambda
     * - Log → logs message
     * - Filter/Transform → pass through (handled by Camel route layer)
     */
    private fun generateHandlerFromSteps(): (RequestContext) -> ResponseContext {
        val stepsCopy = steps.toList()
        val adapterMap = capturedAdapterRefs.associateBy { it.name }
        val code = statusCode
        val logger = LoggerFactory.getLogger("flow.$operationId")
        return fn@{ req ->
            var data: Any? = req.body
            for (step in stepsCopy) {
                when (step) {
                    is Step.Call -> {
                        val ref = adapterMap[step.adapterName]!!
                        val resolvedPath = resolvePath(step.path, req.pathParams)
                        data = when (step.method) {
                            HttpMethod.GET -> ref.backend.execute(
                                "GET", resolvedPath,
                                queryParams = req.queryParams + step.config.queryParams,
                            )
                            HttpMethod.POST -> ref.backend.execute("POST", resolvedPath, body = data)
                            HttpMethod.PUT -> ref.backend.execute("PUT", resolvedPath, body = data)
                            HttpMethod.DELETE -> ref.backend.execute("DELETE", resolvedPath)
                            HttpMethod.PATCH -> ref.backend.execute("PUT", resolvedPath, body = data)
                        }
                    }
                    is Step.Process -> {
                        @Suppress("UNCHECKED_CAST")
                        data = step.handler(data as? Map<String, Any?> ?: emptyMap())
                    }
                    is Step.Log -> {
                        when (step.level) {
                            LogLevel.DEBUG -> logger.debug(step.message)
                            LogLevel.INFO -> logger.info(step.message)
                            LogLevel.WARN -> logger.warn(step.message)
                            LogLevel.ERROR -> logger.error(step.message)
                        }
                    }
                    is Step.Respond -> {
                        @Suppress("UNCHECKED_CAST")
                        val sourceBody = data as? Map<String, Any?> ?: emptyMap()
                        val builder = ResponseBuilder(sourceBody)
                        builder.apply(step.block)
                        return@fn builder.build(statusCode = step.statusCode)
                    }
                    is Step.MapFields -> {
                        @Suppress("UNCHECKED_CAST")
                        val sourceBody = data as? Map<String, Any?> ?: emptyMap()
                        val builder = ResponseBuilder(sourceBody)
                        builder.apply(step.block)
                        data = builder.fields.toMap()
                    }
                    is Step.Transform, is Step.Filter -> { /* handled by Camel route layer */ }
                }
            }
            ResponseContext(statusCode = code, body = data)
        }
    }

    companion object {
        /** Resolve path parameters: "/products/{id}" + {"id":"42"} → "/products/42" */
        internal fun resolvePath(path: String, pathParams: Map<String, String>): String {
            var resolved = path
            for ((key, value) in pathParams) {
                resolved = resolved.replace("{$key}", value)
            }
            return resolved
        }
    }
}

/**
 * Builder for the `onError { }` block inside a flow.
 */
@IntegrationDsl
class ErrorConfigBuilder {
    private var retry: RetryConfig? = null
    private var circuitBreaker: CircuitBreakerConfig? = null

    /**
     * Configure retry with exponential backoff.
     * Maps to Camel's `.onException(...).maximumRedeliveries(n).redeliveryDelay(...)`.
     *
     * ```kotlin
     * retry {
     *     maxAttempts = 3
     *     delayMs = 500
     *     backoffMultiplier = 2.0
     * }
     * ```
     */
    fun retry(block: RetryConfigBuilder.() -> Unit) {
        retry = RetryConfigBuilder().apply(block).build()
    }

    /**
     * Configure circuit breaker (Resilience4j via Camel).
     * Maps to Camel's `.circuitBreaker().resilience4jConfiguration()`.
     *
     * ```kotlin
     * circuitBreaker {
     *     failureRateThreshold = 50f
     *     waitDurationInOpenStateMs = 30_000
     * }
     * ```
     */
    fun circuitBreaker(block: CircuitBreakerConfigBuilder.() -> Unit) {
        circuitBreaker = CircuitBreakerConfigBuilder().apply(block).build()
    }

    fun build(): ErrorConfig = ErrorConfig(retry = retry, circuitBreaker = circuitBreaker)
}

/**
 * Builder for retry configuration.
 */
@IntegrationDsl
class RetryConfigBuilder {
    /** Total number of attempts (including the first). Must be >= 1. */
    var maxAttempts: Int = 3
    /** Initial delay between retries in milliseconds. */
    var delayMs: Long = 500
    /** Multiplier for exponential backoff. */
    var backoffMultiplier: Double = 2.0
    /** Maximum delay cap in milliseconds. */
    var maxDelayMs: Long = 60_000

    fun build(): RetryConfig {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, was $maxAttempts" }
        require(delayMs >= 0) { "delayMs must be >= 0, was $delayMs" }
        return RetryConfig(
            maxAttempts = maxAttempts,
            delayMs = delayMs,
            backoffMultiplier = backoffMultiplier,
            maxDelayMs = maxDelayMs,
        )
    }
}

/**
 * Builder for circuit breaker configuration (Resilience4j via Camel).
 */
@IntegrationDsl
class CircuitBreakerConfigBuilder {
    /** Size of the sliding window for failure rate calculation. */
    var slidingWindowSize: Int = 10
    /** Minimum number of calls before the circuit breaker can trip. */
    var minimumCalls: Int = 5
    /** Failure rate percentage threshold to open the circuit (0-100). */
    var failureRateThreshold: Float = 50.0f
    /** How long to wait in OPEN state before trying HALF_OPEN (ms). */
    var waitDurationInOpenStateMs: Long = 30_000
    /** Number of calls permitted in HALF_OPEN state. */
    var permittedCallsInHalfOpen: Int = 3

    fun build(): CircuitBreakerConfig = CircuitBreakerConfig(
        slidingWindowSize = slidingWindowSize,
        minimumCalls = minimumCalls,
        failureRateThreshold = failureRateThreshold,
        waitDurationInOpenStateMs = waitDurationInOpenStateMs,
        permittedCallsInHalfOpen = permittedCallsInHalfOpen,
    )
}
