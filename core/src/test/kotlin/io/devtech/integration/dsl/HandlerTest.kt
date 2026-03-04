package io.devtech.integration.dsl

import io.devtech.integration.model.RequestContext
import io.devtech.integration.model.ResponseContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class HandlerTest {

    @Nested
    inner class HandlerFunction {

        @Test
        fun `handler returns a callable function`() {
            val h = handler { _ -> respond("ok" to true) }
            assertNotNull(h)
            val result = h(RequestContext())
            assertNotNull(result)
        }

        @Test
        fun `handler receives request context`() {
            val h = handler { req ->
                respond("name" to req.body["name"])
            }
            val result = h(RequestContext(body = mapOf("name" to "Alice")))
            @Suppress("UNCHECKED_CAST")
            val body = result.body as Map<String, Any?>
            assertEquals("Alice", body["name"])
        }

        @Test
        fun `handler can access path params`() {
            val h = handler { req ->
                respond("id" to req.pathParams["id"])
            }
            val result = h(RequestContext(pathParams = mapOf("id" to "42")))
            @Suppress("UNCHECKED_CAST")
            val body = result.body as Map<String, Any?>
            assertEquals("42", body["id"])
        }

        @Test
        fun `handler can access query params`() {
            val h = handler { req ->
                respond("q" to req.queryParams["search"])
            }
            val result = h(RequestContext(queryParams = mapOf("search" to "kotlin")))
            @Suppress("UNCHECKED_CAST")
            val body = result.body as Map<String, Any?>
            assertEquals("kotlin", body["q"])
        }

        @Test
        fun `handler can access headers`() {
            val h = handler { req ->
                respond("auth" to req.headers["Authorization"])
            }
            val result = h(RequestContext(headers = mapOf("Authorization" to "Bearer tok")))
            @Suppress("UNCHECKED_CAST")
            val body = result.body as Map<String, Any?>
            assertEquals("Bearer tok", body["auth"])
        }
    }

    @Nested
    inner class RespondWithPairs {

        @Test
        fun `respond with key-value pairs produces map body`() {
            val result = respond("key" to "value", "count" to 42)
            assertEquals(200, result.statusCode)
            @Suppress("UNCHECKED_CAST")
            val body = result.body as Map<String, Any?>
            assertEquals("value", body["key"])
            assertEquals(42, body["count"])
        }

        @Test
        fun `respond with pairs defaults to 200`() {
            val result = respond("ok" to true)
            assertEquals(200, result.statusCode)
        }

        @Test
        fun `respond with pairs and custom status code`() {
            val result = respond("error" to "not found", statusCode = 404)
            assertEquals(404, result.statusCode)
            @Suppress("UNCHECKED_CAST")
            val body = result.body as Map<String, Any?>
            assertEquals("not found", body["error"])
        }

        @Test
        fun `respond with pairs and custom headers`() {
            val result = respond(
                "data" to "value",
                headers = mapOf("X-Custom" to "test"),
            )
            assertEquals("test", result.headers["X-Custom"])
        }

        @Test
        fun `respond with null values in pairs`() {
            val result = respond("present" to "yes", "absent" to null)
            @Suppress("UNCHECKED_CAST")
            val body = result.body as Map<String, Any?>
            assertEquals("yes", body["present"])
            assertNull(body["absent"])
            assertTrue(body.containsKey("absent"))
        }

        @Test
        fun `respond with single pair`() {
            val result = respond("only" to 1)
            @Suppress("UNCHECKED_CAST")
            val body = result.body as Map<String, Any?>
            assertEquals(1, body.size)
            assertEquals(1, body["only"])
        }
    }

    @Nested
    inner class RespondWithBody {

        @Test
        fun `respond with explicit body`() {
            val result = respond(body = listOf(1, 2, 3))
            assertEquals(200, result.statusCode)
            assertEquals(listOf(1, 2, 3), result.body)
        }

        @Test
        fun `respond with status code only`() {
            val result = respond(statusCode = 204)
            assertEquals(204, result.statusCode)
            assertNull(result.body)
        }

        @Test
        fun `respond with no args gives 200 and null body`() {
            val result = respond()
            assertEquals(200, result.statusCode)
            assertNull(result.body)
            assertTrue(result.headers.isEmpty())
        }

        @Test
        fun `respond with all parameters`() {
            val result = respond(
                statusCode = 201,
                body = mapOf("id" to "abc"),
                headers = mapOf("Location" to "/items/abc"),
            )
            assertEquals(201, result.statusCode)
            @Suppress("UNCHECKED_CAST")
            val body = result.body as Map<String, Any?>
            assertEquals("abc", body["id"])
            assertEquals("/items/abc", result.headers["Location"])
        }

        @Test
        fun `respond with string body`() {
            val result = respond(body = "plain text")
            assertEquals("plain text", result.body)
        }
    }

    @Nested
    inner class RespondWithBuilder {

        @Test
        fun `to maps value from source by key`() {
            val source = mapOf("sourceKey" to "hello")
            val result = respond(source) {
                "key" to "sourceKey"
            }
            @Suppress("UNCHECKED_CAST")
            val body = result.body as Map<String, Any?>
            assertEquals("hello", body["key"])
        }

        @Test
        fun `set assigns a literal value`() {
            val source = mapOf<String, Any?>()
            val result = respond(source) {
                "key" set "literal"
            }
            @Suppress("UNCHECKED_CAST")
            val body = result.body as Map<String, Any?>
            assertEquals("literal", body["key"])
        }

        @Test
        fun `set assigns a computed value from now()`() {
            val source = mapOf<String, Any?>()
            val before = now()
            val result = respond(source) {
                "key" set now()
            }
            val after = now()
            @Suppress("UNCHECKED_CAST")
            val body = result.body as Map<String, Any?>
            val timestamp = body["key"] as String
            assertTrue(timestamp >= before)
            assertTrue(timestamp <= after)
        }

        @Test
        fun `mix of to and set in same builder`() {
            val source = mapOf("name" to "Alice", "age" to 30)
            val result = respond(source) {
                "name" to "name"
                "age" to "age"
                "greeting" set "hello"
                "timestamp" set now()
            }
            @Suppress("UNCHECKED_CAST")
            val body = result.body as Map<String, Any?>
            assertEquals("Alice", body["name"])
            assertEquals(30, body["age"])
            assertEquals("hello", body["greeting"])
            assertNotNull(body["timestamp"])
            assertEquals(4, body.size)
        }

        @Test
        fun `to with rename maps source field to different key`() {
            val source = mapOf("originalName" to "Bob")
            val result = respond(source) {
                "renamed" to "originalName"
            }
            @Suppress("UNCHECKED_CAST")
            val body = result.body as Map<String, Any?>
            assertEquals("Bob", body["renamed"])
            assertFalse(body.containsKey("originalName"))
        }

        @Test
        fun `custom statusCode parameter`() {
            val source = mapOf("id" to "abc-123")
            val result = respond(source, statusCode = 201) {
                "id" to "id"
                "created" set true
            }
            assertEquals(201, result.statusCode)
            @Suppress("UNCHECKED_CAST")
            val body = result.body as Map<String, Any?>
            assertEquals("abc-123", body["id"])
            assertEquals(true, body["created"])
        }
    }
}
