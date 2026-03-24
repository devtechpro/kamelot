import type { LLMResponse } from '../interfaces/ILLMProvider'
import { LLMGenerationError } from '../providers/AnthropicProvider'

/**
 * Parses raw LLM text output into a typed LLMResponse.
 * Handles defensive markdown fence stripping and validates the discriminated union.
 */
export function parseLLMResponse(rawText: string): LLMResponse {
  const cleaned = rawText
    .replace(/^```json\s*/m, '')
    .replace(/^```\s*/m, '')
    .replace(/\s*```$/m, '')
    .trim()

  let parsed: unknown
  try {
    parsed = JSON.parse(cleaned)
  } catch {
    // The CLI provider does not enforce JSON as strictly as the API.
    // If the model replied in plain prose, surface it as an answer rather
    // than crashing — the user sees the response instead of an error.
    if (cleaned.length > 0 && !cleaned.startsWith('{')) {
      return { type: 'answer', content: cleaned }
    }
    throw new LLMGenerationError(
      `LLM response is not valid JSON. Raw: ${cleaned.slice(0, 300)}`
    )
  }

  if (typeof parsed !== 'object' || parsed === null) {
    throw new LLMGenerationError('LLM response is not a JSON object')
  }

  const obj = parsed as Record<string, unknown>

  if (obj.type === 'clarification') {
    if (typeof obj.question !== 'string') {
      throw new LLMGenerationError('Clarification response missing question field')
    }
    const suggestions = Array.isArray(obj.suggestions)
      ? (obj.suggestions as unknown[]).filter((s): s is string => typeof s === 'string')
      : []
    return {
      type: 'clarification',
      question: obj.question,
      suggestions,
      requirements: typeof obj.requirements === 'string' ? obj.requirements : undefined,
    }
  }

  if (obj.type === 'result') {
    if (typeof obj.yaml !== 'string' || typeof obj.plantuml !== 'string') {
      throw new LLMGenerationError('Result response missing yaml or plantuml fields')
    }
    return {
      type: 'result',
      yaml: obj.yaml,
      plantuml: obj.plantuml,
      diagramUrl: '',      // populated by the API route
      applicationYaml: '', // populated by the API route
      explanation: typeof obj.explanation === 'string' ? obj.explanation : '',
    }
  }

  if (obj.type === 'answer') {
    if (typeof obj.content !== 'string') {
      throw new LLMGenerationError('Answer response missing content field')
    }
    return { type: 'answer', content: obj.content }
  }

  throw new LLMGenerationError(
    `Unknown response type: ${String(obj.type)}. Expected 'clarification', 'result', or 'answer'.`
  )
}
