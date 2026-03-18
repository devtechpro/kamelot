import { Hono } from 'hono'
import { streamSSE } from 'hono/streaming'
import { join } from 'node:path'
import * as store from '../services/project-store'
import { buildSystemPrompt } from '../services/ai-context'

const app = new Hono()

const CLAUDE_BIN = process.env.CLAUDE_BIN || 'claude'
const DATA_DIR = join(import.meta.dir, '../../data/projects')

// Check if chat is available (claude CLI exists)
app.get('/status', async (c) => {
  try {
    const proc = Bun.spawn([CLAUDE_BIN, '--version'], {
      stdout: 'pipe',
      stderr: 'pipe',
      env: { ...process.env, CLAUDECODE: '' },
    })
    await proc.exited
    return c.json({ available: proc.exitCode === 0 })
  } catch {
    return c.json({ available: false })
  }
})

// Chat endpoint with SSE streaming
app.post('/stream', async (c) => {
  const body = await c.req.json<{
    projectId: string
    messages: { role: 'user' | 'assistant'; content: string }[]
  }>()

  const project = await store.getProject(body.projectId)
  if (!project) {
    return c.json({ error: 'Project not found' }, 404)
  }

  const projectFile = join(DATA_DIR, `${project.id}.json`)
  const systemPrompt = buildSystemPrompt(project, projectFile)

  // Build prompt with conversation history
  let prompt = ''
  const msgs = body.messages
  if (msgs.length > 1) {
    prompt += 'Previous conversation:\n'
    for (const msg of msgs.slice(0, -1)) {
      prompt += `${msg.role === 'user' ? 'User' : 'Assistant'}: ${msg.content}\n`
    }
    prompt += '\nCurrent request:\n'
  }
  prompt += msgs[msgs.length - 1].content

  return streamSSE(c, async (stream) => {
    // Strip CLAUDECODE env var to allow nested invocation
    const env = { ...process.env }
    delete env.CLAUDECODE
    delete env.CLAUDE_CODE_SESSION

    const proc = Bun.spawn(
      [
        CLAUDE_BIN, '-p',
        '--output-format', 'stream-json',
        '--verbose',
        '--include-partial-messages',
        '--system-prompt', systemPrompt,
        '--max-turns', '10',
        '--allowed-tools', 'Read', 'Edit', 'Write',
        '--no-session-persistence',
      ],
      {
        stdin: 'pipe',
        stdout: 'pipe',
        stderr: 'pipe',
        env,
      },
    )

    // Send prompt via stdin
    proc.stdin.write(prompt)
    proc.stdin.end()

    // Read stdout as stream, parse JSONL
    const reader = proc.stdout.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (!line.trim()) continue
          try {
            const event = JSON.parse(line)

            // Token-level streaming: content_block_delta with text
            if (event.type === 'stream_event' && event.event?.type === 'content_block_delta') {
              const delta = event.event.delta
              if (delta?.type === 'text_delta' && delta.text) {
                await stream.writeSSE({
                  event: 'text',
                  data: JSON.stringify({ text: delta.text }),
                })
              }
            }

            // Tool use from assistant message
            if (event.type === 'assistant') {
              const content = event.message?.content
              if (Array.isArray(content)) {
                for (const block of content) {
                  if (block.type === 'tool_use') {
                    await stream.writeSSE({
                      event: 'tool',
                      data: JSON.stringify({
                        name: block.name,
                        result: `${block.name}: ${summarizeToolInput(block.name, block.input)}`,
                      }),
                    })
                  }
                }
              }
            }

            // Result — conversation complete
            if (event.type === 'result') {
              // If we somehow missed streaming, send the full result
              if (event.result && event.subtype === 'success') {
                // Text was already streamed via deltas
              }
            }
          } catch {
            // Skip unparseable lines
          }
        }
      }
    } catch (err: any) {
      await stream.writeSSE({
        event: 'text',
        data: JSON.stringify({ text: `\n\nError: ${err.message}` }),
      })
    }

    // Wait for process to finish
    await proc.exited

    // Check stderr for errors
    if (proc.exitCode !== 0) {
      try {
        const stderrReader = proc.stderr.getReader()
        const { value } = await stderrReader.read()
        if (value) {
          const errText = new TextDecoder().decode(value)
          if (errText.trim()) {
            await stream.writeSSE({
              event: 'text',
              data: JSON.stringify({ text: `\n\nClaude CLI error: ${errText.trim()}` }),
            })
          }
        }
      } catch {
        // ignore stderr read errors
      }
    }

    // Signal completion — frontend should reload project (Claude may have edited the JSON)
    await stream.writeSSE({
      event: 'done',
      data: JSON.stringify({ projectChanged: true }),
    })
  })
})

function summarizeToolInput(name: string, input: any): string {
  if (!input) return ''
  if (name === 'Read' && input.file_path) return input.file_path
  if (name === 'Edit' && input.file_path) return `editing ${input.file_path}`
  if (name === 'Write' && input.file_path) return `writing ${input.file_path}`
  return JSON.stringify(input).substring(0, 100)
}

export default app
