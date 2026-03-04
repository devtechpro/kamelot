package io.devtech.integration.camel

import org.apache.camel.CamelContext

/**
 * Thread-local holder for [CamelContext]. Set by [ExposeRouteGenerator] before
 * executing handler lambdas, so that DSL blocks like [parallel] and [validate]
 * can access Camel infrastructure without explicit parameter threading.
 *
 * Returns null outside a Camel-managed handler (e.g. in unit tests),
 * allowing callers to fall back to non-Camel implementations.
 */
object CamelContextHolder {
    private val ctx = ThreadLocal<CamelContext>()

    fun set(context: CamelContext) = ctx.set(context)
    fun get(): CamelContext? = ctx.get()
    fun clear() = ctx.remove()
}
