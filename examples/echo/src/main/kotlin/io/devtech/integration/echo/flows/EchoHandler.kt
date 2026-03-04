package io.devtech.integration.echo.flows

import io.devtech.integration.dsl.handler
import io.devtech.integration.dsl.now
import io.devtech.integration.dsl.respond

/**
 * POST /echo — echoes back the request message with metadata.
 */
val echoHandler = handler { req ->
    respond(req.body) {
        "message" to "message"
        "timestamp" set now()
        "source" set "echo-service"
    }
}
