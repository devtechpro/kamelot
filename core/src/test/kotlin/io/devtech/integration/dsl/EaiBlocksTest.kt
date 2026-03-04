package io.devtech.integration.dsl

import io.devtech.integration.model.FieldDef
import io.devtech.integration.model.FieldType
import io.devtech.integration.model.SchemaObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EaiBlocksTest {

    // ────────────────────────────────────────────────────────────────
    // parallel()
    // ────────────────────────────────────────────────────────────────

    @Nested
    inner class ParallelExecution {

        @Test
        fun `all tasks succeed`() {
            val results = parallel(
                "a" to { "result-a" },
                "b" to { 42 },
            )
            assertEquals("result-a", results["a"])
            assertEquals(42, results["b"])
        }

        @Test
        fun `single task works`() {
            val results = parallel("only" to { "value" })
            assertEquals("value", results["only"])
        }

        @Test
        fun `one task fails`() {
            val ex = assertThrows(ParallelExecutionException::class.java) {
                parallel(
                    "good" to { "ok" },
                    "bad" to { throw RuntimeException("boom") },
                )
            }
            assertEquals(1, ex.failures.size)
            assertTrue(ex.failures.containsKey("bad"))
            assertEquals("boom", ex.failures["bad"]!!.message)
        }

        @Test
        fun `multiple tasks fail`() {
            val ex = assertThrows(ParallelExecutionException::class.java) {
                parallel(
                    "bad1" to { throw RuntimeException("fail-1") },
                    "bad2" to { throw RuntimeException("fail-2") },
                )
            }
            assertEquals(2, ex.failures.size)
            assertTrue(ex.failures.containsKey("bad1"))
            assertTrue(ex.failures.containsKey("bad2"))
        }

        @Test
        fun `tasks run concurrently`() {
            val start = System.currentTimeMillis()
            val results = parallel(
                "a" to { Thread.sleep(100); "done-a" },
                "b" to { Thread.sleep(100); "done-b" },
            )
            val elapsed = System.currentTimeMillis() - start
            assertEquals("done-a", results["a"])
            assertEquals("done-b", results["b"])
            assertTrue(elapsed < 180, "Expected concurrent execution, took ${elapsed}ms")
        }

        @Test
        fun `duplicate labels throw`() {
            assertThrows(IllegalArgumentException::class.java) {
                parallel(
                    "same" to { 1 },
                    "same" to { 2 },
                )
            }
        }

        @Test
        fun `empty tasks return empty map`() {
            val results = parallel()
            assertTrue(results.isEmpty())
        }

        @Test
        fun `task returning null`() {
            val results = parallel("nullable" to { null })
            assertTrue(results.containsKey("nullable"))
            assertNull(results["nullable"])
        }
    }

    // ────────────────────────────────────────────────────────────────
    // validate() — custom rules
    // ────────────────────────────────────────────────────────────────

    @Nested
    inner class ValidateCustomRules {

        @Test
        fun `all valid passes`() {
            assertDoesNotThrow {
                validate(mapOf("name" to "Alice", "age" to 30)) {
                    required("name")
                    required("age")
                }
            }
        }

        @Test
        fun `missing required field`() {
            val ex = assertThrows(ValidationException::class.java) {
                validate(mapOf("name" to "Alice")) {
                    required("email")
                }
            }
            assertEquals(1, ex.violations.size)
            assertEquals("email", ex.violations[0].field)
            assertEquals("is required", ex.violations[0].message)
        }

        @Test
        fun `multiple missing required fields`() {
            val ex = assertThrows(ValidationException::class.java) {
                validate(emptyMap()) {
                    required("name")
                    required("email")
                    required("age")
                }
            }
            assertEquals(3, ex.violations.size)
        }

        @Test
        fun `custom predicate passes`() {
            assertDoesNotThrow {
                validate(mapOf("age" to 25)) {
                    field("age", "must be positive") { it is Int && (it as Int) > 0 }
                }
            }
        }

        @Test
        fun `custom predicate fails`() {
            val ex = assertThrows(ValidationException::class.java) {
                validate(mapOf("age" to -5)) {
                    field("age", "must be positive") { it is Int && (it as Int) > 0 }
                }
            }
            assertEquals(1, ex.violations.size)
            assertEquals("age", ex.violations[0].field)
            assertEquals("must be positive", ex.violations[0].message)
        }

        @Test
        fun `absent field skips predicate`() {
            assertDoesNotThrow {
                validate(emptyMap()) {
                    field("optional", "should not trigger") { false }
                }
            }
        }

        @Test
        fun `required and predicate combined`() {
            val ex = assertThrows(ValidationException::class.java) {
                validate(mapOf("age" to -1)) {
                    required("name")
                    field("age", "must be positive") { it is Int && (it as Int) > 0 }
                }
            }
            assertEquals(2, ex.violations.size)
            val fields = ex.violations.map { it.field }.toSet()
            assertEquals(setOf("name", "age"), fields)
        }

        @Test
        fun `null value triggers required`() {
            val ex = assertThrows(ValidationException::class.java) {
                validate(mapOf("name" to null)) {
                    required("name")
                }
            }
            assertEquals(1, ex.violations.size)
        }
    }

    // ────────────────────────────────────────────────────────────────
    // validate() — schema-based
    // ────────────────────────────────────────────────────────────────

    @Nested
    inner class ValidateSchema {

        private val schema = SchemaObject(
            name = "TestSchema",
            fields = mapOf(
                "name" to FieldDef("name", FieldType.STRING),
                "age" to FieldDef("age", FieldType.INTEGER),
                "score" to FieldDef("score", FieldType.NUMBER),
                "active" to FieldDef("active", FieldType.BOOLEAN),
                "tags" to FieldDef("tags", FieldType.ARRAY),
                "meta" to FieldDef("meta", FieldType.OBJECT),
            ),
            required = listOf("name", "age"),
        )

        @Test
        fun `valid body passes`() {
            assertDoesNotThrow {
                validate(
                    mapOf("name" to "Alice", "age" to 30, "active" to true),
                    schema,
                )
            }
        }

        @Test
        fun `missing required field`() {
            val ex = assertThrows(ValidationException::class.java) {
                validate(mapOf("age" to 30), schema)
            }
            assertEquals(1, ex.violations.size)
            assertEquals("name", ex.violations[0].field)
        }

        @Test
        fun `wrong type string where integer expected`() {
            val ex = assertThrows(ValidationException::class.java) {
                validate(mapOf("name" to "Alice", "age" to "thirty"), schema)
            }
            val typeViolation = ex.violations.find { it.field == "age" }
            assertNotNull(typeViolation)
            assertTrue(typeViolation!!.message.contains("INTEGER"))
            assertTrue(typeViolation.message.contains("String"))
        }

        @Test
        fun `INTEGER accepts Int and Long`() {
            assertDoesNotThrow {
                validate(mapOf("name" to "Alice", "age" to 30), schema)
            }
            assertDoesNotThrow {
                validate(mapOf("name" to "Alice", "age" to 30L), schema)
            }
        }

        @Test
        fun `NUMBER accepts all numeric types`() {
            assertDoesNotThrow {
                validate(mapOf("name" to "A", "age" to 1, "score" to 9.5), schema)
            }
            assertDoesNotThrow {
                validate(mapOf("name" to "A", "age" to 1, "score" to 100), schema)
            }
            assertDoesNotThrow {
                validate(mapOf("name" to "A", "age" to 1, "score" to 100L), schema)
            }
        }

        @Test
        fun `extra fields in body ignored`() {
            assertDoesNotThrow {
                validate(mapOf("name" to "Alice", "age" to 30, "unknown" to "extra"), schema)
            }
        }

        @Test
        fun `ARRAY and OBJECT type checks`() {
            assertDoesNotThrow {
                validate(
                    mapOf("name" to "A", "age" to 1, "tags" to listOf("a", "b"), "meta" to mapOf("k" to "v")),
                    schema,
                )
            }
            val ex = assertThrows(ValidationException::class.java) {
                validate(
                    mapOf("name" to "A", "age" to 1, "tags" to "not-a-list", "meta" to "not-a-map"),
                    schema,
                )
            }
            assertEquals(2, ex.violations.size)
        }

        @Test
        fun `enum validation`() {
            val enumSchema = SchemaObject(
                name = "EnumSchema",
                fields = mapOf(
                    "priority" to FieldDef("priority", FieldType.STRING, enumValues = listOf("low", "normal", "high")),
                ),
                required = emptyList(),
            )
            assertDoesNotThrow {
                validate(mapOf("priority" to "high"), enumSchema)
            }
            val ex = assertThrows(ValidationException::class.java) {
                validate(mapOf("priority" to "urgent"), enumSchema)
            }
            assertTrue(ex.violations[0].message.contains("must be one of"))
        }
    }
}
