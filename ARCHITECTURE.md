# Integration DSL — Architecture

## How the Kotlin DSL Works

Kotlin type-safe builders use lambdas with receivers to create clean, declarative syntax that compiles to regular Kotlin code. There's no parser, no transpiler — the DSL **is** the code.

### Builder Pattern

```kotlin
// This clean DSL syntax...
integration("my-integration") {
    val api = adapter("github") {
        rest("https://api.github.com")
        auth { bearer(secret("token")) }
    }

    flow("fetch") {
        every(5.minutes)
        call(api.get("/repos"))
    }
}

// ...is just Kotlin lambdas calling builder methods:
IntegrationBuilder("my-integration").apply {
    val api = AdapterBuilder("github").apply {
        rest("https://api.github.com")
        AuthBuilder().apply { bearer(secret("token")) }
    }.build()

    FlowBuilder("fetch").apply {
        every(Duration.ofMinutes(5))
        call(api.get("/repos"))
    }.build()
}.build()
```

The `build()` methods produce an `IntegrationModel` — a data structure that describes the entire integration. This model is then passed to the Camel route generator.

### Model Layer

```
DSL (Kotlin)  →  Model (data classes)  →  Camel Routes
                                       →  UI (JSON serialization)
                                       →  Tests (mock execution)
```

```kotlin
// Core model data classes
data class Integration(
    val name: String,
    val version: Int,
    val adapters: List<Adapter>,
    val flows: List<Flow>,
    val expose: ExposeConfig?,
    val state: StateConfig?,
    val secrets: SecretsConfig?,
    val errorConfig: ErrorConfig?
)

data class Adapter(
    val name: String,
    val transport: Transport,        // REST, GraphQL, JDBC, AMQP, etc.
    val auth: AuthConfig?,
    val schema: Schema?,             // parsed OpenAPI / GraphQL schema
    val config: Map<String, Any>
)

sealed class Transport {
    data class Rest(val baseUrl: String, val headers: Map<String, String>) : Transport()
    data class GraphQL(val endpoint: String) : Transport()
    data class Jdbc(val url: String) : Transport()
    data class Amqp(val url: String, val queue: String?) : Transport()
    data class Kafka(val brokers: String, val topic: String) : Transport()
    // ...
}

data class Flow(
    val name: String,
    val description: String?,
    val trigger: Trigger,
    val steps: List<Step>,
    val errorConfig: ErrorConfig?
)

sealed class Trigger {
    data class Schedule(val interval: Duration) : Trigger()
    data class Cron(val expression: String) : Trigger()
    data class Webhook(val adapter: String, val events: List<String>) : Trigger()
    data class After(val flowName: String) : Trigger()
    object Manual : Trigger()
    object ApiCall : Trigger()
}

sealed class Step {
    data class Call(val adapter: String, val method: HttpMethod, val path: String, val config: CallConfig) : Step()
    data class GraphQLCall(val adapter: String, val operation: String, val variables: Map<String, Any>) : Step()
    data class Transform(val mappings: List<FieldMapping>) : Step()
    data class Filter(val predicate: Predicate) : Step()
    data class Branch(val conditions: List<ConditionalBranch>, val otherwise: List<Step>?) : Step()
    data class ForEach(val source: FieldRef, val steps: List<Step>) : Step()
    data class Download(val source: CallConfig) : Step()
    data class Upload(val target: CallConfig) : Step()
    data class StateOp(val operation: StateOperation) : Step()
    data class Log(val message: String, val level: LogLevel) : Step()
}

data class FieldMapping(
    val source: FieldRef,
    val target: FieldRef,
    val transform: FieldTransform?    // format, mapped, join, split, etc.
)
```

## Camel Route Generation

The model is converted to Camel `RouteBuilder` instances at startup. Each `Flow` becomes one or more Camel routes.

