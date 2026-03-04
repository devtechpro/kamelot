# Core

DSL builders, model definitions, Camel route generation, spec engine, and adapter backends.

## Key Components

- **DSL Builders** — `IntegrationBuilder`, `FlowBuilder`, `AdapterBuilder`, `TriggeredFlowBuilder`
- **Model** — `Integration`, `Flow`, `Step`, `Adapter`, `Spec`, `Implementation`
- **Route Generators** — `ExposeRouteGenerator` (REST API), `FlowRouteGenerator` (timer/cron)
- **Spec Engine** — `OpenApiSchemaLoader` parses OpenAPI YAML/JSON into typed `Spec` models
- **Adapter Backends** — `CamelHttpBackend`, `PostgresBackend`, `InMemoryBackend`
- **Functions** — String, date, crypto, and collection helpers for handlers
- **Debug** — `DebugManager` for traces, breakpoints, and pause/resume
- **Metrics** — Prometheus via Micrometer with JVM and per-operation stats
