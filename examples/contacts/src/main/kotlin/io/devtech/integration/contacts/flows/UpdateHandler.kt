package io.devtech.integration.contacts.flows

import io.devtech.integration.dsl.AdapterRef
import io.devtech.integration.dsl.handler
import io.devtech.integration.dsl.respond

fun updateHandler(db: AdapterRef) = handler { req ->
    val id = req.pathParams["id"]
    val updated = db.put("/contacts/$id", req.body)
    respond(body = updated)
}
