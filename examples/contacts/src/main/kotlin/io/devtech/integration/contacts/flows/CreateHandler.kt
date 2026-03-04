package io.devtech.integration.contacts.flows

import io.devtech.integration.dsl.AdapterRef
import io.devtech.integration.dsl.handler
import io.devtech.integration.dsl.respond

fun createHandler(db: AdapterRef) = handler { req ->
    val created = db.post("/contacts", req.body)
    respond(body = created, statusCode = 201)
}
