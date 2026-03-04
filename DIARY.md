# Integration DSL — Development Diary

## Session 1 — 2026-02-23: Project Bootstrap & Echo Service

### Goal

Set up the project from scratch and get a spec-first echo service running locally with embedded Camel. Prove the core architecture works end-to-end: OpenAPI spec → typed DSL → Camel routes → HTTP server.

### Decisions Made

**JVM**: GraalVM CE 21.0.2 (linux-x64). Chose JDK 21 as the sweet spot — LTS, fully supported by both Camel 4.18 and Kotlin 2.3. JDK 25 works but has Camel component caveats. GraalVM for fast startup and future native-image option.

**Runtime**: Standalone Camel Main (no Spring Boot). Keeps it lightweight — no Spring context, fast startup, minimal dependencies. Just `./gradlew :examples:echo:run`. Docker can wrap this later trivially.

**Versions locked**:
- Kotlin 2.3.10
- Apache Camel 4.18.0 (LTS, Feb 2026)
- swagger-parser 2.1.38
- Gradle 8.12

**Port convention**: Services start from port 5400 (not 8080).

**Spec-first**: Every API must have an OpenAPI spec written before any code. The spec is the typed interface. The framework parses it and validates field references against it.

**1 flow = 1 file**: Each handler/flow lives in its own file under a `flows/` directory. The top-level integration file wires them together.

### What We Built

#### Project Structure

```
integration-dsl/
├── build.gradle.kts              # Root: shared Kotlin/JVM config
├── settings.gradle.kts           # Multi-module: core, runtime, examples:echo
├── core/                         # DSL framework (the library)
│   ├── build.gradle.kts          # Camel, swagger-parser, Jackson deps
│   └── src/main/kotlin/io/devtech/integration/
│       ├── model/Models.kt       # Data classes
│       ├── schema/OpenApiSchemaLoader.kt
│       ├── dsl/IntegrationBuilder.kt
│       ├── dsl/ExposeBuilder.kt
│       ├── dsl/ImplementBuilder.kt
│       └── camel/ExposeRouteGenerator.kt
├── runtime/                      # Camel Main entry point
│   ├── build.gradle.kts
│   └── src/main/kotlin/io/devtech/integration/
│       └── Application.kt        # IntegrationRuntime class
├── examples/echo/                # First example integration
│   ├── build.gradle.kts          # application plugin, mainClass
│   ├── specs/
│   │   └── echo-openapi.yaml     # THE SPEC (written first!)
│   └── src/main/kotlin/io/devtech/integration/echo/
│       ├── EchoIntegration.kt    # Top-level DSL wiring + main()
│       └── flows/
│           ├── EchoHandler.kt    # POST /echo
│           └── HealthHandler.kt  # GET /health
└── gradle/                       # Gradle wrapper
```

#### The OpenAPI Spec (written first)

`examples/echo/specs/echo-openapi.yaml` defines:
- `POST /echo` (operationId: `echo`) — accepts `EchoRequest{message}`, returns `EchoResponse{message, timestamp, source}`
- `GET /health` (operationId: `health`) — returns `HealthResponse{status, uptime}`

#### Core Framework

**Model layer** (`model/Models.kt`): Data classes for `Integration`, `Spec`, `Operation`, `SchemaObject`, `FieldDef`, `ExposeConfig`, `Implementation`, `RequestContext`, `ResponseContext`. The `Spec` is parsed from OpenAPI and contains typed operations and schemas.

**Schema loader** (`schema/OpenApiSchemaLoader.kt`): Uses swagger-parser to load OpenAPI YAML → produces `Spec` with operations map (keyed by operationId) and schemas map (keyed by schema name). Resolves relative file paths against working directory.

**DSL builders**:
- `IntegrationBuilder` — `integration("name") { }` top-level entry. Has `spec()`, `expose()`, `implement()`.
- `ExposeBuilder` — `expose(specRef) { port(5400) }`. Creates `ExposeConfig`.
- `ImplementBuilder` — `implement(gateway["echo"]) { handler { ... } }`. Wires a handler function to an operationId.
- `SpecRef` / `GatewayRef` / `OperationRef` — typed references for DSL access. `gateway["echo"]` returns an `OperationRef` validated against the spec.

**Camel route generator** (`camel/ExposeRouteGenerator.kt`): Takes an `Integration` model, generates Camel routes:
- `restConfiguration().component("undertow").port(N)` — configures Undertow HTTP server
- For each implemented operation: `rest().post("/echo").to("direct:echo")` + `from("direct:echo").process { handler }`
- OpenAPI spec served at `/openapi.json` via REST DSL (not a separate undertow listener — avoids dual-bind)

**Runtime** (`Application.kt`): `IntegrationRuntime` class wraps Camel Main. Prints a startup banner showing all operations and their implementation status. Calls `camelMain.run()`.

#### The Echo Integration (DSL usage)

```kotlin
val echoIntegration = integration("echo-service") {
    version(1)
    description("Simple echo service — returns what you send it")

    val echoApi = spec("examples/echo/specs/echo-openapi.yaml")
    val gateway = expose(echoApi) { port(5400) }

    implement(gateway["echo"], echoHandler)
    implement(gateway["health"], healthHandler)
}
```

Handlers are defined in separate files (`flows/EchoHandler.kt`, `flows/HealthHandler.kt`) as `ImplementBuilder.() -> Unit` lambdas.

### What Works

```bash
./gradlew :examples:echo:run
```

Startup output:
```
╔══════════════════════════════════════════════╗
║  Integration DSL — echo-service
║  Version 1
║  Simple echo service — returns what you send it
╠══════════════════════════════════════════════╣
║  Exposed API: http://0.0.0.0:5400
║  OpenAPI spec: http://0.0.0.0:5400/openapi.json
║  Operations:
║    ✓ POST /echo (echo)
║    ✓ GET /health (health)
╚══════════════════════════════════════════════╝
Apache Camel 4.18.0 (camel-1) started in 213ms
```

Endpoints:
```bash
$ curl -s -X POST http://localhost:5400/echo \
    -H "Content-Type: application/json" \
    -d '{"message": "hello world"}'
{"message":"hello world","timestamp":"2026-02-23T16:52:19.534645622Z","source":"echo-service"}

$ curl -s http://localhost:5400/health
{"status":"ok","uptime":"0h 0m 37s"}

$ curl -s http://localhost:5400/openapi.json
openapi: 3.0.3
info:
  title: Echo Service
  ...
```

### Issues Encountered & Resolved

1. **No JDK installed** — Downloaded GraalVM CE 21.0.2 tarball directly, set JAVA_HOME in bashrc.

2. **No Gradle installed** — Downloaded Gradle 8.12, used it to generate wrapper in project.

3. **`suspend` handler + coroutines** — Initially made handlers `suspend` with `kotlinx.coroutines.runBlocking`. Unnecessary complexity — Camel processors are synchronous. Removed `suspend`, handlers are plain `(RequestContext) -> ResponseContext`.

4. **Transitive dependency visibility** — Core module used `implementation` for Camel deps, so runtime module couldn't see them. Fixed by using `api` scope in core (added `java-library` plugin).

5. **OpenAPI spec file not found** — `OpenAPIV3Parser().read()` couldn't resolve relative paths. Fixed by resolving against `File(path)` first, and setting `workingDir = rootProject.projectDir` on the Gradle run task.

6. **Port dual-bind** — The OpenAPI spec route used `from("undertow:http://...")` which tried to start a second Undertow listener on the same port as the REST DSL. Fixed by serving `/openapi.json` via the REST DSL instead (`rest().get("/openapi.json").to("direct:openapi-spec")`).

7. **Port 8080 already in use** — Switched to port 5400 convention.

---

## Session 1b — 2026-02-23: Automatic Logging & Metrics

### Goal

Every flow/operation should get structured logging and Prometheus metrics for free. No per-flow code needed — define a flow, it's automatically instrumented.

### What We Added

**Dependencies changed:**
- Replaced `slf4j-simple` with `logback-classic:1.5.16` (structured logging)
- Added `camel-micrometer:4.18.0` (Camel metrics integration)
- Added `micrometer-registry-prometheus:1.14.4` (Prometheus exposition)

**New file: `core/.../camel/MetricsRegistry.kt`**

Singleton `PrometheusMeterRegistry` that auto-registers on init:
- **JVM metrics**: memory (heap/non-heap by pool), GC pauses, threads (live/daemon/states), classloader, CPU usage, uptime
- **Integration metrics** (per operation, tagged by integration name):
  - `integration_calls_total{integration, operation, status, status_class}` — counter
  - `integration_call_duration_seconds{integration, operation}` — timer (count, sum, max)
  - `integration_errors_total{integration, operation, exception}` — error counter

**Updated: `core/.../camel/ExposeRouteGenerator.kt`**

