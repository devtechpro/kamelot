# Runtime

Standalone entry point for running a single integration in one JVM.

## Usage

```kotlin
fun main() = execute("echo-service") {
    val api = spec("specs/echo-openapi.yaml")
    expose(api, port = 5400)
    flow("echo", echoHandler)
}
```

Run with:

```bash
./gradlew :examples:echo:run
```

## What It Does

`execute()` builds the `Integration` model from the DSL, creates an `IntegrationRuntime` (Camel Main wrapper), and starts it blocking. The runtime configures:

- `ExposeRouteGenerator` — REST endpoints from the exposed spec
- `FlowRouteGenerator` — timer/cron triggered flows
- ProducerTemplate binding for Camel-managed adapter backends
- Prometheus metrics via Micrometer
- Debug mode when `INTEGRATION_DEBUG=true`
