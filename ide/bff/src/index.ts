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

const port = 5531
console.log(`Studio BFF running on http://localhost:${port}`)

export default {
  port,
  fetch: app.fetch,
}
