# IDE

Visual IDE for designing Kamelot integrations. Three components:

| Module | Stack | Port | Description |
|--------|-------|------|-------------|
| `studio/` | Kotlin/Compose + Camel | 5531 | Desktop app with embedded runtime |
| `bff/` | Bun + Hono (TypeScript) | 5531 | Backend-for-frontend REST API |
| `frontend/` | Vue 3 + Vite | 5173 | Web UI |

## Features

- Project tree with specs, adapters, and flows
- OpenAPI spec viewer and browser
- Flow editor with drag-and-drop step palette
- Field mapping panel (source → target)
- Database table/column browser
- Monaco code editor for DSL source view
- DSL code generation from visual model
- Run/stop integrations with live log output
