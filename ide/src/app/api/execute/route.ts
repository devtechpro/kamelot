import { NextRequest, NextResponse } from 'next/server'
import { spawn } from 'child_process'
import fs from 'fs/promises'
import path from 'path'
import {
  resolveJBangPath,
  ensureTmpDir,
  writeRouteFile,
  TMP_DIR,
  CAMEL_VERSION,
  JBangNotInstalledError,
} from '@/lib/jbang'
import { FsProjectStore } from '@/lib/providers/FsProjectStore'

interface ExecuteRequestBody {
  yaml?: string
  applicationYaml?: string
  projectId?: string
}

const SAFETY_TIMEOUT_MS = 360_000 // 6 min hard stop (covers 5 min JBang max)

// Module-level singleton: only one JBang process may run at a time.
let activeProc: ReturnType<typeof spawn> | null = null

function killActiveProc(): void {
  if (activeProc) {
    try { activeProc.kill('SIGTERM') } catch { /* already dead */ }
    activeProc = null
  }
}

export async function POST(request: NextRequest): Promise<Response> {
  let body: ExecuteRequestBody
  try {
    body = (await request.json()) as ExecuteRequestBody
  } catch {
    return NextResponse.json({ error: 'Invalid JSON body' }, { status: 400 })
  }

  if (!body.yaml || typeof body.yaml !== 'string' || body.yaml.trim().length === 0) {
    return NextResponse.json(
      { error: 'yaml field is required and must be a non-empty string' },
      { status: 400 }
    )
  }

  // Pre-flight: resolve JBang path before starting the stream
  let jbangBin: string
  try {
    jbangBin = await resolveJBangPath()
  } catch (err) {
    if (err instanceof JBangNotInstalledError) {
      return NextResponse.json({ error: (err as JBangNotInstalledError).message }, { status: 503 })
    }
    return NextResponse.json({ error: 'Failed to locate JBang' }, { status: 500 })
  }

  // Parse application.yaml → key=value pairs
  const propLines = (body.applicationYaml ?? '')
    .split('\n')
    .filter((l) => !l.startsWith('#') && l.includes(':'))
    .map((l) => l.replace(/:\s*"?(.*?)"?\s*$/, '=$1'))
    .filter(Boolean)

  // Substitute {{key}} placeholders directly in the route YAML so Camel's property
  // resolver never needs to look them up — bypasses all env var / classpath issues.
  let resolvedYaml = body.yaml
  for (const line of propLines) {
    const eqIdx = line.indexOf('=')
    if (eqIdx === -1) continue
    const key = line.slice(0, eqIdx).trim()
    const value = line.slice(eqIdx + 1).trim()
    resolvedYaml = resolvedYaml.replaceAll(`{{${key}}}`, value)
  }

  // Write route + properties files
  const routeId = `route-${Date.now()}`
  await ensureTmpDir()
  const routeFile = await writeRouteFile(resolvedYaml, routeId)

  // Also write application.properties as fallback for any remaining placeholders
  const propsContent = propLines.join('\n')
  await fs.writeFile(path.join(TMP_DIR, 'application.properties'), propsContent, 'utf-8')

  // Build env: strip any OS-level overrides for keys we are explicitly providing,
  // so Camel can't accidentally pick up a stale shell export.
  const strippedKeys = new Set(
    propLines.map((l) => {
      const key = l.split('=')[0].trim()
      // Camel maps "foo.bar.baz" -> "FOO_BAR_BAZ"
      return key.toUpperCase().replace(/\./g, '_')
    })
  )
  const cleanEnv: Record<string, string | undefined> = { ...process.env }
  for (const key of strippedKeys) delete cleanEnv[key]
  cleanEnv.JAVA_TOOL_OPTIONS = ''

  const args = [
    `-Dcamel.jbang.version=${CAMEL_VERSION}`,
    'camel@apache/camel',
    'run',
    path.basename(routeFile),
    '--max-seconds=300',
  ]

  const encoder = new TextEncoder()
  const projectId = body.projectId

  function sse(data: unknown): Uint8Array {
    return encoder.encode(`data: ${JSON.stringify(data)}\n\n`)
  }

  // Kill any previous JBang run before starting a new one
  killActiveProc()

  const stream = new ReadableStream({
    start(controller) {
      let stdout = ''
      let stderr = ''
      let timedOut = false

      const proc = spawn(jbangBin, args, {
        cwd: TMP_DIR,
        env: cleanEnv as NodeJS.ProcessEnv,
      })
      activeProc = proc

      let liveSent = false

      proc.stdout.on('data', (chunk: Buffer) => {
        const text = chunk.toString()
        stdout += text
        for (const line of text.split('\n')) {
          if (!line.trim()) continue
          controller.enqueue(sse({ type: 'stdout', line }))
          if (!liveSent && line.includes('Waiting until complete')) {
            liveSent = true
            controller.enqueue(sse({ type: 'live' }))
          }
        }
      })

      proc.stderr.on('data', (chunk: Buffer) => {
        const text = chunk.toString()
        stderr += text
        for (const line of text.split('\n')) {
          if (line.trim()) controller.enqueue(sse({ type: 'stderr', line }))
        }
      })

      const timer = setTimeout(() => {
        timedOut = true
        proc.kill('SIGTERM')
      }, SAFETY_TIMEOUT_MS)

      proc.on('close', (code) => {
        clearTimeout(timer)
        if (activeProc === proc) activeProc = null
        const result = { stdout, stderr, exitCode: code ?? -1, timedOut }
        controller.enqueue(sse({ type: 'done', result }))
        controller.close()

        if (projectId && (stdout || stderr)) {
          const store = new FsProjectStore()
          const content = stdout + (stderr ? `\n\nSTDERR:\n${stderr}` : '')
          void store.saveFile(projectId, 'test-output.txt', content).catch(() => {})
        }
      })

      proc.on('error', (err) => {
        clearTimeout(timer)
        if (activeProc === proc) activeProc = null
        const result = {
          stdout,
          stderr: stderr + `\nProcess error: ${err.message}`,
          exitCode: -1,
          timedOut: false,
        }
        controller.enqueue(sse({ type: 'done', result }))
        controller.close()
      })
    },
  })

  return new Response(stream, {
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
    },
  })
}

export async function DELETE(): Promise<NextResponse> {
  killActiveProc()
  return NextResponse.json({ ok: true })
}
