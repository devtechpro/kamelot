package io.devtech.integration.camel

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TraceIdTest {

    @Test
    fun `generateTraceId returns 8-char string`() {
        val id = ExposeRouteGenerator.generateTraceId()
        assertEquals(8, id.length)
    }

    @Test
    fun `generateTraceId returns unique values`() {
        val ids = (1..1000).map { ExposeRouteGenerator.generateTraceId() }.toSet()
        assertEquals(1000, ids.size)
    }

    @Test
    fun `generateTraceId contains only hex chars`() {
        repeat(100) {
            val id = ExposeRouteGenerator.generateTraceId()
            assertTrue(id.matches(Regex("[0-9a-f]{8}")), "Expected hex, got: $id")
        }
    }
}