Every operation route now automatically:
1. Logs `INFO` on request received (`POST /echo — request received`)
2. Logs `DEBUG` with request body (`POST /echo — body: {message=hello}`)
3. Executes the handler
4. Logs `INFO` on response with duration (`POST /echo — 200 in 13.2ms`)
5. On error: logs `ERROR` with duration and exception, returns 500 with error JSON
6. Records metrics for every call (success or failure)

Also added `/metrics` endpoint via REST DSL — same port, no extra config.

**New file: `core/src/main/resources/logback.xml`**

Console appender with colored output. Log levels:
- `io.devtech.integration` → DEBUG
- `org.apache.camel` → INFO
- `io.undertow` / `org.xnio` / `org.jboss` → WARN (noisy)

**Updated: `runtime/.../Application.kt`**

Startup now uses SLF4J logger instead of `println`. Logs integration name, version, port, all operations, and metrics endpoint URL.

### What It Looks Like

Startup:
```
19:35:46 INFO  [main] i.echo-service — Starting integration: echo-service v1
19:35:46 INFO  [main] i.echo-service —   Simple echo service — returns what you send it
19:35:46 INFO  [main] i.echo-service — Exposed API on http://0.0.0.0:5400
19:35:46 INFO  [main] i.echo-service —   OpenAPI spec: http://0.0.0.0:5400/openapi.json
19:35:46 INFO  [main] i.echo-service —   Metrics:      http://0.0.0.0:5400/metrics
19:35:46 INFO  [main] i.echo-service —   OK POST /echo (echo)
19:35:46 INFO  [main] i.echo-service —   OK GET /health (health)
```

Per-request (automatic):
```
19:35:56 INFO  [XNIO-1 task-2] echo-service — POST /echo — request received
19:35:56 DEBUG [XNIO-1 task-2] echo-service — POST /echo — body: {message=final test}
19:35:56 INFO  [XNIO-1 task-2] echo-service — POST /echo — 200 in 13.2ms
```

Metrics (`GET /metrics`):
```prometheus
integration_calls_total{integration="echo-service",operation="echo",status="200",status_class="2xx"} 3.0
integration_calls_total{integration="echo-service",operation="health",status="200",status_class="2xx"} 2.0
integration_call_duration_seconds_count{integration="echo-service",operation="echo"} 3
integration_call_duration_seconds_sum{integration="echo-service",operation="echo"} 0.0128
jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 2.9360128E7
jvm_threads_live_threads 13.0
process_cpu_usage 3.18E-4
process_uptime_seconds 102.7
system_cpu_count 4.0
```

### Issues Encountered & Resolved

8. **SLF4J duration formatting** — Used Python-style `{:.1f}` in SLF4J format string. SLF4J uses `{}` placeholders only. Fixed by using `String.format("%.1f", durationMs)` and passing the formatted string.

9. **Camel MDC API changed** — `camelMain.configure().withUseMDCLogging(true)` doesn't exist in Camel 4.18. Removed it for now — can be configured via application properties later.

### Architecture Decision: One Process Per Integration

Resolved the open question from Session 1. One Camel `CamelContext` manages all routes — multiple flows, multiple API endpoints, all in parallel on one Undertow port. Timer-based flows run on their own threads. Webhook/API flows share the Undertow thread pool. One integration = one process = one port. Multiple integrations = multiple processes (Docker containers in production).

### Open Questions

- **Transform DSL**: Not yet implemented beyond the builder stub. The echo handler uses raw `mapOf()` instead of the typed `transform { source.field to target.field }` syntax from the spec.
- **Schema validation at build time**: The `OpenApiSchemaLoader` can validate field references, but there's no Gradle task (`schemaValidate`) wired up yet.
- **Testing**: No `TestEnvironment` or `MockAdapter` implemented yet.
- **Logger name in routes**: The per-request logger shows the anonymous `RouteBuilder` class name instead of the integration name. Cosmetic — the log message itself contains the operation info.

---

## Session 2 — 2026-02-23: Debug Mode — Traces, Breakpoints & Step-Through

### Goal

Enable n8n/MuleSoft-style debugging: execution traces, breakpoints, pause/resume, data inspection, and data injection. All automatic — no per-flow debug code needed. Enable with an env var, get a full `/debug/*` HTTP API.

### Architecture Decisions

**Debug is a runtime concern, not a DSL concern.** The DSL defines *what* the integration does. Debug mode controls *how* it runs. Enabled via:
- Environment variable: `INTEGRATION_DEBUG=true`
- Constructor parameter: `IntegrationRuntime(integration, debug = true)`

**CompletableFuture-based pause.** When a breakpoint is hit, the request thread blocks on `CompletableFuture.get()`. The debug API routes (served on the same port, different thread) can inspect/modify/resume. No polling, no extra ports. 5-minute timeout prevents orphaned threads.

**Same port, zero config.** All debug endpoints live under `/debug/*` on the same Undertow server. When debug is disabled, these routes are simply not registered (4 routes in non-debug vs 17 in debug mode).

**Trace ring buffer.** Last 100 traces kept in memory (configurable). No external storage needed — this is a development tool.

### What We Built

#### New Files

**`core/.../debug/DebugModels.kt`** — Data classes:
- `DebugConfig` — enabled flag, trace history size, session timeout
- `DebugTrace` — full request/response capture with timing and status
- `TraceData` — body, headers, status code snapshot
- `Breakpoint` — operationId + phase (BEFORE_HANDLER / AFTER_HANDLER)
- `DebugSession` — paused request state + CompletableFuture for resume
- `SessionAction` — sealed class: Resume, ModifyAndResume, Abort

**`core/.../debug/DebugManager.kt`** — Thread-safe manager:
- Trace history (ConcurrentLinkedDeque, bounded)
- Breakpoints (ConcurrentHashMap by ID)
- Active sessions (ConcurrentHashMap by ID)
- `pauseAtBreakpoint()` — blocks calling thread, returns SessionAction
- `resumeSession()` / `modifyAndResumeSession()` / `abortSession()` — unblocks via CompletableFuture

#### Updated Files

**`core/.../camel/ExposeRouteGenerator.kt`** — Major additions:
- Accepts optional `DebugManager` parameter
- Every operation route now: creates trace, checks BEFORE_HANDLER breakpoint, checks AFTER_HANDLER breakpoint, records response in trace
- When breakpoint hit: pauses thread, handles Resume/ModifyAndResume/Abort actions
- On abort: returns 499 with error JSON
- On modify: replaces `requestContext` body before handler execution
- `configureDebugRoutes()` method adds 13 REST endpoints under `/debug/*`

**`runtime/.../Application.kt`** — Debug support:
- `debug` constructor parameter (nullable Boolean)
- Falls back to `INTEGRATION_DEBUG` env var
- Creates `DebugManager` only when enabled
- Logs debug API URL and status on startup

**`examples/echo/build.gradle.kts`** — Passes `INTEGRATION_DEBUG` env var through to JVM process

### Debug API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/debug` | Overview — trace count, breakpoint count, active sessions |
| GET | `/debug/traces` | List recent traces (with request/response data) |
| GET | `/debug/traces/{id}` | Single trace detail |
| DELETE | `/debug/traces` | Clear all traces |
| GET | `/debug/breakpoints` | List all breakpoints |
| POST | `/debug/breakpoints` | Add breakpoint `{"operationId":"echo","phase":"BEFORE_HANDLER"}` |
| DELETE | `/debug/breakpoints/{id}` | Remove a breakpoint |
| POST | `/debug/breakpoints/clear` | Remove all breakpoints |
| GET | `/debug/sessions` | List paused sessions |
| GET | `/debug/sessions/{id}` | Inspect paused session (see current data) |
| POST | `/debug/sessions/{id}/resume` | Resume without changes |
| POST | `/debug/sessions/{id}/modify` | Modify request body and resume |
| POST | `/debug/sessions/{id}/abort` | Abort the request (499 response) |

### What It Looks Like

Startup (debug enabled):
```
19:49:42 INFO  [main] i.echo-service — Starting integration: echo-service v1
19:49:42 INFO  [main] i.echo-service —   Simple echo service — returns what you send it
19:49:42 INFO  [main] i.echo-service — Exposed API on http://0.0.0.0:5400
19:49:42 INFO  [main] i.echo-service —   OpenAPI spec: http://0.0.0.0:5400/openapi.json
19:49:42 INFO  [main] i.echo-service —   Metrics:      http://0.0.0.0:5400/metrics
19:49:42 INFO  [main] i.echo-service —   Debug API:    http://0.0.0.0:5400/debug
19:49:42 INFO  [main] i.echo-service —   Debug mode is ENABLED — traces, breakpoints, and step-through active
```

