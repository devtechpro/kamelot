package io.devtech.integration.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SecretTest {

    @Test
    fun `toString hides value`() =
        assertEquals("***", Secret("my-password").toString())

    @Test
    fun `value reveals actual secret`() =
        assertEquals("my-password", Secret("my-password").value)

    @Test
    fun `equals compares by value`() {
        assertEquals(Secret("abc"), Secret("abc"))
        assertNotEquals(Secret("abc"), Secret("xyz"))
    }

    @Test
    fun `hashCode matches for equal secrets`() =
        assertEquals(Secret("abc").hashCode(), Secret("abc").hashCode())

    @Test
    fun `EMPTY has empty string value`() =
        assertEquals("", Secret.EMPTY.value)

    @Test
    fun `string interpolation hides value`() {
        val s = Secret("real-token")
        val msg = "Auth: $s"
        assertFalse(msg.contains("real-token"))
        assertTrue(msg.contains("***"))
    }

    @Test
    fun `AuthConfig toString hides secret`() {
        val auth = AuthConfig(type = AuthType.BEARER, secret = Secret("token123"))
        val str = auth.toString()
        assertFalse(str.contains("token123"))
        assertTrue(str.contains("***"))
    }

    @Test
    fun `Jackson serialization writes masked value`() {
        val mapper = jacksonObjectMapper()
        val json = mapper.writeValueAsString(Secret("token123"))
        assertEquals("\"***\"", json)
        assertFalse(json.contains("token123"))
    }

    @Test
    fun `Jackson serialization of AuthConfig hides secret`() {
        val mapper = jacksonObjectMapper()
        val auth = AuthConfig(type = AuthType.BEARER, secret = Secret("real-key"))
        val json = mapper.writeValueAsString(auth)
        assertFalse(json.contains("real-key"))
        assertTrue(json.contains("***"))
    }
}
