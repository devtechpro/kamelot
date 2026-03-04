# Kamelot

Kotlin DSL on Apache Camel for building API integrations. Spec-first, type-safe, testable — with a visual IDE, management plane, and debug tooling.

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

    flow("health", handler { _ ->
        respond("status" to "ok", "timestamp" to now())
    })

    triggered("heartbeat") {
        every(30.seconds)
        log("echo-service heartbeat — alive")
    }
}
```

Every integration starts with a spec. Every adapter has a spec. Field mappings are validated against specs. Wrong field name = build error, not runtime crash.

## HTTP Adapters

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

## Database Adapters

Postgres CRUD with declarative step-based flows:

```kotlin
fun main() = execute("products-api") {
    val api = spec("specs/products-openapi.yaml")
    expose(api, port = env("PORT", 5500))

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

Three adapter backends: `CamelHttpBackend` (default), `PostgresBackend`, and `InMemoryBackend` (testing).

## Error Handling

Retry with exponential backoff and Resilience4j circuit breakers:

```kotlin
flow("enrich") {
    onError {
        retry { maxAttempts = 3; delayMs = 500; backoffMultiplier = 2.0 }
        circuitBreaker { failureRateThreshold = 50f; waitDurationInOpenStateMs = 30_000 }
    }
    handle(enrichHandler(usersApi))
}
```

## Built-in Functions

Available in handlers and `process {}` steps:

| Category | Functions |
|----------|-----------|
| **String** | `slugify()`, `capitalize()`, `camelize()`, `dasherize()`, `truncate()`, `mask()`, `initials()` |
| **Date** | `now()`, `today()`, `epochMs()`, `formatDate()`, `parseDate()`, `dateAdd()`, `dateDiff()` |
| **Crypto** | `uuid()`, `md5()`, `sha256()`, `hmacSha256()`, `base64Encode()`, `base64Decode()` |
| **Collection** | `map.pick()`, `map.omit()`, `map.rename()`, `map.nested()`, `map.pickNonNull()` |
| **Config** | `env("KEY", default)`, `secret("KEY", default)` |

## Per-Integration Infrastructure

Every integration automatically gets:

- **Trace IDs** — MDC-based, propagated via `X-Trace-Id` header
- **Structured logging** — request in, response out, errors tagged by integration + operation
- **Prometheus metrics** — `/metrics` endpoint with call counts, durations, error rates, JVM stats
- **OpenAPI spec** — `/openapi.json` serves the spec
- **Error handling** — retry with backoff + circuit breaker (configurable per flow)
- **Debug API** — `/debug/*` endpoints for traces, breakpoints, pause/resume (when `INTEGRATION_DEBUG=true`)

## Management Plane

Run multiple integrations under one orchestrator:

```kotlin
fun main() = manage(port = 9000) {
    localAgent("services") {
        deploy(EchoFactory())
        deploy(EnrichmentFactory())
    }
    remoteAgent("payment-services", "http://payments:8081")
}
```

REST API at port 9000:
- `POST /mgmt/artifacts` — upload JAR
- `POST /mgmt/deployments` — deploy artifact or inline
- `POST /mgmt/deployments/{id}/start|stop` — lifecycle control
- `GET /mgmt/health` — aggregate health
- `GET /mgmt/events` — event log

Supports local agents (in-JVM) and remote agents (separate JVM with AgentApi).

## Visual IDE

Design integrations visually with the Studio IDE:

- **Studio Desktop** (`ide/studio`) — Kotlin/Compose app with embedded Camel runtime
- **Web Frontend** (`ide/frontend`) — Vue 3 SPA with flow canvas, spec browser, field mapping, database browser
- **BFF** (`ide/bff`) — Bun/TypeScript backend for the web UI

Features: project tree, OpenAPI spec viewer, flow editor with step palette, field mapping panel, database table browser, DSL code generation, run/stop with live logs.

## Modules

| Module | Description |
|--------|-------------|
| `core` | DSL builders, model, Camel route generation, spec engine, adapters, functions |
| `runtime` | Standalone `execute()` entry point (Camel Main) |
| `management` | Management plane, agents, artifact store, deployment lifecycle |
| `ide/studio` | Desktop IDE with embedded runtime (Kotlin/Compose) |
| `ide/bff` | IDE backend-for-frontend (Bun/TypeScript) |
| `ide/frontend` | IDE web UI (Vue 3) |
| `examples/*` | Echo, enrichment, contacts, products, managed |

## Building

```bash
./gradlew build
```

Requires JDK 21+.

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) — Internals, Camel mapping, module structure
- [EXAMPLES.md](EXAMPLES.md) — Integration examples
