package io.devtech.integration.camel

import io.devtech.integration.dsl.AdapterRef
import io.devtech.integration.model.*
import org.apache.camel.Exchange
import org.apache.camel.builder.RouteBuilder
import org.slf4j.LoggerFactory

/**
 * Generates Camel routes from non-ApiCall [Flow] objects.
 *
 * Each triggered flow (timer, cron, after, manual) becomes a Camel route:
 * - Schedule → `timer:{name}?period={ms}`
 * - Cron → `quartz:{name}?cron={expression}`
 * - After → `direct:after-{flowName}`
 * - Manual → `direct:manual-{name}`
 *
 * ApiCall flows are skipped — those are handled by [ExposeRouteGenerator].
 */
class FlowRouteGenerator(
    private val integration: Integration,
    private val adapterRefs: List<AdapterRef> = emptyList(),
) {
    private val logger = LoggerFactory.getLogger(integration.name)
    private val adapterMap: Map<String, AdapterRef> by lazy {
        adapterRefs.associateBy { it.name }
    }

    fun generate(): RouteBuilder = object : RouteBuilder() {
        override fun configure() {
            for (flow in integration.flows) {
                if (flow.trigger is Trigger.ApiCall) continue
                configureFlowRoute(flow)
            }
        }

        private fun configureFlowRoute(flow: Flow) {
            val fromUri = when (val t = flow.trigger) {
                is Trigger.Schedule -> "timer:${flow.name}?period=${t.intervalMs}"
                is Trigger.Cron     -> "quartz:${flow.name}?cron=${t.expression.replace(' ', '+')}"
                is Trigger.After    -> "direct:after-${t.flowName}"
                is Trigger.Manual   -> "direct:manual-${flow.name}"
                is Trigger.Webhook  -> "direct:webhook-${flow.name}"
                is Trigger.ApiCall  -> return
            }

            val route = from(fromUri).routeId("flow-${flow.name}")

            // Error handling
            val retryConfig = flow.errorConfig?.retry
            route.onException(Exception::class.java)
                .maximumRedeliveries(retryConfig?.let { it.maxAttempts - 1 } ?: 0)
                .redeliveryDelay(retryConfig?.delayMs ?: 0)
                .useExponentialBackOff()
                .backOffMultiplier(retryConfig?.backoffMultiplier ?: 2.0)
                .maximumRedeliveryDelay(retryConfig?.maxDelayMs ?: 60_000)
                .handled(true)
                .process { exchange ->
                    val cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception::class.java)
                    logger.error("Flow '{}' failed: {}", flow.name, cause?.message, cause)
                }
                .end()

            // Apply steps sequentially
            for (step in flow.steps) {
                when (step) {
                    is Step.Call -> {
                        val ref = adapterMap[step.adapterName]
                            ?: error("Flow '${flow.name}': adapter '${step.adapterName}' not found")
                        route.process { exchange ->
                            CamelContextHolder.set(exchange.context)
                            try {
                                @Suppress("UNCHECKED_CAST")
                                val body = exchange.message.getBody(Map::class.java) as? Map<String, Any?> ?: emptyMap()
                                val result = when (step.method) {
                                    HttpMethod.GET    -> ref.get(step.path, step.config.queryParams)
                                    HttpMethod.POST   -> ref.post(step.path, body)
                                    HttpMethod.PUT    -> ref.put(step.path, body)
                                    HttpMethod.DELETE -> ref.delete(step.path)
                                    HttpMethod.PATCH  -> ref.put(step.path, body)
                                }
                                exchange.message.body = result
                            } finally {
                                CamelContextHolder.clear()
                            }
                        }
                    }

                    is Step.Process -> {
                        route.process { exchange ->
                            @Suppress("UNCHECKED_CAST")
                            val body = exchange.message.getBody(Map::class.java) as? Map<String, Any?> ?: emptyMap()
                            exchange.message.body = step.handler(body)
                        }
                    }

                    is Step.Transform -> {
                        route.process { exchange ->
                            @Suppress("UNCHECKED_CAST")
                            val body = exchange.message.getBody(Map::class.java) as? MutableMap<String, Any?> ?: mutableMapOf()
                            for (mapping in step.mappings) {
                                val value = resolveFieldPath(body, mapping.source)
                                setFieldPath(body, mapping.target, applyTransform(value, mapping.transform))
                            }
                            exchange.message.body = body
                        }
                    }

                    is Step.Filter -> {
                        route.filter(simple(step.expression))
                    }

                    is Step.Log -> {
                        val camelLevel = when (step.level) {
                            LogLevel.DEBUG -> org.apache.camel.LoggingLevel.DEBUG
                            LogLevel.INFO  -> org.apache.camel.LoggingLevel.INFO
                            LogLevel.WARN  -> org.apache.camel.LoggingLevel.WARN
                            LogLevel.ERROR -> org.apache.camel.LoggingLevel.ERROR
                        }
                        route.log(camelLevel, logger.name, step.message)
                    }

                    is Step.Respond -> {
                        route.process { exchange ->
                            @Suppress("UNCHECKED_CAST")
                            val body = exchange.message.getBody(Map::class.java) as? Map<String, Any?> ?: emptyMap()
                            val builder = io.devtech.integration.dsl.ResponseBuilder(body)
                            builder.apply(step.block)
                            val result = builder.build(statusCode = step.statusCode)
                            exchange.message.body = result.body
                            exchange.message.setHeader(Exchange.HTTP_RESPONSE_CODE, result.statusCode)
                        }
                    }

                    is Step.MapFields -> {
                        route.process { exchange ->
                            @Suppress("UNCHECKED_CAST")
                            val body = exchange.message.getBody(Map::class.java) as? Map<String, Any?> ?: emptyMap()
                            val builder = io.devtech.integration.dsl.ResponseBuilder(body)
                            builder.apply(step.block)
                            exchange.message.body = builder.fields.toMap()
                        }
                    }
                }
            }

            // Fire "after" event if any flow listens for it
            val hasAfterListener = integration.flows.any {
                val t = it.trigger
                t is Trigger.After && t.flowName == flow.name
            }
            if (hasAfterListener) {
                route.to("direct:after-${flow.name}")
            }
        }
    }

    private fun resolveFieldPath(data: Map<String, Any?>, path: String): Any? {
        val parts = path.removePrefix("$.").split(".")
        var current: Any? = data
        for (part in parts) {
            current = (current as? Map<*, *>)?.get(part) ?: return null
        }
        return current
    }

    private fun setFieldPath(data: MutableMap<String, Any?>, path: String, value: Any?) {
        val key = path.removePrefix("$.")
        data[key] = value
    }

    private fun applyTransform(value: Any?, transform: FieldTransform?): Any? = when (transform) {
        null -> value
        is FieldTransform.Constant -> transform.value
        is FieldTransform.Now -> java.time.Instant.now().toString()
        is FieldTransform.Format -> String.format(transform.pattern, value)
        is FieldTransform.Mapped -> transform.mappings[value?.toString()] ?: transform.default ?: value
    }
}
