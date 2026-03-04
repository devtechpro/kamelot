package io.devtech.integration.dsl

import io.devtech.integration.model.Adapter
import io.devtech.integration.model.AuthConfig
import io.devtech.integration.model.Secret
import io.devtech.integration.model.Spec

/**
 * Builder for the `adapter("name", spec) { }` block form.
 *
 * ```kotlin
 * // HTTP adapter (default)
 * val usersApi = adapter("jsonplaceholder", spec("specs/jp-openapi.yaml")) {
 *     baseUrl = "https://jsonplaceholder.typicode.com"
 * }
 *
 * // Postgres adapter
 * val db = adapter("contacts-db", spec("specs/db-spec.yaml")) {
 *     postgres {
 *         url = env("POSTGRES_URL", "jdbc:postgresql://localhost:5432/contacts")
 *         username = env("POSTGRES_USER", "postgres")
 *         password = env("POSTGRES_PASSWORD", "postgres")
 *         table = "contacts"
 *     }
 * }
 *
 * // In-memory adapter (testing / prototyping)
 * val db = adapter("contacts-db", spec("specs/db-spec.yaml")) {
 *     inMemory()
 * }
 * ```
 */
@IntegrationDsl
class AdapterBuilder(private val name: String, private val spec: Spec) {
    var baseUrl: String = ""
    var auth: AuthConfig? = null

    private var backendType: BackendType = BackendType.HTTP
    private var postgresConfig: PostgresConfig? = null

    fun postgres(block: PostgresConfig.() -> Unit) {
        backendType = BackendType.POSTGRES
        postgresConfig = PostgresConfig().apply(block)
    }

    /** Use in-memory storage (testing/prototyping only — no persistence, no Camel). */
    fun inMemory() {
        backendType = BackendType.IN_MEMORY
    }

    fun build(): Adapter = Adapter(
        name = name,
        spec = spec,
        baseUrl = baseUrl,
        auth = auth,
    )

    fun buildRef(): AdapterRef {
        val adapter = build()
        val backend = when (backendType) {
            BackendType.HTTP -> CamelHttpBackend(name, baseUrl, auth)
            BackendType.IN_MEMORY -> InMemoryBackend(name)
            BackendType.POSTGRES -> {
                val pg = postgresConfig ?: error("postgres {} block required")
                PostgresBackend(name, pg.url, pg.username, pg.password, pg.table, spec, pg.schema)
            }
        }
        return AdapterRef(adapter, backend)
    }

    private enum class BackendType { HTTP, IN_MEMORY, POSTGRES }
}

@IntegrationDsl
class PostgresConfig {
    var url: String = ""
    var username: String = ""
    var password: Secret = Secret.EMPTY
    var table: String = ""
    /** Which spec schema defines the table structure (e.g. "Product"). Auto-detected if null. */
    var schema: String? = null
}
