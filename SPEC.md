# Integration DSL — Specification

## Problem

Integration platforms force a choice:

- **Make.com, Zapier, n8n** — clean visual UI, but no versioning, no testing, no CI/CD, no adapter pattern, US-hosted, per-execution pricing, YAML/JSON config that can't be validated at compile time.
- **Apache Camel, Spring Integration, WSO2** — proper engineering (adapters, EIP, testable), but verbose XML/YAML, dated tooling, steep learning curve, ugly visual editors.
- **Kestra, Windmill, Temporal** — modern orchestrators with nice UIs, but generic workflow tools not purpose-built for API integration. No typed adapter concept, no field-level mapping validation.
- **MuleSoft** — gets it right architecturally (connectors, DataWeave, API-led), but proprietary, expensive ($50k+/year), US-hosted, vendor lock-in.

None of them are: free, open source, type-safe, testable, visually clean, and purpose-built for API integration.

## Vision

A Kotlin DSL that wraps Apache Camel to make API integrations:

1. **Spec-first** — every API (consumed or exposed) must have a spec (OpenAPI for REST, GraphQL schema for GraphQL). No spec, no adapter. The spec is the typed interface — field names, types, required/optional, relationships are all known at compile time.
2. **Readable** — integration flows read like English. A non-developer can understand what happens.
3. **Type-safe** — field mappings are validated against specs during build. Wrong field name = compile error, not runtime crash. Autocomplete in IDE.
4. **Testable** — every flow runs against mock adapters in CI. Mocks are auto-generated from specs. Standard JUnit/Kotlin test.
5. **Visual** — a web UI renders flows as node graphs (like Make.com). The UI reads and writes the Kotlin DSL files. The DSL is always the source of truth.
6. **Exposable** — integrations can expose their own REST APIs. Define the spec first, then implement the handlers. The exposed API gets auto-generated OpenAPI docs.
7. **Self-hosted** — runs anywhere Docker runs. No SaaS dependency, no data leaves your infrastructure.
8. **Free and open source** — Apache 2.0 license.

## Design Principle: Spec-First

Inspired by MuleSoft's API-led connectivity, but enforced at the language level.

**Rule: No API without a spec. No field reference without a schema.**

Every external system is represented by a spec file. The spec is imported into the integration and becomes a typed module — like an interface in a programming language. You can only reference fields that exist in the spec. The compiler checks it.

This means:
- Import an OpenAPI spec → get a typed REST client with all endpoints, request/response models
- Import a GraphQL schema → get typed queries/mutations with all fields
- Define an OpenAPI spec for your exposed API → get a typed server with validation
- Map between two specs → the compiler verifies both sides

```
specs/                              # The "type system" of your integrations
  benetics-openapi.json             # Benetics REST API — OpenAPI 3.1
  hero-graphql.graphql              # Hero Software — GraphQL SDL
  my-gateway-openapi.yaml           # Your exposed API — OpenAPI 3.0
```

### How specs become modules

```kotlin
// Import specs — they become typed modules
val BeneticsApi = spec("specs/benetics-openapi.json")     // OpenAPI → typed REST module
val HeroApi     = spec("specs/hero-graphql.graphql")       // GraphQL → typed GraphQL module
val GatewayApi  = spec("specs/my-gateway-openapi.yaml")    // OpenAPI → typed server module

// Now use them: autocomplete works, wrong fields fail at compile time
BeneticsApi.projects.name          // ✓ exists in spec
BeneticsApi.projects.foobar        // ✗ compile error: field 'foobar' not in Project schema
HeroApi.project_matches.address    // ✓ exists in schema
HeroApi.project_matches.zip        // ✗ compile error: field 'zip' not in project_matches
```

### Spec as adapter

An adapter is a spec + connection config. The spec defines *what* you can do, the adapter defines *how* to connect.

```kotlin
val benetics = adapter(BeneticsApi) {
    baseUrl("https://public-api.benetics.io/v1")
    auth { bearer(secret("benetics-api-key")) }
}

val hero = adapter(HeroApi) {
    endpoint("https://login.hero-software.de/api/external/v7/graphql")
    auth { bearer(secret("hero-api-key")) }
}
```

### Spec for exposed APIs

To expose an API, you define or import a spec first. The framework generates the server skeleton. You implement the handlers by wiring them to flows.

```kotlin
val gateway = expose(GatewayApi) {
    port(8080)
    auth { apiKey(header = "X-API-Key") }
}
```

The exposed API automatically:
- Validates request bodies against the spec schema
- Returns proper error responses for missing/wrong fields
- Serves the OpenAPI spec at `/openapi.json`
- Generates Swagger UI at `/docs`

### Mapping between specs

The transform block maps fields between two specs. Both sides are typed.

```kotlin
// Both sides are validated against their respective specs
transform(from = HeroApi.project_matches, to = BeneticsApi.projects) {
    source.project_nr                      to target.name
    source.address.street                  to target.address   // type mismatch! address is a string in Benetics but object in Hero
    source.address.format("%s, %s %s",                         // explicit format resolves the type mismatch
        source.address.street,
        source.address.zipcode,
        source.address.city)               to target.address   // ✓ string → string
    source.current_project_match_status    to target.state     // type mismatch: string vs enum
        mapped {                                                // explicit mapping resolves it
            "Abgeschlossen" to "archived"
            default         to "active"
        }
}
```

