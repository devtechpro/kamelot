package io.devtech.integration.camel

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.util.concurrent.TimeUnit

/**
 * Global metrics registry. Auto-registers JVM metrics on init.
 * Every route gets call count, duration, and error count for free.
 *
 * Exposed at /metrics in Prometheus format.
 */
object MetricsRegistry {

    val registry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    init {
        // JVM metrics — free, automatic
        JvmMemoryMetrics().bindTo(registry)
        JvmGcMetrics().bindTo(registry)
        JvmThreadMetrics().bindTo(registry)
        ClassLoaderMetrics().bindTo(registry)
        ProcessorMetrics().bindTo(registry)
        UptimeMetrics().bindTo(registry)
    }

    /**
     * Record a successful or failed API call.
     * Creates/increments counters and timers per integration + operation.
     */
    fun recordCall(integration: String, operationId: String, statusCode: Int, durationMs: Double) {
        // Call counter
        Counter.builder("integration.calls.total")
            .tag("integration", integration)
            .tag("operation", operationId)
            .tag("status", statusCode.toString())
            .tag("status_class", "${statusCode / 100}xx")
            .register(registry)
            .increment()

        // Duration timer
        Timer.builder("integration.call.duration")
            .tag("integration", integration)
            .tag("operation", operationId)
            .register(registry)
            .record((durationMs * 1_000_000).toLong(), TimeUnit.NANOSECONDS)
    }

    /**
     * Record an error with exception class tag.
     */
    fun recordError(integration: String, operationId: String, error: Exception) {
        Counter.builder("integration.errors.total")
            .tag("integration", integration)
            .tag("operation", operationId)
            .tag("exception", error.javaClass.simpleName)
            .register(registry)
            .increment()
    }
}
