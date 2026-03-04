package io.devtech.integration.managed

import io.devtech.integration.dsl.handler
import io.devtech.integration.dsl.now
import io.devtech.integration.dsl.respond
import io.devtech.integration.echo.EchoFactory
import io.devtech.integration.echo.flows.echoHandler
import io.devtech.integration.echo.flows.healthHandler
import io.devtech.integration.management.dsl.manage
import java.io.File

/**
 * Management plane example — deploys multiple integrations to a local agent.
 *
 * Demonstrates:
 * 1. Factory-based deployment (EchoFactory on default port 5400)
 * 2. Factory-based deployment with port override (port 5500)
 * 3. Inline DSL deployment (port 5401)
 *
 * Run: `./gradlew :examples:managed:run`
 *
 * Then:
 *   curl http://localhost:9000/mgmt/health
 *   curl http://localhost:9000/mgmt/deployments
 *   curl -X POST http://localhost:5400/echo -H 'Content-Type: application/json' -d '{"message":"hello"}'
 *   curl -X POST http://localhost:5500/echo -H 'Content-Type: application/json' -d '{"message":"hello from 5500"}'
 *   curl -X POST http://localhost:5401/echo -H 'Content-Type: application/json' -d '{"message":"inline"}'
 */
fun main() = manage(port = 9000) {
    localAgent()

    val echoDir = File("examples/echo")

    // Deploy echo-service from factory (default port)
    deploy(EchoFactory(), baseDir = echoDir)

    // Deploy echo-service from factory with port override
    deploy(EchoFactory(), properties = mapOf("PORT" to "5500"), baseDir = echoDir)

    // Deploy inline echo variant (no factory/JAR needed)
    deployInline("echo-dev") {
        version = 1
        description = "Inline echo for development"

        val api = spec("examples/echo/specs/echo-openapi.yaml")
        expose(api, port = 5401)

        flow("echo", handler { req ->
            respond(req.body) {
                "message" to "message"
                "timestamp" set now()
                "source" set "echo-dev-inline"
            }
        })

        flow("health", healthHandler)
    }
}