```kotlin
class FlowRouteGenerator(
    private val camelContext: CamelContext,
    private val adapters: Map<String, AdapterRuntime>,
    private val stateStore: StateStore
) {
    fun generate(flow: Flow): RouteBuilder = object : RouteBuilder() {
        override fun configure() {
            // Trigger → Camel "from"
            val route = from(triggerToUri(flow.trigger))
                .routeId(flow.name)
                .description(flow.description)

            // Steps → Camel processors/EIP
            for (step in flow.steps) {
                when (step) {
                    is Step.Call -> route.process(callProcessor(step))
                                       .to(adapterUri(step))
                    is Step.Transform -> route.process(transformProcessor(step))
                    is Step.Filter -> route.filter(filterPredicate(step))
                    is Step.Branch -> {
                        val choice = route.choice()
                        for (cond in step.conditions) {
                            choice.`when`(conditionPredicate(cond))
                                  .apply { cond.steps.forEach { /* recursive */ } }
                        }
                        step.otherwise?.let { choice.otherwise().apply { /* ... */ } }
                    }
                    is Step.ForEach -> route.split(body()).apply { /* ... */ }
                    is Step.Log -> route.log(step.level.toCamel(), step.message)
                    // ...
                }
            }

            // Error handling
            flow.errorConfig?.let { cfg ->
                onException(Exception::class.java)
                    .maximumRedeliveries(cfg.maxRetries)
                    .redeliveryDelay(cfg.backoff.toMillis())
                    .apply {
                        cfg.deadLetter?.let { deadLetterChannel(it) }
                        cfg.circuitBreaker?.let {
                            circuitBreaker()
                                .resilience4jConfiguration()
                                .failureRateThreshold(it.threshold)
                                .waitDurationInOpenState(it.resetAfter.toMillis())
                        }
                    }
            }
        }
    }

    private fun triggerToUri(trigger: Trigger): String = when (trigger) {
        is Trigger.Schedule -> "timer:${flow.name}?period=${trigger.interval.toMillis()}"
        is Trigger.Cron     -> "cron:${flow.name}?schedule=${trigger.expression}"
        is Trigger.Webhook  -> "undertow:http://0.0.0.0:${webhookPort}${webhookPath(trigger)}"
        is Trigger.Manual   -> "direct:${flow.name}"
        is Trigger.ApiCall  -> "direct:${flow.name}"
        is Trigger.After    -> "direct:after-${trigger.flowName}"
    }
}
```

## Expose → Camel REST DSL

The `expose` block compiles to Camel's REST DSL:

```kotlin
class ExposeRouteGenerator(private val config: ExposeConfig) {
    fun generate(): RouteBuilder = object : RouteBuilder() {
        override fun configure() {
            restConfiguration()
                .component("undertow")
                .host("0.0.0.0")
                .port(config.port)
                .apiContextPath(config.docs?.path ?: "/openapi")
                .apiProperty("api.title", config.docs?.title)
                .apiProperty("api.version", config.docs?.version)
                .enableCORS(config.cors != null)

            for (endpoint in config.endpoints) {
                val restDef = when (endpoint.method) {
                    GET    -> rest(config.basePath).get(endpoint.path)
                    POST   -> rest(config.basePath).post(endpoint.path)
                    PUT    -> rest(config.basePath).put(endpoint.path)
                    PATCH  -> rest(config.basePath).patch(endpoint.path)
                    DELETE -> rest(config.basePath).delete(endpoint.path)
                }

                restDef
                    .produces("application/json")
                    .to("direct:expose-${endpoint.method}-${endpoint.path}")

                from("direct:expose-${endpoint.method}-${endpoint.path}")
                    .routeId("expose-${endpoint.path}")
                    .apply {
                        // Auth check
                        config.auth?.let { process(authProcessor(it)) }
                        // Endpoint steps
                        for (step in endpoint.steps) { /* same as flow steps */ }
                        // Response
                        setHeader("CamelHttpResponseCode", constant(endpoint.responseCode))
                    }
            }
        }
    }
}
```

## Spec Engine — The Type System

The spec engine is the core innovation. It turns API specs into typed Kotlin modules at build time, so field references are checked before anything runs.

### Spec Loading

