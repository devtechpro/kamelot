package io.devtech.integration.dsl

import io.devtech.integration.model.*
import java.time.Instant

/**
 * Define a handler as a simple lambda.
 *
 * ```kotlin
 * val echoHandler = handler { req ->
 *     respond(
 *         "message" to req.body["message"],
 *         "timestamp" to now(),
 *     )
 * }
 * ```
 */
fun handler(block: (RequestContext) -> ResponseContext): (RequestContext) -> ResponseContext = block

/**
 * Build a response using `set` and `to` field builders.
 *
 * ```kotlin
 * respond(req.body) {
 *     "message" to "message"          // source["message"] → response
 *     "timestamp" set now()           // computed value
 *     "source" set "echo-service"     // literal
 * }
 * ```
 */
fun respond(source: Map<String, Any?>, statusCode: Int = 200, block: ResponseBuilder.() -> Unit): ResponseContext {
    val builder = ResponseBuilder(source)
    builder.block()
    return builder.build(statusCode)
}

/**
 * Build a response from key-value pairs.
 *
 * ```kotlin
 * respond("key" to "value", "count" to 42)
 * ```
 */
fun respond(vararg fields: Pair<String, Any?>, statusCode: Int = 200, headers: Map<String, String> = emptyMap()): ResponseContext {
    return ResponseContext(statusCode = statusCode, body = mapOf(*fields), headers = headers)
}

/**
 * Build a response with explicit body (any type). For non-map response bodies.
 *
 * ```kotlin
 * respond(body = listOf(1, 2, 3))
 * respond(statusCode = 204)
 * ```
 */
fun respond(statusCode: Int = 200, body: Any? = null, headers: Map<String, String> = emptyMap()): ResponseContext {
    return ResponseContext(statusCode = statusCode, body = body, headers = headers)
}

/**
 * Builder for response fields with `set` (assign value) and `to` (map from source field).
 *
 * ```kotlin
 * val builder = ResponseBuilder(sourceBody)
 * builder.apply {
 *     "message" to "message"          // body["message"] → response.message
 *     "timestamp" set now()           // computed value
 *     "source" set "echo-service"     // literal
 * }
 * ```
 */
@IntegrationDsl
class ResponseBuilder(private val source: Map<String, Any?> = emptyMap()) {
    internal val fields = mutableMapOf<String, Any?>()

    /** Map a field from the source body to a response field. */
    infix fun String.to(sourceField: String) {
        fields[this] = source[sourceField]
    }

    /** Set a response field to an explicit value. */
    infix fun String.set(value: Any?) {
        fields[this] = value
    }

    fun build(statusCode: Int = 200, headers: Map<String, String> = emptyMap()): ResponseContext {
        return ResponseContext(statusCode = statusCode, body = fields.toMap(), headers = headers)
    }
}

// ── DSL helpers for process {} lambdas ─────────────────────────────────────

/** Current ISO-8601 timestamp. */
fun now(): String = Instant.now().toString()

/** Slugify a string: "Super Gadget 3000" → "super-gadget-3000". */
fun slugify(text: String): String =
    text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

// ── Typed map accessors for body maps ──────────────────────────────────────

/** Get a String field, or empty string if missing/wrong type. */
fun Map<String, Any?>.string(key: String): String = get(key) as? String ?: ""

/** Get an Int field, or null if missing/wrong type. */
fun Map<String, Any?>.int(key: String): Int? = (get(key) as? Number)?.toInt()

/** Get a Long field, or null if missing/wrong type. */
fun Map<String, Any?>.long(key: String): Long? = (get(key) as? Number)?.toLong()

/** Get a Double field, or null if missing/wrong type. */
fun Map<String, Any?>.double(key: String): Double? = (get(key) as? Number)?.toDouble()

/** Get a Boolean field, or null if missing/wrong type. */
fun Map<String, Any?>.bool(key: String): Boolean? = get(key) as? Boolean
