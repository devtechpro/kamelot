package io.devtech.integration.contacts.flows

import io.devtech.integration.dsl.AdapterRef
import io.devtech.integration.dsl.handler
import io.devtech.integration.dsl.respond

fun listHandler(db: AdapterRef) = handler { req ->
    val contacts = db.getList("/contacts", queryParams = req.queryParams)
    respond(body = contacts)
}