```kotlin
// SpecLoader — turns spec files into typed modules
class SpecLoader {
    fun loadOpenApi(path: String): SpecModule {
        val spec = OpenAPIV3Parser().read(path)
        val module = SpecModule(name = spec.info.title, type = SpecType.REST)

        // Parse endpoints → typed operations
        for ((path, pathItem) in spec.paths) {
            for ((method, operation) in pathItem.readOperationsMap()) {
                module.addOperation(Operation(
                    id = operation.operationId,
                    method = method,
                    path = path,
                    parameters = operation.parameters.map { toTypedParam(it) },
                    requestBody = operation.requestBody?.let { toTypedSchema(it) },
                    responses = operation.responses.mapValues { toTypedSchema(it.value) }
                ))
            }
        }

        // Parse schemas → typed field references
        for ((name, schema) in spec.components.schemas) {
            module.addSchema(TypedSchema(
                name = name,
                fields = schema.properties.map { (fieldName, fieldSchema) ->
                    TypedField(
                        name = fieldName,
                        type = toSchemaType(fieldSchema),   // String, Int, Boolean, Enum, Array, Object
                        required = fieldName in (schema.required ?: emptyList()),
                        enumValues = fieldSchema.enum?.map { it.toString() },
                        description = fieldSchema.description
                    )
                }
            ))
        }
        return module
    }

    fun loadGraphQL(path: String): SpecModule {
        val sdl = File(path).readText()
        val schema = SchemaParser().parse(sdl)
        val module = SpecModule(name = path, type = SpecType.GRAPHQL)

        // Parse query/mutation types → typed operations
        for (field in schema.queryType.fieldDefinitions) {
            module.addOperation(Operation(
                id = field.name,
                type = OperationType.QUERY,
                returnType = toTypedSchema(field.type),
                arguments = field.arguments.map { toTypedParam(it) }
            ))
        }
        // ... mutations similarly
        return module
    }

    fun introspectGraphQL(endpoint: String, auth: String): SpecModule {
        val introspectionQuery = """{ __schema { types { name kind fields { name type { name kind ofType { name kind } } args { name type { name kind } } } } } }"""
        val result = httpClient.post(endpoint) {
            header("Authorization", "Bearer $auth")
            body(json { "query" to introspectionQuery })
        }
        return parseIntrospection(result)
    }
}
```

### Typed Field References

When you write `source.project_nr` in a transform, this is a `TypedFieldRef` that carries its type information:

```kotlin
// Generated from spec at build time
class ProjectMatchesFields(private val spec: SpecModule) {
    val id: TypedFieldRef = TypedFieldRef("id", SchemaType.Int, required = true)
    val project_nr: TypedFieldRef = TypedFieldRef("project_nr", SchemaType.String, required = true)
    val address: AddressFields = AddressFields(spec)  // nested object
    val current_project_match_status: StatusFields = StatusFields(spec)

    inner class AddressFields(spec: SpecModule) {
        val street: TypedFieldRef = TypedFieldRef("address.street", SchemaType.String)
        val city: TypedFieldRef = TypedFieldRef("address.city", SchemaType.String)
        val zipcode: TypedFieldRef = TypedFieldRef("address.zipcode", SchemaType.String)

        // Helper: format multiple fields into one string
        fun format(pattern: String, vararg fields: TypedFieldRef): FormattedFieldRef =
            FormattedFieldRef(pattern, fields.toList(), SchemaType.String)
    }
}
```

### Build-Time Validation

```kotlin
class SpecValidator {
    fun validate(integration: Integration): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        for (flow in integration.flows) {
            for (step in flow.steps) {
                when (step) {
                    is Step.TypedTransform -> {
                        // Check every field mapping against source and target specs
                        for (mapping in step.mappings) {
                            // Source field exists?
                            if (!mapping.sourceSpec.hasField(mapping.source.path)) {
                                errors += fieldNotFound(flow, mapping.source, mapping.sourceSpec)
                            }
                            // Target field exists?
                            if (!mapping.targetSpec.hasField(mapping.target.path)) {
                                errors += fieldNotFound(flow, mapping.target, mapping.targetSpec)
                            }
                            // Types compatible?
                            if (!mapping.source.type.isAssignableTo(mapping.target.type) && mapping.transform == null) {
                                errors += typeMismatch(flow, mapping)
                            }
                            // Enum value valid?
                            if (mapping.target.type is SchemaType.Enum && mapping.transform is ValueMapping) {
                                for (value in (mapping.transform as ValueMapping).values.values) {
                                    if (value !in mapping.target.enumValues!!) {
                                        errors += invalidEnumValue(flow, mapping, value)
                                    }
                                }
                            }
                        }
                    }
                    is Step.Call -> {
                        // Check operation exists in adapter's spec
                        if (!step.adapter.spec.hasOperation(step.operationId)) {
                            errors += operationNotFound(flow, step)
                        }
                    }
                }
            }
        }

        // Validate exposed API implementations
        integration.expose?.let { expose ->
            for (operation in expose.spec.operations) {
                if (!expose.implementations.containsKey(operation.id)) {
                    errors += unimplementedOperation(operation)
                }
            }
        }

        return errors
    }
}
```

