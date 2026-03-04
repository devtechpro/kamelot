package io.devtech.integration.enrichment.flows

import io.devtech.integration.dsl.AdapterRef
import io.devtech.integration.dsl.handler
import io.devtech.integration.dsl.nested
import io.devtech.integration.dsl.now
import io.devtech.integration.dsl.respond

/**
 * POST /enrich — fetches a user from JSONPlaceholder and maps fields.
 *
 * Multi-step flow:
 * 1. Extract userId from request
 * 2. Call external API via adapter
 * 3. Map/transform fields to our schema
 * 4. Return enriched response
 */
fun enrichHandler(usersApi: AdapterRef) = handler { req ->
    val userId = req.body["userId"]

    // Step 1: Call external API via adapter
    val user = usersApi.get("/users/$userId")

    // Step 2: Map fields from external schema to our schema
    respond(user) {
        "fullName" to "name"
        "emailAddress" to "email"
        "phone" to "phone"
        "city" set user.nested("address.city")
        "company" set user.nested("company.name")
        "website" to "website"
        "source" set "jsonplaceholder"
        "enrichedAt" set now()
    }
}