### Mock generation from specs

Since every adapter has a spec, mocks are auto-generated:

```kotlin
@Test
fun `project sync works`() {
    val env = testEnvironment {
        // Auto-generates mock from spec with realistic fake data
        mock(benetics) { fromSpec(BeneticsApi) }
        mock(hero)     { fromSpec(HeroApi) }

        // Or override specific responses
        mock(hero) {
            fromSpec(HeroApi)
            override(HeroApi.project_matches) {
                returns(listOf(
                    HeroApi.project_matches.example(
                        project_nr = "P-2026-001",
                        address = address(street = "Hauptstr. 1", city = "Berlin", zipcode = "10115")
                    )
                ))
            }
        }
    }

    val result = env.run(flow("sync-projects"))
    result.assertSuccess()
}
```

## Core Concepts

### Spec

A spec is a typed API definition imported from a file. It's the foundation of everything.

```kotlin
// REST APIs — import OpenAPI specs
val BeneticsApi = spec("specs/benetics-openapi.json")
val StripeApi   = spec("specs/stripe-openapi.json")
val GatewayApi  = spec("specs/gateway-openapi.yaml")

// GraphQL APIs — import GraphQL SDL or introspection result
val HeroApi     = spec("specs/hero-graphql.graphql")
val LinearApi   = spec("specs/linear-graphql.graphql")

// Fetch spec at build time (for APIs that serve their spec)
val GithubApi   = spec(fetch = "https://raw.githubusercontent.com/github/rest-api-description/main/descriptions/api.github.com/api.github.com.json")
val HeroApi     = spec(introspect = "https://login.hero-software.de/api/external/v7/graphql", auth = secret("hero-api-key"))
```

Specs can be:
- **Local files** (`specs/` directory, committed to Git)
- **Fetched at build time** from a URL (pinned to a version hash)
- **Introspected at build time** from a live GraphQL endpoint
- **Generated** from your exposed API definition

What a spec gives you:
- Typed field references with autocomplete
- Request/response model validation
- Mock generation for tests
- API documentation (auto-served for exposed APIs)
- Change detection (spec diff between versions)

### Adapter

An adapter is a spec bound to a connection. Spec = interface, adapter = implementation.

```kotlin
// Client adapters (consume external APIs)
val benetics = adapter(BeneticsApi) {
    baseUrl("https://public-api.benetics.io/v1")
    auth { bearer(secret("benetics-api-key")) }
    webhooks { verify { hmac(header = "X-Signature", secret("benetics-webhook-secret")) } }
    rateLimit(100.perMinute)
    timeout(30.seconds)
    retry { maxAttempts(3); backoff(exponential, initial = 1.seconds) }
}

// GraphQL client adapter
val hero = adapter(HeroApi) {
    endpoint("https://login.hero-software.de/api/external/v7/graphql")
    auth { bearer(secret("hero-api-key")) }
}

// Server adapter (expose your own API)
val gateway = expose(GatewayApi) {
    port(8080)
    auth { apiKey(header = "X-API-Key") }
    cors { allowOrigin("*") }
    docs { path("/docs") }
}
```

Adapters are reusable across flows and integrations. An adapter library can be shared across projects.

### Flow

A flow is a named, triggered pipeline that moves data between adapters. It has:
- A **trigger** (schedule, webhook, event, API call, or manual)
- One or more **steps** (call, transform, filter, branch, aggregate)
- Error handling (retry, dead letter, alert)

```kotlin
flow("sync-projects") {
    every(5.minutes)
    // ... steps
}

flow("on-report-created") {
    on(benetics.webhook("export.created"))
    // ... steps
}

flow("manual-resync") {
    manual()  // triggered via UI or API
    // ... steps
}
```

### Transform (Spec-Typed)

A transform maps fields between two specs. Both sides are validated at compile time. You declare `from` and `to` — the compiler knows every field on both sides.

```kotlin
// Typed transform — both sides reference spec schemas
transform(from = HeroApi.project_matches, to = BeneticsApi.CreateProject) {
    source.project_nr       to target.name           // ✓ string → string
    source.address.format("%s, %s %s",
        source.address.street,
        source.address.zipcode,
        source.address.city)  to target.address       // ✓ formatted → string
}

// Value mapping for enums / status fields
transform(from = HeroApi.project_matches, to = BeneticsApi.UpdateProject) {
    source.current_project_match_status.name to target.state mapped {
        "Abgeschlossen" to "archived"       // ✓ target.state is enum: active|archived
        "Storniert"     to "archived"
        default         to "active"
    }
}

// Complex: table fields (reports)
transform(from = BeneticsApi.ReportSubmission, to = HeroApi.add_logbook_entry) {
    source.values["Auftraggeber"]  to target.custom_title
    source.values["Arbeitsstunden"].each { row ->
        row["Mitarbeiter"]  to target.custom_text format { "${it} — " }
        row["Stunden"]      to target.custom_text append { "${it}h" }
    }
}

// Constants and functions
transform(from = HeroApi.contacts, to = BeneticsApi.AddMember) {
    source.id                  to target.user_id     // ✓ string → string
    constant("member")         to target.role        // ✓ role is enum: admin|member|super_admin
    constant("super_admin")    to target.role        // ✓ also valid
    constant("moderator")      to target.role        // ✗ compile error: 'moderator' not in Role enum
}
```