Run in CI:
- `./gradlew specValidate` — validates all field references, type compatibility, enum values, required fields
- `./gradlew specDiff` — compares current specs against previous version, reports breaking changes
- `./gradlew mockServer` — starts mock servers from specs for integration testing

## Testing Framework

### Mock Adapter

```kotlin
class MockAdapter(name: String) : AdapterRuntime {
    private val expectations = mutableListOf<Expectation>()
    private val calls = mutableListOf<RecordedCall>()

    fun on(method: HttpMethod, path: String, response: MockResponse) {
        expectations += Expectation(method, path, response)
    }

    override fun execute(call: Call): Response {
        calls += RecordedCall(call)
        val match = expectations.find { it.matches(call) }
            ?: throw AssertionError("Unexpected call: ${call.method} ${call.path}")
        return match.response.toResponse()
    }

    fun assertCalled(method: HttpMethod, path: String): CallAssertion {
        val matching = calls.filter { it.method == method && it.path.matches(path) }
        if (matching.isEmpty()) throw AssertionError("Expected call to $method $path but none recorded")
        return CallAssertion(matching.last())
    }

    fun assertNotCalled(method: HttpMethod, path: String) {
        val matching = calls.filter { it.method == method && it.path.matches(path) }
        if (matching.isNotEmpty()) throw AssertionError("Expected no call to $method $path but found ${matching.size}")
    }
}
```

### Test Environment

```kotlin
class TestEnvironment(
    private val integration: Integration,
    private val mocks: Map<String, MockAdapter>,
    private val stateStore: InMemoryStateStore = InMemoryStateStore()
) {
    fun run(flowName: String): FlowResult {
        val flow = integration.flows.find { it.name == flowName }!!
        val executor = FlowExecutor(mocks, stateStore)
        return executor.execute(flow)
    }

    fun trigger(flowName: String, payload: Any): FlowResult {
        val flow = integration.flows.find { it.name == flowName }!!
        val executor = FlowExecutor(mocks, stateStore)
        return executor.execute(flow, payload)
    }

    fun api(method: HttpMethod, path: String, body: Any? = null): ApiResponse {
        val endpoint = integration.expose!!.endpoints.find { it.method == method && it.path == path }!!
        val executor = EndpointExecutor(mocks, stateStore)
        return executor.execute(endpoint, body)
    }
}

// Usage in tests
fun testEnvironment(block: TestEnvironmentBuilder.() -> Unit): TestEnvironment {
    return TestEnvironmentBuilder().apply(block).build()
}
```

## UI ↔ DSL Roundtrip

The UI needs to read Kotlin DSL files and write them back. Two approaches:

### Approach A: Model serialization (recommended)

The runtime exposes an API that returns the `IntegrationModel` as JSON. The UI reads/edits the model. On save, the runtime serializes the model back to Kotlin DSL.

```
  Kotlin DSL file  →  IntegrationBuilder  →  IntegrationModel  →  JSON API  →  UI
                                                                                 ↓ (edit)
  Kotlin DSL file  ←  KotlinDslWriter     ←  IntegrationModel  ←  JSON API  ←  UI
```

```kotlin
class KotlinDslWriter {
    fun write(integration: Integration): String {
        return buildString {
            appendLine("integration(\"${integration.name}\") {")
            appendLine("    version(${integration.version})")
            appendLine()

            for (adapter in integration.adapters) {
                writeAdapter(adapter)
            }

            for (flow in integration.flows) {
                writeFlow(flow)
            }

            integration.expose?.let { writeExpose(it) }

            appendLine("}")
        }
    }
}
```

### Approach B: AST manipulation

Use Kotlin compiler's PSI (Program Structure Interface) to parse the DSL file into an AST, modify it, and write it back. Preserves formatting, comments, and custom code. More complex but lossless.

**Recommendation:** Start with Approach A (model serialization). It covers 95% of use cases. Only invest in AST manipulation if users need to mix custom Kotlin code with DSL blocks.

## Runtime Model

