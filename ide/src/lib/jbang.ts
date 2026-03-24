import { execFile } from 'child_process'
import fs from 'fs/promises'
import path from 'path'
import { promisify } from 'util'

const execFileAsync = promisify(execFile)

export const TMP_DIR = '/tmp/kamelot'
export const CAMEL_VERSION = '4.9.0'

export class JBangNotInstalledError extends Error {
  constructor() {
    super(
      'JBang is not installed. Install via: curl -Ls https://sh.jbang.dev | bash -s - app setup'
    )
    this.name = 'JBangNotInstalledError'
  }
}

// Cached absolute path to the jbang binary
let cachedJBangPath: string | null = null

export async function resolveJBangPath(): Promise<string> {
  if (cachedJBangPath) return cachedJBangPath

  // Try common installation locations first before shelling out
  const candidates = [
    process.env.JBANG_PATH,
    `${process.env.HOME}/.jbang/bin/jbang`,
    '/usr/local/bin/jbang',
    '/usr/bin/jbang',
  ].filter((p): p is string => typeof p === 'string')

  for (const candidate of candidates) {
    try {
      await fs.access(candidate)
      cachedJBangPath = candidate
      return cachedJBangPath
    } catch {
      // not found at this path
    }
  }

  // Fall back to which
  try {
    const { stdout } = await execFileAsync('which', ['jbang'])
    const resolved = stdout.trim()
    if (resolved) {
      cachedJBangPath = resolved
      return cachedJBangPath
    }
  } catch {
    // which failed
  }

  throw new JBangNotInstalledError()
}

export async function ensureTmpDir(): Promise<void> {
  await fs.mkdir(TMP_DIR, { recursive: true })
}

export async function writeRouteFile(yamlContent: string, id: string): Promise<string> {
  await ensureTmpDir()
  const filePath = path.join(TMP_DIR, `${id}.yaml`)
  await fs.writeFile(filePath, yamlContent, 'utf-8')
  return filePath
}

// Fire-and-forget JBang warm-up: resolves the path and runs --version
// so Camel dependencies are cached before the first real execution.
let warmUpStarted = false
export function warmUpJBang(): void {
  if (warmUpStarted) return
  warmUpStarted = true
  resolveJBangPath()
    .then((jbangBin) =>
      execFileAsync(jbangBin, [
        `-Dcamel.jbang.version=${CAMEL_VERSION}`,
        'camel@apache/camel',
        '--version',
      ])
    )
    .catch(() => {
      // Warm-up failure is non-fatal — first real execution will handle it
    })
}
