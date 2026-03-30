import yaml from 'js-yaml'
import type { Node, Edge } from '@xyflow/react'
import { MarkerType, Position } from '@xyflow/react'
import type { StepKind } from '@/lib/interfaces/INodeData'

export interface CamelFlowData {
  nodes: Node[]
  edges: Edge[]
}

/**
 * Extracts all {{placeholder}} tokens from a Camel YAML string and
 * returns a flat YAML config file with empty placeholder values.
 */
export function generateApplicationYaml(yamlText: string): string {
  const matches = yamlText.match(/\{\{([^}]+)\}\}/g) ?? []
  const keys = [...new Set(matches.map((m) => m.slice(2, -2).trim()))]

  if (keys.length === 0) return ''

  const lines = [
    '# Integration credentials — fill in your values before running',
    '',
    ...keys.map((key) => `${key}: ""`),
  ]

  return lines.join('\n')
}

export class CamelParseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'CamelParseError'
  }
}

const H_GAP = 220
const EXCEPTION_Y = 180
const MAIN_Y = 0

type NodeColour = string

function getNodeStyle(colour: NodeColour): React.CSSProperties {
  return {
    background: colour,
    color: '#fff',
    border: 'none',
    borderRadius: '8px',
    padding: '8px 14px',
    fontSize: '12px',
    fontWeight: 500,
    minWidth: '120px',
    textAlign: 'center',
    boxShadow: '0 2px 8px rgba(0,0,0,0.15)',
  }
}

/** Returns label for a URI using exact Camel component identifiers. */
function uriToLabel(uri: string): string {
  if (!uri) return 'Step'
  const withoutParams = uri.split('?')[0]

  if (withoutParams.startsWith('http://') || withoutParams.startsWith('https://')) {
    try {
      const urlObj = new URL(withoutParams)
      return urlObj.hostname
    } catch {
      return withoutParams.split('/')[2] || withoutParams
    }
  }

  return withoutParams
}

function componentFromUri(uri: string): string {
  return uri.split(':')[0]
}

/** Parses query-string portion of a URI into a key=value map. */
function extractUriParams(uri: string): Record<string, string> {
  const queryIdx = uri.indexOf('?')
  if (queryIdx === -1) return {}
  const params: Record<string, string> = {}
  uri
    .slice(queryIdx + 1)
    .split('&')
    .forEach((pair) => {
      const eqIdx = pair.indexOf('=')
      if (eqIdx === -1) {
        if (pair) params[pair] = ''
      } else {
        params[pair.slice(0, eqIdx)] = pair.slice(eqIdx + 1)
      }
    })
  return params
}

interface StepInfo {
  label: string
  colour: string
  uri: string
  params: Record<string, string>
  component: string
  stepKind: StepKind
  rawStep: Record<string, unknown>
}

function extractToInfo(
  raw: unknown,
  isDynamic: boolean
): StepInfo | null {
  let uri = ''
  let params: Record<string, string> = {}

  if (typeof raw === 'string') {
    uri = raw
    params = extractUriParams(uri)
  } else if (typeof raw === 'object' && raw !== null) {
    const obj = raw as Record<string, unknown>
    uri = typeof obj.uri === 'string' ? obj.uri : ''
    const uriQueryParams = extractUriParams(uri)
    const paramsBlock =
      typeof obj.parameters === 'object' && obj.parameters !== null
        ? (obj.parameters as Record<string, string>)
        : {}
    params = { ...uriQueryParams, ...paramsBlock }
  }

  if (!uri) return null
  const component = componentFromUri(uri)
  const stepKind: StepKind = isDynamic ? 'toD' : 'to'
  const rawStep: Record<string, unknown> =
    typeof raw === 'string' ? { uri: raw } : (raw as Record<string, unknown>)

  if (component === 'log') {
    return { label: uriToLabel(uri), colour: '#64748B', uri, params, component, stepKind, rawStep }
  }
  const colour = isDynamic ? '#2563EB' : '#3B82F6'
  return { label: uriToLabel(uri), colour, uri, params, component, stepKind, rawStep }
}

