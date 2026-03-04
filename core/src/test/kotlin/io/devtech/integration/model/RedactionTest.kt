package io.devtech.integration.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RedactionTest {

    @Test
    fun `redacts password key`() {
        val input = mapOf("username" to "admin", "password" to "s3cret", "url" to "localhost")
        val redacted = input.redactSecrets()
        assertEquals("admin", redacted["username"])
        assertEquals("***", redacted["password"])
        assertEquals("localhost", redacted["url"])
    }

    @Test
    fun `redacts Authorization header`() {
        val input = mapOf("Authorization" to "Bearer real-token", "Content-Type" to "application/json")
        val redacted = input.redactSecrets()
        assertEquals("***", redacted["Authorization"])
        assertEquals("application/json", redacted["Content-Type"])
    }

    @Test
    fun `redacts api_key and apikey variations`() {
        val input = mapOf("api_key" to "abc", "apikey" to "def", "ApiKey" to "ghi")
        val redacted = input.redactSecrets()
        assertTrue(redacted.values.all { it == "***" })
    }

    @Test
    fun `redacts token and credential keys`() {
        val input = mapOf("access_token" to "tok", "credential" to "cred")
        val redacted = input.redactSecrets()
        assertEquals("***", redacted["access_token"])
        assertEquals("***", redacted["credential"])
    }

    @Test
    fun `redacts nested maps recursively`() {
        val input = mapOf("db" to mapOf("password" to "s3cret", "host" to "localhost"))
        val redacted = input.redactSecrets()
        @Suppress("UNCHECKED_CAST")
        val nested = redacted["db"] as Map<String, Any?>
        assertEquals("***", nested["password"])
        assertEquals("localhost", nested["host"])
    }

    @Test
    fun `redacts Secret instances regardless of key name`() {
        val input = mapOf<String, Any?>("foo" to Secret("bar"), "baz" to "plain")
        val redacted = input.redactSecrets()
        assertEquals("***", redacted["foo"])
        assertEquals("plain", redacted["baz"])
    }

    @Test
    fun `preserves non-sensitive keys`() {
        val input = mapOf("name" to "John", "email" to "john@example.com", "age" to 30)
        val redacted = input.redactSecrets()
        assertEquals(input, redacted)
    }

    @Test
    fun `handles empty map`() =
        assertEquals(emptyMap<String, Any?>(), emptyMap<String, Any?>().redactSecrets())

    @Test
    fun `handles null values`() {
        val input = mapOf("password" to null, "name" to null)
        val redacted = input.redactSecrets()
        assertEquals("***", redacted["password"])
        assertNull(redacted["name"])
    }
}
