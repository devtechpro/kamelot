package io.devtech.integration.model

/**
 * Key-based redaction for maps flowing through debug traces and management APIs.
 *
 * Values whose keys match common secret patterns are replaced with `"***"`.
 * Also catches any [Secret] instance regardless of key name.
 *
 * ```kotlin
 * mapOf("username" to "admin", "password" to "s3cret").redactSecrets()
 * // → {username=admin, password=***}
 * ```
 */

private val SENSITIVE_KEYS = Regex(
    "password|secret|token|key|auth|credential|apikey|api_key",
    RegexOption.IGNORE_CASE,
)

fun Map<String, Any?>.redactSecrets(): Map<String, Any?> = mapValues { (key, value) ->
    when {
        SENSITIVE_KEYS.containsMatchIn(key) -> "***"
        value is Secret -> "***"
        value is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            (value as Map<String, Any?>).redactSecrets()
        }
        else -> value
    }
}

/** Typed overload for `Map<String, String>` (e.g. HTTP headers). */
@JvmName("redactStringSecrets")
fun Map<String, String>.redactSecrets(): Map<String, String> = mapValues { (key, value) ->
    if (SENSITIVE_KEYS.containsMatchIn(key)) "***" else value
}