**What the compiler catches:**
- `source.foobar` → error: field 'foobar' not in HeroApi.project_matches
- `target.zipcode` → error: field 'zipcode' not in BeneticsApi.CreateProject
- `constant("invalid")` to enum field → error: 'invalid' not in allowed values
- String field mapped to integer field → error: type mismatch (unless explicit conversion)

### Expose (Spec-First)

To expose an API, you define an OpenAPI spec first. The framework validates all request/response handling against it. No endpoint without a spec.

```yaml
# specs/gateway-openapi.yaml — define your API contract first
openapi: 3.0.3
info:
  title: Benetics-Hero Integration Gateway
  version: "1.0"
paths:
  /projects:
    get:
      operationId: listProjects
      responses:
        200:
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/Project'
  /projects/{id}/sync:
    post:
      operationId: syncProject
      parameters:
        - name: id
          in: path
          required: true
          schema: { type: string }
      responses:
        202:
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SyncResponse'
  /webhooks/benetics:
    post:
      operationId: beneticsWebhook
      responses:
        200: {}
components:
  schemas:
    Project:
      type: object
      properties:
        hero_id: { type: integer }
        benetics_id: { type: string }
        name: { type: string }
        address: { type: string }
        state: { type: string, enum: [active, archived] }
    SyncResponse:
      type: object
      properties:
        status: { type: string }
```

Then wire it up — the DSL references spec types, not raw strings:

```kotlin
val GatewayApi = spec("specs/gateway-openapi.yaml")

val gateway = expose(GatewayApi) {
    port(8080)
    auth { apiKey(header = "X-API-Key") }
    docs { path("/docs") }    // serves Swagger UI + OpenAPI spec
}

// Implement each operationId from the spec
implement(gateway.listProjects) {
    call(hero.query("project_matches"))
    transform(from = HeroApi.project_matches, to = GatewayApi.Project) {
        source.id          to target.hero_id
        source.project_nr  to target.name
        // target.state, target.address etc — all validated against GatewayApi.Project schema
    }
    // Response automatically validated against spec: must return List<Project>
}

implement(gateway.syncProject) {
    // request.id is typed from the spec's path parameter
    trigger(flow("sync-single-project"), params = mapOf("id" to request.id))
    // Response must match SyncResponse schema
    respond { status = "accepted" }
}

implement(gateway.beneticsWebhook) {
    verify { hmac(header = "X-Signature", secret("benetics-webhook-secret")) }
    route { event ->
        when (event["event"]) {
            "project.created"            -> trigger(flow("project-from-benetics"))
            "export.created"             -> trigger(flow("report-to-hero"))
            "task.created", "task.updated" -> trigger(flow("task-to-hero"))
        }
    }
}
```

What you get for free from the spec:
- **Request validation** — missing required fields, wrong types → automatic 400 error
- **Response validation** — your handler output is checked against the spec schema (in dev/test mode)
- **OpenAPI served** — `/openapi.json` returns the spec, `/docs` renders Swagger UI
- **Client SDK generation** — consumers can generate clients from your spec (standard OpenAPI tooling)
- **Mock server** — `./gradlew mockServer` starts a mock of your exposed API from the spec alone, before you write any handler code

### State

Integrations need to track sync state — ID mappings, last poll timestamps, deduplication. The DSL provides a built-in state store.

```kotlin
state {
    store(postgres)  // or sqlite, or in-memory for testing

    mapping("projects") {
        key(hero["project_matches.id"], benetics["projects.id"])
        track("last_synced", "sync_hash")
    }

    mapping("members") {
        key(hero["contacts.phone_home"], benetics["members.phone_number"])
    }
}
```

Used in flows:

```kotlin
flow("sync-projects") {
    every(5.minutes) poll hero.query("project_matches")

    onNewOrChanged(state.mapping("projects")) {
        transform { /* ... */ }
        upsert(benetics.projects)
        state.update("projects", hero["id"] to benetics["id"])
    }
}
```

### Secrets

Secrets are never in the DSL files. They're resolved at runtime from environment variables, a vault, or a secrets manager.

```kotlin
secrets {
    env()                    // reads from environment variables
    vault("hashicorp") {     // or HashiCorp Vault
        address("https://vault.internal:8200")
        path("integration/")
    }
    infisical {              // or Infisical
        address("https://infisical.internal")
        project("benetics-integration")
    }
}
```

`secret("hero-api-key")` resolves to `HERO_API_KEY` env var, or `integration/hero-api-key` in Vault, depending on config. In tests, secrets are mocked.

## DSL Syntax — Full Reference

### Integration (top level)

```kotlin
integration("name") {
    version(1)
    description("Human-readable description")

    secrets { /* secret provider config */ }
    state { /* state store config */ }

    val adapterA = adapter("name") { /* adapter config */ }
    val adapterB = adapter("name") { /* adapter config */ }

    flow("name") { /* flow definition */ }
    flow("name") { /* flow definition */ }

    expose { /* exposed API endpoints */ }
}
```

### Adapter Types

