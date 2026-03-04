package io.devtech.integration.products

import io.devtech.integration.dsl.*
import io.devtech.integration.execute
import io.devtech.integration.model.HttpMethod

fun main() = execute("products-api") {
    version = 1
    description = "Product catalog — declarative Camel flows with Postgres"

    val api = spec("examples/products/specs/products-openapi.yaml")
    expose(api, port = env("PORT", 5500))

    val db = adapter("products-db", spec("examples/products/specs/products-db-spec.yaml")) {
        postgres {
            url = env("POSTGRES_URL", "jdbc:postgresql://localhost:5432/products")
            username = env("POSTGRES_USER", "postgres")
            password = secret("POSTGRES_PASSWORD", "postgres")
            table = "products"
            schema = "Product"
        }
    }

    flow("listProducts") { call(db, HttpMethod.GET, "/products") }
    flow("getProduct") { call(db, HttpMethod.GET, "/products/{id}") }

    flow("createProduct") {
        statusCode = 201
        process("enrich") { body ->
            body + mapOf(
                "slug" to slugify(body.string("name")),
                "created_at" to now(),
                "updated_at" to now(),
            )
        }
        log("Creating product")
        call(db, HttpMethod.POST, "/products")
    }

    flow("updateProduct") {
        process("addUpdatedAt") { body -> body + ("updated_at" to now()) }
        call(db, HttpMethod.PUT, "/products/{id}")
    }

    flow("deleteProduct") { call(db, HttpMethod.DELETE, "/products/{id}") }
}
