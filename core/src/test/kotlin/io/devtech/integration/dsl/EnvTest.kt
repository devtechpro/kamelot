package io.devtech.integration.dsl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EnvTest {

    @Test
    fun `env returns system env var when set`() {
        // PATH is always set
        val path = env("PATH")
        assertTrue(path.isNotEmpty())
    }

    @Test
    fun `env returns default when var is missing`() {
        assertEquals("fallback", env("DEFINITELY_NOT_SET_XYZ_123", "fallback"))
    }

    @Test
    fun `env returns empty string default when var is missing`() {
        assertEquals("", env("DEFINITELY_NOT_SET_XYZ_123"))
    }

    @Test
    fun `env int returns default when var is missing`() {
        assertEquals(5400, env("DEFINITELY_NOT_SET_XYZ_123", 5400))
    }

    @Test
    fun `env int returns default when var is not a number`() {
        // HOME is set but not a number
        val result = env("HOME", 9999)
        assertEquals(9999, result)
    }
}