```kotlin
// REST API
adapter("name") {
    rest("https://api.example.com/v1")
    auth { bearer(secret("token")) }
    auth { apiKey(header = "X-API-Key", secret("key")) }
    auth { apiKey(param = "api_key", secret("key")) }
    auth { basic(secret("user"), secret("pass")) }
    auth { oauth2 { clientCredentials(secret("id"), secret("secret"), tokenUrl = "...") } }
    schema { openapi("/path/to/spec.json") }
    schema { openapi(url = "https://api.example.com/openapi.json") }
    headers { "Accept" to "application/json" }
    rateLimit(100.perMinute)
    timeout(30.seconds)
    retry { maxAttempts(3); backoff(exponential, initial = 1.seconds) }
}

// GraphQL API
adapter("name") {
    graphql("https://api.example.com/graphql")
    auth { bearer(secret("token")) }
    schema { introspect() }
    schema { file("/path/to/schema.graphql") }
}

// Database
adapter("name") {
    jdbc("postgresql://host:5432/db")
    jdbc("sqlite:///path/to/db.sqlite")
    auth { credentials(secret("user"), secret("pass")) }
}

// Message Queue
adapter("name") {
    amqp("amqp://host:5672")
    kafka("kafka://host:9092") { topic("events") }
    nats("nats://host:4222") { subject("integration.>") }
}

// File / Object Storage
adapter("name") {
    s3 { bucket("my-bucket"); region("eu-central-1") }
    sftp("sftp://host:22/path")
    local("/var/data/imports")
}

// SMTP / Email
adapter("name") {
    smtp("smtp://host:587") { starttls() }
    auth { credentials(secret("user"), secret("pass")) }
}

// Webhooks (inbound)
adapter("name") {
    webhooks {
        path("/hooks/example")
        verify { hmac(header = "X-Signature", secret("webhook-secret")) }
        verify { token(header = "Authorization", secret("webhook-token")) }
    }
}
```

### Triggers

```kotlin
flow("name") {
    // Time-based
    every(5.minutes)
    every(1.hours)
    cron("0 6 * * MON-FRI")

    // Webhook / event
    on(adapter.webhook("event.name"))
    on(adapter.webhook("event.a", "event.b", "event.c"))

    // Message queue
    on(adapter.consume("queue-name"))
    on(adapter.subscribe("topic"))

    // Exposed API call (linked from expose block)
    onApiCall()

    // Manual (UI or API trigger)
    manual()

    // Chained (triggered by another flow)
    after(flow("other-flow"))
}
```

### Steps

```kotlin
flow("name") {
    every(5.minutes)

    // Call an adapter
    call(adapter.get("/endpoint") {
        query("param" to "value")
        header("X-Custom" to "value")
    })

    call(adapter.post("/endpoint") {
        body(mapped)      // use transformed data
        body { json { "key" to "value" } }
    })

    call(adapter.query("graphql_query_name") {
        variable("id" to source["id"])
    })

    call(adapter.mutate("mutation_name") {
        input(mapped)
    })

    // Transform
    transform {
        source["field"]      to target["field"]
        source["a"] + source["b"]  to target["combined"]
        source["nested.deep.field"] to target["flat"]
        source["list"].each { item ->
            item["name"] to target["names"].append()
        }
    }

    // Filter
    filter { source["status"] == "active" }
    filter { source["amount"] gt 0 }

    // Branch
    branch {
        on({ source["type"] == "report" })  then { trigger(flow("handle-report")) }
        on({ source["type"] == "task" })    then { trigger(flow("handle-task")) }
        otherwise { log("Unknown type: ${source["type"]}") }
    }

    // Download / Upload files
    download(adapter.file("/path")) into tempFile()
    upload(tempFile()) to adapter.file("/destination")

    // Aggregate (batch)
    aggregate(100.items or 5.minutes) { batch ->
        call(adapter.post("/bulk") { body(batch) })
    }

    // State operations
    state.lookup("mapping-name", key = source["id"])
    state.update("mapping-name", source["id"] to target["id"])
    state.markSynced("mapping-name", source["id"])

    // Logging
    log("Processing ${source["id"]}")
    log(level = WARN, "Unexpected status: ${source["status"]}")

    // Error handling (per-step or per-flow)
    onError {
        retry { maxAttempts(3); backoff(exponential, initial = 1.seconds) }
        deadLetter(adapter.post("/errors") { body(error) })
        alert { slack(channel = "#alerts", message = "Flow failed: ${error.message}") }
    }
}
```

### Expose — API Gateway