Camel routes (17 in debug, 4 in non-debug):
```
Routes startup (total:17 rest-dsl:17)
    Started openapi-spec (rest://get:/openapi.json)
    Started metrics (rest://get:/metrics)
    Started debug-overview (rest://get:/debug)
    Started debug-traces (rest://get:/debug/traces)
    ... (13 debug routes)
    Started impl-echo (rest://post:/echo)
    Started impl-health (rest://get:/health)
```

Full debug workflow:
```bash
# 1. Set a breakpoint
$ curl -X POST http://localhost:5400/debug/breakpoints \
    -H "Content-Type: application/json" \
    -d '{"operationId":"echo","phase":"BEFORE_HANDLER"}'
{"id":"33db0891","operationId":"echo","phase":"BEFORE_HANDLER","enabled":true}

# 2. Send a request (will pause)
$ curl -X POST http://localhost:5400/echo \
    -H "Content-Type: application/json" \
    -d '{"message":"original message"}' &

# 3. Inspect the paused session
$ curl http://localhost:5400/debug/sessions
[{"id":"d0ec7562","operationId":"echo","phase":"BEFORE_HANDLER",
  "currentData":{"message":"original message"}}]

# 4. Modify the data and resume
$ curl -X POST http://localhost:5400/debug/sessions/d0ec7562/modify \
    -H "Content-Type: application/json" \
    -d '{"message":"INJECTED by debugger"}'
{"modified":true,"resumed":true}

# 5. The original request returns with modified data:
{"message":"INJECTED by debugger","timestamp":"2026-02-23T...","source":"echo-service"}

# 6. Trace shows the full picture:
$ curl http://localhost:5400/debug/traces
[{"operationId":"echo","status":"COMPLETED",
  "request":{"body":{"message":"original message"}},
  "response":{"body":{"message":"INJECTED by debugger",...}}}]
```

### Issues Encountered & Resolved

10. **Shell `!` expansion in curl** — JSON body with `!` gets mangled by bash history expansion even in single quotes in some contexts. Use data without `!` or escape properly. Not a server-side issue.

11. **Error handling in debug routes** — Jackson parse errors return raw stack traces via Camel's default error handler. The debug API routes don't have custom error handling yet — they rely on Camel's default 500 response. Could add try/catch in each processor for cleaner errors. Low priority since this is a dev tool.

### Open Questions

- **Conditional breakpoints**: The `Breakpoint.condition` field exists but isn't evaluated yet. Future: support expressions like `body.message == "test"`.
- **AFTER_HANDLER modify**: Currently modify only works on BEFORE_HANDLER sessions. AFTER_HANDLER pause lets you inspect but not change the response (yet).
- **Debug UI**: The HTTP API is functional but a web UI (or IDE plugin) would make step-through much more ergonomic. Future: WebSocket for real-time session updates instead of polling.
- **Transform DSL**: Still not implemented. Echo handler still uses raw `mapOf()`.
- **Schema validation at build time**: Still pending.

---

## Session 2b — 2026-02-23: Trace IDs, Logger Fix & Unit Tests

### Goal

Three improvements:
1. Every log message and response gets an **end-to-end trace ID** for request correlation
2. Fix **logger name** so per-request logs show `echo-service` instead of the anonymous class name
3. Add **unit tests** for the debug system

### What We Changed

#### End-to-End Trace ID (MDC-based)

Every incoming request now gets a unique 8-char trace ID. The same ID appears in:
- **SLF4J MDC** — all log lines for that request include the trace ID
- **Response header** — `X-Trace-Id: 29fe922f` returned to the client
- **Debug trace API** — `GET /debug/traces/29fe922f` for full request/response data

Implementation:
- `ExposeRouteGenerator.generateTraceId()` — generates UUID prefix (8 hex chars)
- `MDC.put("traceId", traceId)` at request start, `MDC.remove("traceId")` in `finally` block
- Logback pattern updated: `%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} %mdc{traceId:---------} — %msg`
- `X-Trace-Id` header set on all responses (success, error, and abort)
- When debug mode is on, the DebugTrace ID is the same as the MDC trace ID

Per-request log output now looks like:
```
20:39:25.110 INFO  [XNIO-1 task-2] echo-service 2d340e46 — POST /echo — request received
20:39:25.113 INFO  [XNIO-1 task-2] echo-service 2d340e46 — POST /echo — 200 in 10.9ms
20:39:25.142 INFO  [XNIO-1 task-2] echo-service ebdc89ae — GET /health — request received
20:39:25.148 INFO  [XNIO-1 task-2] echo-service ebdc89ae — GET /health — 200 in 7.9ms
```

Startup lines show `--------` (no trace ID outside request processing — correct).

#### Logger Name Fix

**Root cause**: Camel's `RouteBuilder` has a `protected Logger log` field initialized with `LoggerFactory.getLogger(getClass())`. Our `ExposeRouteGenerator.log` field (named after the integration) was being shadowed by this inherited field inside the anonymous `object : RouteBuilder()` class.

**Fix**: Renamed the field from `log` to `logger` on `ExposeRouteGenerator`. All per-request log calls now go through `logger.info(...)` which is `LoggerFactory.getLogger("echo-service")`.

Before: `i.d.i.c.ExposeRouteGenerator$generate$1` (anonymous class name)
After: `echo-service` (integration name)

Also fixed `Application.kt` — removed `"integration."` prefix from logger name so both startup and per-request logs show `echo-service` consistently.

#### Unit Tests

Added JUnit 5 to `core/build.gradle.kts` and created 35 tests across 3 test files:

**`DebugManagerTest.kt`** (25 tests in 5 nested groups):
- **Traces** (6): add/get round-trip, newest-first ordering, limit, history size bound, clear, unknown ID
- **Breakpoints** (6): add/list, remove existing, remove unknown, clear, match by operationId+phase, skip disabled
- **Sessions** (10): pause/resume with threading, modify+resume delivers new body, abort delivers Abort action, timeout auto-aborts, unknown session IDs return false, getSession detail, pause sets trace to PAUSED status
- **Stats** (1): returns correct counts
- **ConcurrentAccess** (2): parallel trace adds respect bound, multiple simultaneous paused sessions

**`DebugModelsTest.kt`** (7 tests):
- Unique ID generation, Breakpoint defaults, CompletableFuture signaling, SessionAction variants, DebugConfig defaults, TraceData defaults, TraceStatus enum values

**`TraceIdTest.kt`** (3 tests):
- 8-char length, uniqueness over 1000 IDs, hex-only characters

### Files Changed

- `core/build.gradle.kts` — added JUnit 5 test deps + `useJUnitPlatform()`
- `core/src/main/resources/logback.xml` — trace ID in pattern via `%mdc{traceId}`
- `core/.../camel/ExposeRouteGenerator.kt` — trace ID generation, MDC lifecycle, X-Trace-Id header, renamed `log` → `logger`, extracted `processRequest()` method
- `runtime/.../Application.kt` — logger name fix (removed `"integration."` prefix)
- `core/src/test/kotlin/.../debug/DebugManagerTest.kt` — **new** (25 tests)
- `core/src/test/kotlin/.../debug/DebugModelsTest.kt` — **new** (7 tests)
- `core/src/test/kotlin/.../camel/TraceIdTest.kt` — **new** (3 tests)

### Issues Encountered & Resolved

12. **RouteBuilder `log` field shadowing** — Camel's `RouteBuilder` has `protected Logger log = LoggerFactory.getLogger(getClass())`. In Kotlin anonymous objects extending RouteBuilder, `log` resolves to this inherited field, not the outer class's field. Fix: renamed to `logger`.

13. **MDC cleanup** — MDC values are thread-local. Undertow reuses threads from a pool. Without cleanup, a thread could carry a stale trace ID to the next request. Fixed with `try/finally` block around `processRequest()`.

### Open Questions

- **Conditional breakpoints**: The `Breakpoint.condition` field exists but isn't evaluated yet.
- **AFTER_HANDLER modify**: Only BEFORE_HANDLER modify is supported currently.
- **Debug UI**: HTTP API works; WebSocket or web UI would be more ergonomic.
- **Transform DSL**: Still not implemented. Echo handler uses raw `mapOf()`.
- **Schema validation at build time**: Still pending.

---

## Session 3 — 2026-02-23: DSL Refactoring — Clean Property-Assignment Style

### Goal

Clean up the DSL to be less noisy and more familiar. The DSL must serve three consumers: **UI/code generators** (structured, predictable emission), **humans** (scannable, low noise), and **LLMs** (familiar patterns, quick to grasp). Critically, the DSL is a **round-trippable serialization format** — hand-edited code gets formatted and imported back into the UI, like `gofmt`.

Design principle: every line regex-parseable, no novel syntax, follows Gradle Kotlin DSL conventions.

### Architecture Decisions

**Property assignment over method calls.** `version = 1` instead of `version(1)`. Standard Kotlin property syntax — every IDE, linter, and parser understands it. Regex-parseable: `^\s*(\w+)\s*=\s*(.+)$`.

