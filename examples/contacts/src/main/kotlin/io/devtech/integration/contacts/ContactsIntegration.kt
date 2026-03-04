package io.devtech.integration.contacts

import io.devtech.integration.contacts.flows.*
import io.devtech.integration.dsl.env
import io.devtech.integration.execute

fun main() = execute("contacts-api") {
    version = 1
    description = "CRUD contacts with swappable database backend"

    val api = spec("examples/contacts/specs/contacts-openapi.yaml")
    expose(api, port = env("PORT", 5402))

    val db = adapter("contacts-db", spec("examples/contacts/specs/contacts-db-spec.yaml")) {
        inMemory()
    }

    flow("listContacts", listHandler(db))
    flow("getContact", getHandler(db))
    flow("createContact", createHandler(db))
    flow("updateContact", updateHandler(db))
    flow("deleteContact", deleteHandler(db))
}
