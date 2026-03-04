package io.devtech.integration.dsl

import io.devtech.integration.camel.CamelContextHolder
import io.devtech.integration.model.FieldType
import io.devtech.integration.model.SchemaObject
import org.apache.camel.AggregationStrategy
import org.apache.camel.CamelContext
import org.apache.camel.Exchange
import org.apache.camel.builder.RouteBuilder
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

// ════════════════════════════════════════════════════════════════════
// 1. parallel()  —  Camel multicast().parallelProcessing()
// ════════════════════════════════════════════════════════════════════

class ParallelExecutionException(
    val failures: Map<String, Throwable>,
) : RuntimeException(
    "Parallel execution failed for: ${failures.keys.joinToString()}. " +
        "Causes: ${failures.map { (k, v) -> "$k: ${v.message}" }.joinToString("; ")}"
)

/**
 * Execute multiple operations concurrently, collecting results by label.
 *
 * When running inside a Camel-managed handler, uses Camel's
 * `multicast().parallelProcessing()` with an [AggregationStrategy] to fan out
 * to per-task `direct:` routes and aggregate results. Falls back to sequential
 * execution in unit tests (no CamelContext).
 *
 * ```kotlin
 * val results = parallel(
 *     "user" to { usersApi.get("/users/1") },
 *     "posts" to { postsApi.get("/posts?userId=1") },
 * )
 * val user = results["user"] as Map<String, Any?>
 * ```
 *
 * All-or-nothing: if any task fails, throws [ParallelExecutionException] with all failures.
 */
fun parallel(vararg tasks: Pair<String, () -> Any?>): Map<String, Any?> {
    val labels = tasks.map { it.first }
    require(labels.size == labels.toSet().size) {
        "Duplicate labels in parallel(): ${labels.groupBy { it }.filter { it.value.size > 1 }.keys}"
    }

    if (tasks.isEmpty()) return emptyMap()

    val context = CamelContextHolder.get()
    return if (context != null) {
        camelParallel(context, tasks)
    } else {
        fallbackParallel(tasks)
    }
}

/**
 * Camel-backed parallel: creates ephemeral direct: routes for each task,
 * multicasts with parallelProcessing(), and aggregates results by label.
 */
private fun camelParallel(
    context: CamelContext,
    tasks: Array<out Pair<String, () -> Any?>>,
): Map<String, Any?> {
    val batchId = UUID.randomUUID().toString().substring(0, 8)
    val tasksByLabel = tasks.toMap()
    // Store tasks where route processors can reach them (routes run on different threads)
    val taskFunctions = ConcurrentHashMap(tasksByLabel)

    val routeBuilder = object : RouteBuilder() {
        override fun configure() {
            // Per-task routes: each executes its lambda and tags the result
            for (label in tasksByLabel.keys) {
                from("direct:parallel-$batchId-$label")
                    .routeId("parallel-$batchId-$label")
                    .process { exchange ->
                        val fn = taskFunctions[label]!!
                        exchange.message.body = fn()
                        exchange.message.setHeader("parallelLabel", label)
                    }
            }

            // Fan-out route: multicast to all task routes in parallel
            val destinations = tasksByLabel.keys.map { "direct:parallel-$batchId-$it" }.toTypedArray()
            from("direct:parallel-$batchId")
                .routeId("parallel-$batchId")
                .multicast(ParallelAggregationStrategy())
                .parallelProcessing()
                .stopOnException()
                .to(*destinations)
                .end()
        }
    }

    context.addRoutes(routeBuilder)
    try {
        val template = context.createProducerTemplate()
        try {
            @Suppress("UNCHECKED_CAST")
            val result = template.requestBody("direct:parallel-$batchId", "") as? Map<String, Any?> ?: emptyMap()
            return result
        } catch (e: org.apache.camel.CamelExecutionException) {
            // Unwrap: multicast wraps task exceptions
            val cause = e.cause ?: e
            throw ParallelExecutionException(mapOf("unknown" to cause))
        } finally {
            template.close()
        }
    } finally {
        // Tear down ephemeral routes
        for (label in tasksByLabel.keys) {
            context.removeRoute("parallel-$batchId-$label")
        }
        context.removeRoute("parallel-$batchId")
    }
}