function extractStepInfo(step: Record<string, unknown>): StepInfo | null {
  // to: (string shorthand or object)
  if ('to' in step) {
    return extractToInfo(step.to, false)
  }

  // toD: / to-d: (dynamic routing expression)
  if ('toD' in step || 'to-d' in step) {
    return extractToInfo(step.toD ?? step['to-d'], true)
  }

  if (typeof step.log === 'object' && step.log !== null) {
    const logObj = step.log as Record<string, unknown>
    const logName = typeof logObj.logName === 'string' ? logObj.logName : 'log'
    const uri = `log:${logName}`
    const params: Record<string, string> = {}
    if (typeof logObj.message === 'string') params.message = logObj.message
    if (typeof logObj.loggingLevel === 'string') params.loggingLevel = logObj.loggingLevel
    if (typeof logObj.logName === 'string') params.logName = logObj.logName
    return { label: uri, colour: '#64748B', uri, params, component: 'log', stepKind: 'log', rawStep: logObj }
  }

  if ('unmarshal' in step) {
    const um = (step.unmarshal ?? {}) as Record<string, unknown>
    const format = typeof um === 'object' && Object.keys(um).length > 0 ? Object.keys(um)[0] : 'json'
    return { label: 'unmarshal', colour: '#8B5CF6', uri: 'unmarshal', params: { format }, component: 'unmarshal', stepKind: 'unmarshal', rawStep: um }
  }

  if ('marshal' in step) {
    const m = (step.marshal ?? {}) as Record<string, unknown>
    const format = typeof m === 'object' && Object.keys(m).length > 0 ? Object.keys(m)[0] : 'json'
    return { label: 'marshal', colour: '#8B5CF6', uri: 'marshal', params: { format }, component: 'marshal', stepKind: 'marshal', rawStep: m }
  }

  if ('transform' in step) {
    const t = (step.transform ?? {}) as Record<string, unknown>
    const params: Record<string, string> = {}
    if (typeof t.simple === 'string') params.simple = t.simple
    else if (typeof t.jq === 'string') params.jq = t.jq
    else if (typeof t.constant === 'string') params.constant = t.constant
    return { label: 'transform', colour: '#8B5CF6', uri: 'transform', params, component: 'transform', stepKind: 'transform', rawStep: t }
  }

  if ('filter' in step) {
    const filterObj = (step.filter ?? {}) as Record<string, unknown>
    const params: Record<string, string> = {}
    if (typeof filterObj.simple === 'string') params.simple = filterObj.simple
    else if (typeof filterObj.xpath === 'string') params.xpath = filterObj.xpath
    return { label: 'filter', colour: '#F59E0B', uri: 'filter', params, component: 'filter', stepKind: 'filter', rawStep: filterObj }
  }

  if ('choice' in step) {
    const choiceObj = (step.choice ?? {}) as Record<string, unknown>
    return { label: 'choice', colour: '#F59E0B', uri: 'choice', params: { hint: 'when/otherwise' }, component: 'choice', stepKind: 'choice', rawStep: choiceObj }
  }

  if ('setBody' in step || 'set-body' in step) {
    const sb = ((step.setBody ?? step['set-body']) ?? {}) as Record<string, unknown>
    const params: Record<string, string> = {}
    if (typeof sb.simple === 'string') params.simple = sb.simple
    else if (typeof sb.constant === 'string') params.constant = sb.constant
    else if (typeof sb.jq === 'string') params.jq = sb.jq
    return { label: 'setBody', colour: '#8B5CF6', uri: 'setBody', params, component: 'setBody', stepKind: 'setBody', rawStep: sb }
  }

  if ('setHeader' in step || 'set-header' in step) {
    const sh = ((step.setHeader ?? step['set-header']) ?? {}) as Record<string, unknown>
    const params: Record<string, string> = {}
    if (typeof sh.name === 'string') params.name = sh.name
    if (typeof sh.constant === 'string') params.constant = sh.constant
    else if (typeof sh.simple === 'string') params.simple = sh.simple
    else if (typeof sh.xpath === 'string') params.xpath = sh.xpath
    else if (typeof sh.jq === 'string') params.jq = sh.jq
    return { label: 'setHeader', colour: '#8B5CF6', uri: 'setHeader', params, component: 'setHeader', stepKind: 'setHeader', rawStep: sh }
  }

  if ('removeHeaders' in step || 'remove-headers' in step) {
    const rh = step.removeHeaders ?? step['remove-headers']
    const pattern =
      typeof rh === 'string'
        ? rh
        : typeof rh === 'object' && rh !== null && typeof (rh as Record<string, unknown>).pattern === 'string'
        ? ((rh as Record<string, unknown>).pattern as string)
        : '*'
    const rawStep: Record<string, unknown> = typeof rh === 'string' ? { pattern: rh } : (rh as Record<string, unknown>) ?? { pattern }
    return { label: 'removeHeaders', colour: '#8B5CF6', uri: 'removeHeaders', params: { pattern }, component: 'removeHeaders', stepKind: 'removeHeaders', rawStep }
  }

  // doTry / do-try — flatten inner steps into the visualization for display;
  // rawStep carries the full doTry object for serialization round-trips
  if ('doTry' in step || 'do-try' in step) {
    const tryObj = ((step.doTry ?? step['do-try']) ?? {}) as Record<string, unknown>
    if (Array.isArray(tryObj.steps) && tryObj.steps.length > 0) {
      // Return the first meaningful step inside the try block
      for (const inner of tryObj.steps) {
        if (typeof inner === 'object' && inner !== null) {
          const info = extractStepInfo(inner as Record<string, unknown>)
          if (info) return info
        }
      }
    }
    return { label: 'doTry', colour: '#F59E0B', uri: 'doTry', params: {}, component: 'doTry', stepKind: 'doTry', rawStep: tryObj }
  }

  return null
}

