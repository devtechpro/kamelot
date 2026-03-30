import yaml from 'js-yaml'
import type { Node } from '@xyflow/react'
import type { NodeData } from '@/lib/interfaces/INodeData'

/**
 * Serializes React Flow nodes back into a Camel YAML route string.
 * This is the inverse operation of parseCamelYaml in camel-parser.ts.
 *
 * Algorithm:
 * 1. Find the from node (stepKind === 'from')
 * 2. Sort main-chain nodes (isException !== true) by stepIndex ascending
 * 3. Emit { [stepKind]: rawStep } for each step node (stepIndex > 0)
 * 4. Append onException handlers from exception nodes
 * 5. Assemble and dump as YAML
 *
 * Note: doTry inner steps that were flattened during parse are re-emitted as
 * plain steps (the doTry wrapper is not reconstructed). Use the YAML editor
 * for complex control-flow structures that need full round-trip fidelity.
 */
export function serializeCamelYaml(nodes: Node[]): string {
  const dataNodes = nodes.filter((n) => n.data && typeof n.data === 'object') as Array<
    Node & { data: NodeData }
  >

  // Find the from node
  const fromNode = dataNodes.find((n) => n.data.stepKind === 'from')
  if (!fromNode) {
    throw new Error('No from node found in canvas — cannot serialize')
  }

  // Separate main chain from exception/catch nodes
  const mainNodes = dataNodes
    .filter((n) => !n.data.isException && n.data.stepKind !== 'from')
    .sort((a, b) => a.data.stepIndex - b.data.stepIndex)

  const exceptionNodes = dataNodes.filter(
    (n) => n.data.isException && n.data.stepKind === 'onException'
  )

  // Build steps array
  const steps = mainNodes
    .filter((n) => n.data.stepIndex > 0)
    .map((n) => buildStepObject(n.data))
    .filter((s): s is Record<string, unknown> => s !== null)

  // Build from block (rawStep contains uri and parameters, without steps)
  const fromRaw = fromNode.data.rawStep as Record<string, unknown>

  // Build the from object with steps embedded
  const fromBlock: Record<string, unknown> = {
    uri: typeof fromRaw.uri === 'string' ? fromRaw.uri : fromNode.data.uri,
  }
  if (
    typeof fromRaw.parameters === 'object' &&
    fromRaw.parameters !== null &&
    Object.keys(fromRaw.parameters as object).length > 0
  ) {
    fromBlock.parameters = fromRaw.parameters
  }
  if (steps.length > 0) {
    fromBlock.steps = steps
  }

  // Build onException entries
  const onExceptionEntries = exceptionNodes.map((n) => ({
    onException: n.data.rawStep,
  }))

  // Assemble the route
  let routeEntry: Record<string, unknown>

  if (fromNode.data.routeId) {
    routeEntry = {
      route: {
        id: fromNode.data.routeId,
        from: fromBlock,
        ...(onExceptionEntries.length > 0 ? { onException: exceptionNodes.map((n) => n.data.rawStep) } : {}),
      },
    }
  } else {
    routeEntry = { from: fromBlock }
    // onException at route level requires the route: wrapper; add it if needed
    if (onExceptionEntries.length > 0) {
      routeEntry = {
        route: {
          from: fromBlock,
          onException: exceptionNodes.map((n) => n.data.rawStep),
        },
      }
    }
  }

  return yaml.dump([routeEntry], { lineWidth: -1, noRefs: true })
}

/**
 * Builds a single YAML step object from a node's data.
 * Returns { [stepKind]: rawStep } for all known step types.
 * Returns null for unknown or structural step kinds.
 */
function buildStepObject(data: NodeData): Record<string, unknown> | null {
  const { stepKind, rawStep } = data

  switch (stepKind) {
    case 'to':
      return { to: rawStep }
    case 'toD':
      return { toD: rawStep }
    case 'log':
      return { log: rawStep }
    case 'marshal':
      return { marshal: rawStep }
    case 'unmarshal':
      return { unmarshal: rawStep }
    case 'transform':
      return { transform: rawStep }
    case 'setBody':
      return { setBody: rawStep }
    case 'setHeader':
      return { setHeader: rawStep }
    case 'removeHeaders': {
      // removeHeaders can be a string pattern or object
      const pattern = typeof rawStep.pattern === 'string' ? rawStep.pattern : '*'
      return { removeHeaders: pattern }
    }
    case 'filter':
      return { filter: rawStep }
    case 'choice':
      return { choice: rawStep }
    case 'doTry':
      return { doTry: rawStep }
    case 'doCatch':
      return { doCatch: rawStep }
    case 'from':
    case 'onException':
      // These are handled at the route level, not as step objects
      return null
    default:
      return null
  }
}
