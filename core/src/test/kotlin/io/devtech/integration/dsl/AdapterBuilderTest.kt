package io.devtech.integration.dsl

import io.devtech.integration.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AdapterBuilderTest {

    private val testSpecPath = javaClass.classLoader.getResource("adapter-test-spec.yaml")!!.path

    @Nested
    inner class AdapterDsl {

        @Test
        fun `adapter registers in integration model`() {
            val apiSpecPath = javaClass.classLoader.getResource("test-spec.yaml")!!.path
            val result = integration("test") {
                val api = spec(apiSpecPath)
                expose(api, port = 9000)
                adapter("external", spec(testSpecPath)) {
                    baseUrl = "https://example.com"
                }
            }
            assertEquals(1, result.adapters.size)
            assertEquals("external", result.adapters[0].name)
            assertEquals("https://example.com", result.adapters[0].baseUrl)
        }

        @Test
        fun `adapter spec is loaded and stored`() {
            val apiSpecPath = javaClass.classLoader.getResource("test-spec.yaml")!!.path
            val result = integration("test") {
                val api = spec(apiSpecPath)
                expose(api, port = 9000)
                adapter("external", spec(testSpecPath)) {
                    baseUrl = "https://example.com"
                }
            }
            assertNotNull(result.adapters[0].spec)
            assertEquals("Test External API", result.adapters[0].spec!!.name)
            assertTrue(result.adapters[0].spec!!.operations.containsKey("getItem"))
            assertTrue(result.adapters[0].spec!!.operations.containsKey("createItem"))
        }

        @Test
        fun `multiple adapters registered`() {
            val apiSpecPath = javaClass.classLoader.getResource("test-spec.yaml")!!.path
            val result = integration("test") {
                val api = spec(apiSpecPath)
                expose(api, port = 9000)
                adapter("api-a", spec(testSpecPath)) { baseUrl = "https://a.example.com" }
                adapter("api-b", spec(testSpecPath)) { baseUrl = "https://b.example.com" }
            }
            assertEquals(2, result.adapters.size)
            assertEquals("api-a", result.adapters[0].name)
            assertEquals("api-b", result.adapters[1].name)
        }

        @Test
        fun `adapter without block uses defaults`() {
            val apiSpecPath = javaClass.classLoader.getResource("test-spec.yaml")!!.path
            val result = integration("test") {
                val api = spec(apiSpecPath)
                expose(api, port = 9000)
                adapter("external", spec(testSpecPath))
            }
            assertEquals(1, result.adapters.size)
            assertEquals("", result.adapters[0].baseUrl)
            assertNull(result.adapters[0].auth)
        }

        @Test
        fun `adapter with auth config`() {
            val apiSpecPath = javaClass.classLoader.getResource("test-spec.yaml")!!.path
            val result = integration("test") {
                val api = spec(apiSpecPath)
                expose(api, port = 9000)
                adapter("external", spec(testSpecPath)) {
                    baseUrl = "https://example.com"
                    auth = AuthConfig(type = AuthType.BEARER, secret = Secret("tok123"))
                }
            }
            assertNotNull(result.adapters[0].auth)
            assertEquals(AuthType.BEARER, result.adapters[0].auth!!.type)
            assertEquals("tok123", result.adapters[0].auth!!.secret?.value)
        }
    }

    @Nested
    inner class AdapterRefProperties {

        @Test
        fun `adapterRef exposes name`() {
            val adapter = Adapter(name = "test-api", baseUrl = "https://example.com")
            val ref = AdapterRef(adapter, InMemoryBackend("test-api"))
            assertEquals("test-api", ref.name)
        }

        @Test
        fun `adapterRef exposes adapter model`() {
            val adapter = Adapter(name = "test-api", baseUrl = "https://example.com")
            val ref = AdapterRef(adapter, InMemoryBackend("test-api"))
            assertEquals("https://example.com", ref.adapter.baseUrl)
        }
    }

    @Nested
    inner class NestedMapAccess {

        @Test
        fun `nested accesses top-level field`() {
            val data = mapOf("name" to "Alice")
            assertEquals("Alice", data.nested("name"))
        }

        @Test
        fun `nested accesses one level deep`() {
            val data = mapOf("address" to mapOf("city" to "Gwenborough"))
            assertEquals("Gwenborough", data.nested("address.city"))
        }

        @Test
        fun `nested accesses two levels deep`() {
            val data = mapOf("address" to mapOf("geo" to mapOf("lat" to "-37.3159")))
            assertEquals("-37.3159", data.nested("address.geo.lat"))
        }

        @Test
        fun `nested returns null for missing path`() {
            val data = mapOf("name" to "Alice")
            assertNull(data.nested("address.city"))
        }

        @Test
        fun `nested returns null for partial path`() {
            val data = mapOf("address" to mapOf("street" to "Kulas Light"))
            assertNull(data.nested("address.city"))
        }

        @Test
        fun `nested returns null for non-map intermediate`() {
            val data = mapOf("name" to "Alice")
            assertNull(data.nested("name.first"))
        }

        @Test
        fun `nested on empty map returns null`() {
            val data = emptyMap<String, Any?>()
            assertNull(data.nested("any.path"))
        }
    }

    @Nested
    inner class AdapterRefHttpCalls {

        private fun createCamelRef(): AdapterRef {
            val adapter = Adapter(
                name = "jsonplaceholder",
                baseUrl = "https://jsonplaceholder.typicode.com",
            )
            val backend = CamelHttpBackend("jsonplaceholder", "https://jsonplaceholder.typicode.com")
            val ref = AdapterRef(adapter, backend)

            // Bootstrap a minimal CamelContext for the test
            val camelMain = org.apache.camel.impl.DefaultCamelContext()
            camelMain.start()
            ref.bindProducerTemplate(camelMain.createProducerTemplate())
            return ref
        }

        @Test
        fun `get calls real JSONPlaceholder API`() {
            val ref = createCamelRef()
            val user = ref.get("/users/1")

            assertNotNull(user["name"])
            assertNotNull(user["email"])
            assertEquals(1, user["id"])
            assertNotNull(user.nested("address.city"))
            assertNotNull(user.nested("company.name"))
        }

        @Test
        fun `post calls real JSONPlaceholder API`() {
            val ref = createCamelRef()
            val result = ref.post("/posts", mapOf("title" to "test", "body" to "hello", "userId" to 1))

            assertNotNull(result["id"]) // JSONPlaceholder returns created ID
        }

        @Test
        fun `get with invalid path throws AdapterCallException`() {
            val ref = createCamelRef()
            val ex = assertThrows(AdapterCallException::class.java) {
                ref.get("/users/99999")
            }
            assertEquals("jsonplaceholder", ex.adapterName)
            assertEquals("GET", ex.method)
            assertEquals(404, ex.statusCode)
        }
    }
}
