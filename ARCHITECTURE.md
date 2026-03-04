# Kamelot — Architecture

## How the Kotlin DSL Works

Kotlin type-safe builders use lambdas with receivers to create clean, declarative syntax that compiles to regular Kotlin code. There's no parser, no transpiler — the DSL **is** the code.

### Builder Pattern

```kotlin
// This clean DSL syntax...
integration("echo-service") {
    version = 1
    val api = spec("specs/echo-openapi.yaml")
    expose(api, port = 5400)

    flow("echo", handler { req ->
        respond(req.body) {
            "message" to "message"
            "timestamp" set now()
        }
    })
}

// ...is just Kotlin lambdas calling builder methods:
IntegrationBuilder("echo-service").apply {
    version = 1
    val api = spec("specs/echo-openapi.yaml")
    expose(api, port = 5400)

    flow("echo", handler { req ->
        respond(req.body) {
            "message" to "message"
            "timestamp" set now()
        }
    })
}.build()
```

The `build()` method produces an `Integration` model — a data structure that describes the entire integration. This model is then passed to the Camel route generators.

### Model Layer

```
DSL (Kotlin)  →  Model (data classes)  →  Camel Routes
                                       →  UI (JSON serialization)
                                       →  Tests (mock execution)
```

```kotlin
data class Integration(
    val name: String,
    val version: Int = 1,
    val description: String? = null,
    val specs: List<Spec> = emptyList(),
    val adapters: List<Adapter> = emptyList(),
    val flows: List<Flow> = emptyList(),
    val expose: ExposeConfig? = null,
    val implementations: List<Implementation> = emptyList(),
)

data class Spec(
    val name: String,
    val path: String,
    val type: SpecType,
    val operations: Map<String, Operation> = emptyMap(),
    val schemas: Map<String, SchemaObject> = emptyMap(),
)

data class Adapter(
    val name: String,
    val spec: Spec? = null,
    val baseUrl: String = "",
    val auth: AuthConfig? = null,
)

data class Flow(
    val name: String,
    val description: String? = null,
    val trigger: Trigger,
    val steps: List<Step> = emptyList(),
    val errorConfig: ErrorConfig? = null,
)

sealed class Trigger {
    data class Schedule(val intervalMs: Long) : Trigger()
    data class Cron(val expression: String) : Trigger()
    data class After(val flowName: String) : Trigger()
    data object Manual : Trigger()
    data object ApiCall : Trigger()
}

sealed class Step {
    data class Call(val adapterName: String, val method: HttpMethod, val path: String, val config: CallConfig) : Step()
    data class Process(val name: String, val handler: (Map<String, Any?>) -> Any?) : Step()
    data class Filter(val expression: String) : Step()
    data class Log(val message: String, val level: LogLevel) : Step()
    data class Respond(val statusCode: Int, val block: ResponseBuilder.() -> Unit) : Step()
    data class MapFields(val block: ResponseBuilder.() -> Unit) : Step()
}

data class Implementation(
    val operationId: String,
    val steps: List<Step> = emptyList(),
    val handler: ((RequestContext) -> ResponseContext)? = null,
    val errorConfig: ErrorConfig? = null,
)
```

## Camel Route Generation

### ExposeRouteGenerator

Generates Camel REST DSL routes from the exposed API spec. Each `Implementation` (wired via `flow()`) becomes a REST endpoint backed by a `direct:` route.

Every route automatically gets:
- Trace ID (MDC + `X-Trace-Id` header)
- Structured logging (request in, response out, errors)
- Micrometer metrics (`/metrics` endpoint, Prometheus format)
- Error handling (Camel `onException` with retry + Resilience4j circuit breaker)
- Debug API (`/debug/*` endpoints when `INTEGRATION_DEBUG=true`)

```kotlin
class ExposeRouteGenerator(
    private val integration: Integration,
    private val debugManager: DebugManager? = null,
) {
    fun generate(): RouteBuilder = object : RouteBuilder() {
        override fun configure() {
            val expose = integration.expose ?: return

            restConfiguration()
                .component("undertow")
                .host(expose.host)
                .port(expose.port)

            // OpenAPI spec endpoint
            rest().get("/openapi.json").to("direct:openapi-spec")

            // Metrics endpoint (Prometheus)
            rest().get("/metrics").to("direct:metrics")

            // One route per implementation
            for (impl in integration.implementations) {
                val operation = expose.spec.operations[impl.operationId] ?: continue

                // REST DSL -> direct route
                when (operation.method) {
                    HttpMethod.GET -> rest().get(operation.path)
                    HttpMethod.POST -> rest().post(operation.path)
                    // ...
                }.to("direct:${impl.operationId}")

                // Implementation route with error handling + circuit breaker
                configureImplementationRoute(impl, operation)
            }
        }
    }
}
```

