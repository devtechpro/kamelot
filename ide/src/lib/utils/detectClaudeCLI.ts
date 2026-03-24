import { execFile } from 'child_process'
import { promisify } from 'util'

const execFileAsync = promisify(execFile)

// undefined = not yet probed, null = probed and not found, string = absolute path
let cachedPath: string | null | undefined = undefined

/**
 * Detects whether the `claude` CLI is installed and functional on PATH.
 * Result is cached after the first call.
 * Returns the absolute path to the binary, or null if unavailable.
 */
export async function detectClaudeCLI(): Promise<string | null> {
  if (cachedPath !== undefined) {
    return cachedPath
  }

  try {
    await execFileAsync('claude', ['--version'])
    const { stdout } = await execFileAsync('which', ['claude'])
    cachedPath = stdout.trim()
    return cachedPath
  } catch {
    cachedPath = null
    return null
  }
}
