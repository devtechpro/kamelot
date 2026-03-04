package io.devtech.integration.dsl

import io.devtech.integration.model.ExposeConfig
import io.devtech.integration.model.Spec

/**
 * Builder for the `expose(spec) { }` block form.
 *
 * ```kotlin
 * expose(api) {
 *     port = 5400
 *     host = "0.0.0.0"
 * }
 * ```
 */
@IntegrationDsl
class ExposeBuilder(private val spec: Spec) {
    var port: Int = 8080
    var host: String = "0.0.0.0"
    var docsPath: String? = "/docs"

    fun build(): ExposeConfig = ExposeConfig(
        spec = spec,
        port = port,
        host = host,
        docsPath = docsPath,
    )
}