The Integration DSL has two runtime modes — **standalone** (one integration, one JVM) and **managed** (many integrations, orchestrated). Both produce the same Camel routes from the same DSL; the difference is lifecycle ownership.

### Standalone Runtime (`execute {}`)

Single integration, single JVM. Uses Camel Main (blocking — owns the process lifecycle). The simplest way to run an integration.

```
JVM Process
  └── Camel Main (blocking)
        └── CamelContext
              ├── restConfiguration(port=5400)
              ├── Route: direct:echo → echoHandler
              ├── Route: direct:health → healthHandler
              └── ProducerTemplate → adapter backends
```

Entry point:
```kotlin
fun main() = execute("echo-service") {
    val api = spec("specs/echo-openapi.yaml")
    expose(api, port = 5400)
    flow("echo", echoHandler)
}
```

### Managed Runtime (Management Plane)

Multiple integrations, each with its own `DefaultCamelContext` (non-blocking). Orchestrated by a management plane that provides REST APIs for deployment, lifecycle control, health, and events.

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
│ Local    │  │ Remote Agent     │  (separate JVM)
│ Agent    │  │ port 8081        │
│ (in-JVM) │  │ AgentApi         │
│          │  │                  │
│ CamelCtx │  │ CamelCtx A      │
│ A, B, C  │  │ CamelCtx B      │
└──────────┘  └──────────────────┘
```

Each integration gets its own `DefaultCamelContext`. This avoids `restConfiguration()` port conflicts — each context owns its HTTP port. Management and agent APIs use Undertow directly (not Camel REST DSL).

The **agent** is the grouping unit. One agent = one JVM/container running N integrations. Related integrations share a process:

```kotlin
fun main() = manage(port = 9000) {
    localAgent("order-services") {
        deploy(OrderFactory())          // port 5400
        deploy(InventoryFactory())      // port 5401
        deploy(NotificationFactory())   // port 5402
    }
    remoteAgent("payment-services", "http://payments:8081")
}
```

### Deployment Modes

How an integration gets into a runtime:

| Mode | Packaging | Deploy Path | Use Case |
|------|-----------|-------------|----------|
| **Inline DSL** | None (code) | `deployInline {}` | Development, prototyping |
| **Factory** | Class on classpath | `deploy(MyFactory())` | Multi-integration apps |
| **JAR artifact** | JAR with `META-INF/integration.properties` | REST API upload | Production, CI/CD |

All three produce the same `IntegrationPackage(integration, adapterRefs)`. The runtime doesn't care how it was created.

**JAR artifact flow:**
1. JAR uploaded via `POST /mgmt/artifacts` (or sent to remote agent)
2. `ArtifactStore` saves JAR, reads `META-INF/integration.properties`
3. `JarLoader` extracts JAR to temp dir (for spec file resolution), creates `URLClassLoader`, loads factory class
4. Factory creates `IntegrationPackage` from `DeploymentContext(baseDir=extractedDir)`
5. Package handed to `IntegrationContextRuntime` which creates CamelContext and starts routes

### Integration Lifecycle

```
  PENDING → DEPLOYING → DEPLOYED → STARTING → RUNNING
                                        ↓         ↓
                                      FAILED   STOPPING → STOPPED
```

Each state transition is tracked in the deployment registry and emitted as an event.

### Per-Integration Infrastructure

Every integration automatically gets (via `ExposeRouteGenerator`):
- **Trace ID**: MDC-based, propagated via `X-Trace-Id` header
- **Structured logging**: Request in, response out, errors — all tagged by integration + operation
- **Metrics**: Micrometer counters/timers per operation (`integration.calls.total`, `integration.call.duration`)
- **Error handling**: Retry with backoff + Resilience4j circuit breaker (configurable per flow)
- **Debug API**: Optional `/debug` endpoints for traces, breakpoints, pause/resume (when `INTEGRATION_DEBUG=true`)

### Future: Container + Native Runtimes

The `AgentConnection` interface is the extension point for additional runtime targets:

```
Runtime modes (how an integration runs):
  JVM JAR .............. Hot-deploy JARs, URLClassLoader, DefaultCamelContext  [NOW]
  GraalVM native ....... Compile to native binary, fast cold start            [PLANNED]
  Docker container ..... Wrap JAR or native binary in container image          [PLANNED]

