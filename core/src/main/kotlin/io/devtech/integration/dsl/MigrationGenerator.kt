package io.devtech.integration.dsl

import io.devtech.integration.model.FieldType
import io.devtech.integration.model.SchemaObject
import io.devtech.integration.model.Spec

/**
 * Generates Postgres DDL from a spec's schema definitions.
 *
 * The adapter spec describes what the DB stores — this turns that into
 * `CREATE TABLE` statements, so the spec is the single source of truth
 * for both the application layer and the database.
 *
 * ```kotlin
 * val spec = OpenApiSchemaLoader.load("specs/products-db-spec.yaml")
 * val ddl = MigrationGenerator.generateCreate(spec, "Product", "products")
 * // → CREATE TABLE IF NOT EXISTS products (
 * //       id BIGSERIAL PRIMARY KEY,
 * //       name TEXT NOT NULL,
 * //       price DOUBLE PRECISION NOT NULL,
 * //       ...
 * //   );
 * ```
 */
object MigrationGenerator {

    /**
     * Generate a `CREATE TABLE IF NOT EXISTS` statement from a spec schema.
     *
     * @param spec      The adapter spec containing schema definitions
     * @param schema    Name of the schema to use (e.g. "Product")
     * @param table     Target table name (e.g. "products")
     */
    fun generateCreate(spec: Spec, schema: String, table: String): String {
        val schemaObj = spec.schemas[schema]
            ?: error("Schema '$schema' not found in spec '${spec.name}'. Available: ${spec.schemas.keys}")
        return generateCreate(schemaObj, table)
    }

    /**
     * Generate a `CREATE TABLE IF NOT EXISTS` statement from a [SchemaObject].
     */
    fun generateCreate(schema: SchemaObject, table: String): String {
        val columns = schema.fields.map { (name, field) ->
            val sqlType = when {
                name == "id" -> return@map "    id BIGSERIAL PRIMARY KEY"
                else -> fieldTypeToSql(field.type)
            }
            val notNull = if (name in schema.required) " NOT NULL" else ""
            "    $name $sqlType$notNull"
        }
        return buildString {
            appendLine("CREATE TABLE IF NOT EXISTS $table (")
            appendLine(columns.joinToString(",\n"))
            append(");")
        }
    }

    /**
     * Generate DDL for all entity schemas in a spec (those with an `id` field).
     * Returns a map of schema name → DDL, using the schema name lowercased + pluralized
     * as the default table name.
     */
    fun generateAll(spec: Spec): Map<String, String> {
        return spec.schemas
            .filter { (_, schema) -> schema.fields.containsKey("id") }
            .map { (name, schema) ->
                val table = defaultTableName(name)
                name to generateCreate(schema, table)
            }
            .toMap()
    }

    /**
     * Map OpenAPI field types to Postgres column types.
     */
    fun fieldTypeToSql(type: FieldType): String = when (type) {
        FieldType.STRING  -> "TEXT"
        FieldType.INTEGER -> "BIGINT"
        FieldType.NUMBER  -> "DOUBLE PRECISION"
        FieldType.BOOLEAN -> "BOOLEAN"
        FieldType.ARRAY   -> "JSONB"
        FieldType.OBJECT  -> "JSONB"
    }

    /**
     * Derive a default table name from a schema name: "Product" → "products".
     */
    private fun defaultTableName(schemaName: String): String {
        val lower = schemaName.lowercase()
        return if (lower.endsWith("s")) lower else "${lower}s"
    }
}