### FlowRouteGenerator

Generates Camel routes from triggered (non-API) flows — timers, cron, after-chains, manual triggers.

```kotlin
class FlowRouteGenerator(
    private val integration: Integration,
    private val adapterRefs: List<AdapterRef> = emptyList(),
) {
    fun generate(): RouteBuilder = object : RouteBuilder() {
        override fun configure() {
            for (flow in integration.flows) {
                if (flow.trigger is Trigger.ApiCall) continue

                val fromUri = when (val t = flow.trigger) {
                    is Trigger.Schedule -> "timer:${flow.name}?period=${t.intervalMs}"
                    is Trigger.Cron     -> "quartz:${flow.name}?cron=${t.expression}"
                    is Trigger.After    -> "direct:after-${t.flowName}"
                    is Trigger.Manual   -> "direct:manual-${flow.name}"
                    else -> continue
                }

                val route = from(fromUri).routeId("flow-${flow.name}")

                // Steps become Camel processors
                for (step in flow.steps) {
                    when (step) {
                        is Step.Call -> route.process { /* delegate to adapter backend */ }
                        is Step.Process -> route.process { /* transform with lambda */ }
                        is Step.Log -> route.log(step.level, step.message)
                        is Step.Filter -> route.filter(simple(step.expression))
                        // ...
                    }
                }
            }
        }
    }
}
```

## Spec Engine

The spec engine parses OpenAPI specs into typed `Spec` models. Every adapter and exposed API requires a spec. Operations and schemas are extracted and used to validate flow wiring at DSL build time.

```kotlin
object OpenApiSchemaLoader {
    fun load(path: String): Spec {
        val result = OpenAPIV3Parser().read(path)
        return Spec(
            name = result.info?.title ?: path,
            path = path,
            type = SpecType.OPENAPI,
            operations = parseOperations(result),  // operationId → Operation
            schemas = parseSchemas(result),         // name → SchemaObject
        )
    }
}
```

Validation happens at DSL build time in `IntegrationBuilder`:
- `flow("operationId", handler)` — validates that `operationId` exists in the exposed spec
- Each operation can only be wired once
- `expose()` must be called before `flow()`

## Adapter Backends

Adapters have swappable backends. The `AdapterRef` provides a uniform interface (`get`, `post`, `put`, `delete`) regardless of backend:

```kotlin
class AdapterRef(val adapter: Adapter, internal val backend: AdapterBackend) {
    fun get(path: String, queryParams: Map<String, String> = emptyMap()): Map<String, Any?>
    fun post(path: String, body: Any? = null): Map<String, Any?>
    fun put(path: String, body: Any? = null): Map<String, Any?>
    fun delete(path: String): Map<String, Any?>
}
```

Available backends:
- **CamelHttpBackend** — HTTP calls via Camel `ProducerTemplate` (default)
- **PostgresBackend** — SQL via HikariCP connection pool
- **InMemoryBackend** — in-memory storage for testing/prototyping

```kotlin
val usersApi = adapter("jsonplaceholder", spec("specs/jp.yaml")) {
    baseUrl = "https://jsonplaceholder.typicode.com"   // → CamelHttpBackend
}

val db = adapter("products-db", spec("specs/db.yaml")) {
    postgres {                                          // → PostgresBackend
        url = env("POSTGRES_URL", "jdbc:postgresql://localhost:5432/products")
        username = env("POSTGRES_USER", "postgres")
        password = secret("POSTGRES_PASSWORD", "postgres")
        table = "products"
    }
}

val mockDb = adapter("test-db", spec("specs/db.yaml")) {
    inMemory()                                          // → InMemoryBackend
}
```

## Runtime Model

Two runtime modes — **standalone** and **managed**. Both produce the same Camel routes; the difference is lifecycle ownership.

### Standalone Runtime (`execute {}`)

Single integration, single JVM. Uses Camel Main (blocking).