/**
 * Fallback for tests without CamelContext — still concurrent via virtual threads,
 * but without Camel's multicast routing infrastructure.
 */
private fun fallbackParallel(tasks: Array<out Pair<String, () -> Any?>>): Map<String, Any?> {
    val results = mutableMapOf<String, Any?>()
    val failures = mutableMapOf<String, Throwable>()

    Executors.newVirtualThreadPerTaskExecutor().use { executor ->
        val futures = tasks.map { (label, block) ->
            label to CompletableFuture.supplyAsync({ block() }, executor)
        }

        for ((label, future) in futures) {
            try {
                results[label] = future.get()
            } catch (e: ExecutionException) {
                failures[label] = e.cause ?: e
            } catch (e: Exception) {
                failures[label] = e
            }
        }
    }

    if (failures.isNotEmpty()) {
        throw ParallelExecutionException(failures)
    }
    return results
}

/**
 * Camel AggregationStrategy that collects parallel results into a Map keyed by label.
 */
private class ParallelAggregationStrategy : AggregationStrategy {
    override fun aggregate(oldExchange: Exchange?, newExchange: Exchange): Exchange {
        val label = newExchange.message.getHeader("parallelLabel", String::class.java)
        val value = newExchange.message.body

        if (oldExchange == null) {
            val result = mutableMapOf<String, Any?>(label to value)
            newExchange.message.body = result
            return newExchange
        }

        @Suppress("UNCHECKED_CAST")
        val result = oldExchange.message.body as MutableMap<String, Any?>
        result[label] = value
        return oldExchange
    }
}

// ════════════════════════════════════════════════════════════════════
// 2. validate()  —  Camel json-schema-validator
// ════════════════════════════════════════════════════════════════════

data class Violation(
    val field: String,
    val message: String,
)

class ValidationException(
    val violations: List<Violation>,
) : RuntimeException(
    "Validation failed (${violations.size} violation${if (violations.size == 1) "" else "s"}): " +
        violations.joinToString("; ") { "${it.field}: ${it.message}" }
)

@IntegrationDsl
class ValidationBuilder(private val body: Map<String, Any?>) {
    private val violations = mutableListOf<Violation>()

    /** Require a field to be present and non-null. */
    fun required(fieldName: String) {
        if (!body.containsKey(fieldName) || body[fieldName] == null) {
            violations += Violation(fieldName, "is required")
        }
    }

    /** Validate a field with a custom predicate. Only runs if field is present. */
    fun field(fieldName: String, message: String = "is invalid", predicate: (Any?) -> Boolean) {
        if (body.containsKey(fieldName) && !predicate(body[fieldName])) {
            violations += Violation(fieldName, message)
        }
    }

    fun build(): List<Violation> = violations.toList()
}

/**
 * Validate request body against custom rules. Throws [ValidationException]
 * with all violations if any rule fails.
 *
 * ```kotlin
 * validate(req.body) {
 *     required("userId")
 *     required("email")
 *     field("userId", "must be positive") { it is Int && (it as Int) > 0 }
 * }
 * ```
 */
fun validate(body: Map<String, Any?>, block: ValidationBuilder.() -> Unit) {
    val violations = ValidationBuilder(body).apply(block).build()
    if (violations.isNotEmpty()) {
        throw ValidationException(violations)
    }
}

/**
 * Validate request body against a [SchemaObject] from the parsed OpenAPI spec.
 *
 * When running inside a Camel-managed handler, routes through Camel's
 * `json-validator` component (backed by networknt/json-schema-validator).
 * Falls back to manual checking outside Camel (unit tests).
 *
 * ```kotlin
 * validate(req.body, schema)
 * ```
 */
fun validate(body: Map<String, Any?>, schema: SchemaObject) {
    val context = CamelContextHolder.get()
    if (context != null) {
        camelValidate(context, body, schema)
    } else {
        manualValidate(body, schema)
    }
}

// ── Camel-backed schema validation ──────────────────────────────────

/** Cache of SchemaObject.name → generated JSON Schema string. */
private val jsonSchemaCache = ConcurrentHashMap<String, String>()