**Expose shorthand with named params.** `expose(api, port = 5400)` instead of `expose(api) { port(5400) }`. Block form kept for advanced config. Named parameters are self-documenting and unambiguous.

**Indexed assignment for handler wiring.** `gw["echo"] = echoHandler` instead of `implement(gateway["echo"], echoHandler)`. Reads like a map assignment, which it conceptually is. Uses Kotlin `operator fun set`.

**Top-level handler function.** `handler { req -> respond(...) }` instead of wrapping in `ImplementBuilder`. The `handler {}` function is just identity — returns the lambda directly. No builder, no ceremony.

**`respond()` with pair syntax.** `respond("key" to "value", "count" to 42)` instead of `body = mapOf("key" to "value")`. Removes one layer of nesting. `to` is Kotlin stdlib, universally known.

### What Changed

#### Before (noisy)
```kotlin
val echoIntegration = integration("echo-service") {
    version(1)
    description("Simple echo service — returns what you send it")

    val echoApi = spec("examples/echo/specs/echo-openapi.yaml")
    val gateway = expose(echoApi) { port(5400) }

    implement(gateway["echo"], echoHandler)
    implement(gateway["health"], healthHandler)
}

// Handler was ImplementBuilder.() -> Unit
val echoHandler: ImplementBuilder.() -> Unit = {
    handler { req ->
        respond(body = mapOf(
            "message" to req.body["message"],
            "timestamp" to Instant.now().toString(),
            "source" to "echo-service",
        ))
    }
}
```

#### After (clean)
```kotlin
val echoIntegration = integration("echo-service") {
    version = 1
    description = "Simple echo service — returns what you send it"

    val api = spec("examples/echo/specs/echo-openapi.yaml")
    val gw = expose(api, port = 5400)

    gw["echo"] = echoHandler
    gw["health"] = healthHandler
}

// Handler is a plain lambda
val echoHandler = handler { req ->
    respond(
        "message" to req.body["message"],
        "timestamp" to Instant.now().toString(),
        "source" to "echo-service",
    )
}
```

### Files Changed

**`core/.../dsl/IntegrationBuilder.kt`** — Major refactor:
- `version(v)` / `description(d)` → `var version: Int = 1` / `var description: String? = null`
- Added `expose(specRef, port, host)` shorthand (named params, no block needed)
- Kept `expose(specRef) { }` block form for advanced config
- Removed `implement()` method
- `GatewayRef` gained `operator fun set(operationId, handler)` — validates operationId against spec, registers `Implementation` via back-reference to builder
- `GatewayRef` gained `operator fun get(operationId)` returning `OperationRef`

**`core/.../dsl/ExposeBuilder.kt`** — Property vars:
- `port(p)` / `host(h)` → `var port: Int = 8080` / `var host: String = "0.0.0.0"` / `var docsPath: String? = "/docs"`

**`core/.../dsl/ImplementBuilder.kt`** — Replaced builder with functions:
- Old `ImplementBuilder` class removed
- Added `fun handler(block: (RequestContext) -> ResponseContext)` — identity function, returns the lambda
- Added `fun respond(vararg fields: Pair<String, Any?>, statusCode, headers)` — shorthand for map body
- Kept `fun respond(statusCode, body, headers)` for explicit body (lists, strings, etc.)

**`examples/echo/.../EchoIntegration.kt`** — Rewritten with new syntax
**`examples/echo/.../flows/EchoHandler.kt`** — Rewritten: `handler { req -> respond("key" to "value") }`
**`examples/echo/.../flows/HealthHandler.kt`** — Rewritten: `handler { _ -> respond("status" to "ok") }`

### New Unit Tests

**`core/src/test/kotlin/.../dsl/IntegrationBuilderTest.kt`** (28 tests in 7 nested groups):
- **PropertyAssignment** (5): version/description defaults, assignment, name passthrough
- **SpecLoading** (5): loads spec, included in build, operations parsed, schemas parsed, invalid path throws
- **SpecRefAccess** (3): schema lookup, unknown schema error, field enumeration
- **ExposeShorthand** (3): port config, host override, spec reference
- **ExposeBlockForm** (4): port/host assignment, defaults (8080, 0.0.0.0)
- **HandlerWiring** (6): `gw["op"] = handler` wiring, multiple ops, handler invocation, unknown op error with available ops message, `gw["op"]` get accessor, unknown op get throws
- **BuildOutput** (2): complete Integration model, minimal defaults

**`core/src/test/kotlin/.../dsl/HandlerTest.kt`** (16 tests in 3 nested groups):
- **HandlerFunction** (5): callable, request body/path params/query params/headers access
- **RespondWithPairs** (6): map body, default 200, custom status code, custom headers, null values, single pair
- **RespondWithBody** (5): explicit body, status-only, no-args, all params, string body

**`core/src/test/resources/test-spec.yaml`** — Minimal test OpenAPI spec with 2 operations (greet, status) and 3 schemas

### Test Results

**79 total tests**, all passing:
- 35 existing (DebugManager, DebugModels, TraceId)
- 28 new IntegrationBuilderTest
- 16 new HandlerTest

### Verification

- `./gradlew :core:test` — 79 tests pass
- `./gradlew :examples:echo:compileKotlin` — compiles clean
- `INTEGRATION_DEBUG=true ./gradlew :examples:echo:run` — starts on :5400
- `curl POST /echo` — 200 with correct response + X-Trace-Id header
- `curl GET /health` — 200 with status + uptime
- `curl GET /debug/traces` — both requests captured with trace IDs

### What's Unchanged

The model layer (`Models.kt`) and all consumers of the `Integration` model (route generator, metrics, debug manager, runtime) are **untouched**. The refactoring is entirely in the DSL builder layer — the output `Integration` data class is identical.

### Open Questions

- **DSL formatter**: Model → canonical `.kt` code emission (like `gofmt`). Next step for round-trip support.
- **DSL parser**: `.kt` source → Model for UI import. Lightweight AST or regex-based.
- **Transform DSL**: Not yet implemented. Handlers still use raw `respond()` with manual mapping.
- **Schema validation at build time**: Still pending.
- **Conditional breakpoints**: Still not evaluated.

---

## Session 4 — 2026-02-23: Adapter Runtime + User Enrichment Example

### Goal

Build the adapter abstraction and a multi-step enrichment example that calls an external API, maps fields, and demonstrates the full debug workflow (pause, inspect, inject modified data, resume).

### Architecture Decisions

**Every adapter must have an OpenAPI spec.** No untyped connections. If the external API doesn't publish a spec, we write one ourselves. The spec is the interface contract — it defines what fields the external API provides and what we can rely on. Future: GraphQL specs too.

**Adapter as the universal abstraction.** The `adapter()` DSL call is how you connect to anything external — HTTP APIs today, Postgres/GraphQL/Kafka later. The same `AdapterRef` pattern (define at DSL time, call at runtime) applies to all adapter types.

**AdapterRef is both DSL reference and runtime object.** No separate "adapter runtime" setup phase — the ref returned from `adapter()` is directly callable in handlers. Uses JDK 21's built-in `java.net.http.HttpClient` + Jackson. No new dependencies needed.

**`nested()` dot-path accessor.** External API responses often have nested objects (`address.city`, `company.name`). Rather than requiring manual map traversal, `map.nested("address.city")` walks the dot-separated path. Returns null for missing intermediates.

### What We Built

#### Adapter Framework (core)

**`core/.../dsl/AdapterBuilder.kt`** — DSL builder:
```kotlin
class AdapterBuilder(name: String, spec: Spec) {
    var baseUrl: String = ""
    var auth: AuthConfig? = null
}
```

**`core/.../dsl/AdapterRef.kt`** — Runtime HTTP client:
- `get(path, headers)`, `post(path, body, headers)`, `put(...)`, `delete(...)` methods
- Auth support: Bearer, API key (header), Basic, OAuth2
- JSON serialization via Jackson
- `AdapterCallException` for non-2xx responses (includes adapter name, method, path, status code)
- `Map.nested(dotPath)` extension function

**`core/.../dsl/IntegrationBuilder.kt`** — Added `adapter()`:
```kotlin
fun adapter(name: String, specRef: SpecRef, block: AdapterBuilder.() -> Unit = {}): AdapterRef
```

**`core/.../model/Models.kt`** — `Adapter.spec` made nullable (DSL enforces it via signature, model allows flexibility).

#### User Enrichment Example

**`examples/enrichment/specs/enrichment-openapi.yaml`** — Our exposed API: `POST /enrich` accepts `{userId}`, returns enriched user.

**`examples/enrichment/specs/jsonplaceholder-openapi.yaml`** — External API spec (authored by us). Defines the User, Address, Company, Geo schemas from JSONPlaceholder. Enforces the typed contract.

