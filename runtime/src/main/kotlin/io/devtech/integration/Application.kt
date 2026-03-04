package io.devtech.integration

import io.devtech.integration.camel.ExposeRouteGenerator
import io.devtech.integration.camel.FlowRouteGenerator
import io.devtech.integration.camel.MetricsRegistry
import io.devtech.integration.debug.DebugConfig
import io.devtech.integration.debug.DebugManager
import io.devtech.integration.dsl.AdapterRef
import io.devtech.integration.dsl.IntegrationBuilder
import io.devtech.integration.model.Integration
import org.apache.camel.main.BaseMainSupport
import org.apache.camel.main.Main
import org.apache.camel.main.MainListenerSupport
import org.slf4j.LoggerFactory

/**
 * Convenience entry point — define and run an integration in one call.
 *
 * ```kotlin
 * fun main() = execute("echo-service") {
 *     val api = spec("specs/echo-openapi.yaml")
 *     expose(api, port = 5400)
 *     flow("echo", echoHandler)
 * }
 * ```
 */
fun execute(name: String, debug: Boolean? = null, block: IntegrationBuilder.() -> Unit) {
    val builder = IntegrationBuilder(name).apply(block)
    IntegrationRuntime(builder.build(), builder.adapterRefs, debug).start()
}

/**
 * Standalone Camel Main runtime.
 * Takes an [Integration] model and runs it as an embedded Camel application.
 *
 * Automatically provides:
 * - Structured logging for all routes
 * - Prometheus metrics at /metrics
 * - JVM metrics (memory, GC, threads, CPU)
 * - Per-operation call count, duration, error count
 * - Debug mode with traces, breakpoints, and step-through (when enabled)
 *
 * Debug mode can be enabled via:
 * - Constructor parameter: `IntegrationRuntime(integration, debug = true)`
 * - Environment variable: `INTEGRATION_DEBUG=true`
 */
class IntegrationRuntime(
    private val integration: Integration,
    private val adapterRefs: List<AdapterRef> = emptyList(),
    debug: Boolean? = null,
) {
    private val log = LoggerFactory.getLogger(integration.name)
    private val camelMain = Main()

    // Debug mode: explicit flag overrides env var
    private val debugEnabled = debug ?: (System.getenv("INTEGRATION_DEBUG")?.lowercase() == "true")
    private val debugManager: DebugManager? = if (debugEnabled) {
        DebugManager(DebugConfig(enabled = true))
    } else null

    fun start() {
        val expose = integration.expose

        log.info("Starting integration: {} v{}", integration.name, integration.version)
        integration.description?.let { log.info("  {}", it) }

        if (expose != null) {
            log.info("Exposed API on http://{}:{}", expose.host, expose.port)
            log.info("  OpenAPI spec: http://{}:{}/openapi.json", expose.host, expose.port)
            log.info("  Metrics:      http://{}:{}/metrics", expose.host, expose.port)

            if (debugEnabled) {
                log.info("  Debug API:    http://{}:{}/debug", expose.host, expose.port)
                log.info("  Debug mode is ENABLED — traces, breakpoints, and step-through active")
            }

            for (op in expose.spec.operations.values) {
                val implemented = integration.implementations.any { it.operationId == op.operationId }
                val status = if (implemented) "OK" else "NOT IMPLEMENTED"
                log.info("  {} {} {} ({})", status, op.method, op.path, op.operationId)
            }
        }

        // Touch MetricsRegistry to init JVM metrics
        MetricsRegistry.registry

        // Generate and add Camel routes
        val routeGenerator = ExposeRouteGenerator(integration, debugManager)
        camelMain.configure().addRoutesBuilder(routeGenerator.generate())

        // Generate flow routes (non-ApiCall triggered flows)
        val flowRouteGenerator = FlowRouteGenerator(integration, adapterRefs)
        camelMain.configure().addRoutesBuilder(flowRouteGenerator.generate())

        // Configure Camel context: micrometer route policy, MDC logging, adapter binding
        val camelAdapters = adapterRefs.filter { it.isCamelManaged }
        camelMain.addMainListener(object : MainListenerSupport() {
            override fun afterConfigure(main: BaseMainSupport) {
                val ctx = main.camelContext
                // Camel-managed metrics: route-level counters, timers, and failure tracking
                val policyFactory = org.apache.camel.component.micrometer.routepolicy.MicrometerRoutePolicyFactory()
                policyFactory.meterRegistry = MetricsRegistry.registry
                ctx.addRoutePolicyFactory(policyFactory)
                ctx.setMDCLoggingKeysPattern("*")

                // Inject ProducerTemplate into Camel-managed adapter backends
                if (camelAdapters.isNotEmpty()) {
                    val template = ctx.createProducerTemplate()
                    for (ref in camelAdapters) {
                        ref.bindProducerTemplate(template)
                        log.info("Adapter '{}' bound to Camel ProducerTemplate", ref.name)
                    }
                }
            }
        })

        // Start Camel
        camelMain.run()
    }

    fun stop() {
        camelMain.stop()
    }
}
