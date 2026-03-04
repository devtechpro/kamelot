package io.devtech.integration.enrichment

import io.devtech.integration.enrichment.flows.enrichHandler
import io.devtech.integration.execute

fun main() = execute("user-enrichment") {
    version = 1
    description = "Enriches user data from JSONPlaceholder"

    val api = spec("examples/enrichment/specs/enrichment-openapi.yaml")
    expose(api, port = 5401)

    val usersApi = adapter("jsonplaceholder", spec("examples/enrichment/specs/jsonplaceholder-openapi.yaml")) {
        baseUrl = "https://jsonplaceholder.typicode.com"
    }

    flow("enrich", enrichHandler(usersApi))
}
