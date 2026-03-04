package io.devtech.integration.dsl

import io.devtech.integration.model.Secret

/**
 * Read an environment variable with a default fallback.
 *
 * ```kotlin
 * val gw = expose(api, port = env("PORT", 5400))
 * adapter("api", spec(...)) {
 *     baseUrl = env("API_URL", "https://example.com")
 * }
 * ```
 */
fun env(name: String, default: String = ""): String = System.getenv(name) ?: default

fun env(name: String, default: Int): Int = System.getenv(name)?.toIntOrNull() ?: default

/**
 * Read a secret from the configured [SecretProvider]. Returns a [Secret]
 * that hides its value from `toString()` and Jackson serialization.
 *
 * ```kotlin
 * adapter("api", spec(...)) {
 *     auth = AuthConfig(type = AuthType.BEARER, secret = secret("API_KEY"))
 *     postgres {
 *         password = secret("POSTGRES_PASSWORD", "postgres")
 *     }
 * }
 * ```
 */
fun secret(name: String, default: String = ""): Secret =
    SecretProvider.current.resolve(name) ?: Secret(default)
