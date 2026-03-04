package io.devtech.integration.dsl

import io.devtech.integration.model.Adapter
import org.apache.camel.ProducerTemplate

/**
 * Runtime-callable adapter reference. Returned from `adapter()` in the DSL,
 * usable inside handler lambdas to call external APIs or database backends.
 *
 * ```kotlin
 * val user = usersApi.get("/users/1")
 * val created = db.post("/contacts", mapOf("name" to "Alice"))
 * val results = db.get("/contacts", queryParams = mapOf("search" to "alice"))
 * ```
 */
class AdapterRef(val adapter: Adapter, internal val backend: AdapterBackend) {
    val name: String get() = adapter.name

    /** Whether this adapter's backend needs a Camel ProducerTemplate at runtime. */
    val isCamelManaged: Boolean get() = backend is CamelBindable

    /**
     * Bind a ProducerTemplate to this adapter's backend (if it supports Camel).
     * Called by the runtime after CamelContext is initialized.
     */
    fun bindProducerTemplate(template: ProducerTemplate) {
        val b = backend
        if (b is CamelBindable) {
            b.bindProducerTemplate(template)
        }
    }

    fun get(path: String, queryParams: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return backend.execute("GET", path, queryParams = queryParams, headers = headers) as? Map<String, Any?> ?: emptyMap()
    }

    fun getList(path: String, queryParams: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return backend.execute("GET", path, queryParams = queryParams, headers = headers) as? List<Map<String, Any?>> ?: emptyList()
    }

    fun post(path: String, body: Any? = null, headers: Map<String, String> = emptyMap()): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return backend.execute("POST", path, body = body, headers = headers) as? Map<String, Any?> ?: emptyMap()
    }

    fun put(path: String, body: Any? = null, headers: Map<String, String> = emptyMap()): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return backend.execute("PUT", path, body = body, headers = headers) as? Map<String, Any?> ?: emptyMap()
    }

    fun delete(path: String, headers: Map<String, String> = emptyMap()): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return backend.execute("DELETE", path, headers = headers) as? Map<String, Any?> ?: emptyMap()
    }
}

class AdapterCallException(
    val adapterName: String,
    val method: String,
    val path: String,
    val statusCode: Int,
    val responseBody: String?,
) : RuntimeException("Adapter '$adapterName' call failed: $method $path → $statusCode")

/**
 * Access nested fields in a map using dot notation.
 *
 * ```kotlin
 * val city = user.nested("address.city")       // → "Gwenborough"
 * val company = user.nested("company.name")    // → "Romaguera-Crona"
 * ```
 */
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.nested(dotPath: String): Any? {
    val parts = dotPath.split(".")
    var current: Any? = this
    for (part in parts) {
        current = (current as? Map<String, Any?>)?.get(part) ?: return null
    }
    return current
}
