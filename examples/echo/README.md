# Echo Service

Simple echo service — returns what you send it. Demonstrates the basic Integration DSL pattern: spec-first API definition, handler wiring, automatic logging, metrics, and debug mode.

## Quick Start

```bash
# Start the service
./gradlew :examples:echo:run

# Start with debug mode enabled
INTEGRATION_DEBUG=true ./gradlew :examples:echo:run
```

The service starts on **port 5400**.

## API Endpoints

### POST /echo

Echoes back the request message with metadata.

```bash
curl -s -X POST http://localhost:5400/echo \
  -H 'Content-Type: application/json' \
  -d '{"message": "hello world"}'
```

Expected response:

```json
{
  "message": "hello world",
  "timestamp": "2026-02-23T16:52:19.534Z",
  "source": "echo-service"
}
```

### GET /health

Returns service health and uptime.

```bash
curl -s http://localhost:5400/health
```

Expected response:

```json
{
  "status": "ok",
  "uptime": "0h 2m 15s"
}
```

### GET /openapi.json

Returns the OpenAPI spec that defines this service.

```bash
curl -s http://localhost:5400/openapi.json
```

### GET /metrics

Prometheus metrics — JVM stats, per-operation call counts and durations.

```bash
curl -s http://localhost:5400/metrics
```

Key metrics:

```
integration_calls_total{integration="echo-service",operation="echo",status="200",status_class="2xx"} 3.0
integration_call_duration_seconds_count{integration="echo-service",operation="echo"} 3
integration_call_duration_seconds_sum{integration="echo-service",operation="echo"} 0.025
jvm_memory_used_bytes{area="heap"} ...
process_uptime_seconds ...
```

## Logging

Every request is automatically logged with an **end-to-end trace ID**. The same 8-character hex ID appears in:
- All log lines for that request
- The `X-Trace-Id` response header
- The debug trace API (when debug is enabled)

### Startup logs

```
20:30:00.123 INFO  [main] echo-service --------- — Starting integration: echo-service v1
20:30:00.124 INFO  [main] echo-service --------- —   Simple echo service — returns what you send it
20:30:00.125 INFO  [main] echo-service --------- — Exposed API on http://0.0.0.0:5400
20:30:00.125 INFO  [main] echo-service --------- —   OpenAPI spec: http://0.0.0.0:5400/openapi.json
20:30:00.126 INFO  [main] echo-service --------- —   Metrics:      http://0.0.0.0:5400/metrics
20:30:00.126 INFO  [main] echo-service --------- —   OK POST /echo (echo)
20:30:00.126 INFO  [main] echo-service --------- —   OK GET /health (health)
```

### Per-request logs

```
20:30:10.100 INFO  [XNIO-1 task-2] echo-service a44dfc72 — POST /echo — request received
20:30:10.102 DEBUG [XNIO-1 task-2] echo-service a44dfc72 — POST /echo — body: {message=hello world}
20:30:10.110 INFO  [XNIO-1 task-2] echo-service a44dfc72 — POST /echo — 200 in 10.3ms
```

The trace ID `a44dfc72` is also returned in the response header:

```
X-Trace-Id: a44dfc72
```

## Debug Mode

Start with `INTEGRATION_DEBUG=true` to enable traces, breakpoints, and step-through debugging.

### View execution traces

Every request is recorded with full request/response data:

```bash
# List recent traces
curl -s http://localhost:5400/debug/traces | python3 -m json.tool

# Get a specific trace by ID (same as X-Trace-Id header)
curl -s http://localhost:5400/debug/traces/a44dfc72 | python3 -m json.tool
```

Example trace:

```json
{
  "id": "a44dfc72",
  "operationId": "echo",
  "method": "POST",
  "path": "/echo",
  "status": "COMPLETED",
  "durationMs": 10.3,
  "request": { "body": { "message": "hello world" } },
  "response": { "body": { "message": "hello world", "timestamp": "...", "source": "echo-service" }, "statusCode": 200 }
}
```

### Set breakpoints and pause requests

```bash
# Set a breakpoint before the echo handler runs
curl -s -X POST http://localhost:5400/debug/breakpoints \
  -H 'Content-Type: application/json' \
  -d '{"operationId":"echo","phase":"BEFORE_HANDLER"}'
```

Response:

```json
{
  "id": "33db0891",
  "operationId": "echo",
  "phase": "BEFORE_HANDLER",
  "enabled": true
}
```

### Pause, inspect, and inject data

Full workflow — pause a request, modify its data, and resume:

```bash
# 1. Send a request (it will pause at the breakpoint)
curl -s -X POST http://localhost:5400/echo \
  -H 'Content-Type: application/json' \
  -d '{"message": "original message"}' &

# 2. Check paused sessions
curl -s http://localhost:5400/debug/sessions | python3 -m json.tool
```

```json
[{
  "id": "d0ec7562",
  "operationId": "echo",
  "phase": "BEFORE_HANDLER",
  "currentData": { "message": "original message" }
}]
```

```bash
# 3. Inject modified data and resume
curl -s -X POST http://localhost:5400/debug/sessions/d0ec7562/modify \
  -H 'Content-Type: application/json' \
  -d '{"message": "INJECTED by debugger"}'
```

The original request now returns the injected message:

```json
{
  "message": "INJECTED by debugger",
  "timestamp": "2026-02-23T...",
  "source": "echo-service"
}
```

### Other debug actions

```bash
# Resume without modification
curl -s -X POST http://localhost:5400/debug/sessions/{id}/resume

# Abort the request (returns 499 to caller)
curl -s -X POST http://localhost:5400/debug/sessions/{id}/abort

# Clear all breakpoints
curl -s -X POST http://localhost:5400/debug/breakpoints/clear

# Clear all traces
curl -s -X DELETE http://localhost:5400/debug/traces

# Debug overview (counts)
curl -s http://localhost:5400/debug
```

## Project Structure

```
examples/echo/
├── specs/
│   └── echo-openapi.yaml              # API spec (written first)
├── src/main/kotlin/.../echo/
│   ├── EchoIntegration.kt             # DSL wiring + main()
│   └── flows/
│       ├── EchoHandler.kt             # POST /echo handler
│       └── HealthHandler.kt           # GET /health handler
└── build.gradle.kts
```

## DSL Overview

```kotlin
val echoIntegration = integration("echo-service") {
    version = 1
    description = "Simple echo service — returns what you send it"

    val api = spec("examples/echo/specs/echo-openapi.yaml")
    val gw = expose(api, port = 5400)

    gw["echo"] = echoHandler
    gw["health"] = healthHandler
}
```

Handlers are defined in separate files (1 flow = 1 file):

```kotlin
val echoHandler = handler { req ->
    respond(req.body) {
        "message" to "message"
        "timestamp" set now()
        "source" set "echo-service"
    }
}
```
