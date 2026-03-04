# User Enrichment Service

Multi-step integration that calls an external API (JSONPlaceholder), maps fields to a different schema, and returns enriched data. Demonstrates adapters, field mapping, and the full debug workflow with data injection.

## Quick Start

```bash
# Start the service
./gradlew :examples:enrichment:run

# Start with debug mode enabled
INTEGRATION_DEBUG=true ./gradlew :examples:enrichment:run
```

The service starts on **port 5401**. Requires internet access (calls jsonplaceholder.typicode.com).

## API Endpoints

### POST /enrich

Enriches a user by ID. Fetches the user from JSONPlaceholder's `/users/{id}` endpoint and maps the fields to our schema.

```bash
curl -s -X POST http://localhost:5401/enrich \
  -H 'Content-Type: application/json' \
  -d '{"userId": 1}'
```

Expected response:

```json
{
  "fullName": "Leanne Graham",
  "emailAddress": "Sincere@april.biz",
  "phone": "1-770-736-8031 x56442",
  "city": "Gwenborough",
  "company": "Romaguera-Crona",
  "website": "hildegard.org",
  "source": "jsonplaceholder",
  "enrichedAt": "2026-02-23T20:30:52.114Z"
}
```

Try different user IDs (1-10 are available):

```bash
curl -s -X POST http://localhost:5401/enrich \
  -H 'Content-Type: application/json' \
  -d '{"userId": 5}' | python3 -m json.tool
```

### Field Mapping

The handler maps fields from JSONPlaceholder's schema to our enrichment schema:

| External field (JSONPlaceholder) | Our field (EnrichResponse) |
|----------------------------------|---------------------------|
| `name`                           | `fullName`                |
| `email`                          | `emailAddress`            |
| `phone`                          | `phone`                   |
| `address.city` (nested)          | `city`                    |
| `company.name` (nested)          | `company`                 |
| `website`                        | `website`                 |
| _(added)_                        | `source`                  |
| _(added)_                        | `enrichedAt`              |

### GET /openapi.json

```bash
curl -s http://localhost:5401/openapi.json
```

### GET /metrics

```bash
curl -s http://localhost:5401/metrics
```

Key metrics:

```
integration_calls_total{integration="user-enrichment",operation="enrich",status="200",status_class="2xx"} 5.0
integration_call_duration_seconds_sum{integration="user-enrichment",operation="enrich"} 0.892
```

## Logging

Every request gets an end-to-end trace ID that appears in all log lines and the `X-Trace-Id` response header.

### Startup logs

```
20:30:00.123 INFO  [main] user-enrichment --------- — Starting integration: user-enrichment v1
20:30:00.124 INFO  [main] user-enrichment --------- —   Enriches user data from JSONPlaceholder
20:30:00.125 INFO  [main] user-enrichment --------- — Exposed API on http://0.0.0.0:5401
20:30:00.125 INFO  [main] user-enrichment --------- —   OpenAPI spec: http://0.0.0.0:5401/openapi.json
20:30:00.126 INFO  [main] user-enrichment --------- —   Metrics:      http://0.0.0.0:5401/metrics
20:30:00.126 INFO  [main] user-enrichment --------- —   Debug API:    http://0.0.0.0:5401/debug
20:30:00.126 INFO  [main] user-enrichment --------- —   Debug mode is ENABLED
20:30:00.127 INFO  [main] user-enrichment --------- —   OK POST /enrich (enrich)
```

### Per-request logs

```
20:30:52.100 INFO  [XNIO-1 task-2] user-enrichment 1e27544e — POST /enrich — request received
20:30:52.102 DEBUG [XNIO-1 task-2] user-enrichment 1e27544e — POST /enrich — body: {userId=1}
20:30:52.450 INFO  [XNIO-1 task-2] user-enrichment 1e27544e — POST /enrich — 200 in 350.2ms
```

Note: response time includes the external API call latency (~200-400ms for JSONPlaceholder).

## Debug Mode

Start with `INTEGRATION_DEBUG=true` to enable the full debug workflow.

### View execution traces

```bash
curl -s http://localhost:5401/debug/traces | python3 -m json.tool
```

Example trace showing the enrichment flow:

```json
{
  "id": "1e27544e",
  "operationId": "enrich",
  "method": "POST",
  "path": "/enrich",
  "status": "COMPLETED",
  "durationMs": 350.2,
  "request": {
    "body": { "userId": 1 }
  },
  "response": {
    "body": {
      "fullName": "Leanne Graham",
      "emailAddress": "Sincere@april.biz",
      "city": "Gwenborough",
      "company": "Romaguera-Crona",
      "source": "jsonplaceholder"
    },
    "statusCode": 200
  }
}
```

### Showcase: Debug Injection Workflow

