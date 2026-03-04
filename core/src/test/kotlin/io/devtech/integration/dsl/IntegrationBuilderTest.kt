package io.devtech.integration.dsl

import io.devtech.integration.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class IntegrationBuilderTest {

    private val testSpecPath = javaClass.classLoader.getResource("test-spec.yaml")!!.path

    @Nested
    inner class PropertyAssignment {

        @Test
        fun `version defaults to 1`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
            }
            assertEquals(1, result.version)
        }

        @Test
        fun `version can be set`() {
            val result = integration("test") {
                version = 2
                val api = spec(testSpecPath)
                expose(api, port = 9000)
            }
            assertEquals(2, result.version)
        }

        @Test
        fun `description defaults to null`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
            }
            assertNull(result.description)
        }

        @Test
        fun `description can be set`() {
            val result = integration("test") {
                description = "My service"
                val api = spec(testSpecPath)
                expose(api, port = 9000)
            }
            assertEquals("My service", result.description)
        }

        @Test
        fun `name is passed through`() {
            val result = integration("my-service") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
            }
            assertEquals("my-service", result.name)
        }
    }

    @Nested
    inner class SpecLoading {

        @Test
        fun `spec loads and returns SpecRef`() {
            var specRef: SpecRef? = null
            integration("test") {
                specRef = spec(testSpecPath)
                expose(specRef!!, port = 9000)
            }
            assertNotNull(specRef)
            assertEquals("Test API", specRef!!.name)
        }

        @Test
        fun `spec is included in built integration`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
            }
            assertEquals(1, result.specs.size)
            assertEquals("Test API", result.specs[0].name)
            assertEquals(SpecType.OPENAPI, result.specs[0].type)
        }

        @Test
        fun `spec operations are parsed`() {
            var specRef: SpecRef? = null
            integration("test") {
                specRef = spec(testSpecPath)
                expose(specRef!!, port = 9000)
            }
            val spec = specRef!!.spec
            assertEquals(2, spec.operations.size)
            assertTrue(spec.operations.containsKey("greet"))
            assertTrue(spec.operations.containsKey("status"))
        }

        @Test
        fun `spec schemas are parsed`() {
            var specRef: SpecRef? = null
            integration("test") {
                specRef = spec(testSpecPath)
                expose(specRef!!, port = 9000)
            }
            val spec = specRef!!.spec
            assertEquals(3, spec.schemas.size)
            assertTrue(spec.schemas.containsKey("GreetRequest"))
            assertTrue(spec.schemas.containsKey("GreetResponse"))
            assertTrue(spec.schemas.containsKey("StatusResponse"))
        }

        @Test
        fun `invalid spec path throws`() {
            assertThrows(IllegalArgumentException::class.java) {
                integration("test") {
                    spec("nonexistent/path.yaml")
                }
            }
        }
    }

    @Nested
    inner class SpecRefAccess {

        @Test
        fun `SpecRef get returns SchemaRef for valid schema`() {
            var specRef: SpecRef? = null
            integration("test") {
                specRef = spec(testSpecPath)
                expose(specRef!!, port = 9000)
            }
            val schemaRef = specRef!!["GreetRequest"]
            assertEquals("GreetRequest", schemaRef.name)
            assertTrue(schemaRef.hasField("name"))
        }

        @Test
        fun `SpecRef get throws for unknown schema`() {
            var specRef: SpecRef? = null
            integration("test") {
                specRef = spec(testSpecPath)
                expose(specRef!!, port = 9000)
            }
            val ex = assertThrows(IllegalArgumentException::class.java) {
                specRef!!["NonExistent"]
            }
            assertTrue(ex.message!!.contains("NonExistent"))
            assertTrue(ex.message!!.contains("Available"))
        }

        @Test
        fun `SchemaRef fieldNames returns all fields`() {
            var specRef: SpecRef? = null
            integration("test") {
                specRef = spec(testSpecPath)
                expose(specRef!!, port = 9000)
            }
            val fields = specRef!!["GreetResponse"].fieldNames
            assertEquals(setOf("greeting", "timestamp"), fields)
        }
    }

    @Nested
    inner class ExposeShorthand {

        @Test
        fun `expose with port produces correct config`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 5400)
            }
            assertNotNull(result.expose)
            assertEquals(5400, result.expose!!.port)
            assertEquals("0.0.0.0", result.expose!!.host)
        }

        @Test
        fun `expose with port and host`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 5400, host = "127.0.0.1")
            }
            assertEquals("127.0.0.1", result.expose!!.host)
        }

        @Test
        fun `expose config references loaded spec`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 5400)
            }
            assertEquals("Test API", result.expose!!.spec.name)
        }
    }

    @Nested
    inner class ExposeBlockForm {

        @Test
        fun `expose block form sets port`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api) {
                    port = 7000
                }
            }
            assertEquals(7000, result.expose!!.port)
        }

        @Test
        fun `expose block form sets host`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api) {
                    port = 7000
                    host = "localhost"
                }
            }
            assertEquals("localhost", result.expose!!.host)
        }

        @Test
        fun `expose block form default port is 8080`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api) {}
            }
            assertEquals(8080, result.expose!!.port)
        }

        @Test
        fun `expose block form default host is 0_0_0_0`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api) {}
            }
            assertEquals("0.0.0.0", result.expose!!.host)
        }
    }

    @Nested
    inner class HandlerWiring {

        @Test
        fun `flow wires handler to operation`() {
            val myHandler = handler { _ -> respond("ok" to true) }
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
                flow("greet", myHandler)
            }
            assertEquals(1, result.implementations.size)
            assertEquals("greet", result.implementations[0].operationId)
            assertNotNull(result.implementations[0].handler)
        }

        @Test
        fun `flow with block form wires handler`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
                flow("greet") { handle { _ -> respond("greeting" to "hi") } }
            }
            assertEquals(1, result.implementations.size)
            assertEquals("greet", result.implementations[0].operationId)
        }

        @Test
        fun `multiple operations wired correctly`() {
            val greetHandler = handler { _ -> respond("greeting" to "hi") }
            val statusHandler = handler { _ -> respond("ok" to true) }
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
                flow("greet", greetHandler)
                flow("status", statusHandler)
            }
            assertEquals(2, result.implementations.size)
            val opIds = result.implementations.map { it.operationId }.toSet()
            assertEquals(setOf("greet", "status"), opIds)
        }

        @Test
        fun `wired handler is callable`() {
            val myHandler = handler { req ->
                respond("echo" to req.body["name"])
            }
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
                flow("greet", myHandler)
            }
            val impl = result.implementations[0]
            val response = impl.handler!!(RequestContext(body = mapOf("name" to "Alice")))
            assertEquals(200, response.statusCode)
            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any?>
            assertEquals("Alice", body["echo"])
        }

        @Test
        fun `flow throws for unknown operation`() {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                integration("test") {
                    val api = spec(testSpecPath)
                    expose(api, port = 9000)
                    flow("nonexistent", handler { _ -> respond() })
                }
            }
            assertTrue(ex.message!!.contains("nonexistent"))
            assertTrue(ex.message!!.contains("Available"))
            assertTrue(ex.message!!.contains("greet"))
            assertTrue(ex.message!!.contains("status"))
        }

        @Test
        fun `flow before expose throws`() {
            val ex = assertThrows(IllegalStateException::class.java) {
                integration("test") {
                    spec(testSpecPath)
                    flow("greet", handler { _ -> respond() })
                }
            }
            assertTrue(ex.message!!.contains("expose()"))
        }

        @Test
        fun `duplicate flow for same operation throws`() {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                integration("test") {
                    val api = spec(testSpecPath)
                    expose(api, port = 9000)
                    flow("greet", handler { _ -> respond("greeting" to "hi") })
                    flow("greet", handler { _ -> respond("greeting" to "hello") })
                }
            }
            assertTrue(ex.message!!.contains("already has a handler"))
        }

        @Test
        fun `flow with adapter-returning handler`() {
            val adapterSpecPath = javaClass.classLoader.getResource("adapter-test-spec.yaml")!!.path
            fun enrichHandler(api: AdapterRef) = handler { req ->
                respond("userId" to req.body["userId"], "adapter" to api.name)
            }

            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
                val externalApi = adapter("external", spec(adapterSpecPath)) {
                    baseUrl = "https://example.com"
                }
                flow("greet", enrichHandler(externalApi))
            }
            assertEquals(1, result.implementations.size)
            val response = result.implementations[0].handler!!(
                RequestContext(body = mapOf("userId" to 42))
            )
            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any?>
            assertEquals(42, body["userId"])
            assertEquals("external", body["adapter"])
        }
    }

    @Nested
    inner class BuildOutput {

        @Test
        fun `build produces complete Integration model`() {
            val greetHandler = handler { _ -> respond("greeting" to "hello") }
            val statusHandler = handler { _ -> respond("ok" to true) }
            val result = integration("my-api") {
                version = 3
                description = "My API service"
                val api = spec(testSpecPath)
                expose(api, port = 5400)
                flow("greet", greetHandler)
                flow("status", statusHandler)
            }

            assertEquals("my-api", result.name)
            assertEquals(3, result.version)
            assertEquals("My API service", result.description)
            assertEquals(1, result.specs.size)
            assertNotNull(result.expose)
            assertEquals(5400, result.expose!!.port)
            assertEquals(2, result.implementations.size)
        }

        @Test
        fun `minimal integration has sensible defaults`() {
            val result = integration("minimal") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
            }
            assertEquals("minimal", result.name)
            assertEquals(1, result.version)
            assertNull(result.description)
            assertTrue(result.adapters.isEmpty())
            assertTrue(result.flows.isEmpty())
            assertTrue(result.implementations.isEmpty())
        }
    }

    @Nested
    inner class FlowWithErrorConfig {

        @Test
        fun `flow block form with retry config`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
                flow("greet") {
                    onError {
                        retry { maxAttempts = 5; delayMs = 1000 }
                    }
                    handle { _ -> respond("greeting" to "hi") }
                }
            }
            val impl = result.implementations.first()
            assertEquals("greet", impl.operationId)
            assertNotNull(impl.handler)
            assertNotNull(impl.errorConfig?.retry)
            assertEquals(5, impl.errorConfig!!.retry!!.maxAttempts)
            assertEquals(1000, impl.errorConfig!!.retry!!.delayMs)
            assertNull(impl.errorConfig!!.circuitBreaker)
        }

        @Test
        fun `flow block form with circuit breaker config`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
                flow("greet") {
                    onError {
                        circuitBreaker {
                            failureRateThreshold = 75f
                            waitDurationInOpenStateMs = 60_000
                        }
                    }
                    handle { _ -> respond("greeting" to "hi") }
                }
            }
            val impl = result.implementations.first()
            assertNotNull(impl.errorConfig?.circuitBreaker)
            assertEquals(75.0f, impl.errorConfig!!.circuitBreaker!!.failureRateThreshold)
            assertEquals(60_000, impl.errorConfig!!.circuitBreaker!!.waitDurationInOpenStateMs)
            assertNull(impl.errorConfig!!.retry)
        }

        @Test
        fun `flow block form with retry and circuit breaker`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
                flow("greet") {
                    onError {
                        retry { maxAttempts = 3 }
                        circuitBreaker { slidingWindowSize = 20 }
                    }
                    handle { _ -> respond("greeting" to "hi") }
                }
            }
            val impl = result.implementations.first()
            assertNotNull(impl.errorConfig?.retry)
            assertNotNull(impl.errorConfig?.circuitBreaker)
            assertEquals(3, impl.errorConfig!!.retry!!.maxAttempts)
            assertEquals(20, impl.errorConfig!!.circuitBreaker!!.slidingWindowSize)
        }

        @Test
        fun `flow block form without onError has null errorConfig`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
                flow("greet") {
                    handle { _ -> respond("greeting" to "hi") }
                }
            }
            val impl = result.implementations.first()
            assertNotNull(impl.handler)
            assertNull(impl.errorConfig)
        }

        @Test
        fun `flow block form validates operation exists`() {
            assertThrows(IllegalArgumentException::class.java) {
                integration("test") {
                    val api = spec(testSpecPath)
                    expose(api, port = 9000)
                    flow("nonexistent") {
                        handle { _ -> respond() }
                    }
                }
            }
        }

        @Test
        fun `flow block form prevents duplicate operations`() {
            assertThrows(IllegalArgumentException::class.java) {
                integration("test") {
                    val api = spec(testSpecPath)
                    expose(api, port = 9000)
                    flow("greet") { handle { _ -> respond() } }
                    flow("greet") { handle { _ -> respond() } }
                }
            }
        }

        @Test
        fun `simple and block form can coexist`() {
            val result = integration("test") {
                val api = spec(testSpecPath)
                expose(api, port = 9000)
                flow("greet") {
                    onError { retry { maxAttempts = 3 } }
                    handle { _ -> respond("greeting" to "hi") }
                }
                flow("status", handler { _ -> respond("ok" to true) })
            }
            assertEquals(2, result.implementations.size)
            val greet = result.implementations.find { it.operationId == "greet" }!!
            val status = result.implementations.find { it.operationId == "status" }!!
            assertNotNull(greet.errorConfig?.retry)
            assertNull(status.errorConfig)
        }
    }
}
