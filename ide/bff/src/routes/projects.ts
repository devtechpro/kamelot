import { Hono } from 'hono'
import { v4 as uuidv4 } from 'uuid'
import * as store from '../services/project-store'
import { parseOpenApiSpec } from '../services/spec-parser'
import { generateDsl } from '../codegen/dsl-generator'
import type { Project, SpecFile } from '../types'

const app = new Hono()

// List projects
app.get('/', async (c) => {
  const projects = await store.listProjects()
  return c.json(projects)
})

// Create project
app.post('/', async (c) => {
  const body = await c.req.json<{ name: string; description?: string }>()
  const project: Project = {
    id: uuidv4().substring(0, 8),
    name: body.name,
    version: 1,
    description: body.description,
    specs: [],
    adapters: [],
    flows: [],
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  }
  await store.saveProject(project)
  return c.json(project, 201)
})

// Get project
app.get('/:id', async (c) => {
  const project = await store.getProject(c.req.param('id'))
  if (!project) return c.json({ error: 'Not found' }, 404)
  return c.json(project)
})

// Update project
app.put('/:id', async (c) => {
  const existing = await store.getProject(c.req.param('id'))
  if (!existing) return c.json({ error: 'Not found' }, 404)
  const body = await c.req.json<Partial<Project>>()
  const updated = { ...existing, ...body, id: existing.id, createdAt: existing.createdAt }
  await store.saveProject(updated)
  return c.json(updated)
})

// Delete project
app.delete('/:id', async (c) => {
  const deleted = await store.deleteProject(c.req.param('id'))
  if (!deleted) return c.json({ error: 'Not found' }, 404)
  return c.json({ deleted: true })
})

// Upload spec
app.post('/:id/specs', async (c) => {
  const project = await store.getProject(c.req.param('id'))
  if (!project) return c.json({ error: 'Not found' }, 404)

  const body = await c.req.json<{ filename: string; content: string }>()
  let parsed
  try {
    parsed = await parseOpenApiSpec(body.content)
  } catch (err: any) {
    return c.json({ error: `Invalid OpenAPI spec: ${err.message}` }, 400)
  }

  const spec: SpecFile = {
    id: uuidv4().substring(0, 8),
    filename: body.filename,
    content: body.content,
    parsed,
  }

  project.specs.push(spec)

  // No auto-expose, no auto-flow-creation — spec goes to library only

  await store.saveProject(project)
  return c.json(spec, 201)
})

// Delete spec
app.delete('/:id/specs/:specId', async (c) => {
  const project = await store.getProject(c.req.param('id'))
  if (!project) return c.json({ error: 'Not found' }, 404)

  const specId = c.req.param('specId')
  const spec = project.specs.find(s => s.id === specId)
  if (!spec) return c.json({ error: 'Spec not found' }, 404)

  // Collect operation IDs unique to this spec
  const specOpIds = new Set(spec.parsed.operations.map(o => o.operationId))
  const otherSpecOpIds = new Set(
    project.specs
      .filter(s => s.id !== specId)
      .flatMap(s => s.parsed.operations.map(o => o.operationId))
  )
  const orphanedOps = new Set([...specOpIds].filter(id => !otherSpecOpIds.has(id)))

  project.specs = project.specs.filter(s => s.id !== specId)

  // Remove from expose.specIds if present
  if (project.expose?.specIds.includes(specId)) {
    project.expose = {
      ...project.expose,
      specIds: project.expose.specIds.filter(id => id !== specId),
    }
  }

  // Remove flows for orphaned operations
  project.flows = project.flows.filter(f => !orphanedOps.has(f.operationId))

  await store.saveProject(project)
  return c.json({ deleted: true })
})

// Test connector connection
app.post('/:id/connectors/test', async (c) => {
  const body = await c.req.json<{ type: string; postgres?: { url: string; username: string; password?: string } }>()
  if (body.type !== 'postgres') {
    return c.json({ ok: false, error: 'Only postgres test is supported' }, 400)
  }
  // BFF forwards to Kotlin backend when available; standalone BFF returns not-implemented
  return c.json({ ok: false, error: 'Test connection requires the Kotlin backend' })
})

// Generate DSL
app.get('/:id/dsl', async (c) => {
  const project = await store.getProject(c.req.param('id'))
  if (!project) return c.json({ error: 'Not found' }, 404)

  const dsl = generateDsl(project)
  return c.json({ dsl })
})

export default app
