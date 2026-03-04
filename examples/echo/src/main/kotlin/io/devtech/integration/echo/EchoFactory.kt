package io.devtech.integration.echo

import io.devtech.integration.dsl.IntegrationBuilder
import io.devtech.integration.echo.flows.echoHandler
import io.devtech.integration.echo.flows.healthHandler
import io.devtech.integration.factory.DeploymentContext
import io.devtech.integration.factory.IntegrationFactory
import io.devtech.integration.factory.IntegrationPackage

/**
 * Factory for the echo-service integration.
 *
 * Discovered via `META-INF/integration.properties` when deployed as a JAR.
 * Can also be used directly in the manage {} DSL.
 *
 * Supports runtime property overrides:
 * - `PORT`: HTTP port (default 5400)
 */
class EchoFactory : IntegrationFactory {
    override val name = "echo-service"

    override fun create(ctx: DeploymentContext): IntegrationPackage {
        val builder = IntegrationBuilder(name).apply {
            version = 1
            description = "Simple echo service — returns what you send it"

            val api = spec(ctx.specPath("specs/echo-openapi.yaml"))
            expose(api, port = ctx.property("PORT", 5400))

            flow("echo", echoHandler)
            flow("health", healthHandler)
        }
        return IntegrationPackage(builder.build(), builder.adapterRefs)
    }
}
