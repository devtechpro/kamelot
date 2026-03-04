package io.devtech.integration.contacts.flows

import io.devtech.integration.dsl.AdapterRef
import io.devtech.integration.dsl.handler
import io.devtech.integration.dsl.respond

fun deleteHandler(db: AdapterRef) = handler { req ->
    val id = req.pathParams["id"]
    val deleted = db.delete("/contacts/$id")
    respond(body = deleted)
}