/**
 * Flattens steps, expanding doTry blocks so the main steps are visible.
 */
function flattenSteps(steps: unknown[]): Record<string, unknown>[] {
  const result: Record<string, unknown>[] = []
  for (const step of steps) {
    if (typeof step !== 'object' || step === null) continue
    const s = step as Record<string, unknown>
    if ('doTry' in s || 'do-try' in s) {
      const tryObj = (s.doTry ?? s['do-try']) as Record<string, unknown> | undefined
      if (tryObj && Array.isArray(tryObj.steps)) {
        result.push(...flattenSteps(tryObj.steps))
        continue
      }
    }
    result.push(s)
  }
  return result
}

interface CatchBlock {
  excTypes: string[]
  catchSteps: unknown[]
}

/** Walks top-level steps and extracts doCatch clauses from any doTry blocks. */
function extractCatchNodes(steps: unknown[]): CatchBlock[] {
  const result: CatchBlock[] = []
  for (const step of steps) {
    if (typeof step !== 'object' || step === null) continue
    const s = step as Record<string, unknown>
    if ('doTry' in s || 'do-try' in s) {
      const tryObj = (s.doTry ?? s['do-try']) as Record<string, unknown> | undefined
      if (tryObj) {
        const catchList = Array.isArray(tryObj.doCatch)
          ? tryObj.doCatch
          : Array.isArray(tryObj['do-catch'])
          ? tryObj['do-catch']
          : []
        for (const catchClause of catchList) {
          if (typeof catchClause !== 'object' || catchClause === null) continue
          const cc = catchClause as Record<string, unknown>
          const excTypes = Array.isArray(cc.exception)
            ? (cc.exception as unknown[]).map(String)
            : []
          const catchSteps = Array.isArray(cc.steps) ? cc.steps : []
          result.push({ excTypes, catchSteps })
        }
      }
    }
  }
  return result
}

function makeEdge(id: string, source: string, target: string, colour = '#94A3B8'): Edge {
  return {
    id,
    source,
    target,
    type: 'default',
    animated: false,
    style: { stroke: colour, strokeWidth: 2 },
    markerEnd: { type: MarkerType.ArrowClosed, color: colour },
  }
}

