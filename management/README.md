# Management

Multi-integration orchestration with agents, artifact deployment, and lifecycle control.

## Usage

```kotlin
fun main() = manage(port = 9000) {
    localAgent("services")
    deploy(EchoFactory())
    deploy(EnrichmentFactory())
    remoteAgent("payments", "http://payments:8081")
}
```

Run with:

```bash
./gradlew :examples:managed:run
```

## REST API

| Endpoint | Description |
|----------|-------------|
| `POST /mgmt/artifacts` | Upload JAR artifact |
| `GET /mgmt/artifacts` | List artifacts |
| `POST /mgmt/deployments` | Deploy to agent |
| `GET /mgmt/deployments` | List deployments |
| `POST /mgmt/deployments/{id}/start` | Start integration |
| `POST /mgmt/deployments/{id}/stop` | Stop integration |
| `DELETE /mgmt/deployments/{id}` | Undeploy |
| `GET /mgmt/health` | Aggregate health |
| `GET /mgmt/events` | Event audit log |

## Agents

- **LocalAgentConnection** — in-JVM, same process as management plane
- **RemoteAgentConnection** — separate JVM, communicates via HTTP agent API

## Deployment Modes

| Mode | Method | Use Case |
|------|--------|----------|
| Factory | `deploy(MyFactory())` | Multi-integration apps |
| Inline | `deployInline("name") { }` | Development |
| JAR | REST API upload | Production, CI/CD |
