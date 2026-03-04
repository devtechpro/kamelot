package io.devtech.integration.dsl

import io.devtech.integration.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FlowBuilderTest {

    @Nested
    inner class RetryConfigBuilderTests {

        @Test
        fun `defaults are sensible`() {
            val config = RetryConfigBuilder().build()
            assertEquals(3, config.maxAttempts)
            assertEquals(500, config.delayMs)
            assertEquals(2.0, config.backoffMultiplier)
            assertEquals(60_000, config.maxDelayMs)
        }

        @Test
        fun `custom values`() {
            val config = RetryConfigBuilder().apply {
                maxAttempts = 5
                delayMs = 1000
                backoffMultiplier = 3.0
                maxDelayMs = 120_000
            }.build()
            assertEquals(5, config.maxAttempts)
            assertEquals(1000, config.delayMs)
            assertEquals(3.0, config.backoffMultiplier)
            assertEquals(120_000, config.maxDelayMs)
        }

        @Test
        fun `maxAttempts less than 1 throws`() {
            assertThrows(IllegalArgumentException::class.java) {
                RetryConfigBuilder().apply { maxAttempts = 0 }.build()
            }
        }

        @Test
        fun `negative delayMs throws`() {
            assertThrows(IllegalArgumentException::class.java) {
                RetryConfigBuilder().apply { delayMs = -1 }.build()
            }
        }
    }

    @Nested
    inner class CircuitBreakerConfigBuilderTests {

        @Test
        fun `defaults are sensible`() {
            val config = CircuitBreakerConfigBuilder().build()
            assertEquals(10, config.slidingWindowSize)
            assertEquals(5, config.minimumCalls)
            assertEquals(50.0f, config.failureRateThreshold)
            assertEquals(30_000, config.waitDurationInOpenStateMs)
            assertEquals(3, config.permittedCallsInHalfOpen)
        }

        @Test
        fun `custom values`() {
            val config = CircuitBreakerConfigBuilder().apply {
                slidingWindowSize = 20
                minimumCalls = 10
                failureRateThreshold = 75.0f
                waitDurationInOpenStateMs = 60_000
                permittedCallsInHalfOpen = 5
            }.build()
            assertEquals(20, config.slidingWindowSize)
            assertEquals(10, config.minimumCalls)
            assertEquals(75.0f, config.failureRateThreshold)
            assertEquals(60_000, config.waitDurationInOpenStateMs)
            assertEquals(5, config.permittedCallsInHalfOpen)
        }
    }

    @Nested
    inner class ErrorConfigBuilderTests {

        @Test
        fun `retry only`() {
            val config = ErrorConfigBuilder().apply {
                retry { maxAttempts = 5 }
            }.build()
            assertNotNull(config.retry)
            assertNull(config.circuitBreaker)
            assertEquals(5, config.retry!!.maxAttempts)
        }

        @Test
        fun `circuit breaker only`() {
            val config = ErrorConfigBuilder().apply {
                circuitBreaker { failureRateThreshold = 80f }
            }.build()
            assertNull(config.retry)
            assertNotNull(config.circuitBreaker)
            assertEquals(80.0f, config.circuitBreaker!!.failureRateThreshold)
        }

        @Test
        fun `both retry and circuit breaker`() {
            val config = ErrorConfigBuilder().apply {
                retry { maxAttempts = 3; delayMs = 200 }
                circuitBreaker { slidingWindowSize = 20 }
            }.build()
            assertNotNull(config.retry)
            assertNotNull(config.circuitBreaker)
            assertEquals(3, config.retry!!.maxAttempts)
            assertEquals(200, config.retry!!.delayMs)
            assertEquals(20, config.circuitBreaker!!.slidingWindowSize)
        }
    }

    @Nested
    inner class FlowBuilderTests {

        @Test
        fun `build with handler only`() {
            val impl = FlowBuilder("testOp").apply {
                handle { _ -> respond("ok" to true) }
            }.build()
            assertEquals("testOp", impl.operationId)
            assertNotNull(impl.handler)
            assertNull(impl.errorConfig)
        }

        @Test
        fun `build with onError and handler`() {
            val impl = FlowBuilder("testOp").apply {
                onError {
                    retry { maxAttempts = 5 }
                    circuitBreaker { failureRateThreshold = 60f }
                }
                handle { _ -> respond("ok" to true) }
            }.build()
            assertEquals("testOp", impl.operationId)
            assertNotNull(impl.handler)
            assertNotNull(impl.errorConfig)
            assertEquals(5, impl.errorConfig!!.retry!!.maxAttempts)
            assertEquals(60.0f, impl.errorConfig!!.circuitBreaker!!.failureRateThreshold)
        }

        @Test
        fun `build without handler or steps produces null handler`() {
            val impl = FlowBuilder("testOp").apply {
                onError { retry { maxAttempts = 2 } }
            }.build()
            assertEquals("testOp", impl.operationId)
            assertNull(impl.handler)
            assertNotNull(impl.errorConfig)
        }

        @Test
        fun `build without onError`() {
            val impl = FlowBuilder("testOp").apply {
                handle { _ -> respond("ok" to true) }
            }.build()
            assertNull(impl.errorConfig)
        }
    }

    @Nested
    inner class StepBasedFlows {

        private fun testAdapter(name: String = "test-db"): AdapterRef {
            val adapter = Adapter(name = name, spec = null)
            return AdapterRef(adapter, InMemoryBackend(name))
        }

        @Test
        fun `call adds Step Call and captures adapter ref`() {
            val db = testAdapter()
            val builder = FlowBuilder("listItems").apply {
                call(db, HttpMethod.GET, "/items")
            }
            val impl = builder.build()
            assertEquals(1, impl.steps.size)
            val step = impl.steps[0] as Step.Call
            assertEquals("test-db", step.adapterName)
            assertEquals(HttpMethod.GET, step.method)
            assertEquals("/items", step.path)
            assertEquals(1, builder.adapterRefs.size)
            assertEquals("test-db", builder.adapterRefs[0].name)
        }

        @Test
        fun `steps auto-generate handler when no handle called`() {
            val db = testAdapter()
            // Seed data
            db.backend.execute("POST", "/items", body = mapOf("name" to "Widget"))

            val impl = FlowBuilder("listItems").apply {
                call(db, HttpMethod.GET, "/items")
            }.build()

            assertNotNull(impl.handler)
            val response = impl.handler!!(RequestContext())
            assertEquals(200, response.statusCode)
            @Suppress("UNCHECKED_CAST")
            val items = response.body as List<Map<String, Any?>>
            assertEquals(1, items.size)
            assertEquals("Widget", items[0]["name"])
        }

        @Test
        fun `handle takes precedence over steps`() {
            val db = testAdapter()
            val impl = FlowBuilder("custom").apply {
                call(db, HttpMethod.GET, "/items")
                handle { _ -> respond("custom" to true) }
            }.build()

            val response = impl.handler!!(RequestContext())
            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any?>
            assertEquals(true, body["custom"])
        }

        @Test
        fun `statusCode property sets response code`() {
            val db = testAdapter()
            val impl = FlowBuilder("createItem").apply {
                statusCode = 201
                call(db, HttpMethod.POST, "/items")
            }.build()

            val response = impl.handler!!(RequestContext(body = mapOf("name" to "Gadget")))
            assertEquals(201, response.statusCode)
        }

        @Test
        fun `statusCode defaults to 200`() {
            val db = testAdapter()
            val impl = FlowBuilder("listItems").apply {
                call(db, HttpMethod.GET, "/items")
            }.build()

            val response = impl.handler!!(RequestContext())
            assertEquals(200, response.statusCode)
        }

        @Test
        fun `path params resolved in call path`() {
            val db = testAdapter()
            db.backend.execute("POST", "/items", body = mapOf("name" to "Widget"))

            val impl = FlowBuilder("getItem").apply {
                call(db, HttpMethod.GET, "/items/{id}")
            }.build()

            val response = impl.handler!!(RequestContext(pathParams = mapOf("id" to "1")))
            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any?>
            assertEquals("Widget", body["name"])
        }

        @Test
        fun `query params forwarded to adapter GET calls`() {
            val db = testAdapter()
            db.backend.execute("POST", "/items", body = mapOf("name" to "Widget"))
            db.backend.execute("POST", "/items", body = mapOf("name" to "Gadget"))

            val impl = FlowBuilder("searchItems").apply {
                call(db, HttpMethod.GET, "/items")
            }.build()

            val response = impl.handler!!(RequestContext(queryParams = mapOf("search" to "Widget")))
            @Suppress("UNCHECKED_CAST")
            val items = response.body as List<Map<String, Any?>>
            assertEquals(1, items.size)
            assertEquals("Widget", items[0]["name"])
        }

        @Test
        fun `process step transforms data before call`() {
            val db = testAdapter()
            val impl = FlowBuilder("createItem").apply {
                statusCode = 201
                process("addTimestamp") { body -> body + ("created_at" to "2026-01-01T00:00:00Z") }
                call(db, HttpMethod.POST, "/items")
            }.build()

            val response = impl.handler!!(RequestContext(body = mapOf("name" to "Widget")))
            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any?>
            assertEquals("Widget", body["name"])
            assertEquals("2026-01-01T00:00:00Z", body["created_at"])
            assertNotNull(body["id"])
        }

        @Test
        fun `multiple steps execute in order`() {
            val order = mutableListOf<String>()
            val db = testAdapter()

            val impl = FlowBuilder("pipeline").apply {
                process("step1") { data ->
                    order += "process1"
                    mapOf("name" to "processed")
                }
                log("between steps")
                process("step2") { data ->
                    order += "process2"
                    data
                }
                call(db, HttpMethod.POST, "/items")
            }.build()

            impl.handler!!(RequestContext(body = mapOf("name" to "raw")))
            assertEquals(listOf("process1", "process2"), order)
        }

        @Test
        fun `empty steps and no handler produces null handler`() {
            val impl = FlowBuilder("empty").build()
            assertNull(impl.handler)
            assertTrue(impl.steps.isEmpty())
        }

        @Test
        fun `resolvePath replaces path parameters`() {
            assertEquals("/items/42", FlowBuilder.resolvePath("/items/{id}", mapOf("id" to "42")))
            assertEquals("/a/1/b/2", FlowBuilder.resolvePath("/a/{x}/b/{y}", mapOf("x" to "1", "y" to "2")))
            assertEquals("/items/{id}", FlowBuilder.resolvePath("/items/{id}", emptyMap()))
        }

        @Test
        fun `DELETE call works through auto-handler`() {
            val db = testAdapter()
            db.backend.execute("POST", "/items", body = mapOf("name" to "Widget"))

            val impl = FlowBuilder("deleteItem").apply {
                call(db, HttpMethod.DELETE, "/items/{id}")
            }.build()

            val response = impl.handler!!(RequestContext(pathParams = mapOf("id" to "1")))
            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any?>
            assertEquals("Widget", body["name"])
        }

        @Test
        fun `PUT call works through auto-handler`() {
            val db = testAdapter()
            db.backend.execute("POST", "/items", body = mapOf("name" to "Widget"))

            val impl = FlowBuilder("updateItem").apply {
                call(db, HttpMethod.PUT, "/items/{id}")
            }.build()

            val response = impl.handler!!(RequestContext(
                body = mapOf("name" to "Super Widget"),
                pathParams = mapOf("id" to "1"),
            ))
            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any?>
            assertEquals("Super Widget", body["name"])
        }
    }
}
