package io.devtech.integration.dsl

import io.devtech.integration.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TriggeredFlowBuilderTest {

    private val testSpecPath = javaClass.classLoader.getResource("test-spec.yaml")!!.path

    @Nested
    inner class TriggerTypes {

        @Test
        fun `every() creates Schedule trigger`() {
            val flow = TriggeredFlowBuilder("test").apply {
                every(5.minutes)
                log("tick")
            }.build()
            val trigger = flow.trigger as Trigger.Schedule
            assertEquals(300_000, trigger.intervalMs)
        }

        @Test
        fun `cron() creates Cron trigger`() {
            val flow = TriggeredFlowBuilder("test").apply {
                cron("0 0 2 * * ?")
                log("cron fired")
            }.build()
            val trigger = flow.trigger as Trigger.Cron
            assertEquals("0 0 2 * * ?", trigger.expression)
        }

        @Test
        fun `after() creates After trigger`() {
            val flow = TriggeredFlowBuilder("test").apply {
                after("sync-users")
                log("post-sync")
            }.build()
            val trigger = flow.trigger as Trigger.After
            assertEquals("sync-users", trigger.flowName)
        }

        @Test
        fun `manual() creates Manual trigger`() {
            val flow = TriggeredFlowBuilder("test").apply {
                manual()
                log("manual run")
            }.build()
            assertEquals(Trigger.Manual, flow.trigger)
        }

        @Test
        fun `no trigger throws error`() {
            val ex = assertThrows(IllegalStateException::class.java) {
                TriggeredFlowBuilder("test").apply {
                    log("no trigger")
                }.build()
            }
            assertTrue(ex.message!!.contains("no trigger"))
        }
    }

    @Nested
    inner class StepCollection {

        @Test
        fun `no steps throws error`() {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                TriggeredFlowBuilder("test").apply {
                    every(1.minutes)
                }.build()
            }
            assertTrue(ex.message!!.contains("no steps"))
        }

        @Test
        fun `steps collected in order`() {
            val flow = TriggeredFlowBuilder("test").apply {
                every(1.minutes)
                log("first")
                process("proc") { it }
                log("last")
            }.build()
            assertEquals(3, flow.steps.size)
            assertTrue(flow.steps[0] is Step.Log)
            assertTrue(flow.steps[1] is Step.Process)
            assertTrue(flow.steps[2] is Step.Log)
        }

        @Test
        fun `log step with level`() {
            val flow = TriggeredFlowBuilder("test").apply {
                every(1.minutes)
                log("warning!", LogLevel.WARN)
            }.build()
            val step = flow.steps[0] as Step.Log
            assertEquals("warning!", step.message)
            assertEquals(LogLevel.WARN, step.level)
        }

        @Test
        fun `process step with handler`() {
            val flow = TriggeredFlowBuilder("test").apply {
                every(1.minutes)
                process("transform") { data -> mapOf("processed" to true) }
            }.build()
            val step = flow.steps[0] as Step.Process
            assertEquals("transform", step.name)
            @Suppress("UNCHECKED_CAST")
            val result = step.handler(emptyMap()) as Map<String, Any?>
            assertEquals(true, result["processed"])
        }

        @Test
        fun `filter step`() {
            val flow = TriggeredFlowBuilder("test").apply {
                every(1.minutes)
                filter("\${body} != null")
                log("filtered")
            }.build()
            val step = flow.steps[0] as Step.Filter
            assertEquals("\${body} != null", step.expression)
        }

        @Test
        fun `call step captures adapter ref`() {
            var adapterRef: AdapterRef? = null
            integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
                val adapterSpecPath = javaClass.classLoader.getResource("adapter-test-spec.yaml")!!.path
                val ext = adapter("external", spec(adapterSpecPath)) { baseUrl = "https://example.com" }
                adapterRef = ext
                flow("greet", handler { _ -> respond("ok" to true) })
            }

            val builder = TriggeredFlowBuilder("test").apply {
                every(1.minutes)
                call(adapterRef!!, HttpMethod.GET, "/users")
            }
            val flow = builder.build()
            val step = flow.steps[0] as Step.Call
            assertEquals("external", step.adapterName)
            assertEquals(HttpMethod.GET, step.method)
            assertEquals("/users", step.path)
            assertEquals(1, builder.adapterRefs.size)
            assertEquals("external", builder.adapterRefs[0].name)
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `onError creates ErrorConfig`() {
            val flow = TriggeredFlowBuilder("test").apply {
                every(1.minutes)
                onError {
                    retry { maxAttempts = 5; delayMs = 1000 }
                }
                log("with retry")
            }.build()
            assertNotNull(flow.errorConfig)
            assertEquals(5, flow.errorConfig!!.retry!!.maxAttempts)
            assertEquals(1000, flow.errorConfig!!.retry!!.delayMs)
        }

        @Test
        fun `no onError means null errorConfig`() {
            val flow = TriggeredFlowBuilder("test").apply {
                every(1.minutes)
                log("no error config")
            }.build()
            assertNull(flow.errorConfig)
        }
    }

    @Nested
    inner class FlowMetadata {

        @Test
        fun `name is set`() {
            val flow = TriggeredFlowBuilder("my-flow").apply {
                every(1.minutes)
                log("tick")
            }.build()
            assertEquals("my-flow", flow.name)
        }

        @Test
        fun `description can be set`() {
            val flow = TriggeredFlowBuilder("my-flow").apply {
                description = "A periodic task"
                every(1.minutes)
                log("tick")
            }.build()
            assertEquals("A periodic task", flow.description)
        }

        @Test
        fun `description defaults to null`() {
            val flow = TriggeredFlowBuilder("my-flow").apply {
                every(1.minutes)
                log("tick")
            }.build()
            assertNull(flow.description)
        }
    }

    @Nested
    inner class IntegrationBuilderTriggered {

        @Test
        fun `triggered() populates integration flows`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
                flow("greet", handler { _ -> respond("ok" to true) })
                triggered("heartbeat") {
                    every(30.seconds)
                    log("alive")
                }
            }
            assertEquals(2, result.flows.size)
            val apiFlow = result.flows.find { it.name == "greet" }!!
            val triggeredFlow = result.flows.find { it.name == "heartbeat" }!!
            assertTrue(apiFlow.trigger is Trigger.ApiCall)
            assertTrue(triggeredFlow.trigger is Trigger.Schedule)
        }

        @Test
        fun `flow() also populates integration flows`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
                flow("greet", handler { _ -> respond("greeting" to "hi") })
                flow("status", handler { _ -> respond("ok" to true) })
            }
            assertEquals(2, result.flows.size)
            assertTrue(result.flows.all { it.trigger is Trigger.ApiCall })
        }

        @Test
        fun `flow block form populates integration flows`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
                flow("greet") {
                    onError { retry { maxAttempts = 3 } }
                    handle { _ -> respond("greeting" to "hi") }
                }
            }
            assertEquals(1, result.flows.size)
            val flow = result.flows[0]
            assertEquals("greet", flow.name)
            assertTrue(flow.trigger is Trigger.ApiCall)
            assertNotNull(flow.errorConfig)
        }

        @Test
        fun `triggered flow without expose works`() {
            val result = integration("worker") {
                triggered("poll") {
                    every(1.minutes)
                    log("polling...")
                }
            }
            assertEquals(1, result.flows.size)
            assertNull(result.expose)
        }
    }

    @Nested
    inner class FlowRouteGeneratorBasic {

        @Test
        fun `ApiCall flows are skipped`() {
            val integration = Integration(
                name = "test",
                flows = listOf(
                    Flow("api-flow", trigger = Trigger.ApiCall, steps = listOf(Step.Log("hi"))),
                    Flow("timer-flow", trigger = Trigger.Schedule(1000), steps = listOf(Step.Log("tick"))),
                ),
            )
            val generator = io.devtech.integration.camel.FlowRouteGenerator(integration)
            val routeBuilder = generator.generate()

            // Create a context to verify routes
            val ctx = org.apache.camel.impl.DefaultCamelContext()
            ctx.addRoutes(routeBuilder)
            ctx.start()
            try {
                val routeIds = ctx.routes.map { it.routeId }
                assertTrue(routeIds.contains("flow-timer-flow"))
                assertFalse(routeIds.any { it.contains("api-flow") })
            } finally {
                ctx.stop()
            }
        }

        @Test
        fun `Schedule trigger generates timer route`() {
            val integration = Integration(
                name = "test",
                flows = listOf(
                    Flow("ticker", trigger = Trigger.Schedule(5000), steps = listOf(Step.Log("tick"))),
                ),
            )
            val generator = io.devtech.integration.camel.FlowRouteGenerator(integration)
            val routeBuilder = generator.generate()

            val ctx = org.apache.camel.impl.DefaultCamelContext()
            ctx.addRoutes(routeBuilder)
            ctx.start()
            try {
                val route = ctx.getRoute("flow-ticker")
                assertNotNull(route)
                assertTrue(route.endpoint.endpointUri.contains("timer://ticker"))
            } finally {
                ctx.stop()
            }
        }

        @Test
        fun `After trigger generates direct route`() {
            val integration = Integration(
                name = "test",
                flows = listOf(
                    Flow("post-sync", trigger = Trigger.After("sync"), steps = listOf(Step.Log("done"))),
                ),
            )
            val generator = io.devtech.integration.camel.FlowRouteGenerator(integration)
            val routeBuilder = generator.generate()

            val ctx = org.apache.camel.impl.DefaultCamelContext()
            ctx.addRoutes(routeBuilder)
            ctx.start()
            try {
                val route = ctx.getRoute("flow-post-sync")
                assertNotNull(route)
                assertTrue(route.endpoint.endpointUri.contains("direct://after-sync"))
            } finally {
                ctx.stop()
            }
        }

        @Test
        fun `Manual trigger generates direct route`() {
            val integration = Integration(
                name = "test",
                flows = listOf(
                    Flow("manual-job", trigger = Trigger.Manual, steps = listOf(Step.Log("manual run"))),
                ),
            )
            val generator = io.devtech.integration.camel.FlowRouteGenerator(integration)
            val routeBuilder = generator.generate()

            val ctx = org.apache.camel.impl.DefaultCamelContext()
            ctx.addRoutes(routeBuilder)
            ctx.start()
            try {
                val route = ctx.getRoute("flow-manual-job")
                assertNotNull(route)
                assertTrue(route.endpoint.endpointUri.contains("direct://manual-manual-job"))
            } finally {
                ctx.stop()
            }
        }

        @Test
        fun `Process step invokes handler`() {
            var handlerCalled = false
            val integration = Integration(
                name = "test",
                flows = listOf(
                    Flow(
                        "proc-flow",
                        trigger = Trigger.Manual,
                        steps = listOf(Step.Process("test") { data ->
                            handlerCalled = true
                            mapOf("result" to "done")
                        }),
                    ),
                ),
            )
            val generator = io.devtech.integration.camel.FlowRouteGenerator(integration)
            val routeBuilder = generator.generate()

            val ctx = org.apache.camel.impl.DefaultCamelContext()
            ctx.addRoutes(routeBuilder)
            ctx.start()
            try {
                val template = ctx.createProducerTemplate()
                template.sendBody("direct:manual-proc-flow", "test")
                assertTrue(handlerCalled)
            } finally {
                ctx.stop()
            }
        }

        @Test
        fun `after-chain fires when listener exists`() {
            var afterCalled = false
            val integration = Integration(
                name = "test",
                flows = listOf(
                    Flow(
                        "source-flow",
                        trigger = Trigger.Manual,
                        steps = listOf(Step.Log("source done")),
                    ),
                    Flow(
                        "after-flow",
                        trigger = Trigger.After("source-flow"),
                        steps = listOf(Step.Process("marker") {
                            afterCalled = true
                            null
                        }),
                    ),
                ),
            )
            val generator = io.devtech.integration.camel.FlowRouteGenerator(integration)
            val routeBuilder = generator.generate()

            val ctx = org.apache.camel.impl.DefaultCamelContext()
            ctx.addRoutes(routeBuilder)
            ctx.start()
            try {
                val template = ctx.createProducerTemplate()
                template.sendBody("direct:manual-source-flow", "trigger")
                assertTrue(afterCalled)
            } finally {
                ctx.stop()
            }
        }
    }
}