export function parseCamelYaml(yamlText: string): CamelFlowData {
  let parsed: unknown
  try {
    parsed = yaml.load(yamlText)
  } catch (err) {
    throw new CamelParseError(`Failed to parse YAML: ${String(err)}`)
  }

  if (!Array.isArray(parsed) || parsed.length === 0) {
    throw new CamelParseError('YAML must be a list containing at least one route definition')
  }

  // Support both:
  //   Format A: - route: { id, from: { uri, parameters }, steps: [...] }   (old, some versions)
  //   Format B: - route: { id, from: { uri, parameters, steps: [...] } }   (steps inside from)
  //   Format C: - from: { uri, parameters, steps: [...] }                  (Camel 4.x default)
  let fromObj: Record<string, unknown> | undefined
  let rawSteps: unknown[]
  let onException: unknown[]

  const routeEntry = (parsed as unknown[]).find(
    (e): e is { route: Record<string, unknown> } =>
      typeof e === 'object' && e !== null && 'route' in (e as object) &&
      typeof (e as Record<string, unknown>).route === 'object'
  )

  if (routeEntry) {
    const route = routeEntry.route
    fromObj = typeof route.from === 'object' && route.from !== null
      ? (route.from as Record<string, unknown>)
      : undefined
    // steps at route level (Format A) OR inside from (Format B)
    rawSteps = Array.isArray(route.steps)
      ? route.steps
      : Array.isArray(fromObj?.steps)
      ? (fromObj!.steps as unknown[])
      : []
    onException = Array.isArray(route.onException) ? route.onException : []
  } else {
    // Format C: top-level from:
    const fromEntry = (parsed as unknown[]).find(
      (e): e is { from: Record<string, unknown> } =>
        typeof e === 'object' && e !== null && 'from' in (e as object) &&
        typeof (e as Record<string, unknown>).from === 'object'
    )
    if (!fromEntry) {
      throw new CamelParseError('No valid route definition found in YAML')
    }
    fromObj = fromEntry.from
    rawSteps = Array.isArray(fromEntry.from.steps) ? fromEntry.from.steps : []
    onException = []
  }

  if (!fromObj) {
    throw new CamelParseError('No from endpoint found in route')
  }

  const fromUri = typeof fromObj.uri === 'string' ? fromObj.uri : 'timer:trigger'
  const fromUriQueryParams = extractUriParams(fromUri)
  const fromParamsBlock =
    typeof fromObj.parameters === 'object' && fromObj.parameters !== null
      ? (fromObj.parameters as Record<string, string>)
      : {}
  const fromParams = { ...fromUriQueryParams, ...fromParamsBlock }

  const nodes: Node[] = []
  const edges: Edge[] = []

  const routeId =
    routeEntry && typeof routeEntry.route.id === 'string' ? routeEntry.route.id : undefined

  // FROM node
  nodes.push({
    id: 'node-0',
    type: 'default',
    position: { x: 0, y: MAIN_Y },
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    data: {
      label: uriToLabel(fromUri),
      params: fromParams,
      uri: fromUri,
      component: componentFromUri(fromUri),
      stepKind: 'from' as const,
      rawStep: { uri: fromUri, ...(Object.keys(fromParamsBlock).length > 0 ? { parameters: fromParamsBlock } : {}) },
      stepIndex: 0,
      ...(routeId ? { routeId } : {}),
    },
    style: getNodeStyle('#22C55E'),
  })

  // Flatten doTry blocks so inner steps are visualised directly
  const steps = flattenSteps(rawSteps)
  let nodeIndex = 1

  for (const step of steps) {
    const info = extractStepInfo(step)
    if (!info) continue

    const nodeId = `node-${nodeIndex}`
    nodes.push({
      id: nodeId,
      type: 'default',
      position: { x: nodeIndex * H_GAP, y: MAIN_Y },
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      data: {
        label: info.label,
        params: info.params,
        uri: info.uri,
        component: info.component,
        stepKind: info.stepKind,
        rawStep: info.rawStep,
        stepIndex: nodeIndex,
      },
      style: getNodeStyle(info.colour),
    })
    edges.push(makeEdge(`edge-${nodeIndex - 1}-${nodeIndex}`, `node-${nodeIndex - 1}`, nodeId))
    nodeIndex++
  }

  // doCatch nodes from doTry blocks inside steps
  const catchBlocks = extractCatchNodes(rawSteps)
  catchBlocks.forEach((block, blockIdx) => {
    const simpleName =
      block.excTypes.length > 0
        ? String(block.excTypes[0]).split('.').pop() ?? 'Exception'
        : 'Exception'
    const label = `catch: ${simpleName}`
    let catchX = 1 * H_GAP
    const firstCatchNodeId = `catch-${blockIdx}-0`

    nodes.push({
      id: firstCatchNodeId,
      type: 'default',
      position: { x: catchX, y: EXCEPTION_Y + blockIdx * 80 },
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      data: { label, params: {}, uri: 'doCatch', component: 'doCatch', stepKind: 'doCatch' as const, rawStep: { exception: block.excTypes }, stepIndex: -1, isException: true },
      style: getNodeStyle('#EF4444'),
    })
    edges.push(makeEdge(`catch-edge-${blockIdx}-from`, 'node-0', firstCatchNodeId, '#EF4444'))

    let prevCatchNodeId = firstCatchNodeId
    block.catchSteps.forEach((catchStep, stepIdx) => {
      if (typeof catchStep !== 'object' || catchStep === null) return
      const info = extractStepInfo(catchStep as Record<string, unknown>)
      if (!info) return
      catchX += H_GAP
      const catchNodeId = `catch-${blockIdx}-${stepIdx + 1}`
      nodes.push({
        id: catchNodeId,
        type: 'default',
        position: { x: catchX, y: EXCEPTION_Y + blockIdx * 80 },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        data: { label: info.label, params: info.params, uri: info.uri, component: info.component, stepKind: info.stepKind, rawStep: info.rawStep, stepIndex: -1, isException: true },
        style: getNodeStyle('#EF4444'),
      })
      edges.push(makeEdge(`catch-edge-${blockIdx}-${stepIdx}`, prevCatchNodeId, catchNodeId, '#EF4444'))
      prevCatchNodeId = catchNodeId
    })
  })

  // onException nodes
  const exceptions = Array.isArray(onException) ? onException : []
  exceptions.forEach((exc, i) => {
    if (typeof exc !== 'object' || exc === null) return
    const excObj = exc as Record<string, unknown>
    const inner = (excObj.exception ?? excObj) as Record<string, unknown>
    const excTypes = Array.isArray(inner.exception) ? inner.exception : []
    const label = excTypes.length > 0
      ? `onException: ${String(excTypes[0]).split('.').pop()}`
      : 'onException'

    const excNodeId = `exc-node-${i}`
    nodes.push({
      id: excNodeId,
      type: 'default',
      position: { x: (nodeIndex / 2) * H_GAP, y: EXCEPTION_Y },
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      data: { label, params: {}, uri: 'onException', component: 'onException', stepKind: 'onException' as const, rawStep: excObj, stepIndex: -1, isException: true },
      style: getNodeStyle('#EF4444'),
    })
    edges.push(makeEdge(`exc-edge-${i}`, 'node-0', excNodeId, '#EF4444'))
  })

  if (nodes.length === 1) {
    nodes.push({
      id: 'node-1',
      type: 'default',
      position: { x: H_GAP, y: MAIN_Y },
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      data: { label: 'Processing…', params: {}, uri: '', component: '', stepKind: 'to' as const, rawStep: { uri: '' }, stepIndex: 1 },
      style: getNodeStyle('#3B82F6'),
    })
    edges.push(makeEdge('edge-0-1', 'node-0', 'node-1'))
  }

  return { nodes, edges }
}