**`examples/enrichment/.../EnrichmentIntegration.kt`**:
```kotlin
val enrichment = integration("user-enrichment") {
    version = 1
    description = "Enriches user data from JSONPlaceholder"
    val api = spec("examples/enrichment/specs/enrichment-openapi.yaml")
    val gw = expose(api, port = 5401)
    val usersApi = adapter("jsonplaceholder", spec("examples/enrichment/specs/jsonplaceholder-openapi.yaml")) {
        baseUrl = "https://jsonplaceholder.typicode.com"
    }
    gw["enrich"] = enrichHandler(usersApi)
}
```

**`examples/enrichment/.../flows/EnrichHandler.kt`** — Multi-step handler:
1. Extract `userId` from request
2. Call `usersApi.get("/users/$userId")` — real HTTP to JSONPlaceholder
3. Map fields: `name`→`fullName`, `email`→`emailAddress`, `address.city`→`city`, `company.name`→`company`
4. Add metadata: `source`, `enrichedAt`

#### READMEs

Full documentation for both examples: startup, all endpoints with curl/expected responses, logging format with trace IDs, metrics, and complete debug workflow walkthroughs.

### Debug Injection Walkthrough (verified)

1. `POST /debug/breakpoints` — set BEFORE_HANDLER breakpoint on `enrich`
2. `POST /enrich {"userId":1}` — request pauses at breakpoint
3. `GET /debug/sessions` — shows `currentData: {"userId": 1}`
4. `POST /debug/sessions/{id}/modify {"userId":3}` — inject user 3
5. Original request returns **Clementine Bauch** (user 3) instead of Leanne Graham (user 1)
6. Trace shows `request.body: {"userId": 1}` but `response.body.fullName: "Clementine Bauch"` — proving injection worked

### Unit Tests

17 new tests in `AdapterBuilderTest.kt`:
- **AdapterDsl** (5): registration, spec loaded, multiple adapters, defaults, auth config
- **AdapterRefProperties** (2): name, adapter model access
- **NestedMapAccess** (7): top-level, one/two levels deep, missing path, partial path, non-map intermediate, empty map
- **AdapterRefHttpCalls** (3): real GET to JSONPlaceholder, real POST, 404 throws AdapterCallException

**96 total tests**, all passing.

### Files Created

- `core/.../dsl/AdapterBuilder.kt`
- `core/.../dsl/AdapterRef.kt`
- `core/src/test/.../dsl/AdapterBuilderTest.kt`
- `core/src/test/resources/adapter-test-spec.yaml`
- `examples/enrichment/build.gradle.kts`
- `examples/enrichment/specs/enrichment-openapi.yaml`
- `examples/enrichment/specs/jsonplaceholder-openapi.yaml`
- `examples/enrichment/.../EnrichmentIntegration.kt`
- `examples/enrichment/.../flows/EnrichHandler.kt`
- `examples/echo/README.md`
- `examples/enrichment/README.md`

### Files Modified

- `core/.../dsl/IntegrationBuilder.kt` — added `adapter()` function
- `core/.../model/Models.kt` — `Adapter.spec` nullable
- `settings.gradle.kts` — added enrichment module

### Open Questions

- **Deeply nested folder structure**: `src/main/kotlin/io/devtech/integration/...` is very deep. Could flatten.
- **Environment variables**: Ports, API keys, adapter credentials should be configurable via env vars. Currently hardcoded in DSL.
- **Path parameters**: `RequestContext.pathParams` exists but isn't wired in `ExposeRouteGenerator` — Camel extracts them but they aren't passed to the handler.
- **Query parameters**: Same — `RequestContext.queryParams` exists but isn't populated.
- **Multi-path API example**: Need an example with GET/POST/PUT/DELETE on different paths, path params, query params, to exercise the full REST surface.
- **Switch/router building block**: For flows that branch based on conditions (e.g., different logic per HTTP method or input field value).
- **DSL formatter/parser**: Still pending.
- **Transform DSL**: Still manual field mapping in handlers.

---

## Session 5 — 2026-02-23: DSL Simplification — flow() and execute()

### Goal

Remove ceremony from the DSL. Two things bothered us:
1. The `gw["op"] = handler` pattern requires storing a `GatewayRef` that adds no value — there's always exactly one gateway.
2. Every integration file repeats the same `val x = integration(...) { }` + `fun main() { IntegrationRuntime(x).start() }` boilerplate.

### Architecture Decisions

**`flow()` replaces `gw["op"] = handler`.** The gateway is implicit — `expose()` no longer returns a value. Handlers are wired with `flow("operationId", handler)` directly on the integration builder. Same function supports trailing lambdas: `flow("health") { respond("status" to "ok") }`. One method, two forms.

**`execute()` replaces `IntegrationRuntime` construction.** Top-level convenience function in the runtime module combines `integration()` + `IntegrationRuntime.start()` into a single call. Eliminates the intermediate `val` and explicit runtime instantiation.

**GatewayRef and OperationRef removed.** No longer part of the public API. The `flow()` method validates operationId against the exposed spec directly, same validation that `GatewayRef.set` previously did.

### What Changed

#### Before
```kotlin
val echoIntegration = integration("echo-service") {
    version = 1
    description = "Simple echo service — returns what you send it"

    val api = spec("examples/echo/specs/echo-openapi.yaml")
    val gw = expose(api, port = 5400)

    gw["echo"] = echoHandler
    gw["health"] = healthHandler
}

fun main() {
    IntegrationRuntime(echoIntegration).start()
}
```

#### After
```kotlin
fun main() = execute("echo-service") {
    version = 1
    description = "Simple echo service — returns what you send it"

    val api = spec("examples/echo/specs/echo-openapi.yaml")
    expose(api, port = 5400)

    flow("echo", echoHandler)
    flow("health", healthHandler)
}
```

### Files Changed

**`core/.../dsl/IntegrationBuilder.kt`** — Major changes:
- Removed `GatewayRef` class (operator set/get, spec reference, builder back-reference)
- Removed `OperationRef` class (operation metadata accessors)
- Removed `activeGateway` field and `addImplementation()` internal method
- Both `expose()` overloads now return `Unit` instead of `GatewayRef`
- Added `flow(operationId, handlerFn)` — validates operationId against exposed spec, registers `Implementation` directly

**`runtime/.../Application.kt`** — Added `execute()`:
```kotlin
fun execute(name: String, debug: Boolean? = null, block: IntegrationBuilder.() -> Unit) {
    IntegrationRuntime(integration(name, block), debug).start()
}
```

**`core/.../dsl/ExposeBuilder.kt`** — Updated kdoc (removed `val gw =` from example)

**`core/src/test/.../dsl/IntegrationBuilderTest.kt`** — Rewritten HandlerWiring section:
- `gw set wires handler` → `flow wires handler to operation`
- Added `flow with trailing lambda wires handler`
- Added `flow before expose throws`
- Removed `gw get returns OperationRef` and `gw get throws for unknown operation`
- All other test sections updated to drop `val gw =` where unused

**`examples/echo/.../EchoIntegration.kt`** — Rewritten with `execute()` + `flow()`
**`examples/enrichment/.../EnrichmentIntegration.kt`** — Rewritten with `execute()` + `flow()`
**`README.md`** — Replaced aspirational weather-slack example with real enrichment example

### Test Results

All existing tests pass. Net change: 7 files, +93 / -137 lines (44 lines removed).

### Open Questions

Same as Session 4 — no new questions introduced. This was a pure simplification pass.

---

## Session 6 — 2026-02-23: Route Adapter Calls Through Camel

### Goal

The DSL was designed as a clean layer over Apache Camel, reusing Camel's ecosystem. But adapters bypassed Camel entirely — `HttpBackend` used `java.net.http.HttpClient`, `PostgresBackend` used raw JDBC. Camel only served inbound REST endpoints. This session fixes the outbound side: HTTP adapter calls now go through Camel's `ProducerTemplate` and `camel-http` component, unlocking Camel's connection pooling, redirect handling, SSL, and the full component ecosystem.

### Architecture Decisions

**CamelHttpBackend as the default HTTP backend.** New class that uses `ProducerTemplate.request()` with the `camel-http` component URI scheme. `HttpBackend` (raw `java.net.http.HttpClient`) is preserved for unit tests that don't need a full CamelContext.

**Late-binding via AtomicReference.** The core problem: `AdapterRef` objects are created at DSL evaluation time (inside `integration("name") { ... }`), but `ProducerTemplate` only exists after Camel starts. Solution: `CamelHttpBackend` holds an `AtomicReference<ProducerTemplate?>`, injected by the runtime via `MainListener.afterConfigure()` — after CamelContext exists but before routes accept traffic.

