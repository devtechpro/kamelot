package io.devtech.integration.factory

import io.devtech.integration.dsl.AdapterRef
import io.devtech.integration.model.Integration
import java.io.File

/**
 * Context provided to an [IntegrationFactory] at deployment time.
 * Provides the base directory for spec file resolution and runtime property overrides.
 */
data class DeploymentContext(
    /** Base directory for resolving spec file paths (e.g., extracted JAR contents). */
    val baseDir: File,
    /** Runtime property overrides (e.g., PORT, DB_URL). Checked before env vars. */
    val properties: Map<String, String> = emptyMap(),
) {
    /** Resolve a relative spec path against [baseDir]. Returns an absolute path string. */
    fun specPath(relative: String): String = File(baseDir, relative).absolutePath

    /** Read a property: deployment properties > env vars > default. */
    fun property(name: String, default: String): String =
        properties[name] ?: System.getenv(name) ?: default

    /** Read an int property: deployment properties > env vars > default. */
    fun property(name: String, default: Int): Int =
        properties[name]?.toIntOrNull() ?: System.getenv(name)?.toIntOrNull() ?: default
}

/**
 * Bundles an [Integration] model with its live [AdapterRef] instances.
 *
 * The AdapterRefs are needed at runtime for ProducerTemplate binding —
 * they are captured inside handler lambdas as closures and the runtime
 * must inject CamelContext resources into [CamelBindable] backends.
 */
data class IntegrationPackage(
    val integration: Integration,
    val adapterRefs: List<AdapterRef> = emptyList(),
)

/**
 * Factory interface for packaged integrations.
 *
 * Implementations live inside deployable JARs and are discovered via
 * `META-INF/integration.properties`:
 * ```properties
 * integration.name=echo-service
 * integration.factory=io.devtech.integration.echo.EchoFactory
 * ```
 *
 * The factory receives a [DeploymentContext] which provides:
 * - `baseDir`: where spec YAML files can be found (extracted from JAR)
 * - `properties`: runtime overrides for ports, URLs, secrets, etc.
 *
 * Must have a no-arg constructor for reflective instantiation by [JarLoader].
 */
interface IntegrationFactory {
    val name: String
    fun create(ctx: DeploymentContext): IntegrationPackage
}