private fun camelValidate(context: CamelContext, body: Map<String, Any?>, schema: SchemaObject) {
    val jsonSchema = jsonSchemaCache.getOrPut(schema.name) { toJsonSchema(schema) }

    // Write schema to a temp file for Camel's json-validator endpoint
    val schemaFile = java.nio.file.Files.createTempFile("schema-${schema.name}-", ".json")
    java.nio.file.Files.writeString(schemaFile, jsonSchema)

    val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    val bodyJson = mapper.writeValueAsString(body)

    val template = context.createProducerTemplate()
    try {
        template.requestBody("json-validator:file:${schemaFile.toAbsolutePath()}", bodyJson)
    } catch (e: org.apache.camel.CamelExecutionException) {
        // Parse Camel's validation error into our Violation model
        val cause = e.cause
        val violations = if (cause is com.networknt.schema.JsonSchemaException) {
            cause.message?.let { parseValidationMessage(it) } ?: listOf(Violation("_body", cause.message ?: "Validation failed"))
        } else if (cause?.message?.contains("$.") == true || cause?.message?.contains("required") == true) {
            parseValidationMessage(cause.message ?: "Validation failed")
        } else {
            listOf(Violation("_body", cause?.message ?: "Schema validation failed"))
        }
        throw ValidationException(violations)
    } finally {
        template.close()
        java.nio.file.Files.deleteIfExists(schemaFile)
    }
}

/** Convert a [SchemaObject] to a JSON Schema draft-07 string. */
internal fun toJsonSchema(schema: SchemaObject): String {
    val properties = mutableMapOf<String, Any>()

    for ((fieldName, fieldDef) in schema.fields) {
        val prop = mutableMapOf<String, Any>()
        prop["type"] = when (fieldDef.type) {
            FieldType.STRING -> "string"
            FieldType.INTEGER -> "integer"
            FieldType.NUMBER -> "number"
            FieldType.BOOLEAN -> "boolean"
            FieldType.ARRAY -> "array"
            FieldType.OBJECT -> "object"
        }
        fieldDef.description?.let { prop["description"] = it }
        fieldDef.enumValues?.let { prop["enum"] = it }
        properties[fieldName] = prop
    }

    val jsonSchema = mutableMapOf<String, Any>(
        "\$schema" to "http://json-schema.org/draft-07/schema#",
        "type" to "object",
        "properties" to properties,
    )
    if (schema.required.isNotEmpty()) {
        jsonSchema["required"] = schema.required
    }

    return com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(jsonSchema)
}

/** Best-effort extraction of field-level violations from a validation error message. */
private fun parseValidationMessage(message: String): List<Violation> {
    val violations = mutableListOf<Violation>()
    // networknt validator messages look like: "$.fieldName: string found, integer expected"
    val pattern = Regex("""\$\.(\w+):\s*(.+)""")
    for (match in pattern.findAll(message)) {
        violations += Violation(match.groupValues[1], match.groupValues[2].trim())
    }
    // Also catch "required property" messages
    val reqPattern = Regex("""required property '(\w+)'""")
    for (match in reqPattern.findAll(message)) {
        violations += Violation(match.groupValues[1], "is required")
    }
    if (violations.isEmpty()) {
        violations += Violation("_body", message)
    }
    return violations
}

// ── Manual fallback (tests without CamelContext) ────────────────────

private fun manualValidate(body: Map<String, Any?>, schema: SchemaObject) {
    val violations = mutableListOf<Violation>()

    for (fieldName in schema.required) {
        if (!body.containsKey(fieldName) || body[fieldName] == null) {
            violations += Violation(fieldName, "is required")
        }
    }

    for ((fieldName, fieldDef) in schema.fields) {
        val value = body[fieldName] ?: continue

        val typeOk = when (fieldDef.type) {
            FieldType.STRING -> value is String
            FieldType.INTEGER -> value is Int || value is Long
            FieldType.NUMBER -> value is Number
            FieldType.BOOLEAN -> value is Boolean
            FieldType.ARRAY -> value is List<*>
            FieldType.OBJECT -> value is Map<*, *>
        }
        if (!typeOk) {
            violations += Violation(fieldName, "expected type ${fieldDef.type} but got ${value::class.simpleName}")
        }

        if (fieldDef.enumValues != null && value is String && value !in fieldDef.enumValues) {
            violations += Violation(fieldName, "must be one of ${fieldDef.enumValues}, got '$value'")
        }
    }

    if (violations.isNotEmpty()) {
        throw ValidationException(violations)
    }
}