```
DSL evaluation → AdapterRef created (ProducerTemplate = null)
                       ↓
Runtime start  → MainListener.afterConfigure() injects ProducerTemplate
                       ↓
First request  → AdapterRef.get() → CamelHttpBackend → ProducerTemplate → camel-http
```

**CamelBindable interface.** Marker interface for backends that need a `ProducerTemplate`. The runtime iterates all `AdapterRef` instances and calls `bindProducerTemplate()` on those whose backend implements `CamelBindable`. Clean separation — the runtime doesn't need to know about specific backend types.

**Public binding method on AdapterRef.** Since `backend` is `internal` (module-scoped to core), the runtime module can't access it directly. Added `AdapterRef.bindProducerTemplate(template)` and `AdapterRef.isCamelManaged` as the public API for the runtime. Keeps backend encapsulated.

**Keep InMemoryBackend, PostgresBackend, HttpBackend as-is.** InMemory is a testing convenience with no Camel equivalent. PostgresBackend does domain-specific path→SQL translation that `camel-jdbc` doesn't handle. HttpBackend remains for unit tests without CamelContext.

### What We Built

#### User's Pre-Session Changes: FlowBuilder + Error Config

Before the Camel routing work, the DSL gained a block form for `flow()` with error handling:

```kotlin
flow("enrich") {
    onError {
        retry { maxAttempts = 3; delayMs = 500 }
        circuitBreaker { failureRateThreshold = 50f }
    }
    handle(enrichHandler(usersApi))
}
```

New classes: `FlowBuilder`, `ErrorConfigBuilder`, `RetryConfigBuilder`, `CircuitBreakerConfigBuilder` in `FlowBuilder.kt`. New model types: `ErrorConfig`, `RetryConfig`, `CircuitBreakerConfig` in `Models.kt`. The `ExposeRouteGenerator` now maps these to Camel's `onException` (retry with exponential backoff) and `circuitBreaker` (Resilience4j via Camel). The old handler-level `CircuitBreakerRegistry` and `retry()` function in `EaiBlocks.kt` were removed — error handling is now a route-level concern via Camel EIPs.

#### Camel HTTP Routing (this session's work)

**`core/build.gradle.kts`** — Added `camel-http` dependency:
```kotlin
api("org.apache.camel:camel-http:$camelVersion")
```

**`core/.../dsl/AdapterBackend.kt`** — New interface + class:
```kotlin
interface CamelBindable {
    fun bindProducerTemplate(template: ProducerTemplate)
}

class CamelHttpBackend(
    private val adapterName: String,
    private val baseUrl: String,
    private val auth: AuthConfig? = null,
) : AdapterBackend, CamelBindable {
    private val _producerTemplate = AtomicReference<ProducerTemplate?>(null)

    override fun execute(method, path, body, queryParams, headers): Any? {
        val template = _producerTemplate.get()
            ?: error("CamelContext not yet initialized")
        // URI: http://host/path?bridgeEndpoint=true&throwExceptionOnFailure=false
        // Headers: Exchange.HTTP_METHOD, Exchange.HTTP_QUERY, auth, Content-Type
        // Send via template.request("http:$uri") { ... }
        // Parse response, throw AdapterCallException on non-2xx
    }
}
```

**`core/.../dsl/AdapterRef.kt`** — Public binding API:
```kotlin
val isCamelManaged: Boolean get() = backend is CamelBindable

fun bindProducerTemplate(template: ProducerTemplate) {
    val b = backend
    if (b is CamelBindable) b.bindProducerTemplate(template)
}
```

**`core/.../dsl/IntegrationBuilder.kt`** — Tracks adapter refs:
```kotlin
private val _adapterRefs = mutableListOf<AdapterRef>()
val adapterRefs: List<AdapterRef> get() = _adapterRefs.toList()

fun adapter(...): AdapterRef {
    // ... existing logic ...
    val ref = builder.buildRef()
    _adapterRefs += ref  // NEW — runtime needs these for binding
    return ref
}
```

**`core/.../dsl/AdapterBuilder.kt`** — One-line swap:
```kotlin
BackendType.HTTP -> CamelHttpBackend(name, baseUrl, auth)  // was: HttpBackend(...)
```

**`runtime/.../Application.kt`** — Threading + binding:
```kotlin
fun execute(name: String, debug: Boolean? = null, block: IntegrationBuilder.() -> Unit) {
    val builder = IntegrationBuilder(name).apply(block)
    IntegrationRuntime(builder.build(), builder.adapterRefs, debug).start()
}

class IntegrationRuntime(
    private val integration: Integration,
    private val adapterRefs: List<AdapterRef> = emptyList(),
    debug: Boolean? = null,
) {
    fun start() {
        // ... existing route setup ...

        val camelAdapters = adapterRefs.filter { it.isCamelManaged }
        if (camelAdapters.isNotEmpty()) {
            camelMain.addMainListener(object : MainListenerSupport() {
                override fun afterConfigure(main: BaseMainSupport) {
                    val template = main.camelContext.createProducerTemplate()
                    for (ref in camelAdapters) {
                        ref.bindProducerTemplate(template)
                        log.info("Adapter '{}' bound to Camel ProducerTemplate", ref.name)
                    }
                }
            })
        }

        camelMain.run()
    }
}
```

### Overload Resolution Fix

The two `flow()` overloads — `(String, (RequestContext) -> ResponseContext)` and `(String, FlowBuilder.() -> Unit)` — caused Kotlin overload ambiguity for trailing lambdas. The pre-commit hook resolved this by adding a dummy `unused: Unit = Unit` parameter to the handler overload:
```kotlin
fun flow(operationId: String, handlerFn: (RequestContext) -> ResponseContext, unused: Unit = Unit)
```
This ensures `flow("op") { ... }` always resolves to the FlowBuilder form (trailing lambda), while `flow("op", handler)` uses the handler form (explicit second argument). Clean disambiguation without renaming.

### Architecture After

```
Inbound:   HTTP request → Undertow → Camel REST DSL → handler
Outbound:  handler → AdapterRef → CamelHttpBackend → ProducerTemplate → camel-http → external API
                                 → InMemoryBackend → ConcurrentHashMap (testing)
                                 → PostgresBackend → JDBC (domain-specific)
                                 → HttpBackend → java.net.http (unit tests)
```

Both inbound and outbound HTTP now flow through Camel.

### Verification

- `./gradlew :core:test` — all tests pass
- All modules compile (core, runtime, echo, enrichment, contacts)
- **Enrichment E2E**: `POST /enrich {"userId":1}` returns enriched user data from JSONPlaceholder. Log confirms: `Adapter 'jsonplaceholder' bound to Camel ProducerTemplate`
- **Contacts E2E**: InMemory CRUD works unchanged — create, list, get all return correct data

### What's Unchanged

| Component | Status |
|-----------|--------|
| InMemoryBackend | Kept as-is (testing) |
| PostgresBackend | Kept as-is (domain-specific SQL) |
| HttpBackend | Kept as-is (unit tests without Camel) |
| Handler API `(RequestContext) -> ResponseContext` | Untouched |
| AdapterRef user API (get/post/put/delete) | Untouched |
| DSL syntax | Untouched |
| All example source files | Zero changes |
| Debug system | Untouched |
| Metrics | Untouched |

### Issues Encountered & Resolved

14. **JVM platform declaration clash** — Two `flow()` overloads with lambda parameters erase to the same JVM signature `flow(String, Function1)V`. Initially fixed with `@JvmName("flowWithBuilder")`, but the pre-commit hook found a better solution: dummy parameter disambiguation.

15. **Kotlin `internal` visibility is module-scoped** — `AdapterRef.backend` was marked `internal` (visible within the core module), but the runtime module needs to inject ProducerTemplate. Fixed by adding public `bindProducerTemplate()` and `isCamelManaged` methods on `AdapterRef`, keeping the backend field encapsulated.

16. **`DefaultMainListener` doesn't exist in Camel 4.18** — Initial attempt used `DefaultMainListener` which isn't a real class. The correct base class is `MainListenerSupport`.

17. **Port binding conflict during E2E testing** — Previous Gradle daemon held a port open. Needed to kill all Java processes between example runs.

### Open Questions

- **camel-jdbc backend**: Could replace `PostgresBackend` with a Camel-native JDBC backend, but the path→SQL translation is domain-specific. Low priority.
- **camel-kafka, camel-graphql**: Future adapter backends that would naturally use ProducerTemplate. The `CamelBindable` pattern is ready for them.
- **Connection pooling tuning**: `camel-http` uses Apache HttpClient 5 under the hood with sensible defaults. May need tuning for high-throughput adapters.
- **Adapter health checks**: Could use the ProducerTemplate to ping adapter endpoints on startup.
- **Transform DSL**: Still manual field mapping in handlers.
- **Schema validation at build time**: Still pending.
- **DSL formatter/parser**: Still pending.