This is the key demo — pause a request, change the input data, and observe how the output changes. The original caller sent `userId: 1` but we inject `userId: 3`, proving the debug system can modify data mid-flight.

**Step 1: Set a breakpoint**

```bash
curl -s -X POST http://localhost:5401/debug/breakpoints \
  -H 'Content-Type: application/json' \
  -d '{"operationId":"enrich","phase":"BEFORE_HANDLER"}'
```

```json
{
  "id": "2f664624",
  "operationId": "enrich",
  "phase": "BEFORE_HANDLER",
  "enabled": true
}
```

**Step 2: Send a request (it will pause)**

```bash
# Run in background — the request will block at the breakpoint
curl -s -X POST http://localhost:5401/enrich \
  -H 'Content-Type: application/json' \
  -d '{"userId": 1}' &
```

**Step 3: Inspect the paused session**

```bash
curl -s http://localhost:5401/debug/sessions | python3 -m json.tool
```

```json
[{
  "id": "2ebc8101",
  "traceId": "1e27544e",
  "operationId": "enrich",
  "phase": "BEFORE_HANDLER",
  "pausedAt": "2026-02-23T20:31:09.548Z",
  "currentData": {
    "userId": 1
  }
}]
```

You can see the request body (`userId: 1`) before the handler runs — before the external API is called.

**Step 4: Inject modified data**

Change `userId` from 1 to 3:

```bash
curl -s -X POST http://localhost:5401/debug/sessions/2ebc8101/modify \
  -H 'Content-Type: application/json' \
  -d '{"userId": 3}'
```

```json
{ "modified": true, "resumed": true }
```

**Step 5: Observe the result**

The original request (from step 2) now returns data for user 3, not user 1:

```json
{
  "fullName": "Clementine Bauch",
  "emailAddress": "Nathan@yesenia.net",
  "phone": "1-463-123-4447",
  "city": "McKenziehaven",
  "company": "Romaguera-Jacobson",
  "website": "ramiro.info",
  "source": "jsonplaceholder",
  "enrichedAt": "2026-02-23T20:31:21.909Z"
}
```

**Step 6: Verify in trace**

```bash
curl -s http://localhost:5401/debug/traces | python3 -m json.tool
```

The trace shows the original request body (`userId: 1`) but the response contains user 3's data — proving the injection worked.

### Other debug operations

```bash
# Resume a paused session without modification
curl -s -X POST http://localhost:5401/debug/sessions/{id}/resume

# Abort a paused request (returns 499 to caller)
curl -s -X POST http://localhost:5401/debug/sessions/{id}/abort

# List all breakpoints
curl -s http://localhost:5401/debug/breakpoints

# Remove a specific breakpoint
curl -s -X DELETE http://localhost:5401/debug/breakpoints/{id}

# Clear all breakpoints
curl -s -X POST http://localhost:5401/debug/breakpoints/clear

# Clear all traces
curl -s -X DELETE http://localhost:5401/debug/traces

# Debug overview
curl -s http://localhost:5401/debug
```

## Adapter Architecture

The external API call is modeled as an **adapter** — a typed connection to an external service. Every adapter requires an OpenAPI spec (the interface contract).

```kotlin
val usersApi = adapter("jsonplaceholder", spec("specs/jsonplaceholder-openapi.yaml")) {
    baseUrl = "https://jsonplaceholder.typicode.com"
}
```

We wrote the JSONPlaceholder spec ourselves (`specs/jsonplaceholder-openapi.yaml`) because the external API doesn't provide one. This enforces a typed contract: we know exactly what fields the API provides and can validate against them.

The adapter is then used in handlers:

```kotlin
fun enrichHandler(usersApi: AdapterRef) = handler { req ->
    val userId = req.body["userId"]
    val user = usersApi.get("/users/$userId")       // HTTP GET via adapter

    respond(user) {
        "fullName" to "name"                         // field mapping: user["name"] → fullName
        "city" set user.nested("address.city")       // nested dot-path access
        "company" set user.nested("company.name")    // nested dot-path access
    }
}
```

The `to` operator maps a source field to a response field. The `set` operator assigns an explicit value. The `nested()` function supports dot-notation for accessing nested fields in the API response (e.g., `address.city` extracts `city` from the `address` object).

## Project Structure

```
examples/enrichment/
├── specs/
│   ├── enrichment-openapi.yaml         # Our exposed API (written first)
│   └── jsonplaceholder-openapi.yaml    # External API spec (authored by us)
├── src/main/kotlin/.../enrichment/
│   ├── EnrichmentIntegration.kt        # DSL wiring + main()
│   └── flows/
│       └── EnrichHandler.kt           # Multi-step handler
└── build.gradle.kts
```

## DSL Overview

```kotlin
val enrichmentIntegration = integration("user-enrichment") {
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