/**
 * Post-processes LLM-generated Camel YAML to fix the common mistake where
 * `steps` is placed as a sibling of `from` inside `route:` instead of nested
 * inside `from:`. Camel 4.9.0 requires steps inside from.
 *
 * Input (wrong):
 *   - route:
 *       from: { uri: timer:trigger }
 *       steps: [...]
 *
 * Output (correct):
 *   - from:
 *       uri: timer:trigger
 *       steps: [...]
 */
export function fixCamelYamlFormat(yamlText: string): string {
  try {
    const parsed = yaml.load(yamlText)
    if (!Array.isArray(parsed)) return yamlText

    const fixed = (parsed as unknown[]).map((entry) => {
      if (typeof entry !== 'object' || entry === null) return entry
      const e = entry as Record<string, unknown>

      // Detect: { route: { from: {...}, steps: [...], ...rest } }
      if (
        typeof e.route === 'object' &&
        e.route !== null &&
        typeof (e.route as Record<string, unknown>).from === 'object' &&
        Array.isArray((e.route as Record<string, unknown>).steps)
      ) {
        const route = e.route as Record<string, unknown>
        const fromObj = route.from as Record<string, unknown>
        const steps = route.steps as unknown[]
        // Rewrite to top-level from with steps inside
        return {
          from: {
            ...fromObj,
            steps,
          },
        }
      }

      return entry
    })

    return yaml.dump(fixed, { lineWidth: -1, noRefs: true })
  } catch {
    return yamlText
  }
}

// ── XML helpers ──────────────────────────────────────────────────────────────

