package io.devtech.integration.management.runtime

import io.devtech.integration.camel.ExposeRouteGenerator
import io.devtech.integration.camel.FlowRouteGenerator
import io.devtech.integration.camel.MetricsRegistry
import io.devtech.integration.dsl.AdapterRef
import io.devtech.integration.model.Integration
import org.apache.camel.impl.DefaultCamelContext
import org.slf4j.LoggerFactory

/**
 * Lifecycle state for a managed integration context.
 */
enum class IntegrationState {
    CREATED, STARTING, RUNNING, STOPPING, STOPPED, FAILED
}

/**
 * Non-blocking CamelContext runtime for a single integration.
 *
 * Unlike [io.devtech.integration.IntegrationRuntime] which uses Camel Main (blocking,
 * owns the JVM lifecycle), this uses [DefaultCamelContext] directly — start/stop are
 * non-blocking, allowing multiple integrations to coexist in the same JVM.
 *
 * Each integration gets its own CamelContext with its own `restConfiguration()`,
 * avoiding port conflicts between integrations.
 */
class IntegrationContextRuntime(
    val integration: Integration,
    private val adapterRefs: List<AdapterRef> = emptyList(),
) {
    private val log = LoggerFactory.getLogger("mgmt.${integration.name}")
    private var camelContext: DefaultCamelContext? = null

    var state: IntegrationState = IntegrationState.CREATED
        private set
    var error: String? = null
        private set

    fun start() {
        if (state == IntegrationState.RUNNING) return
        state = IntegrationState.STARTING
        error = null

        try {
            val ctx = DefaultCamelContext()
            val ctxName = "ctx-${integration.name}"
            ctx.nameStrategy = org.apache.camel.impl.engine.DefaultCamelContextNameStrategy(ctxName)

            // Camel-managed metrics: route-level counters, timers, and failure tracking
            val policyFactory = org.apache.camel.component.micrometer.routepolicy.MicrometerRoutePolicyFactory()
            policyFactory.meterRegistry = MetricsRegistry.registry
            ctx.addRoutePolicyFactory(policyFactory)
            ctx.isUseMDCLogging = true

            // Touch MetricsRegistry to init JVM metrics
            MetricsRegistry.registry

            // Generate and add Camel routes from the integration model
            val routeGenerator = ExposeRouteGenerator(integration)
            ctx.addRoutes(routeGenerator.generate())

            // Generate flow routes (non-ApiCall triggered flows)
            val flowRouteGenerator = FlowRouteGenerator(integration, adapterRefs)
            ctx.addRoutes(flowRouteGenerator.generate())

            // Start the context (non-blocking)
            ctx.start()

            // Bind ProducerTemplate to Camel-managed adapter backends
            val camelAdapters = adapterRefs.filter { it.isCamelManaged }
            if (camelAdapters.isNotEmpty()) {
                val template = ctx.createProducerTemplate()
                for (ref in camelAdapters) {
                    ref.bindProducerTemplate(template)
                    log.info("Adapter '{}' bound to ProducerTemplate", ref.name)
                }
            }

            camelContext = ctx
            state = IntegrationState.RUNNING

            val expose = integration.expose
            if (expose != null) {
                log.info("Started {} v{} on http://{}:{}", integration.name, integration.version, expose.host, expose.port)
                for (op in expose.spec.operations.values) {
                    val implemented = integration.implementations.any { it.operationId == op.operationId }
                    val status = if (implemented) "OK" else "NOT IMPL"
                    log.info("  {} {} {} ({})", status, op.method, op.path, op.operationId)
                }
            } else {
                log.info("Started {} v{} (no exposed API)", integration.name, integration.version)
            }
        } catch (e: Exception) {
            state = IntegrationState.FAILED
            error = e.message
            log.error("Failed to start {}: {}", integration.name, e.message, e)
            // Clean up partial context
            try { camelContext?.stop() } catch (_: Exception) {}
            camelContext = null
            throw e
        }
    }

    fun stop() {
        if (state != IntegrationState.RUNNING && state != IntegrationState.FAILED) return
        state = IntegrationState.STOPPING

        try {
            camelContext?.stop()
            camelContext = null
            state = IntegrationState.STOPPED
            log.info("Stopped {}", integration.name)
        } catch (e: Exception) {
            state = IntegrationState.FAILED
            error = e.message
            log.error("Error stopping {}: {}", integration.name, e.message, e)
        }
    }

    fun status(): Map<String, Any?> = mapOf(
        "name" to integration.name,
        "version" to integration.version,
        "state" to state.name,
        "port" to integration.expose?.port,
        "error" to error,
        "operations" to (integration.expose?.spec?.operations?.keys?.toList() ?: emptyList<String>()),
    )
}