```
JVM Process
  └── Camel Main (blocking)
        └── CamelContext
              ├── restConfiguration(port=5400)
              ├── Route: direct:echo → handler
              ├── Route: direct:health → handler
              ├── Route: timer:heartbeat (30s)
              └── ProducerTemplate → adapter backends
```

```kotlin
fun main() = execute("echo-service") {
    val api = spec("specs/echo-openapi.yaml")
    expose(api, port = 5400)
    flow("echo", echoHandler)
    flow("health", healthHandler)
    triggered("heartbeat") {
        every(30.seconds)
        log("echo-service heartbeat — alive")
    }
}
```

### Managed Runtime (Management Plane)

Multiple integrations, each with its own `DefaultCamelContext` (non-blocking). Orchestrated by a management plane with REST APIs.

```
┌─────────────────────────────────────────────────┐
│  Management Plane (port 9000)                   │
│  ManagementPlane + ManagementApi (Undertow)     │
│                                                 │
│  ArtifactStore  │  DeploymentRegistry           │
│  AgentRegistry  │  EventLog                     │
└──────────┬──────────────────────────────────────┘
           │ AgentConnection (interface)
   ┌───────┴───────┐
   ▼               ▼
┌──────────┐  ┌──────────────────┐
│ Local    │  │ Remote Agent     │
│ Agent    │  │ (separate JVM)   │
│ (in-JVM) │  │ AgentApi         │
│          │  │                  │
│ CamelCtx │  │ CamelCtx A      │
│ A, B, C  │  │ CamelCtx B      │
└──────────┘  └──────────────────┘
```

```kotlin
fun main() = manage(port = 9000) {
    localAgent("services") {
        deploy(EchoFactory())
        deploy(EnrichmentFactory())
    }
    remoteAgent("payment-services", "http://payments:8081")
}
```

### Deployment Modes

| Mode | Packaging | Deploy Path | Use Case |
|------|-----------|-------------|----------|
| **Inline DSL** | None (code) | `deployInline {}` | Development, prototyping |
| **Factory** | Class on classpath | `deploy(MyFactory())` | Multi-integration apps |
| **JAR artifact** | JAR with `META-INF/integration.properties` | REST API upload | Production, CI/CD |

### Integration Lifecycle

```
  PENDING → DEPLOYING → DEPLOYED → STARTING → RUNNING
                                        ↓         ↓
                                      FAILED   STOPPING → STOPPED
```

## Build & Dependencies

```kotlin
// build.gradle.kts (root)
plugins {
    kotlin("jvm") version "2.3.10" apply false
}
allprojects {
    group = "io.devtech.integration"
    version = "0.1.0"
}

// core/build.gradle.kts
val camelVersion = "4.18.0"
dependencies {
    api("org.apache.camel:camel-main:$camelVersion")
    api("org.apache.camel:camel-undertow:$camelVersion")
    api("org.apache.camel:camel-jackson:$camelVersion")
    api("org.apache.camel:camel-rest:$camelVersion")
    api("org.apache.camel:camel-micrometer:$camelVersion")
    api("org.apache.camel:camel-http:$camelVersion")
    api("org.apache.camel:camel-resilience4j:$camelVersion")
    api("org.apache.camel:camel-quartz:$camelVersion")
    api("org.apache.camel:camel-jdbc:$camelVersion")
    api("org.apache.camel:camel-sql:$camelVersion")
    api("com.zaxxer:HikariCP:6.2.1")
    implementation("io.swagger.parser.v3:swagger-parser:2.1.38")
    api("io.micrometer:micrometer-registry-prometheus:1.14.4")
}
```

Requires JDK 21+.

## Module Structure

```
kamelot/
├── core/           DSL builders, model, Camel route generation, spec engine
├── runtime/        Standalone execute() entry point (Camel Main)
├── management/     Management plane, agents, artifact deployment
├── ide/
│   ├── studio/     Desktop IDE (Kotlin/Compose)
│   ├── bff/        IDE backend-for-frontend (Bun/TypeScript)
│   └── frontend/   IDE web UI (Vue)
└── examples/
    ├── echo/       Simple echo service
    ├── enrichment/ HTTP adapter + field mapping
    ├── contacts/   Postgres CRUD
    ├── products/   Postgres CRUD with process steps
    └── managed/    Multi-integration managed runtime
```
