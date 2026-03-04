import SwaggerParser from '@apidevtools/swagger-parser'
import type { ParsedSpec, OperationDef, SchemaDef, FieldDef, ParameterDef } from '../types'

export async function parseOpenApiSpec(content: string): Promise<ParsedSpec> {
  const yaml = await import('yaml')
  const doc = yaml.parse(content)

  // Parse raw doc first to extract $ref names before they're resolved
  const raw = structuredClone(doc)
  // Then validate (which also dereferences)
  const api = await SwaggerParser.validate(doc) as any

  const operations: OperationDef[] = []
  const paths = api.paths || {}

  for (const [path, pathItem] of Object.entries(paths) as any[]) {
    const methods = ['get', 'post', 'put', 'patch', 'delete'] as const
    for (const method of methods) {
      const op = pathItem[method]
      if (!op || !op.operationId) continue

      const parameters: ParameterDef[] = [
        ...(pathItem.parameters || []),
        ...(op.parameters || []),
      ].map((p: any) => ({
        name: p.name,
        in: p.in,
        required: p.required || false,
        type: p.schema?.type || 'string',
      }))

      // Extract $ref names from the raw (unresolved) spec
      const rawOp = raw.paths?.[path]?.[method]
      const requestSchema = rawOp?.requestBody
        ?.content?.['application/json']
        ?.schema?.$ref?.split('/')?.pop()
        || op.requestBody?.content?.['application/json']?.schema?.title
        || undefined

      const responseSchema = findResponseSchema(rawOp?.responses, op.responses)

      operations.push({
        operationId: op.operationId,
        method: method.toUpperCase(),
        path,
        summary: op.summary,
        requestSchema,
        responseSchema,
        parameters,
      })
    }
  }

  const schemas: SchemaDef[] = []
  const components = api.components?.schemas || {}
  for (const [name, schema] of Object.entries(components) as any[]) {
    const fields: FieldDef[] = []
    for (const [fieldName, fieldSchema] of Object.entries(schema.properties || {}) as any[]) {
      fields.push({
        name: fieldName,
        type: fieldSchema.type || 'string',
        format: fieldSchema.format,
        description: fieldSchema.description,
      })
    }
    schemas.push({
      name,
      fields,
      required: schema.required || [],
    })
  }

  return {
    title: api.info?.title || 'Untitled',
    operations,
    schemas,
  }
}

function findResponseSchema(rawResponses: any, resolvedResponses: any): string | undefined {
  // Try raw first (has $ref), fall back to resolved (has title)
  for (const responses of [rawResponses, resolvedResponses]) {
    if (!responses) continue
    for (const code of ['200', '201', '2XX', 'default']) {
      const schema = responses[code]?.content?.['application/json']?.schema
      if (schema?.$ref) return schema.$ref.split('/').pop()
      if (schema?.title) return schema.title
      if (schema?.items?.$ref) return schema.items.$ref.split('/').pop()
    }
  }
  return undefined
}
