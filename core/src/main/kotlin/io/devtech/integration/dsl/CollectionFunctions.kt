package io.devtech.integration.dsl

/**
 * DataWeave-inspired collection functions for `Map<String, Any?>`.
 *
 * These address the ugly cast/access patterns that come from working with
 * untyped maps in integration flows.
 *
 * ```kotlin
 * body.pick("name", "email")                // keep only these keys
 * body.omit("password", "secret")           // drop these keys
 * body.rename("fullName" to "name")         // rename keys
 * body.list("items")                        // safe cast to List<Map>
 * body.nestedString("address.city")         // typed nested access
 * ```
 */

/** Keep only the specified keys. */
fun Map<String, Any?>.pick(vararg keys: String): Map<String, Any?> {
    val keySet = keys.toSet()
    return filterKeys { it in keySet }
}

/** Remove the specified keys. */
fun Map<String, Any?>.omit(vararg keys: String): Map<String, Any?> {
    val keySet = keys.toSet()
    return filterKeys { it !in keySet }
}

/** Keep only the specified keys that have non-null values. */
fun Map<String, Any?>.pickNonNull(vararg keys: String): Map<String, Any?> {
    val keySet = keys.toSet()
    return filterKeys { it in keySet }.filterValues { it != null }
}

/**
 * Rename keys. Existing values are preserved; original keys are removed.
 *
 * ```kotlin
 * mapOf("fullName" to "John").rename("fullName" to "name")
 * // → {"name": "John"}
 * ```
 */
fun Map<String, Any?>.rename(vararg mappings: Pair<String, String>): Map<String, Any?> {
    val renameMap = mappings.toMap()
    return map { (k, v) -> (renameMap[k] ?: k) to v }.toMap()
}

/**
 * Safely extract a list of maps from a key. Returns empty list if missing or wrong type.
 *
 * Replaces the common pattern:
 * ```kotlin
 * // Before:
 * (body["items"] as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
 * // After:
 * body.list("items")
 * ```
 */
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.list(key: String): List<Map<String, Any?>> =
    (get(key) as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()

/** Safely extract a list of strings from a key. */
fun Map<String, Any?>.stringList(key: String): List<String> =
    (get(key) as? List<*>)?.filterIsInstance<String>() ?: emptyList()

/** Nested dot-path access returning String (empty string if missing). */
fun Map<String, Any?>.nestedString(path: String): String =
    nested(path) as? String ?: ""

/** Nested dot-path access returning Int (null if missing). */
fun Map<String, Any?>.nestedInt(path: String): Int? =
    (nested(path) as? Number)?.toInt()

/** Nested dot-path access returning Double (null if missing). */
fun Map<String, Any?>.nestedDouble(path: String): Double? =
    (nested(path) as? Number)?.toDouble()

/**
 * Conditionally transform a map. Returns the original if condition is false.
 *
 * ```kotlin
 * body.mapIf(body.string("type") == "premium") { it + ("discount" to 0.1) }
 * ```
 */
fun Map<String, Any?>.mapIf(
    condition: Boolean,
    transform: (Map<String, Any?>) -> Map<String, Any?>,
): Map<String, Any?> = if (condition) transform(this) else this
