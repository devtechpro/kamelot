import { Hono } from 'hono'
import { cors } from 'hono/cors'
import { logger } from 'hono/logger'
import projects from './routes/projects'
import chat from './routes/chat'

const app = new Hono()

app.use('*', cors())
app.use('*', logger())

app.route('/api/projects', projects)
app.route('/api/chat', chat)

app.get('/api/health', (c) => c.json({ status: 'ok' }))

// Proxy test requests to the running integration
app.post('/api/test-endpoint', async (c) => {
  const { port: targetPort, method, path } = await c.req.json<{ port: number; method: string; path: string }>()
  try {
    const url = `http://localhost:${targetPort}${path}`
    const res = await fetch(url, { method })
    let body: string
    try {
      const json = await res.json()
      body = JSON.stringify(json, null, 2)
    } catch {
      body = await res.text()
    }
    return c.json({ status: res.status, body })
  } catch (err: any) {
    return c.json({ status: 0, body: `Connection failed: ${err.message}` })
  }
})

const port = 5531
console.log(`Studio BFF running on http://localhost:${port}`)

export default {
  port,
  fetch: app.fetch,
}