Agent types (where it runs):
  Local (in-JVM) ....... Same process as management plane                     [NOW]
  Remote (HTTP) ........ Separate JVM, HTTP-based agent API                   [NOW]
  Docker ............... Agent connection that manages containers via Docker API [PLANNED]
  Fleet ................ Runner/backplane managing Docker hosts                [PLANNED]
```

The production deployment model:
```
Backplane (fleet orchestrator, cluster-wide)
  └── Runner (per-host daemon, manages local Docker)
        └── Container (one agent = N related integrations)
              └── Runtime (JAR or native binary)
```

The management plane is the dev/small-scale path. The runner/backplane is the production path. Same `IntegrationFactory`, same packaging — different deployment targets.

## Build & Dependencies

### Gradle (build.gradle.kts)

```kotlin
plugins {
    kotlin("jvm") version "2.1.0"
    id("org.springframework.boot") version "3.4.0"  // or Quarkus
}

dependencies {
    // Camel
    implementation("org.apache.camel.springboot:camel-spring-boot-starter:4.9.0")
    implementation("org.apache.camel:camel-undertow:4.9.0")
    implementation("org.apache.camel:camel-graphql:4.9.0")
    implementation("org.apache.camel:camel-jdbc:4.9.0")
    implementation("org.apache.camel:camel-kafka:4.9.0")
    implementation("org.apache.camel:camel-micrometer:4.9.0")
    implementation("org.apache.camel:camel-resilience4j:4.9.0")

    // Schema loading
    implementation("io.swagger.parser.v3:swagger-parser:2.1.25")   // OpenAPI
    implementation("com.graphql-java:graphql-java:22.3")           // GraphQL introspection

    // State store
    implementation("org.xerial:sqlite-jdbc:3.47.0.0")
    implementation("org.postgresql:postgresql:42.7.4")

    // Secrets
    implementation("io.github.jopenlibs:vault-java-driver:6.2.0")  // HashiCorp Vault

    // Testing
    testImplementation("org.apache.camel:camel-test-spring-junit5:4.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation(kotlin("test"))
}
```

## Implementation Phases

### Phase 1: Spec Engine + Core DSL (weekend)
- `SpecLoader` — parse OpenAPI 3.x JSON/YAML → `SpecModule` with typed fields
- `SpecModule`, `TypedFieldRef`, `SchemaType` — the type system
- `IntegrationBuilder`, `FlowBuilder`, `AdapterBuilder` (REST only, requires spec)
- `TransformBuilder` with typed field mapping (`from = Spec.X, to = Spec.Y`)
- `SpecValidator` — validate all field refs against specs
- Camel route generation (timer trigger, HTTP calls)
- `TestEnvironment` with `MockAdapter` (auto-generated from spec)
- Example: JSONPlaceholder hello-world (with OpenAPI spec)
- Gradle task: `specValidate`

### Phase 2: GraphQL + State + Webhooks
- GraphQL spec loading (SDL file + live introspection)
- GraphQL adapter (queries, mutations)
- Webhook trigger (inbound, HMAC verification)
- `StateStore` (SQLite) for ID mappings
- Example: GitHub ↔ Linear sync

### Phase 3: Expose (Spec-First) + Error Handling
- `ExposeBuilder` — requires spec, generates Camel REST DSL
- `implement(gateway.operationId)` — wire spec endpoints to flows
- Auto-serve OpenAPI spec + Swagger UI
- Request/response validation against spec
- Error handling (retry, circuit breaker, dead letter)
- Metrics (Micrometer/Prometheus)
- Gradle tasks: `mockServer`, `specDiff`
- Example: Stripe → Accounting with exposed API

### Phase 4: UI
- Model → JSON API endpoint (runtime serves integration model)
- Vue/Svelte SPA with flow canvas (use @xyflow/svelte or vue-flow)
- **Spec browser** — browse imported specs, inspect types, see field docs
- **Visual field mapper** — source spec fields on left, target spec fields on right, draw connections
- DSL writer (model → Kotlin)
- Live execution trace

### Phase 5: Production Hardening
- Multi-tenant (namespace per customer)
- Hot reload (file watcher → re-validate specs → regenerate routes)
- Spec version pinning + breaking change detection
- Auth on UI and API
- Logging, alerting
- Docker image, Helm chart
