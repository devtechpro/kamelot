package io.devtech.integration.dsl

import io.devtech.integration.model.*
import kotlin.time.Duration

/**
 * Builder for triggered (non-API) flows. Collects a trigger + steps, builds a [Flow] model.
 *
 * ```kotlin
 * triggered("sync-users") {
 *     every(5.minutes)
 *     call(usersApi, HttpMethod.GET, "/users")
 *     process { data -> transform(data) }
 *     log("Users synced")
 * }
 * ```
 */
@IntegrationDsl
class TriggeredFlowBuilder(private val name: String) {
    private var trigger: Trigger? = null
    private val steps = mutableListOf<Step>()
    private var errorConfig: ErrorConfig? = null
    var description: String? = null

    // --- Triggers (exactly one required) ---

    /** Timer trigger — fires at a fixed interval. */
    fun every(duration: Duration) {
        trigger = Trigger.Schedule(duration.inWholeMilliseconds)
    }

    /** Cron trigger — fires on a cron schedule (Quartz format). */
    fun cron(expression: String) {
        trigger = Trigger.Cron(expression)
    }

    /** After trigger — fires after another flow completes. */
    fun after(flowName: String) {
        trigger = Trigger.After(flowName)
    }

    /** Manual trigger — only fires when invoked programmatically via direct:manual-{name}. */
    fun manual() {
        trigger = Trigger.Manual
    }

    // --- Steps ---

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

    // --- Error handling ---

    /** Configure error handling (retry + circuit breaker) — reuses existing builders. */
    fun onError(block: ErrorConfigBuilder.() -> Unit) {
        errorConfig = ErrorConfigBuilder().apply(block).build()
    }

    // --- Adapter ref tracking ---

    private val capturedAdapterRefs = mutableListOf<AdapterRef>()

    /** Adapter refs referenced by call() steps — used by the runtime for ProducerTemplate binding. */
    val adapterRefs: List<AdapterRef> get() = capturedAdapterRefs.toList()

    fun build(): Flow {
        val t = trigger ?: error("Flow '$name' has no trigger. Call every(), cron(), after(), or manual().")
        require(steps.isNotEmpty()) { "Flow '$name' has no steps." }
        return Flow(
            name = name,
            description = description,
            trigger = t,
            steps = steps,
            errorConfig = errorConfig,
        )
    }
}

/**
 * Builder for call() step configuration (query params, headers, body expression).
 */
@IntegrationDsl
class CallConfigBuilder {
    var queryParams: Map<String, String> = emptyMap()
    var headers: Map<String, String> = emptyMap()
    var bodyExpression: String? = null

    fun build() = CallConfig(queryParams, headers, bodyExpression)
}
