import type { Project } from '../types'
import { generateDsl } from '../codegen/dsl-generator'

export function buildSystemPrompt(project: Project, projectFilePath: string): string {
  const sections: string[] = []

  sections.push(`You are an AI assistant embedded in Kamelot, an integration platform IDE built on Apache Camel.
You help users create and modify integration projects through natural language.

## How to Make Changes

The project is stored as a JSON file at: ${projectFilePath}
To modify the project, use the Read tool to inspect it, then the Edit tool to update specific sections.
After making changes, briefly confirm what you did.

The JSON structure follows this schema:
- "specs": array of uploaded OpenAPI specs (don't modify these directly)
- "expose": { "specIds": string[], "port": number, "host": string } — which specs are exposed as REST endpoints
- "adapters": array of connectors — each has { "id", "name", "type" (http|postgres|in-memory), "baseUrl?", "specId?", "postgres?" }
- "flows": array — each has { "operationId", "steps": StepConfig[], "statusCode?" }

### Step Types (in the "steps" array)
- process: { "type": "process", "name": string, "expression": string }
- call: { "type": "call", "adapterName": string, "method": "GET"|"POST"|"PUT"|"PATCH"|"DELETE", "path": string }
- log: { "type": "log", "message": string, "level": "DEBUG"|"INFO"|"WARN"|"ERROR" }
- respond: { "type": "respond", "fields": [{ "key": string, "value": string, "mode": "to"|"set" }] }
- map: { "type": "map", "fields": [{ "key": string, "value": string, "mode": "to"|"set" }] }

### Field Mapping Modes
- "to": Map from source field — "targetKey" to "sourceField"
- "set": Set a literal or expression — expressions: req.param("x"), body["field"], now(), uuid(), env("KEY")

### Adapter IDs
When adding new adapters or flows, generate a short 8-character ID (like "a1b2c3d4").

### Important Rules
- To expose a spec: add its id to "expose.specIds" and create flow stubs for each operation
- To add a flow: add to "flows" array with the operationId matching an exposed spec operation
- Always update "updatedAt" to current ISO timestamp when making changes
- Keep the JSON valid — do not break the structure`)

  // Current project state
  sections.push(`## Current Project: "${project.name}"
${project.description ? `Description: ${project.description}` : ''}
Version: ${project.version}
File: ${projectFilePath}`)

  // Specs
  if (project.specs.length > 0) {
    const specLines = project.specs.map(s => {
      const ops = s.parsed.operations.map(o => `  - ${o.method.toUpperCase()} ${o.path} (${o.operationId})${o.summary ? ` — ${o.summary}` : ''}`)
      const schemas = s.parsed.schemas.map(sc => `  - ${sc.name}: { ${sc.fields.map(f => `${f.name}: ${f.type}`).join(', ')} }`)
      return `### Spec: ${s.filename} (id: ${s.id})
Title: ${s.parsed.title}
Operations:\n${ops.join('\n')}
Schemas:\n${schemas.join('\n')}`
    })
    sections.push(specLines.join('\n\n'))
  } else {
    sections.push('No specs uploaded yet.')
  }

  // Expose config
  if (project.expose?.specIds.length) {
    const exposed = project.specs.filter(s => project.expose!.specIds.includes(s.id))
    sections.push(`## Exposed Specs
Port: ${project.expose.port}, Host: ${project.expose.host}
Specs: ${exposed.map(s => s.filename).join(', ')}`)
  } else {
    sections.push('No specs exposed yet. To expose a spec, edit the project JSON and add the spec id to expose.specIds, and create empty flow stubs for each operation.')
  }

  // Adapters
  if (project.adapters.length > 0) {
    const adapterLines = project.adapters.map(a => {
      let detail = `type: ${a.type}`
      if (a.type === 'http' && a.baseUrl) detail += `, baseUrl: ${a.baseUrl}`
      if (a.type === 'postgres' && a.postgres) detail += `, table: ${a.postgres.table}`
      return `- ${a.name} (id: ${a.id}) — ${detail}`
    })
    sections.push(`## Adapters\n${adapterLines.join('\n')}`)
  } else {
    sections.push('No adapters configured yet.')
  }

  // Flows
  if (project.flows.length > 0) {
    const flowLines = project.flows.map(f => {
      const steps = f.steps.length === 0
        ? '  (empty — no steps)'
        : f.steps.map((s, i) => `  ${i}: [${s.type}] ${stepSummary(s)}`).join('\n')
      return `### Flow: ${f.operationId}${f.statusCode ? ` (status: ${f.statusCode})` : ''}\n${steps}`
    })
    sections.push(`## Flows\n${flowLines.join('\n\n')}`)
  } else {
    sections.push('No flows yet. Expose a spec first, then create flow stubs for each operation.')
  }

  // Generated DSL
  try {
    const dsl = generateDsl(project)
    if (dsl.trim()) {
      sections.push(`## Generated Kotlin DSL\n\`\`\`kotlin\n${dsl}\n\`\`\``)
    }
  } catch {
    // skip if generation fails
  }

  return sections.join('\n\n')
}

function stepSummary(step: import('../types').StepConfig): string {
  switch (step.type) {
    case 'process': return `"${step.name}" — ${step.expression || '(empty)'}`
    case 'call': return `${step.method} ${step.path} via ${step.adapterName}`
    case 'log': return `${step.level}: "${step.message}"`
    case 'respond': return `${step.fields.length} field(s)`
    case 'map': return `${step.fields.length} mapping(s)`
  }
}