---

## Session 7 — 2026-02-24: Management Plane — Archive Deployment + Multi-Runtime

### Goal

Build a MuleSoft-style management plane that deploys integration archives (JARs) to runtime agents, manages multiple integrations per JVM, and provides REST APIs for lifecycle control. Move from "one integration, one JVM" to "many integrations, orchestrated."

### Decisions Made

**DefaultCamelContext per integration** — The standalone runtime uses Camel Main (blocking, owns the JVM). The managed runtime uses `DefaultCamelContext` directly — non-blocking start/stop, multiple contexts coexist. Each integration owns its port via its own `restConfiguration()`, avoiding conflicts.

**Undertow direct for management/agent APIs** — Management REST API (port 9000) and agent API (port 8081) use `io.undertow:undertow-core` directly, not Camel REST DSL. This avoids `restConfiguration()` conflicts with integration ports.

**Agent as grouping unit** — One agent = one JVM/container running N integrations. Related integrations share a process. The agent is the deployment target, not the individual integration:
```kotlin
manage(port = 9000) {
    localAgent("order-services")
    deploy(OrderFactory())
    deploy(InventoryFactory())
    deploy(NotificationFactory())
}
```

**Three deployment modes** — All produce the same `IntegrationPackage(integration, adapterRefs)`:
| Mode | Packaging | Use Case |
|------|-----------|----------|
| Inline DSL | None (code) | Development |
| Factory class | On classpath | Multi-integration apps |
| JAR artifact | JAR with `META-INF/integration.properties` | Production, CI/CD |

**IntegrationFactory interface in core** — Factory implementations live inside deployable JARs. Discovered via `META-INF/integration.properties` (`integration.name`, `integration.factory`). Receives a `DeploymentContext` with `baseDir` (extracted JAR contents for spec resolution) and `properties` (runtime overrides).

**No coroutines** — `AgentConnection` uses regular functions. Java `HttpClient` is synchronous. Simpler, no extra dependency.

**Single `management` module** — Contains both management plane and agent code. Different main classes for different deployment modes (plane vs standalone remote agent). Simpler than splitting into two modules.

### What We Built

#### Module Layout (after)

```
core/
  factory/IntegrationFactory.kt       NEW — factory interface, DeploymentContext, IntegrationPackage
management/                            NEW MODULE (11 files)
  build.gradle.kts                     depends on :core, :runtime, adds undertow
  model/ManagementModels.kt            Deployment, DeploymentState, AgentInfo, ManagementEvent
  runtime/IntegrationContextRuntime.kt non-blocking per-integration CamelContext
  artifact/ArtifactStore.kt            JAR storage + metadata extraction
  artifact/JarLoader.kt                JAR extraction, URLClassLoader, factory loading
  agent/AgentConnection.kt             interface + DeployCommand + AgentCommandResult
  agent/LocalAgentConnection.kt        in-JVM agent, ConcurrentHashMap of runtimes
  agent/RemoteAgentConnection.kt       HTTP-based agent (Java HttpClient)
  api/ManagementApi.kt                 REST API on port 9000 (Undertow)
  api/AgentApi.kt                      Remote agent API on port 8081 (Undertow)
  dsl/ManageDsl.kt                     manage {} DSL entry point
examples/echo/
  EchoFactory.kt                       NEW — IntegrationFactory implementation
  resources/META-INF/integration.properties  NEW — JAR metadata
examples/managed/                      NEW MODULE
  build.gradle.kts                     depends on :management, :examples:echo
  ManagedExample.kt                    management plane with 3 deployments
```

#### Management Plane Architecture

```
┌─────────────────────────────────────────────────┐
│  Management Plane (port 9000)                   │
│  ManagementPlane + ManagementApi (Undertow)     │
│                                                 │
│  ArtifactStore  │  DeploymentRegistry           │
│  AgentRegistry  │  EventLog (500 cap)           │
└──────────┬──────────────────────────────────────┘
           │ AgentConnection (interface)
   ┌───────┴───────┐
   ▼               ▼
┌──────────┐  ┌──────────────────┐
│ Local    │  │ Remote Agent     │
│ Agent    │  │ port 8081        │
│ (in-JVM) │  │ AgentApi         │
│          │  │                  │
│ CamelCtx │  │ CamelCtx A      │
│ A, B, C  │  │ CamelCtx B      │
└──────────┘  └──────────────────┘
```

#### Management REST API (port 9000)

```
POST   /mgmt/artifacts              upload JAR (raw bytes, X-Filename header)
GET    /mgmt/artifacts              list artifacts
GET    /mgmt/artifacts/{id}/download
GET    /mgmt/agents                 list agents
POST   /mgmt/deployments            deploy (artifactId, agentId, properties, autoStart)
GET    /mgmt/deployments            list deployments
POST   /mgmt/deployments/{id}/start
POST   /mgmt/deployments/{id}/stop
DELETE /mgmt/deployments/{id}       undeploy
GET    /mgmt/health                 aggregate health
GET    /mgmt/events                 recent events
```

#### Agent REST API (port 8081, remote agents)

```
POST   /agent/artifacts              receive JAR bytes
POST   /agent/deploy                 deploy integration from artifact
POST   /agent/integrations/{id}/start
POST   /agent/integrations/{id}/stop
DELETE /agent/integrations/{id}      undeploy
GET    /agent/status                 health + integration statuses
```

#### Integration Lifecycle

```
PENDING → DEPLOYING → DEPLOYED → STARTING → RUNNING
                                      ↓         ↓
                                    FAILED   STOPPING → STOPPED
```

#### JAR Artifact Flow

1. JAR uploaded via `POST /mgmt/artifacts` — stored in `management-data/artifacts/`
2. `ArtifactStore` reads `META-INF/integration.properties` for metadata
3. On deploy: `JarLoader` extracts JAR to temp dir, creates `URLClassLoader`, loads factory class
4. Factory receives `DeploymentContext(baseDir=extractedDir, properties=overrides)`
5. Factory returns `IntegrationPackage` — handed to `IntegrationContextRuntime`
6. Runtime creates `DefaultCamelContext`, adds routes, starts, binds ProducerTemplate

#### ManagedExample

```kotlin
fun main() = manage(port = 9000) {
    localAgent()
    deploy(EchoFactory())                                    // port 5400
    deploy(EchoFactory(), properties = mapOf("PORT" to "5500"))  // port 5500
    deployInline("echo-dev") {                               // port 5401
        val api = spec("examples/echo/specs/echo-openapi.yaml")
        expose(api, port = 5401)
        flow("echo", handler { req -> respond("message" to req.body["message"], "source" to "inline") })
        flow("health", healthHandler)
    }
}
```

### Issues Encountered & Resolved

18. **`DefaultCamelContext.setName()` removed in Camel 4.18** — The method doesn't exist. Fixed by using `ctx.nameStrategy = DefaultCamelContextNameStrategy(name)` instead.

19. **`camel-json-schema-validator` artifact renamed** — The correct Maven artifact is `camel-json-validator` (not `camel-json-schema-validator`). Caused resolution failures until renamed in `core/build.gradle.kts`.

20. **Kotlin incremental compilation cache corruption** — `ArrayIndexOutOfBoundsException` in LZ4 decompression during incremental build. Cleared with `rm -rf .gradle/kotlin build */build`. Kotlin fell back to a clean build and succeeded.

### Runtime Model Documentation

Updated `ARCHITECTURE.md` with a comprehensive "Runtime Model" section covering:
- Standalone vs managed runtime comparison
- Agent as grouping unit (N integrations per agent)
- Three deployment modes (inline, factory, JAR)
- Integration lifecycle states
- Per-integration infrastructure (traces, metrics, error handling, debug)
- Future runtime targets (GraalVM native, Docker, fleet runner/backplane)

### Future: Multi-Runtime Execution Modes

Discussion established the roadmap for runtime evolution:

**Runtime modes** (how an integration runs):
- JVM JAR — hot-deploy, URLClassLoader, DefaultCamelContext ✅ DONE
- GraalVM native — compile to native binary, fast cold start ⏳ PLANNED
- Docker container — wrap JAR or native binary in container image ⏳ PLANNED

**Agent types** (where it runs):
- Local (in-JVM) — same process as management plane ✅ DONE
- Remote (HTTP) — separate JVM, HTTP-based agent API ✅ DONE
- Docker — manages containers via Docker API ⏳ PLANNED
- Fleet — runner/backplane managing Docker hosts ⏳ PLANNED

The production deployment model:
```
Backplane (fleet orchestrator, cluster-wide)
  └── Runner (per-host daemon, manages local Docker)
        └── Container (one agent = N related integrations)
              └── Runtime (JAR or native binary)
```

`AgentConnection` is the extension point — Docker, native, and fleet implementations slot in as new implementations of the same interface.

### Verification