function escapeXml(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function appendParamsToUri(uri: string, params: Record<string, string>): string {
  const entries = Object.entries(params)
  if (entries.length === 0) return uri
  const sep = uri.includes('?') ? '&' : '?'
  return uri + sep + entries.map(([k, v]) => `${k}=${v}`).join('&')
}

function stepsToXml(steps: unknown[], indent: string): string[] {
  const lines: string[] = []
  for (const step of steps) {
    if (typeof step !== 'object' || step === null) continue
    const s = step as Record<string, unknown>

    if (typeof s.to === 'string') {
      lines.push(`${indent}<to uri="${escapeXml(s.to)}"/>`)
    } else if (typeof s.to === 'object' && s.to !== null) {
      const toObj = s.to as Record<string, unknown>
      const uri = typeof toObj.uri === 'string' ? toObj.uri : ''
      const paramsBlock =
        typeof toObj.parameters === 'object' && toObj.parameters !== null
          ? (toObj.parameters as Record<string, string>)
          : {}
      const uriQueryParams = extractUriParams(uri)
      const allParams = { ...uriQueryParams, ...paramsBlock }
      const baseUri = uri.split('?')[0]
      const fullUri = appendParamsToUri(baseUri, allParams)
      lines.push(`${indent}<to uri="${escapeXml(fullUri)}"/>`)
    } else if ('toD' in s || 'to-d' in s) {
      // Dynamic routing (toD) — render as <toD> with the expression or uri
      const raw = s.toD ?? s['to-d']
      if (typeof raw === 'string') {
        lines.push(`${indent}<toD uri="${escapeXml(raw)}"/>`)
      } else if (typeof raw === 'object' && raw !== null) {
        const obj = raw as Record<string, unknown>
        const uri = typeof obj.uri === 'string' ? obj.uri : ''
        const paramsBlock =
          typeof obj.parameters === 'object' && obj.parameters !== null
            ? (obj.parameters as Record<string, string>)
            : {}
        const uriQueryParams = extractUriParams(uri)
        const allParams = { ...uriQueryParams, ...paramsBlock }
        const baseUri = uri.split('?')[0]
        const fullUri = appendParamsToUri(baseUri, allParams)
        lines.push(`${indent}<toD uri="${escapeXml(fullUri)}"/>`)
      }
    } else if (typeof s.log === 'object' && s.log !== null) {
      const logObj = s.log as Record<string, unknown>
      const msg = typeof logObj.message === 'string' ? logObj.message : ''
      const levelAttr =
        typeof logObj.loggingLevel === 'string' ? ` loggingLevel="${logObj.loggingLevel}"` : ''
      lines.push(`${indent}<log message="${escapeXml(msg)}"${levelAttr}/>`)
    } else if ('unmarshal' in s) {
      lines.push(`${indent}<unmarshal><json/></unmarshal>`)
    } else if ('marshal' in s) {
      lines.push(`${indent}<marshal><json/></marshal>`)
    } else if ('setHeader' in s || 'set-header' in s) {
      const sh = (s.setHeader ?? s['set-header']) as Record<string, unknown> | undefined
      if (sh) {
        const name = typeof sh.name === 'string' ? sh.name : ''
        lines.push(`${indent}<setHeader name="${escapeXml(name)}">`)
        if (typeof sh.constant === 'string') {
          lines.push(`${indent}  <constant>${escapeXml(sh.constant)}</constant>`)
        } else if (typeof sh.simple === 'string') {
          lines.push(`${indent}  <simple>${escapeXml(sh.simple)}</simple>`)
        }
        lines.push(`${indent}</setHeader>`)
      }
    } else if ('setBody' in s || 'set-body' in s) {
      const sb = (s.setBody ?? s['set-body']) as Record<string, unknown> | undefined
      if (sb) {
        lines.push(`${indent}<setBody>`)
        if (typeof sb.simple === 'string') {
          lines.push(`${indent}  <simple>${escapeXml(sb.simple)}</simple>`)
        } else if (typeof sb.constant === 'string') {
          lines.push(`${indent}  <constant>${escapeXml(sb.constant)}</constant>`)
        }
        lines.push(`${indent}</setBody>`)
      }
    } else if ('removeHeaders' in s || 'remove-headers' in s) {
      const rh = s.removeHeaders ?? s['remove-headers']
      const pattern =
        typeof rh === 'string'
          ? rh
          : typeof rh === 'object' && rh !== null && typeof (rh as Record<string, unknown>).pattern === 'string'
          ? ((rh as Record<string, unknown>).pattern as string)
          : '*'
      lines.push(`${indent}<removeHeaders pattern="${escapeXml(pattern)}"/>`)
    } else if ('doTry' in s || 'do-try' in s) {
      const tryObj = (s.doTry ?? s['do-try']) as Record<string, unknown> | undefined
      if (tryObj) {
        lines.push(`${indent}<doTry>`)
        if (Array.isArray(tryObj.steps)) {
          lines.push(...stepsToXml(tryObj.steps, indent + '  '))
        }
        const catchList = Array.isArray(tryObj.doCatch)
          ? tryObj.doCatch
          : Array.isArray(tryObj['do-catch'])
          ? tryObj['do-catch']
          : []
        for (const catchClause of catchList) {
          if (typeof catchClause !== 'object' || catchClause === null) continue
          const cc = catchClause as Record<string, unknown>
          lines.push(`${indent}  <doCatch>`)
          const excTypes = Array.isArray(cc.exception) ? cc.exception : []
          for (const exc of excTypes) {
            lines.push(`${indent}    <exception>${escapeXml(String(exc))}</exception>`)
          }
          if (Array.isArray(cc.steps)) {
            lines.push(...stepsToXml(cc.steps, indent + '    '))
          }
          lines.push(`${indent}  </doCatch>`)
        }
        lines.push(`${indent}</doTry>`)
      }
    } else if ('filter' in s) {
      const filterObj = s.filter as Record<string, unknown> | undefined
      if (filterObj) {
        lines.push(`${indent}<filter>`)
        if (typeof filterObj.simple === 'string') {
          lines.push(`${indent}  <simple>${escapeXml(filterObj.simple)}</simple>`)
        }
        if (Array.isArray(filterObj.steps)) {
          lines.push(...stepsToXml(filterObj.steps, indent + '  '))
        }
        lines.push(`${indent}</filter>`)
      }
    }
    // Unknown steps — skip gracefully
  }
  return lines
}

/**
 * Generates Camel XML DSL from a Camel YAML route string.
 * Falls back to empty string on any parse or generation error.
 */
export function generateCamelXml(yamlText: string): string {
  try {
    const parsed = yaml.load(yamlText)
    if (!Array.isArray(parsed)) return ''

    let fromObj: Record<string, unknown> | undefined
    let rawSteps: unknown[] = []

    const routeEntry = (parsed as unknown[]).find(
      (e): e is { route: Record<string, unknown> } =>
        typeof e === 'object' && e !== null && 'route' in (e as object) &&
        typeof (e as Record<string, unknown>).route === 'object'
    )

    if (routeEntry) {
      const route = routeEntry.route
      fromObj =
        typeof route.from === 'object' && route.from !== null
          ? (route.from as Record<string, unknown>)
          : undefined
      rawSteps = Array.isArray(route.steps)
        ? route.steps
        : Array.isArray((fromObj as Record<string, unknown> | undefined)?.steps)
        ? ((fromObj as Record<string, unknown>).steps as unknown[])
        : []
    } else {
      const fromEntry = (parsed as unknown[]).find(
        (e): e is { from: Record<string, unknown> } =>
          typeof e === 'object' && e !== null && 'from' in (e as object) &&
          typeof (e as Record<string, unknown>).from === 'object'
      )
      if (fromEntry) {
        fromObj = fromEntry.from
        rawSteps = Array.isArray(fromEntry.from.steps) ? fromEntry.from.steps : []
      }
    }

    if (!fromObj) return ''

    const fromUri = typeof fromObj.uri === 'string' ? fromObj.uri : 'timer:trigger'
    const fromUriQueryParams = extractUriParams(fromUri)
    const fromParamsBlock =
      typeof fromObj.parameters === 'object' && fromObj.parameters !== null
        ? (fromObj.parameters as Record<string, string>)
        : {}
    const fromParams = { ...fromUriQueryParams, ...fromParamsBlock }
    const baseFromUri = fromUri.split('?')[0]
    const fullFromUri = appendParamsToUri(baseFromUri, fromParams)

    const lines: string[] = [
      '<?xml version="1.0" encoding="UTF-8"?>',
      '<routes xmlns="http://camel.apache.org/schema/spring">',
      '  <route id="route1">',
      `    <from uri="${escapeXml(fullFromUri)}"/>`,
    ]

    lines.push(...stepsToXml(rawSteps, '    '))

    lines.push('  </route>')
    lines.push('</routes>')

    return lines.join('\n')
  } catch {
    return ''
  }
}
