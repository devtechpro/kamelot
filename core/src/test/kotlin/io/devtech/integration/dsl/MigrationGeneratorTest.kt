package io.devtech.integration.dsl

import io.devtech.integration.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MigrationGeneratorTest {

    @Nested
    inner class FieldTypeMapping {

        @Test
        fun `STRING maps to TEXT`() = assertEquals("TEXT", MigrationGenerator.fieldTypeToSql(FieldType.STRING))

        @Test
        fun `INTEGER maps to BIGINT`() = assertEquals("BIGINT", MigrationGenerator.fieldTypeToSql(FieldType.INTEGER))

        @Test
        fun `NUMBER maps to DOUBLE PRECISION`() = assertEquals("DOUBLE PRECISION", MigrationGenerator.fieldTypeToSql(FieldType.NUMBER))

        @Test
        fun `BOOLEAN maps to BOOLEAN`() = assertEquals("BOOLEAN", MigrationGenerator.fieldTypeToSql(FieldType.BOOLEAN))

        @Test
        fun `ARRAY maps to JSONB`() = assertEquals("JSONB", MigrationGenerator.fieldTypeToSql(FieldType.ARRAY))

        @Test
        fun `OBJECT maps to JSONB`() = assertEquals("JSONB", MigrationGenerator.fieldTypeToSql(FieldType.OBJECT))
    }

    @Nested
    inner class GenerateCreate {

        private val productSchema = SchemaObject(
            name = "Product",
            fields = mapOf(
                "id" to FieldDef("id", FieldType.INTEGER),
                "name" to FieldDef("name", FieldType.STRING),
                "price" to FieldDef("price", FieldType.NUMBER),
                "active" to FieldDef("active", FieldType.BOOLEAN),
                "tags" to FieldDef("tags", FieldType.ARRAY),
            ),
            required = listOf("name", "price"),
        )

        @Test
        fun `generates CREATE TABLE with correct types`() {
            val ddl = MigrationGenerator.generateCreate(productSchema, "products")
            assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS products"))
            assertTrue(ddl.contains("id BIGSERIAL PRIMARY KEY"))
            assertTrue(ddl.contains("name TEXT NOT NULL"))
            assertTrue(ddl.contains("price DOUBLE PRECISION NOT NULL"))
            assertTrue(ddl.contains("active BOOLEAN"))
            assertTrue(ddl.contains("tags JSONB"))
        }

        @Test
        fun `id is always BIGSERIAL PRIMARY KEY`() {
            val ddl = MigrationGenerator.generateCreate(productSchema, "products")
            assertTrue(ddl.contains("id BIGSERIAL PRIMARY KEY"))
            assertFalse(ddl.contains("id BIGINT"))
        }

        @Test
        fun `required fields get NOT NULL`() {
            val ddl = MigrationGenerator.generateCreate(productSchema, "products")
            assertTrue(ddl.contains("name TEXT NOT NULL"))
            assertTrue(ddl.contains("price DOUBLE PRECISION NOT NULL"))
            // non-required
            assertFalse(ddl.contains("active BOOLEAN NOT NULL"))
        }

        @Test
        fun `non-required fields omit NOT NULL`() {
            val ddl = MigrationGenerator.generateCreate(productSchema, "products")
            // "active BOOLEAN" without NOT NULL
            assertTrue(ddl.contains("active BOOLEAN"))
            assertFalse(ddl.contains("active BOOLEAN NOT NULL"))
        }
    }

    @Nested
    inner class GenerateFromSpec {

        private val spec = Spec(
            name = "test-db",
            path = "test.yaml",
            type = SpecType.OPENAPI,
            schemas = mapOf(
                "ProductInput" to SchemaObject(
                    name = "ProductInput",
                    fields = mapOf(
                        "name" to FieldDef("name", FieldType.STRING),
                        "price" to FieldDef("price", FieldType.NUMBER),
                    ),
                ),
                "Product" to SchemaObject(
                    name = "Product",
                    fields = mapOf(
                        "id" to FieldDef("id", FieldType.INTEGER),
                        "name" to FieldDef("name", FieldType.STRING),
                        "price" to FieldDef("price", FieldType.NUMBER),
                        "slug" to FieldDef("slug", FieldType.STRING),
                    ),
                    required = listOf("name", "price"),
                ),
            ),
        )

        @Test
        fun `generateCreate with spec and schema name`() {
            val ddl = MigrationGenerator.generateCreate(spec, "Product", "products")
            assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS products"))
            assertTrue(ddl.contains("id BIGSERIAL PRIMARY KEY"))
            assertTrue(ddl.contains("slug TEXT"))
        }

        @Test
        fun `generateCreate with unknown schema throws`() {
            val ex = assertThrows(IllegalStateException::class.java) {
                MigrationGenerator.generateCreate(spec, "Unknown", "things")
            }
            assertTrue(ex.message!!.contains("Unknown"))
        }

        @Test
        fun `generateAll finds entity schemas with id field`() {
            val all = MigrationGenerator.generateAll(spec)
            assertEquals(1, all.size)
            assertTrue(all.containsKey("Product"))
            assertFalse(all.containsKey("ProductInput"))
            assertTrue(all["Product"]!!.contains("CREATE TABLE IF NOT EXISTS products"))
        }
    }

    @Nested
    inner class FromRealSpec {

        private val specPath = javaClass.classLoader.getResource("adapter-test-spec.yaml")!!.path

        @Test
        fun `generates DDL from loaded spec`() {
            val spec = io.devtech.integration.schema.OpenApiSchemaLoader.load(specPath)
            val ddl = MigrationGenerator.generateCreate(spec, "Item", "items")
            assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS items"))
            assertTrue(ddl.contains("id BIGSERIAL PRIMARY KEY"))
            assertTrue(ddl.contains("name TEXT"))
        }
    }
}
