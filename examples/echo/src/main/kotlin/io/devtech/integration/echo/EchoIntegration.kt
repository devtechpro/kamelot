package io.devtech.integration.echo

import io.devtech.integration.echo.flows.echoHandler
import io.devtech.integration.echo.flows.healthHandler
import io.devtech.integration.execute
import kotlin.time.Duration.Companion.seconds

fun main() = execute("echo-service") {
    version = 1
    description = "Echo service with periodic heartbeat"

    val api = spec("examples/echo/specs/echo-openapi.yaml")
    expose(api, port = 5400)

    flow("echo", echoHandler)
    flow("health", healthHandler)

    // Heartbeat: logs every 30 seconds
    triggered("heartbeat") {
        every(30.seconds)
        log("echo-service heartbeat — alive")
    }
}
