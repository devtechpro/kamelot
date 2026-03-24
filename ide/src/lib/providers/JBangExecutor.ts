import { spawn } from 'child_process'
import type { IRouteExecutor, ExecutionResult } from '../interfaces/IRouteExecutor'
import {
  resolveJBangPath,
  writeRouteFile,
  warmUpJBang,
  TMP_DIR,
  CAMEL_VERSION,
  ensureTmpDir,
} from '../jbang'
import fs from 'fs/promises'
import path from 'path'

const TIMEOUT_MS = 20_000

export class JBangExecutor implements IRouteExecutor {
  private readonly applicationYaml: string

  /**
   * @param applicationYaml Contents of application.yaml (flat YAML format) to use
   *   for credential injection. Converted to application.properties before writing
   *   because Camel JBang 4.9.0 auto-loads .properties from cwd.
   */
  constructor(applicationYaml: string) {
    this.applicationYaml = applicationYaml
    warmUpJBang()
  }

  async execute(yamlContent: string): Promise<ExecutionResult> {
    const jbangBin = await resolveJBangPath()
    const routeId = `route-${Date.now()}`

    await ensureTmpDir()
    const routeFile = await writeRouteFile(yamlContent, routeId)

    // Convert flat YAML ("key: value") → .properties ("key=value") for JBang
    const propsContent = this.applicationYaml
      .split('\n')
      .filter((l) => !l.startsWith('#') && l.includes(':'))
      .map((l) => l.replace(/:\s*"?(.*?)"?\s*$/, '=$1'))
      .join('\n')

    const propsFile = path.join(TMP_DIR, 'application.properties')
    await fs.writeFile(propsFile, propsContent, 'utf-8')

    const args = [
      `-Dcamel.jbang.version=${CAMEL_VERSION}`,
      'camel@apache/camel',
      'run',
      path.basename(routeFile),
      '--max-seconds=15',
    ]

    return new Promise<ExecutionResult>((resolve) => {
      let stdout = ''
      let stderr = ''
      let timedOut = false

      const proc = spawn(jbangBin, args, {
        cwd: TMP_DIR,
        env: {
          ...process.env,
          JAVA_TOOL_OPTIONS: '',
        },
      })

      proc.stdout.on('data', (chunk: Buffer) => {
        stdout += chunk.toString()
      })

      proc.stderr.on('data', (chunk: Buffer) => {
        stderr += chunk.toString()
      })

      const timer = setTimeout(() => {
        timedOut = true
        proc.kill('SIGTERM')
      }, TIMEOUT_MS)

      proc.on('close', (code) => {
        clearTimeout(timer)
        resolve({ stdout, stderr, exitCode: code ?? -1, timedOut })
      })

      proc.on('error', (err) => {
        clearTimeout(timer)
        resolve({
          stdout,
          stderr: stderr + `\nProcess error: ${err.message}`,
          exitCode: -1,
          timedOut: false,
        })
      })
    })
  }
}
