# Kamelot

A Kotlin DSL on Apache Camel for building API integrations. Spec-first, type-safe, testable.

## Quick Start

```kotlin
fun main() = execute("echo-service") {
    val api = spec("specs/echo-openapi.yaml")
    expose(api, port = 5400)

    flow("echo", handler { req ->
        respond(req.body) {
            "message" to "message"
            "timestamp" set now()
            "source" set "echo-service"
        }
    })
}
```

## Adapter Example

Call external APIs with typed adapters backed by OpenAPI specs:

```kotlin
fun main() = execute("user-enrichment") {
    val api = spec("specs/enrichment-openapi.yaml")
    expose(api, port = 5401)

    val usersApi = adapter("jsonplaceholder", spec("specs/jp-openapi.yaml")) {
        baseUrl = "https://jsonplaceholder.typicode.com"
    }

    flow("enrich", enrichHandler(usersApi))
}

fun enrichHandler(usersApi: AdapterRef) = handler { req ->
    val user = usersApi.get("/users/${req.body["userId"]}")
    respond(user) {
        "fullName" to "name"
        "city" set user.nested("address.city")
        "company" set user.nested("company.name")
        "enrichedAt" set now()
    }
}
```

## Database-Backed CRUD

```kotlin
fun main() = execute("products-api") {
    val api = spec("specs/products-openapi.yaml")
    expose(api, port = 5500)

    val db = adapter("products-db", spec("specs/products-db-spec.yaml")) {
        postgres {
            url = env("POSTGRES_URL", "jdbc:postgresql://localhost:5432/products")
            username = env("POSTGRES_USER", "postgres")
            password = secret("POSTGRES_PASSWORD", "postgres")
            table = "products"
        }
    }

    flow("listProducts") { call(db, HttpMethod.GET, "/products") }
    flow("getProduct") { call(db, HttpMethod.GET, "/products/{id}") }
    flow("createProduct") {
        statusCode = 201
        process("enrich") { body ->
            body + mapOf("slug" to slugify(body.string("name")), "created_at" to now())
        }
        call(db, HttpMethod.POST, "/products")
    }
    flow("deleteProduct") { call(db, HttpMethod.DELETE, "/products/{id}") }
}
```

## Managed Runtime

Run multiple integrations under a management plane:

```kotlin
fun main() = manage(port = 9000) {
    localAgent("services") {
        deploy(EchoFactory())
        deploy(EnrichmentFactory())
    }
}
```

## Modules

| Module | Description |
|--------|-------------|
| `core` | DSL builders, model, Camel route generation, spec engine |
| `runtime` | Standalone `execute()` entry point |
| `management` | Management plane, agents, artifact deployment |
| `ide/studio` | Desktop IDE (Kotlin/Compose) |
| `ide/bff` | IDE backend-for-frontend (Bun/TypeScript) |
| `ide/frontend` | IDE web UI (Vue) |
| `examples/*` | Echo, enrichment, contacts, products, managed |

## Building

```bash
./gradlew build
```

Requires JDK 21+.

## Documentation

- [SPEC.md](SPEC.md) — Full specification
- [ARCHITECTURE.md](ARCHITECTURE.md) — Internals, Camel mapping, module structure
- [EXAMPLES.md](EXAMPLES.md) — Integration examples
