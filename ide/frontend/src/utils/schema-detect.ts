import type { FlowConfig, SchemaDef, OperationDef, AdapterConfig, SpecFile } from '@/types'

export interface DetectedSchema {
  schema: SchemaDef | null
  label: string
  source: 'api-request' | 'api-response' | 'adapter' | 'unknown'
}

const UNKNOWN: DetectedSchema = { schema: null, label: 'Unknown', source: 'unknown' }

/**
 * Detect the input schema for a step (what comes from its left).
 */
export function detectLeftSchema(
  flow: FlowConfig,
  stepIndex: number,
  operation: OperationDef | undefined,
  adapters: AdapterConfig[],
  specs: SpecFile[],
): DetectedSchema {
  if (stepIndex === 0) {
    if (operation?.requestSchema) {
      const schema = findSchema(operation.requestSchema, specs)
      return { schema, label: `${operation.requestSchema} (request)`, source: 'api-request' }
    }
    return { schema: null, label: 'Request body', source: 'api-request' }
  }

  const prevStep = flow.steps[stepIndex - 1]
  if (prevStep.type === 'call') {
    return resolveAdapterSchema(prevStep.adapterName, prevStep.method, prevStep.path, 'response', adapters, specs)
  }

  // Skip through process/log/map — look further left
  if (prevStep.type === 'process' || prevStep.type === 'log') {
    return detectLeftSchema(flow, stepIndex - 1, operation, adapters, specs)
  }

  return UNKNOWN
}

/**
 * Detect the expected output schema for a step (what its right neighbor expects).
 */
export function detectRightSchema(
  flow: FlowConfig,
  stepIndex: number,
  operation: OperationDef | undefined,
  adapters: AdapterConfig[],
  specs: SpecFile[],
): DetectedSchema {
  const nextIndex = stepIndex + 1
  if (nextIndex >= flow.steps.length) {
    if (operation?.responseSchema) {
      const schema = findSchema(operation.responseSchema, specs)
      return { schema, label: `${operation.responseSchema} (response)`, source: 'api-response' }
    }
    return { schema: null, label: 'Response body', source: 'api-response' }
  }

  const nextStep = flow.steps[nextIndex]
  if (nextStep.type === 'call') {
    return resolveAdapterSchema(nextStep.adapterName, nextStep.method, nextStep.path, 'request', adapters, specs)
  }

  if (nextStep.type === 'respond') {
    if (operation?.responseSchema) {
      const schema = findSchema(operation.responseSchema, specs)
      return { schema, label: `${operation.responseSchema} (response)`, source: 'api-response' }
    }
    return { schema: null, label: 'Response body', source: 'api-response' }
  }

  // Skip through process/log — look further right
  if (nextStep.type === 'process' || nextStep.type === 'log') {
    return detectRightSchema(flow, nextIndex, operation, adapters, specs)
  }

  return UNKNOWN
}

function resolveAdapterSchema(
  adapterName: string,
  method: string,
  path: string,
  side: 'request' | 'response',
  adapters: AdapterConfig[],
  specs: SpecFile[],
): DetectedSchema {
  const adapter = adapters.find(a => a.name === adapterName)
  if (!adapter?.specId) {
    return { schema: null, label: `${adapterName}`, source: 'adapter' }
  }

  const adapterSpec = specs.find(s => s.id === adapter.specId)
  if (!adapterSpec) {
    return { schema: null, label: `${adapterName}`, source: 'adapter' }
  }

  const op = adapterSpec.parsed.operations.find(
    o => o.method === method && o.path === path
  )
  const schemaName = side === 'request' ? op?.requestSchema : op?.responseSchema
  if (schemaName) {
    const schema = adapterSpec.parsed.schemas.find(s => s.name === schemaName) ?? null
    return { schema, label: `${schemaName} (${adapterName})`, source: 'adapter' }
  }

  // Fallback: use the first schema with an "id" field (common for DB tables)
  if (adapterSpec.parsed.schemas.length > 0) {
    const schema = adapterSpec.parsed.schemas[0]
    return { schema, label: `${schema.name} (${adapterName})`, source: 'adapter' }
  }

  return { schema: null, label: `${adapterName}`, source: 'adapter' }
}

function findSchema(name: string, specs: SpecFile[]): SchemaDef | null {
  for (const spec of specs) {
    const schema = spec.parsed.schemas.find(s => s.name === name)
    if (schema) return schema
  }
  return null
}
