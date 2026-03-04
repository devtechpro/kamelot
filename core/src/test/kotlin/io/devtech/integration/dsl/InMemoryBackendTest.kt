package io.devtech.integration.dsl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class InMemoryBackendTest {

    private lateinit var backend: InMemoryBackend

    @BeforeEach
    fun setUp() {
        backend = InMemoryBackend("test-db")
    }

    @Nested
    inner class Create {

        @Test
        fun `post creates record with auto-generated id`() {
            @Suppress("UNCHECKED_CAST")
            val result = backend.execute("POST", "/contacts", mapOf("name" to "Alice", "email" to "alice@test.com")) as Map<String, Any?>
            assertEquals(1L, result["id"])
            assertEquals("Alice", result["name"])
            assertEquals("alice@test.com", result["email"])
        }

        @Test
        fun `post generates sequential ids`() {
            @Suppress("UNCHECKED_CAST")
            val r1 = backend.execute("POST", "/contacts", mapOf("name" to "Alice")) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val r2 = backend.execute("POST", "/contacts", mapOf("name" to "Bob")) as Map<String, Any?>
            assertEquals(1L, r1["id"])
            assertEquals(2L, r2["id"])
        }
    }

    @Nested
    inner class Read {

        @Test
        fun `get by id returns record`() {
            backend.execute("POST", "/contacts", mapOf("name" to "Alice"))
            @Suppress("UNCHECKED_CAST")
            val result = backend.execute("GET", "/contacts/1") as Map<String, Any?>
            assertEquals("Alice", result["name"])
        }

        @Test
        fun `get by id throws 404 for missing record`() {
            val ex = assertThrows(AdapterCallException::class.java) {
                backend.execute("GET", "/contacts/999")
            }
            assertEquals(404, ex.statusCode)
        }

        @Test
        fun `get list returns all records`() {
            backend.execute("POST", "/contacts", mapOf("name" to "Alice"))
            backend.execute("POST", "/contacts", mapOf("name" to "Bob"))
            @Suppress("UNCHECKED_CAST")
            val result = backend.execute("GET", "/contacts") as List<Map<String, Any?>>
            assertEquals(2, result.size)
        }

        @Test
        fun `get list with search filters results`() {
            backend.execute("POST", "/contacts", mapOf("name" to "Alice", "city" to "Berlin"))
            backend.execute("POST", "/contacts", mapOf("name" to "Bob", "city" to "Munich"))
            @Suppress("UNCHECKED_CAST")
            val result = backend.execute("GET", "/contacts", queryParams = mapOf("search" to "alice")) as List<Map<String, Any?>>
            assertEquals(1, result.size)
            assertEquals("Alice", result[0]["name"])
        }

        @Test
        fun `get list with limit caps results`() {
            backend.execute("POST", "/contacts", mapOf("name" to "Alice"))
            backend.execute("POST", "/contacts", mapOf("name" to "Bob"))
            backend.execute("POST", "/contacts", mapOf("name" to "Charlie"))
            @Suppress("UNCHECKED_CAST")
            val result = backend.execute("GET", "/contacts", queryParams = mapOf("limit" to "2")) as List<Map<String, Any?>>
            assertEquals(2, result.size)
        }

        @Test
        fun `get empty list returns empty`() {
            @Suppress("UNCHECKED_CAST")
            val result = backend.execute("GET", "/contacts") as List<Map<String, Any?>>
            assertTrue(result.isEmpty())
        }
    }

    @Nested
    inner class Update {

        @Test
        fun `put updates existing record`() {
            backend.execute("POST", "/contacts", mapOf("name" to "Alice", "city" to "Berlin"))
            @Suppress("UNCHECKED_CAST")
            val updated = backend.execute("PUT", "/contacts/1", mapOf("name" to "Alice Updated", "city" to "Munich")) as Map<String, Any?>
            assertEquals("Alice Updated", updated["name"])
            assertEquals("Munich", updated["city"])
            assertEquals(1L, updated["id"])
        }

        @Test
        fun `put throws 404 for missing record`() {
            val ex = assertThrows(AdapterCallException::class.java) {
                backend.execute("PUT", "/contacts/999", mapOf("name" to "Ghost"))
            }
            assertEquals(404, ex.statusCode)
        }
    }

    @Nested
    inner class Delete {

        @Test
        fun `delete removes and returns record`() {
            backend.execute("POST", "/contacts", mapOf("name" to "Alice"))
            @Suppress("UNCHECKED_CAST")
            val deleted = backend.execute("DELETE", "/contacts/1") as Map<String, Any?>
            assertEquals("Alice", deleted["name"])

            // Verify it's gone
            val ex = assertThrows(AdapterCallException::class.java) {
                backend.execute("GET", "/contacts/1")
            }
            assertEquals(404, ex.statusCode)
        }

        @Test
        fun `delete throws 404 for missing record`() {
            val ex = assertThrows(AdapterCallException::class.java) {
                backend.execute("DELETE", "/contacts/999")
            }
            assertEquals(404, ex.statusCode)
        }
    }

    @Nested
    inner class BackendSelection {

        private val testSpecPath = javaClass.classLoader.getResource("adapter-test-spec.yaml")!!.path
        private val apiSpecPath = javaClass.classLoader.getResource("test-spec.yaml")!!.path

        @Test
        fun `adapter with baseUrl uses CamelHttpBackend`() {
            var ref: AdapterRef? = null
            integration("test") {
                val api = spec(apiSpecPath)
                expose(api, port = 9000)
                ref = adapter("ext", spec(testSpecPath)) {
                    baseUrl = "https://example.com"
                }
            }
            // CamelHttpBackend is default — ref should work (we just verify it was created)
            assertNotNull(ref)
            assertEquals("ext", ref!!.name)
            assertTrue(ref!!.isCamelManaged)
        }

        @Test
        fun `adapter with inMemory uses InMemoryBackend`() {
            var ref: AdapterRef? = null
            integration("test") {
                val api = spec(apiSpecPath)
                expose(api, port = 9000)
                ref = adapter("db", spec(testSpecPath)) {
                    inMemory()
                }
            }
            assertNotNull(ref)
            // Verify it works as in-memory store
            val created = ref!!.post("/items", mapOf("name" to "test"))
            assertEquals(1L, created["id"])
            val fetched = ref!!.get("/items/1")
            assertEquals("test", fetched["name"])
        }
    }
}
