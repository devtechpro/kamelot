/**
 * Extract blocks from the full BFF-generated DSL string.
 * No DSL generation here — just string extraction.
 */

/**
 * Extract a brace-balanced block starting from a matching line.
 * Returns the block with leading indentation removed.
 */
function extractBlock(dsl: string, startPattern: RegExp): string | null {
  const lines = dsl.split('\n')
  let start = -1
  for (let i = 0; i < lines.length; i++) {
    if (startPattern.test(lines[i])) {
      start = i
      break
    }
  }
  if (start === -1) return null

  let depth = 0
  let end = start
  for (let i = start; i < lines.length; i++) {
    for (const ch of lines[i]) {
      if (ch === '{') depth++
      if (ch === '}') depth--
    }
    if (depth <= 0 && i >= start) {
      end = i
      break
    }
  }

  const block = lines.slice(start, end + 1)
  const minIndent = block.reduce((min, line) => {
    if (!line.trim()) return min
    const indent = line.match(/^(\s*)/)?.[1].length ?? 0
    return Math.min(min, indent)
  }, Infinity)
  return block.map(line => line.slice(minIndent === Infinity ? 0 : minIndent)).join('\n')
}

export function extractFlowBlock(dsl: string, operationId: string): string | null {
  return extractBlock(dsl, new RegExp(`\\bflow\\("${escapeRegex(operationId)}"\\)`))
}

export function extractAdapterBlock(dsl: string, adapterName: string): string | null {
  return extractBlock(dsl, new RegExp(`\\badapter\\("${escapeRegex(adapterName)}"`))
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
