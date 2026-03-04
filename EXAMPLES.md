# Kamelot — Examples

Working examples using the current DSL. All examples are in `examples/`.

---

## 1. Echo Service (simplest possible)

Expose an API that echoes back requests. Demonstrates `execute()`, `spec()`, `expose()`, `flow()`, and `handler`.

```kotlin
fun main() = execute("echo-service") {
    version = 1
    description = "Echo service with periodic heartbeat"

    val api = spec("specs/echo-openapi.yaml")
    expose(api, port = 5400)

    flow("echo", handler { req ->
        respond(req.body) {
            "message" to "message"
            "timestamp" set now()
            "source" set "echo-service"
        }
    })

    flow("health", handler { _ ->
        respond("status" to "ok", "timestamp" to now())
    })

    // Heartbeat: logs every 30 seconds
    triggered("heartbeat") {
        every(30.seconds)
        log("echo-service heartbeat — alive")
    }
}
```

**Source:** `examples/echo/`

---

## 2. User Enrichment (HTTP adapter)

Call an external REST API via a typed adapter, map fields from the response.

```kotlin
fun main() = execute("user-enrichment") {
    version = 1
    description = "Enriches user data from JSONPlaceholder"

    val api = spec("specs/enrichment-openapi.yaml")
    expose(api, port = 5401)

    val usersApi = adapter("jsonplaceholder", spec("specs/jsonplaceholder-openapi.yaml")) {
        baseUrl = "https://jsonplaceholder.typicode.com"
    }

    flow("enrich", enrichHandler(usersApi))
}

fun enrichHandler(usersApi: AdapterRef) = handler { req ->
    val userId = req.body["userId"]
    val user = usersApi.get("/users/$userId")

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
```

**Source:** `examples/enrichment/`

---

## 3. Products CRUD (Postgres adapter)

Full CRUD API backed by Postgres. Demonstrates the declarative step-based flow style with `call()`, `process()`, and database adapters.

```kotlin
fun main() = execute("products-api") {
    version = 1
    description = "Product catalog with Postgres"

    val api = spec("specs/products-openapi.yaml")
    expose(api, port = env("PORT", 5500))

    val db = adapter("products-db", spec("specs/products-db-spec.yaml")) {
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
```

**Source:** `examples/products/`

---

## 4. Contacts CRUD (Postgres, multiple handlers)

CRUD with separate handler files per operation. Shows how to organize larger integrations.

```kotlin
fun main() = execute("contacts-api") {
    version = 1
    description = "Contacts CRUD service"

    val api = spec("specs/contacts-openapi.yaml")
    expose(api, port = env("PORT", 5600))

    val db = adapter("contacts-db", spec("specs/contacts-db-spec.yaml")) {
        postgres {
            url = env("POSTGRES_URL", "jdbc:postgresql://localhost:5432/contacts")
            username = env("POSTGRES_USER", "postgres")
            password = secret("POSTGRES_PASSWORD", "postgres")
            table = "contacts"
        }
    }

    flow("listContacts") { call(db, HttpMethod.GET, "/contacts") }
    flow("getContact") { call(db, HttpMethod.GET, "/contacts/{id}") }
    flow("createContact") {
        statusCode = 201
        call(db, HttpMethod.POST, "/contacts")
    }
    flow("updateContact") { call(db, HttpMethod.PUT, "/contacts/{id}") }
    flow("deleteContact") { call(db, HttpMethod.DELETE, "/contacts/{id}") }
}
```

**Source:** `examples/contacts/`

---

## 5. Managed Runtime (multiple integrations)

Run several integrations under one management plane with REST APIs for deployment, lifecycle control, and health.

```kotlin
fun main() = manage(port = 9000) {
    localAgent("services") {
        deploy(EchoFactory())
        deploy(EnrichmentFactory())
    }
}
```

Each factory implements `IntegrationFactory` and produces an `IntegrationPackage`. The management plane provides:

- `GET /mgmt/integrations` — list deployed integrations
- `POST /mgmt/integrations/{name}/start|stop` — lifecycle control
- `GET /mgmt/health` — health checks
- `POST /mgmt/artifacts` — deploy from JAR

**Source:** `examples/managed/`

---

## 6. Error Handling

Flows support retry with exponential backoff and Resilience4j circuit breakers via `onError`:

```kotlin
flow("enrich") {
    onError {
        retry {
            maxAttempts = 3
            delayMs = 500
            backoffMultiplier = 2.0
        }
        circuitBreaker {
            failureRateThreshold = 50f
            waitDurationInOpenStateMs = 30_000
        }
    }
    handle(enrichHandler(usersApi))
}
```

---

## 7. Triggered Flows (timers, cron)

Non-API flows that run on a schedule:

```kotlin
triggered("sync-data") {
    every(5.minutes)
    call(externalApi, HttpMethod.GET, "/data")
    process("transform") { data -> /* transform */ data }
    log("Data synced")
}

triggered("nightly-cleanup") {
    cron("0 0 2 * * ?")  // 2am daily
    call(db, HttpMethod.DELETE, "/records/expired")
    log("Cleanup complete")
}
```

---

## DSL Reference

| Construct | Description |
|-----------|-------------|
| `execute("name") { }` | Standalone entry point (single JVM, Camel Main) |
| `spec("path")` | Load OpenAPI spec, returns `SpecRef` |
| `expose(spec, port = N)` | Expose API on HTTP port |
| `adapter("name", spec) { }` | Define typed adapter (HTTP, Postgres, in-memory) |
| `flow("op", handler)` | Wire handler to spec operation |
| `flow("op") { }` | Declarative flow with steps |
| `triggered("name") { }` | Timer/cron triggered flow |
| `handler { req -> }` | Define a handler lambda |
| `respond(source) { }` | Build response with field mapping |
| `call(adapter, method, path)` | Call adapter in declarative flow |
| `process("name") { body -> }` | Transform data in declarative flow |
| `log("message")` | Log in declarative flow |
| `env("KEY", default)` | Read environment variable |
| `secret("KEY", default)` | Read secret (hidden from logs/serialization) |
| `now()` | Current ISO-8601 timestamp |
| `slugify("text")` | URL-safe slug |
| `map.nested("a.b.c")` | Dot-notation nested field access |
| `map.string("key")` | Typed map accessor (also `int`, `long`, `double`, `bool`) |
