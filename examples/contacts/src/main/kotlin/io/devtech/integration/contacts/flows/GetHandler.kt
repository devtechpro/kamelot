package io.devtech.integration.contacts.flows

import io.devtech.integration.dsl.AdapterRef
import io.devtech.integration.dsl.handler
import io.devtech.integration.dsl.respond

fun getHandler(db: AdapterRef) = handler { req ->
    val id = req.pathParams["id"]
    val contact = db.get("/contacts/$id")
    respond(body = contact)
}