```kotlin
expose {
    rest {
        port(8080)
        basePath("/api/v1")
        cors { allowOrigin("*") }
        auth {
            apiKey(header = "X-API-Key")
            // or: bearer(), basic(), oauth2()
        }
        docs {
            openapi(title = "My Integration API", version = "1.0")
            path("/docs")
        }
    }

    // Read endpoint — proxy to an adapter with transformation
    endpoint(GET, "/projects") {
        call(hero.query("project_matches"))
        transform { /* ... */ }
        respond(200)
    }

    endpoint(GET, "/projects/{id}") {
        param("id") to source["project_id"]
        call(hero.query("project_matches") { variable("ids" to listOf(source["project_id"])) })
        transform { /* ... */ }
        respond(200)
    }

    // Write endpoint — accept data, transform, forward
    endpoint(POST, "/projects") {
        validate {
            required("name")
            optional("address")
        }
        transform { /* request body → adapter format */ }
        call(hero.mutate("create_project_match") { input(mapped) })
        respond(201) { body(mapped) }
    }

    // Trigger endpoint — kick off a flow
    endpoint(POST, "/sync") {
        trigger(flow("full-sync"))
        respond(202) { body { json { "status" to "accepted" } } }
    }

    // Webhook receiver — route incoming webhooks
    endpoint(POST, "/webhooks/{source}") {
        param("source") to source["adapter_name"]
        verify { hmac(header = "X-Signature", secret("${source["adapter_name"]}-webhook-secret")) }
        route { event ->
            when (event["event"]) {
                matches("project.*")  -> trigger(flow("project-sync"))
                matches("export.*")   -> trigger(flow("export-handler"))
                matches("task.*")     -> trigger(flow("task-sync"))
            }
        }
        respond(200)
    }

    // Health / status
    endpoint(GET, "/health") {
        respond(200) {
            body {
                json {
                    "status" to "ok"
                    "adapters" to adapters.map { it.name to it.health() }
                    "flows" to flows.map { it.name to it.lastRun() }
                }
            }
        }
    }

    // Metrics (Prometheus-compatible)
    metrics {
        path("/metrics")
        include(flowDuration, flowErrors, adapterLatency, webhookDelivery)
    }
}
```

### Error Handling

```kotlin
// Global (integration level)
integration("name") {
    onError {
        log(level = ERROR)
        retry { maxAttempts(3); backoff(exponential, initial = 2.seconds, max = 60.seconds) }
        deadLetter(errorAdapter.post("/dead-letter") { body(error) })
        alert { email(to = "ops@devtech.pro", subject = "Integration failure") }
    }
}

// Per-flow (overrides global)
flow("critical-sync") {
    onError {
        retry { maxAttempts(5); backoff(linear, initial = 5.seconds) }
        circuit { breakAfter(10.failures); resetAfter(5.minutes) }
        fallback { call(backupAdapter.post("/fallback")) }
    }
}

// Per-step
call(adapter.post("/endpoint")) {
    onError { retry { maxAttempts(2) } }
}
```

### Testing

```kotlin
@Test
fun `project sync creates benetics project from hero`() {
    val env = testEnvironment {
        mock(hero) {
            on(query("project_matches")) respond {
                json("""[{
                    "id": 1,
                    "project_nr": "P-2026-001",
                    "address": {"street": "Hauptstr. 1", "city": "Berlin", "zipcode": "10115"},
                    "current_project_match_status": {"name": "Offen"}
                }]""")
            }
        }

        mock(benetics) {
            on(post("/projects")) respond { status(201); json("""{"id": "abc123"}""") }
        }
    }

    val result = env.run(flow("sync-projects"))

    result.assertSuccess()
    result.assertCalled(benetics.post("/projects")) {
        body["name"] eq "P-2026-001"
        body["address"] eq "Hauptstr. 1, 10115 Berlin"
        body["state"] eq "active"
    }
    result.assertState("projects") {
        mapping(1, "abc123").exists()
    }
}

@Test
fun `webhook triggers report export`() {
    val env = testEnvironment {
        mock(benetics) {
            on(get("/projects/p1/exports/e1/content")) respond {
                file("test-report.pdf")
            }
        }
        mock(hero) {
            on(post("/documents")) respond { status(201) }
        }
    }

    val result = env.trigger(flow("report-to-hero"), webhookPayload {
        json("""{
            "event": "export.created",
            "payload": {
                "project": {"id": "p1"},
                "export": {"id": "e1", "kind": "reports", "state": {"status": "completed"}}
            }
        }""")
    })

    result.assertSuccess()
    result.assertCalled(hero.post("/documents")) {
        hasFile("test-report.pdf")
        body["folder"] eq "Benetics Rapporte"
    }
}

@Test
fun `exposed API returns transformed projects`() {
    val env = testEnvironment {
        mock(hero) {
            on(query("project_matches")) respond {
                json("""[{"id": 1, "project_nr": "P-001", "customer": {"first_name": "Hans", "last_name": "Muller"}}]""")
            }
        }
    }

    val response = env.api(GET, "/api/v1/projects")

    response.assertStatus(200)
    response.assertBody {
        jsonPath("[0].number") eq "P-001"
        jsonPath("[0].customer.first_name") eq "Hans"
    }
}
```

## Camel Mapping

The DSL compiles to standard Apache Camel constructs. No runtime overhead — the DSL is syntactic sugar that produces Camel routes at startup.

