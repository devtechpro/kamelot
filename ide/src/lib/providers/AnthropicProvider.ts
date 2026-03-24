import Anthropic from '@anthropic-ai/sdk'
import type {
  ILLMProvider,
  LLMResponse,
  ConversationMessage,
} from '../interfaces/ILLMProvider'
import { SYSTEM_PROMPT_MAIN, SYSTEM_PROMPT_PLANTUML } from '../llm'

export class LLMGenerationError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message)
    this.name = 'LLMGenerationError'
  }
}

export class AnthropicProvider implements ILLMProvider {
  private readonly client: Anthropic

  constructor(apiKey: string) {
    this.client = new Anthropic({ apiKey })
  }

  async generate(
    messages: ConversationMessage[],
    plantUml?: string,
    context?: string
  ): Promise<LLMResponse> {
    const basePrompt = plantUml ? SYSTEM_PROMPT_PLANTUML : SYSTEM_PROMPT_MAIN
    const systemPrompt = context ? `${basePrompt}${context}` : basePrompt

    const anthropicMessages: Anthropic.MessageParam[] = plantUml
      ? [{ role: 'user', content: `Here is the PlantUML diagram:\n\n${plantUml}` }]
      : messages.map((m) => ({ role: m.role, content: m.content }))

    let responseText: string
    try {
      const message = await this.client.messages.create({
        model: 'claude-sonnet-4-6',
        max_tokens: 4096,
        system: systemPrompt,
        messages: anthropicMessages,
      })
      const content = message.content[0]
      if (content.type !== 'text') {
        throw new LLMGenerationError('Unexpected non-text response from LLM')
      }
      responseText = content.text
    } catch (err) {
      if (err instanceof LLMGenerationError) throw err
      throw new LLMGenerationError('Anthropic API call failed', err)
    }

    // Strip markdown fences defensively
    const cleaned = responseText
      .replace(/^```json\s*/m, '')
      .replace(/^```\s*/m, '')
      .replace(/\s*```$/m, '')
      .trim()

    let parsed: unknown
    try {
      parsed = JSON.parse(cleaned)
    } catch {
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
        diagramUrl: '',   // populated by the API route
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
}
