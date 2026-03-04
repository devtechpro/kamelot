import type { Project, FlowConfig, StepConfig, AdapterConfig, RespondField } from '../types'

export function generateDsl(project: Project): string {
  const lines: string[] = []
  const indent = (n: number) => '    '.repeat(n)

  lines.push(`fun main() = execute("${project.name}") {`)
  lines.push(`${indent(1)}version = ${project.version}`)
  if (project.description) {
    lines.push(`${indent(1)}description = "${esc(project.description)}"`)
  }
  lines.push('')

  // Spec + expose
  const exposedSpecIds = project.expose?.specIds ?? []
  const exposedSpecs = project.specs.filter(s => exposedSpecIds.includes(s.id))
  if (exposedSpecs.length > 0) {
    exposedSpecs.forEach((spec, i) => {
      const varName = exposedSpecs.length === 1 ? 'api' : `api${i + 1}`
      lines.push(`${indent(1)}val ${varName} = spec("specs/${spec.filename}")`)
    })
    const specVars = exposedSpecs.length === 1 ? 'api' : exposedSpecs.map((_, i) => `api${i + 1}`).join(', ')
    lines.push(`${indent(1)}expose(${specVars}, port = ${project.expose?.port || 8080})`)
    lines.push('')
  }

  // Adapters (connectors)
  for (const adapter of project.adapters) {
    const boundSpec = project.specs.find(s => s.id === adapter.specId)
    lines.push(...generateAdapter(adapter, boundSpec))
    lines.push('')
  }

  // Flows
  for (const flow of project.flows) {
    lines.push(...generateFlow(flow, project.adapters))
    lines.push('')
  }

  lines.push('}')
  return lines.join('\n')
}

function generateAdapter(adapter: AdapterConfig, boundSpec?: { filename: string }): string[] {
  const lines: string[] = []
  const indent = (n: number) => '    '.repeat(n)
  const varName = toCamelCase(adapter.name)
  const specPath = boundSpec ? `specs/${boundSpec.filename}` : `specs/${adapter.name}-spec.yaml`

  if (adapter.type === 'http') {
    lines.push(`${indent(1)}val ${varName} = adapter("${adapter.name}", spec("${specPath}")) {`)
    if (adapter.baseUrl) {
      lines.push(`${indent(2)}baseUrl = "${esc(adapter.baseUrl)}"`)
    }
    lines.push(`${indent(1)}}`)
  } else if (adapter.type === 'postgres') {
    const pg = adapter.postgres
    lines.push(`${indent(1)}val ${varName} = adapter("${adapter.name}", spec("${specPath}")) {`)
    lines.push(`${indent(2)}postgres {`)
    if (pg?.url) lines.push(`${indent(3)}url = env("POSTGRES_URL", "${esc(pg.url)}")`)
    if (pg?.username) lines.push(`${indent(3)}username = env("POSTGRES_USER", "${esc(pg.username)}")`)
    lines.push(`${indent(3)}password = secret("POSTGRES_PASSWORD", "postgres")`)
    if (pg?.table) lines.push(`${indent(3)}table = "${esc(pg.table)}"`)
    if (pg?.schema) lines.push(`${indent(3)}schema = "${esc(pg.schema)}"`)
    lines.push(`${indent(2)}}`)
    lines.push(`${indent(1)}}`)
  } else {
    lines.push(`${indent(1)}val ${varName} = adapter("${adapter.name}", spec("${specPath}")) {`)
    lines.push(`${indent(2)}inMemory()`)
    lines.push(`${indent(1)}}`)
  }

  return lines
}

function generateFlow(flow: FlowConfig, adapters: AdapterConfig[]): string[] {
  const lines: string[] = []
  const indent = (n: number) => '    '.repeat(n)

  // Single respond step with no other steps → respond builder
  if (flow.steps.length === 1 && flow.steps[0].type === 'respond') {
    const step = flow.steps[0] as { type: 'respond'; fields: RespondField[] }
    lines.push(`${indent(1)}flow("${flow.operationId}") {`)
    lines.push(`${indent(2)}respond {`)
    for (const f of step.fields) {
      lines.push(`${indent(3)}${respondField(f)}`)
    }
    lines.push(`${indent(2)}}`)
    lines.push(`${indent(1)}}`)
    return lines
  }

  // Multi-step declarative flow
  const hasNonRespondSteps = flow.steps.some(s => s.type !== 'respond')
  if (hasNonRespondSteps || flow.steps.length > 1) {
    lines.push(`${indent(1)}flow("${flow.operationId}") {`)
    if (flow.statusCode && flow.statusCode !== 200) {
      lines.push(`${indent(2)}statusCode = ${flow.statusCode}`)
    }
    for (const step of flow.steps) {
      lines.push(...generateStep(step, adapters, 2))
    }
    lines.push(`${indent(1)}}`)
    return lines
  }

  // Empty flow
  lines.push(`${indent(1)}flow("${flow.operationId}") {`)
  lines.push(`${indent(2)}respond {}`)
  lines.push(`${indent(1)}}`)
  return lines
}

function generateStep(step: StepConfig, adapters: AdapterConfig[], depth: number): string[] {
  const indent = (n: number) => '    '.repeat(n)
  const lines: string[] = []

  switch (step.type) {
    case 'process':
      lines.push(`${indent(depth)}process("${esc(step.name)}") { body ->`)
      if (step.expression.trim()) {
        lines.push(`${indent(depth + 1)}${step.expression.trim()}`)
      } else {
        lines.push(`${indent(depth + 1)}body`)
      }
      lines.push(`${indent(depth)}}`)
      break

    case 'call': {
      const varName = toCamelCase(step.adapterName)
      lines.push(`${indent(depth)}call(${varName}, HttpMethod.${step.method}, "${esc(step.path)}")`)
      break
    }

    case 'log':
      lines.push(`${indent(depth)}log("${esc(step.message)}"${step.level !== 'INFO' ? `, LogLevel.${step.level}` : ''})`)
      break

    case 'respond': {
      lines.push(`${indent(depth)}respond {`)
      for (const f of step.fields) {
        lines.push(`${indent(depth + 1)}${respondField(f)}`)
      }
      lines.push(`${indent(depth)}}`)
      break
    }

    case 'map': {
      lines.push(`${indent(depth)}map {`)
      for (const f of step.fields) {
        lines.push(`${indent(depth + 1)}${respondField(f)}`)
      }
      lines.push(`${indent(depth)}}`)
      break
    }
  }

  return lines
}

/**
 * Generate a respond field line directly from the field's mode.
 * `to` → map source field: `"key" to "sourceField"`
 * `set` → assign value: `"key" set value`
 */
function respondField(f: RespondField): string {
  if (f.mode === 'to') {
    return `"${esc(f.key)}" to "${esc(f.value)}"`
  }
  return `"${esc(f.key)}" set ${resolveValue(f.value)}`
}

function resolveValue(value: string): string {
  // DSL function calls and expressions — pass through as-is
  if (value.startsWith('req.') || value.startsWith('body.') || value.startsWith('body[')) {
    return value
  }
  if (value.match(/^(now|uuid|today|slugify|env|secret)\(/)) {
    return value
  }
  // Plain string literal
  return `"${esc(value)}"`
}

function toCamelCase(name: string): string {
  return name.replace(/[-_](\w)/g, (_, c) => c.toUpperCase())
}

function esc(s: string): string {
  return s.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
}