| DSL Concept | Camel Equivalent |
|---|---|
| `adapter` (REST) | Camel HTTP/Undertow component + configuration |
| `adapter` (GraphQL) | Camel GraphQL component |
| `adapter` (JDBC) | Camel JDBC/SQL component |
| `adapter` (AMQP/Kafka/NATS) | Respective Camel components |
| `flow` | `RouteBuilder` |
| `every(5.minutes)` | `from("timer:name?period=300000")` |
| `on(webhook)` | `from("undertow:http://0.0.0.0:port/path")` |
| `call(adapter.get(...))` | `.to("http://host/path?httpMethod=GET")` |
| `transform` | `.process { exchange -> ... }` or `.transform().body(...)` |
| `filter` | `.filter().simple(...)` |
| `branch` | `.choice().when(...).to(...).otherwise().to(...)` |
| `onError.retry` | `.onException(...).maximumRedeliveries(n).redeliveryDelay(...)` |
| `onError.deadLetter` | `.deadLetterChannel("...")` |
| `onError.circuit` | Camel Circuit Breaker (Resilience4j) |
| `expose.endpoint` | `from("undertow:...")` with REST DSL |
| `aggregate` | `.aggregate(...)` with completion predicates |
| `state` | Camel idempotent repository / custom processor with JDBC |

### Startup Sequence

1. Load integration DSL (Kotlin file)
2. Resolve secrets from configured provider
3. Load adapter schemas (OpenAPI parse, GraphQL introspect)
4. Validate all transforms against schemas (compile-time check)
5. Generate Camel `RouteBuilder` instances from flows
6. Generate Camel REST DSL from `expose` block
7. Start Camel context with generated routes
8. Health endpoint becomes available

### Runtime

- Camel context manages all routes, threading, error handling
- State store is accessed via Camel processors (inside transforms/steps)
- Webhook endpoints are Undertow HTTP servers (Camel component)
- Exposed API is Camel REST DSL with Undertow
- Metrics exported via Camel Micrometer component

## Module Structure

```
integration-dsl/
  core/                          # DSL builder classes + spec engine
    src/main/kotlin/
      io/devtech/integration/
        spec/                    # Spec-first: the type system
          SpecLoader.kt          # Load specs from files, URLs, introspection
          OpenApiSpec.kt         # OpenAPI → typed module (endpoints, schemas, fields)
          GraphQLSpec.kt         # GraphQL SDL → typed module (queries, mutations, types)
          SpecModule.kt          # A loaded spec: typed field refs, endpoint refs
          FieldRef.kt            # Type-safe field reference (knows name, type, required, enum values)
          SchemaType.kt          # String, Int, Boolean, Enum, Array, Object, etc.
          SpecValidator.kt       # Validates transforms against specs at compile/build time
          SpecDiff.kt            # Detect breaking changes between spec versions
        dsl/
          IntegrationBuilder.kt  # integration("name") { }
          AdapterBuilder.kt      # adapter(Spec) { } — spec is required
          FlowBuilder.kt         # flow("name") { }
          TransformBuilder.kt    # transform(from = Spec.X, to = Spec.Y) { } — typed both sides
          ExposeBuilder.kt       # expose(Spec) { } — spec is required
          ImplementBuilder.kt    # implement(gateway.operationId) { } — wire spec endpoints to flows
          StateBuilder.kt        # state { }
          SecretsBuilder.kt      # secrets { }
          ErrorBuilder.kt        # onError { }
        adapter/
          RestAdapter.kt         # REST client — operations derived from OpenAPI spec
          GraphQLAdapter.kt      # GraphQL client — operations derived from schema
          JdbcAdapter.kt         # Database adapter
          MessageAdapter.kt      # AMQP/Kafka/NATS
          FileAdapter.kt         # S3/SFTP/local
        camel/
          RouteGenerator.kt      # DSL model → Camel RouteBuilder
          RestGenerator.kt       # expose {} → Camel REST DSL (from spec)
          ProcessorFactory.kt    # transform/filter/branch → Camel processors
        state/
          StateStore.kt          # Interface
          JdbcStateStore.kt      # PostgreSQL/SQLite implementation
          InMemoryStateStore.kt  # For testing
        secrets/
          SecretProvider.kt      # Interface
          EnvSecretProvider.kt   # Environment variables
          VaultSecretProvider.kt # HashiCorp Vault
        test/
          TestEnvironment.kt     # testEnvironment { mock(...) }
          MockAdapter.kt         # Mock adapter — auto-generated from spec
          SpecMockGenerator.kt   # Generates realistic mock data from spec schemas
          Assertions.kt          # assertCalled, assertState, etc.
    src/test/kotlin/
      io/devtech/integration/
        spec/
          OpenApiSpecTest.kt     # Parse real specs, verify typed fields
          GraphQLSpecTest.kt
          SpecValidatorTest.kt   # Verify compile-time field checking
        dsl/
          IntegrationBuilderTest.kt
          TransformBuilderTest.kt
        camel/
          RouteGeneratorTest.kt

  runtime/                       # Executable runtime
    src/main/kotlin/
      io/devtech/integration/
        Application.kt           # Spring Boot / Quarkus entry point
        IntegrationLoader.kt     # Discovers .kt files + specs/, loads and validates
        HealthEndpoint.kt        # /health
        MetricsEndpoint.kt       # /metrics
        MockServerCommand.kt     # ./gradlew mockServer — serves mock API from spec

  ui/                            # Visual editor (web app)
    src/
      components/
        FlowCanvas.vue           # Node graph editor (like Make.com)
        AdapterPanel.vue         # Shows spec: endpoints, schemas, fields
        TransformEditor.vue      # Visual field mapper: source spec ↔ target spec
        SpecBrowser.vue          # Browse imported specs, inspect types
        FlowList.vue             # List of flows
        RunHistory.vue           # Execution logs
      api/
        integration.ts           # Read/write DSL files via API
      parser/
        dsl-parser.ts            # Parse Kotlin DSL → UI model
        dsl-writer.ts            # UI model → Kotlin DSL

  examples/                      # Example integrations
    hello-world/
      integration.kt             # JSONPlaceholder example
      specs/
        jsonplaceholder.json     # OpenAPI spec
    weather-slack/
      integration.kt
      specs/
        openweather.json
    benetics-hero/
      integration.kt
      specs/
        benetics-openapi.json    # Benetics REST API — already have this
        hero-graphql.graphql     # Hero GraphQL schema — from introspection
        gateway-openapi.yaml     # Our exposed API
    github-linear/
      integration.kt
      specs/
        github-openapi.json
        linear-graphql.graphql

  gradle/
  build.gradle.kts
  settings.gradle.kts
```

