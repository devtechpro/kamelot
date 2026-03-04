# Integration DSL

A Kotlin DSL on top of Apache Camel for building API integrations. Clean syntax, type-safe, testable, visual.

Think Make.com UX with proper engineering underneath.

## Quick Example

```kotlin
val enrichment = integration("user-enrichment") {
    val api = spec("specs/enrichment-openapi.yaml")
    expose(api, port = 5401)

    val usersApi = adapter("jsonplaceholder", spec("specs/jp-openapi.yaml")) {
        baseUrl = "https://jsonplaceholder.typicode.com"
    }

    flow("enrich") { req ->
        val user = usersApi.get("/users/${req.body["userId"]}")
        respond(
            "fullName" to user["name"],
            "city" to user.nested("address.city"),
            "company" to user.nested("company.name"),
        )
    }
}
```

## Documentation

- [SPEC.md](SPEC.md) — Full specification
- [EXAMPLES.md](EXAMPLES.md) — Integration examples with public APIs
- [ARCHITECTURE.md](ARCHITECTURE.md) — Internals, Camel mapping, module structure
