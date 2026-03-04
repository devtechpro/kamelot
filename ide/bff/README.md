# IDE BFF

Backend-for-frontend for the Studio web UI. Bun + Hono REST API.

## Running

```bash
cd ide/bff
bun install
bun run dev     # watch mode on port 5531
```

## API

| Endpoint | Description |
|----------|-------------|
| `GET /api/projects` | List projects |
| `POST /api/projects` | Create project |
| `PUT /api/projects/:id` | Update project |
| `DELETE /api/projects/:id` | Delete project |
| `POST /api/projects/:id/specs` | Upload OpenAPI spec |
| `DELETE /api/projects/:id/specs/:specId` | Delete spec |
| `POST /api/projects/:id/test-connector` | Test adapter connection |
| `POST /api/projects/:id/generate-dsl` | Generate Kotlin DSL |

## Stack

- [Hono](https://hono.dev) — HTTP framework
- [@apidevtools/swagger-parser](https://github.com/APIDevTools/swagger-parser) — OpenAPI validation
- File-based project storage in `data/projects/`