## UI Architecture

The web UI is a separate SPA that communicates with the runtime via API. It provides a Make.com-style visual editor but the source of truth is always the Kotlin DSL file.

### How it works

1. **Load**: UI calls runtime API → gets list of integrations → parses DSL files into a UI model (nodes, connections, configs)
2. **Display**: Renders flows as node graphs. Each adapter is a node, each step is a node, connections show data flow.
3. **Edit**: User drags/connects nodes, configures transforms in a visual field mapper, sets triggers. Every edit updates the in-memory UI model.
4. **Save**: UI model is serialized back to Kotlin DSL → written to file → committed to Git (optional auto-commit).
5. **Deploy**: Runtime hot-reloads the changed DSL file → regenerates Camel routes → zero-downtime deploy.

### UI Components

- **Flow Canvas** — drag-and-drop node graph. Nodes: adapter (colored by type), trigger, transform, filter, branch. Connections: data flow arrows.
- **Adapter Panel** — configure adapter connection (URL, auth, schema). Shows schema fields for autocomplete in transforms.
- **Transform Editor** — visual field mapper. Left: source fields (from schema). Right: target fields. Draw connections between fields. Add transformations (format, map, join, split) inline.
- **Run / Test** — execute a flow with mock data or live. Shows step-by-step execution trace with data at each stage.
- **History** — execution log with status, duration, errors. Click to inspect individual runs.

### Key principle

The UI never stores its own state. Everything is derived from the DSL files. Two developers can edit the same integration — one in IntelliJ, one in the UI — and Git merges work because the DSL is plain text Kotlin.

## Configuration & Deployment

### Runtime Modes

The Integration DSL supports two runtime modes:

**Standalone** — one integration, one JVM. Uses `execute {}`:
```kotlin
fun main() = execute("echo-service") {
    val api = spec("specs/echo-openapi.yaml")
    expose(api, port = 5400)
    flow("echo", echoHandler)
}
```
Run with `./gradlew :examples:echo:run`. Uses Camel Main (blocking). The simplest deployment model.

**Managed** — multiple integrations, orchestrated by a management plane. Uses `manage {}`:
```kotlin
fun main() = manage(port = 9000) {
    localAgent("order-services")
    deploy(OrderFactory())
    deploy(InventoryFactory())
    deploy(NotificationFactory())
    remoteAgent("payments", "http://payments:8081")
}
```
Run with `./gradlew :examples:managed:run`. Each integration gets its own `DefaultCamelContext`. Management API on port 9000.

### Deployment Modes

All three modes produce the same `IntegrationPackage(integration, adapterRefs)`:

| Mode | Packaging | Deploy Path | Use Case |
|------|-----------|-------------|----------|
| **Inline DSL** | None (code) | `deployInline("name") { }` | Development, prototyping |
| **Factory class** | Class on classpath | `deploy(MyFactory())` | Multi-integration apps |
| **JAR artifact** | JAR with `META-INF/integration.properties` | REST API upload | Production, CI/CD |

### Integration Factory

Packaged integrations implement `IntegrationFactory`:

```kotlin
class EchoFactory : IntegrationFactory {
    override val name = "echo-service"
    override fun create(ctx: DeploymentContext) = IntegrationPackage(
        IntegrationBuilder(name).apply {
            val api = spec(ctx.specPath("specs/echo-openapi.yaml"))
            expose(api, port = ctx.property("PORT", 5400))
            flow("echo", echoHandler)
        }.let { IntegrationPackage(it.build(), it.adapterRefs) }
    )
}
```

JARs must include `META-INF/integration.properties`:
```properties
integration.name=echo-service
integration.factory=io.devtech.integration.echo.EchoFactory
```

### Management Plane

The management plane provides REST APIs for artifact upload, deployment, lifecycle control, health, and event audit:

```
POST   /mgmt/artifacts              upload JAR
GET    /mgmt/artifacts              list artifacts
GET    /mgmt/agents                 list agents
POST   /mgmt/deployments            deploy to agent
GET    /mgmt/deployments            list deployments
POST   /mgmt/deployments/{id}/start
POST   /mgmt/deployments/{id}/stop
DELETE /mgmt/deployments/{id}       undeploy
GET    /mgmt/health                 aggregate health
GET    /mgmt/events                 audit log
```

### Agent Model

The **agent** is the grouping unit. One agent = one JVM/container running N integrations. Related integrations share a process for efficiency.

