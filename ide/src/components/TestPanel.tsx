'use client'

import { useRef, useEffect } from 'react'
import type { ExecutionResult } from '@/lib/interfaces/IRouteExecutor'

function stripAnsi(str: string): string {
  // eslint-disable-next-line no-control-regex
  return str.replace(/[\u001b\u009b][[()#;?]*(?:[0-9]{1,4}(?:;[0-9]{0,4})*)?[0-9A-ORZcf-nqry=><~]/g, '')
}

export interface StreamLine {
  type: 'stdout' | 'stderr'
  line: string
}

interface Props {
  isExecuting: boolean
  isLive: boolean
  result: ExecutionResult | null
  error: string | null
  jbangMissing: boolean
  streamLines: StreamLine[]
}

/** Extracts HTTP endpoints exposed by Camel from the log lines. */
function extractHttpEndpoints(lines: StreamLine[]): string[] {
  const endpoints: string[] = []
  for (const { line } of lines) {
    const clean = stripAnsi(line)
    // Camel prints lines like: "http://0.0.0.0:8080/flights (GET)"
    const match = clean.match(/http:\/\/0\.0\.0\.0:(\d+)(\/\S*)/i)
    if (match) {
      endpoints.push(`http://localhost:${match[1]}${match[2]}`)
    }
  }
  return [...new Set(endpoints)]
}

/** Returns the latest deployment substep label based on log content. */
function getDeploymentSubstep(lines: StreamLine[]): string {
  for (let i = lines.length - 1; i >= 0; i--) {
    const clean = stripAnsi(lines[i].line)
    if (clean.includes('Routes startup') || clean.includes('Route started')) return 'Routes starting…'
    if (
      clean.includes('he.camel.cli.connector.LocalCliConnector') ||
      clean.includes('camel.cli.connector')
    ) {
      return 'Starting Camel context…'
    }
    if (
      clean.includes('MavenDependencyDownloader') ||
      clean.includes('Resolved ') ||
      clean.includes('Downloading ')
    ) {
      return 'Downloading dependencies…'
    }
  }
  return 'Starting up…'
}

export function TestPanel({ isExecuting, isLive, result, error, jbangMissing, streamLines }: Props) {
  const outputRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const el = outputRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [streamLines, result])

  const httpEndpoints = isExecuting || (result === null && streamLines.length > 0)
    ? extractHttpEndpoints(streamLines)
    : []

  const deploymentSubstep = isExecuting && !isLive ? getDeploymentSubstep(streamLines) : null

  return (
    <div className="flex flex-col h-full bg-slate-900">
      {/* Status bar */}
      {(isExecuting || result) && (
        <div
          className={`flex-shrink-0 px-3 py-1.5 text-xs font-semibold flex items-center gap-2 ${
            isExecuting && !isLive
              ? 'bg-slate-800 text-slate-300'
              : isExecuting && isLive
              ? 'bg-green-900/60 text-green-300'
              : result?.timedOut
              ? 'bg-amber-900/60 text-amber-300'
              : result?.exitCode === 0
              ? 'bg-green-900/60 text-green-300'
              : 'bg-red-900/60 text-red-300'
          }`}
        >
          {isExecuting && !isLive && (
            <div className="flex flex-col gap-0.5">
              <div className="flex items-center gap-2">
                <Spinner />
                <span>Deploying via Camel JBang…</span>
              </div>
              {deploymentSubstep && (
                <span className="text-[10px] text-slate-400 font-normal pl-5">
                  {deploymentSubstep}
                </span>
              )}
            </div>
          )}
          {isExecuting && isLive && '● Deployment Successful — Running'}
          {!isExecuting && result?.timedOut && '⚠  TIMED OUT (partial output)'}
          {!isExecuting && result && !result.timedOut && result.exitCode === 0 && '✓  Stopped'}
          {!isExecuting && result && !result.timedOut && result.exitCode !== 0 &&
            `✗  EXIT CODE ${result.exitCode}`}
        </div>
      )}

      {/* HTTP endpoint banner — shown while route is running */}
      {httpEndpoints.length > 0 && (
        <div className="flex-shrink-0 px-3 py-2 bg-blue-900/30 border-b border-blue-500/20 text-xs">
          <span className="text-blue-300 font-semibold mr-2">Listening:</span>
          {httpEndpoints.map((url) => (
            <a
              key={url}
              href={url}
              target="_blank"
              rel="noreferrer"
              className="text-blue-400 underline hover:text-blue-200 mr-3 font-mono"
            >
              {url}
            </a>
          ))}
        </div>
      )}

      {/* Output area */}
      <div ref={outputRef} className="flex-1 overflow-y-auto px-3 py-2 font-mono text-xs leading-relaxed">
        {jbangMissing && (
          <div className="rounded-md border border-amber-500/40 bg-amber-900/20 px-3 py-2 text-amber-300 text-xs">
            <p className="font-semibold mb-1">JBang is not installed</p>
            <p className="font-mono bg-amber-900/30 rounded px-2 py-1 mt-1 select-all">
              curl -Ls https://sh.jbang.dev | bash -s - app setup
            </p>
          </div>
        )}

        {error && !jbangMissing && (
          <p className="text-red-400">{error}</p>
        )}

        {!isExecuting && !result && !error && !jbangMissing && streamLines.length === 0 && (
          <p className="text-slate-600 py-4 text-center">
            Click Run to execute your route with live data
          </p>
        )}

        {/* Live stream output */}
        {streamLines.map((entry, i) => {
          const clean = stripAnsi(entry.line)
          const isPickup = clean.startsWith('Picked up')
          if (isPickup) return null
          const isJBangMsg = clean.includes('[jbang]')
          const colour = isJBangMsg
            ? 'text-slate-500'
            : clean.includes(' ERROR ')
            ? 'text-red-400'
            : clean.includes(' WARN ') || clean.includes(' WARNING ')
            ? 'text-amber-300'
            : clean.includes(' INFO ')
            ? 'text-green-300'
            : entry.type === 'stderr'
            ? 'text-amber-300'
            : 'text-slate-300'
          return (
            <div key={i} className={colour}>
              {clean}
            </div>
          )
        })}

        {/* Blank line separator after stream ends */}
        {result && streamLines.length > 0 && <div className="h-2" />}
      </div>
    </div>
  )
}

function Spinner() {
  return (
    <svg
      className="animate-spin h-3 w-3"
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
      aria-hidden="true"
    >
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path
        className="opacity-75"
        fill="currentColor"
        d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
      />
    </svg>
  )
}
