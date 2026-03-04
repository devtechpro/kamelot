package io.devtech.integration.dsl

import io.devtech.integration.model.Secret

/**
 * Pluggable secret resolution. The default reads environment variables.
 *
 * Swap at startup for vault-backed resolution:
 * ```kotlin
 * SecretProvider.current = InfisicalSecretProvider(sdk, projectId, "production")
 * ```
 *
 * Camel's native vault property functions (`{{aws:...}}`, `{{hashicorp:...}}`)
 * are wired separately through `PropertiesComponent` and don't go through this interface.
 */
interface SecretProvider {
    /** Resolve a secret by name. Returns null if not found. */
    fun resolve(name: String): Secret?

    companion object {
        /** The active provider. Set before building integrations. */
        var current: SecretProvider = EnvSecretProvider
    }
}

/** Default provider — reads secrets from environment variables. */
object EnvSecretProvider : SecretProvider {
    override fun resolve(name: String): Secret? =
        System.getenv(name)?.let { Secret(it) }
}