Agent types:
- **Local** — in-JVM, same process as management plane (development)
- **Remote** — separate JVM, communicates via HTTP agent API on port 8081 (production)
- **Docker** — manages containers via Docker API (planned)
- **Fleet** — runner/backplane managing multiple Docker hosts (planned)

### Runtime Config

Integrations read configuration via `DeploymentContext.property()` which checks: deployment properties → environment variables → default value. No YAML config files needed.

```kotlin
val port = ctx.property("PORT", 5400)        // int
val dbUrl = ctx.property("DB_URL", "")       // string
```

For standalone mode, use `env()`:
```kotlin
expose(api, port = env("PORT", "5400").toInt())
```

### Docker

```dockerfile
# Standalone: single integration
FROM eclipse-temurin:21-jre-alpine
COPY build/libs/echo-service.jar /app/
COPY specs/ /app/specs/
WORKDIR /app
EXPOSE 5400
CMD ["java", "-jar", "echo-service.jar"]
```

```dockerfile
# Managed: management plane with multiple integrations
FROM eclipse-temurin:21-jre-alpine
COPY build/libs/managed-app.jar /app/
COPY specs/ /app/specs/
WORKDIR /app
EXPOSE 9000 5400 5401 5402
CMD ["java", "-jar", "managed-app.jar"]
```

```yaml
# docker-compose.yml — production with remote agents
services:
  management:
    build: { context: ., dockerfile: Dockerfile.management }
    ports:
      - "9000:9000"
    environment:
      HERO_API_KEY: ${HERO_API_KEY}

  worker-orders:
    build: { context: ., dockerfile: Dockerfile.agent }
    ports:
      - "5400-5402:5400-5402"
      - "8081:8081"
    environment:
      AGENT_ID: worker-orders
      AGENT_PORT: 8081

  worker-payments:
    build: { context: ., dockerfile: Dockerfile.agent }
    ports:
      - "5500-5502:5500-5502"
      - "8082:8081"
    environment:
      AGENT_ID: worker-payments
      AGENT_PORT: 8081
```

### CI/CD

```yaml
# .github/workflows/test-and-deploy.yml
name: Test & Deploy
on: [push]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: 21 }
      - run: ./gradlew test
      - run: ./gradlew :management:compileKotlin

  deploy:
    needs: test
    if: github.ref == 'refs/heads/main'
    steps:
      - run: ./gradlew shadowJar
      - run: docker build -t integration:${GITHUB_SHA} .
      - run: docker push registry.internal/integration:${GITHUB_SHA}
      # Deploy via management API
      - run: |
          curl -X POST http://management:9000/mgmt/artifacts \
            --data-binary @build/libs/echo-service.jar \
            -H 'X-Filename: echo-service.jar'
          curl -X POST http://management:9000/mgmt/deployments \
            -H 'Content-Type: application/json' \
            -d '{"artifactId":"$ID","agentId":"worker-orders","autoStart":true}'
```

### Future: Production Runtime

```
Backplane (fleet orchestrator, cluster-wide)
  └── Runner (per-host daemon, manages local Docker)
        └── Container (one agent = N related integrations)
              └── Runtime (JVM JAR or GraalVM native binary)
```

The management plane is the dev/small-scale path. The runner/backplane is the production path. Same `IntegrationFactory`, same packaging — different deployment targets. `AgentConnection` is the extension point where Docker, native, and fleet implementations plug in.

## Comparison: Why This Exists

| | Make.com | n8n | Camel (raw) | Kestra | MuleSoft | **Integration DSL** |
|---|---|---|---|---|---|---|
| Spec-first / API contract | No | No | No | No | Yes (RAML/OAS) | **Yes (OpenAPI + GraphQL enforced)** |
| Visual UI | Clean | Clean | Kaoto (XML) | Clean | Anypoint Studio | Clean (Make.com-style) |
| Config format | Proprietary | JSON | XML/YAML | YAML | XML + DataWeave | **Kotlin (type-safe)** |
| Adapter/connector pattern | No | No | Yes (components) | Yes (plugins) | Yes (connectors) | **Yes (spec = interface, adapter = impl)** |
| Compile-time field validation | No | No | No | No | Partial (DataWeave) | **Yes (full, from spec)** |
| Auto-generated mocks from spec | No | No | No | No | Partial (API Kit) | **Yes** |
| CI/CD pipeline | No | Partial | Yes | Yes | Yes | **Yes** |
| Expose own API | No | Partial | Yes (verbose) | No | Yes (API-led) | **Yes (spec-first, auto-docs)** |
| IDE support | Browser only | Browser only | IntelliJ (XML) | Browser + YAML | Anypoint Studio | **Full IntelliJ (autocomplete, refactoring)** |
| GDPR / Self-hosted | No (US) | Yes | Yes | Yes | No (US) | **Yes** |
| Cost | Per-execution | Free | Free | Free | $50k+/year | **Free (Apache 2.0)** |

## Naming

Working title: **Integration DSL** (placeholder).

Ideas:
- **Conduit** — data flows through conduits
- **Flux** — flow-based
- **Bridge** — connects systems
- **Weave** — weaves APIs together (nod to MuleSoft DataWeave)
- **Loom** — weaving integrations (Kotlin, looms/threads)