- `./gradlew :management:compileKotlin` — compiles clean (1 unchecked cast warning in RemoteAgentConnection, harmless)
- `./gradlew :examples:managed:compileKotlin` — compiles clean
- All 16 new files + 2 modified files in place
- Management REST API endpoints defined and routed
- Agent API endpoints defined and routed

### Open Questions

- **Shutdown ordering**: When stopping a management plane, should integrations be stopped in reverse deployment order? Currently shutdown hook only stops the API server.
- **Deployment persistence**: Deployments are in-memory only. Restart loses all state. Could persist to a JSON file or embedded DB for recovery.
- **Remote agent discovery**: Currently agents are registered manually. Could add mDNS/DNS-SD or a registration endpoint for auto-discovery.
- **JAR versioning**: No version tracking for artifacts. Uploading the same integration twice creates two separate artifacts. Could diff or deduplicate.
- **Health check intervals**: Remote agent health is only checked on-demand. Could add periodic polling with circuit breaker on the management side.
- **GraalVM native-image**: Would need reflection configuration for factory class loading. `URLClassLoader` won't work in native mode — would need ahead-of-time compilation instead.

## Session 8 — 2026-02-24: Camel-ify All the Things

### Goal

Replace custom Kotlin logic with Camel-backed implementations wherever possible. The framework should use Camel as the execution engine, not just for HTTP routing — parallel execution, validation, database access, and HTTP calls should all flow through Camel components.

### What We Converted

#### 1. HttpBackend → deleted (replaced by CamelHttpBackend)

The custom `java.net.http.HttpClient`-based `HttpBackend` was removed entirely. All HTTP adapter calls now go through `CamelHttpBackend`, which uses Camel's `ProducerTemplate` to send requests via `camel-undertow`. This gives us Camel's connection management, tracing, and error handling for free.

#### 2. PostgresBackend → Camel JDBC component

Previously used raw `DriverManager.getConnection()` and manual `PreparedStatement` calls. Now implements `CamelBindable`, registers a `javax.sql.DataSource` in Camel's registry at bind time, and routes all SQL through `jdbc:dataSourceName` via ProducerTemplate. Connection lifecycle is now Camel-managed.

#### 3. parallel() → Camel multicast().parallelProcessing()

The `parallel()` EAI block was backed by raw `CompletableFuture` + virtual thread executor. Now creates ephemeral `direct:` routes per task, fans out via `multicast().parallelProcessing()` with a custom `AggregationStrategy`, and tears down routes after completion. Falls back to virtual threads when no CamelContext is available (unit tests).

#### 4. validate() → Camel json-validator component

Schema validation was custom type-checking code. Now generates a JSON Schema from our `SchemaObject`, writes it to a temp file, and routes through Camel's `json-validator:file:` endpoint. Falls back to manual validation in tests without CamelContext.

#### 5. CamelContextHolder (new)

Thread-local holder that lets DSL functions (`parallel()`, `validate()`) discover the active CamelContext without parameter threading. Set by `ExposeRouteGenerator` before handler execution, cleared after.

### Tests Updated

- `AdapterBuilderTest` — replaced all `HttpBackend` references with `CamelHttpBackend` + `DefaultCamelContext` bootstrap
- `AdapterRefHttpCalls` — extracted `createCamelRef()` helper that spins up a minimal CamelContext for live API tests
- All 16 tests pass (including JSONPlaceholder live API tests)

### Still Not Camel-Backed

| Component | Location | Reason |
|-----------|----------|--------|
| InMemoryBackend | AdapterBackend.kt | Testing-only — no Camel equivalent for in-memory REST store |
| PostgresBackend SQL construction | AdapterBackend.kt | SQL built via string interpolation, not Camel SQL component with parameter binding |
| SimpleDataSource | AdapterBackend.kt | No connection pooling — should use HikariCP via Camel registry |
| DebugManager thread blocking | DebugManager.kt | Debug infrastructure — intentional custom blocking at breakpoints |
| Handler tracing/metrics | ExposeRouteGenerator.kt | Framework-level cross-cutting concerns, appropriate outside Camel routes |
| RemoteAgentConnection | management/ | Uses Java HttpClient directly for agent-to-plane communication |
| Management/Agent APIs | management/ | Uses Undertow directly (intentional — avoids restConfiguration() conflicts) |
| Flow model (sealed classes) | Models.kt | Defined but not yet implemented — future workflow orchestration |

### Verification

- `./gradlew :core:test` — all tests pass
- `./gradlew :core:compileKotlin` — clean (0 warnings after fixing non-null assertion)

## Session 8b — 2026-02-24: Complete Camel Migration

### Goal

Finish converting all remaining components to Camel-backed implementations. After session 8, PostgresBackend still used string-interpolated SQL, management APIs used raw Undertow, RemoteAgentConnection used Java HttpClient, and tracing/metrics were manual.

### What We Converted

#### 1. PostgresBackend SQL → camel-sql with named parameters + HikariCP

Replaced `SimpleDataSource` (raw `DriverManager.getConnection()`) with `HikariDataSource` (connection pooling, pool-per-adapter). Switched from `jdbc:` component to `sql:` component with `:#paramName` named parameter binding. All SQL values now go through Camel's parameter binding — no more string interpolation. Deleted `quote()` helper. DDL (`CREATE TABLE IF NOT EXISTS`) stays on `jdbc:` since it doesn't benefit from parameter binding.

#### 2. Tracing/Metrics → camel-micrometer route policy + MDC logging

Added `MicrometerRoutePolicyFactory` to both `IntegrationContextRuntime` (managed) and `IntegrationRuntime` (standalone). Enabled `isUseMDCLogging = true` / `setMDCLoggingKeysPattern("*")`. Camel now automatically tracks per-route: `camel.exchanges.total`, `camel.exchanges.duration`, `camel.exchanges.failed`. Removed manual `MetricsRegistry.recordCall()` and `MetricsRegistry.recordError()` from `executeHandler()` and `handleError()`. MetricsRegistry singleton remains for JVM metrics and the `/metrics` Prometheus endpoint — Camel writes to the same registry.

#### 3. RemoteAgentConnection → Camel ProducerTemplate

Replaced `java.net.http.HttpClient` with Camel's HTTP component via ProducerTemplate. Constructor now accepts `() -> ProducerTemplate` lambda (lazy — created when first needed). All 6 methods (deploy, start, stop, undeploy, status, healthCheck) use a shared `camelRequest()` helper that routes through `http:` component with `bridgeEndpoint=true&throwExceptionOnFailure=false`. ManageDsl creates a dedicated `DefaultCamelContext("mgmt-http")` for outbound HTTP.

#### 4. ManagementApi → Camel REST DSL (own CamelContext, port 9000)

Replaced raw Undertow server with `DefaultCamelContext("mgmt-api")` using `restConfiguration().component("undertow").port(9000)`. All 11 endpoints defined via `rest("/mgmt").post("/artifacts").to("direct:mgmt-upload-artifact")` pattern. Handler methods converted from `HttpServerExchange` to `org.apache.camel.Exchange`. Path parameters come from Camel headers (e.g., `exchange.message.getHeader("id")`).

#### 5. AgentApi → Camel REST DSL (own CamelContext, port 8081)

Same pattern as ManagementApi. 6 endpoints under `rest("/agent")`. Standalone factory method preserved.

### Decisions

**Per-API CamelContext** — Each API (ManagementApi, AgentApi) gets its own `DefaultCamelContext` with its own `restConfiguration()`. This preserves port isolation between management plane, agent API, and per-integration APIs. Same pattern as `IntegrationContextRuntime`.

**Lazy ProducerTemplate for RemoteAgentConnection** — Constructor takes `() -> ProducerTemplate` instead of `ProducerTemplate` directly. This handles the ordering problem: remote agents are registered before the management CamelContext starts.

**Skip InMemoryBackend** — Test infrastructure, intentionally Camel-free. 16 tests depend on standalone use.

**Skip DebugManager blocking** — `CompletableFuture.get(timeout)` is the correct pattern for request-level thread blocking. Camel has no equivalent for pausing a request mid-flight.

**Skip Flow model** — Sealed classes in Models.kt are data definitions, not custom logic to convert. Implementation is new feature work.

### Still Not Camel-Backed (By Design)

| Component | Reason |
|-----------|--------|
| InMemoryBackend | Test infrastructure — must work without CamelContext |
| DebugManager thread blocking | CompletableFuture.get() is the correct pattern |
| Flow model (Trigger/Step) | Undefined — new feature, not migration |

### Verification

- `./gradlew :core:test` — all tests pass
- `./gradlew compileKotlin` — all modules compile clean
- No `java.net.http` imports remain in management module
- No `io.undertow` direct imports remain (Undertow used only via camel-undertow)
- No string-interpolated SQL values in PostgresBackend
