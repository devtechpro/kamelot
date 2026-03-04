package io.devtech.integration.echo.flows

import io.devtech.integration.dsl.handler
import io.devtech.integration.dsl.respond
import java.lang.management.ManagementFactory
import java.time.Duration

/**
 * GET /health — returns service health and uptime.
 */
val healthHandler = handler { _ ->
    val uptime = Duration.ofMillis(ManagementFactory.getRuntimeMXBean().uptime)
    respond(emptyMap()) {
        "status" set "ok"
        "uptime" set formatDuration(uptime)
    }
}

private fun formatDuration(d: Duration): String {
    val hours = d.toHours()
    val minutes = d.toMinutesPart()
    val seconds = d.toSecondsPart()
    return "${hours}h ${minutes}m ${seconds}s"
}
