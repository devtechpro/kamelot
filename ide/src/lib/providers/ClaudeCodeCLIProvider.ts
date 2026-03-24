import { spawn } from 'child_process'
import * as os from 'os'
import type { ILLMProvider, LLMResponse, ConversationMessage } from '../interfaces/ILLMProvider'
import { LLMGenerationError } from './AnthropicProvider'
import { SYSTEM_PROMPT_MAIN, SYSTEM_PROMPT_PLANTUML } from '../llm'
import { parseLLMResponse } from '../utils/parseLLMResponse'

const CLI_TIMEOUT_MS = 90_000
const CLI_TIMEOUT_LABEL = '90 seconds'

interface CliEnvelope {
  type: string
  is_error: boolean
  result: string
}

/**
 * LLM provider that shells out to the local `claude` CLI.
 * Developers who have Claude Code installed and authenticated (via API key or Claude.ai OAuth)
 * can use this provider without configuring a separate ANTHROPIC_API_KEY.
 */
export class ClaudeCodeCLIProvider implements ILLMProvider {
  constructor(private readonly cliPath: string) {}

  async generate(
    messages: ConversationMessage[],
    plantUml?: string,
    context?: string
  ): Promise<LLMResponse> {
    const basePrompt = plantUml ? SYSTEM_PROMPT_PLANTUML : SYSTEM_PROMPT_MAIN
    const systemPrompt = context ? `${basePrompt}${context}` : basePrompt

    const userMessage = plantUml
      ? `Here is the PlantUML diagram:\n\n${plantUml}`
      : serializeConversation(messages)

    // The Claude Code CLI does not enforce JSON output as strictly as the API's
    // system parameter. Append a hard reminder to every message.
    const prompt =
      `${userMessage}\n\n` +
      `CRITICAL: Respond with valid JSON only. No prose, no markdown, no explanation outside JSON. ` +
      `Start your response with { and end with }.`

    const rawOutput = await this.runCLI(systemPrompt, prompt)
    const envelope = parseEnvelope(rawOutput)

    if (envelope.is_error) {
      throw new LLMGenerationError(`Claude CLI returned an error: ${envelope.result}`)
    }

    return parseLLMResponse(envelope.result)
  }

  private runCLI(systemPrompt: string, prompt: string): Promise<string> {
    return new Promise((resolve, reject) => {
      const args = [
        '--print',
        '--output-format', 'json',
        '--system-prompt', systemPrompt,
        '--model', 'claude-sonnet-4-6',
      ]

      let stdout = ''
      let stderr = ''
      let timedOut = false

      const proc = spawn(this.cliPath, args, {
        env: { ...process.env },
        // Run in a neutral directory so Claude Code does not discover
        // Kamelot's CLAUDE.md and contaminate the LLM context.
        // (Using --bare would achieve the same but disables OAuth auth.)
        cwd: os.tmpdir(),
        stdio: ['pipe', 'pipe', 'pipe'],
      })

      // Write prompt to stdin — avoids CLI argument parser misreading message
      // content (e.g. lines starting with --) as unknown flags.
      proc.stdin.on('error', () => {
        // Ignore EPIPE — process may have already consumed stdin and closed it.
      })
      proc.stdin.write(prompt, 'utf8')
      proc.stdin.end()

      const timer = setTimeout(() => {
        timedOut = true
        proc.kill('SIGTERM')
        reject(new LLMGenerationError(`Claude CLI timed out after ${CLI_TIMEOUT_LABEL}`))
      }, CLI_TIMEOUT_MS)

      proc.stdout.on('data', (chunk: Buffer) => { stdout += chunk.toString() })
      proc.stderr.on('data', (chunk: Buffer) => { stderr += chunk.toString() })

      proc.on('error', (err) => {
        clearTimeout(timer)
        reject(new LLMGenerationError('Claude CLI process failed to start', err))
      })

      proc.on('close', (code) => {
        clearTimeout(timer)
        if (timedOut) return
        if (code !== 0) {
          reject(new LLMGenerationError(
            `Claude CLI exited with code ${String(code)}: ${stderr.slice(0, 500)}`
          ))
          return
        }
        resolve(stdout)
      })
    })
  }
}

function serializeConversation(messages: ConversationMessage[]): string {
  if (messages.length === 0) return ''
  if (messages.length === 1) return messages[0].content

  // Separate prior context from the current user message so the model does not
  // mistake the history format for a dialogue template to continue in prose.
  const prior = messages.slice(0, -1)
  const current = messages[messages.length - 1]

  const historyBlock = prior
    .map((m) => `${m.role === 'user' ? 'USER' : 'ASSISTANT'}: ${m.content}`)
    .join('\n')

  return `[Conversation history]\n${historyBlock}\n[End of history]\n\nCurrent message: ${current.content}`
}

function parseEnvelope(raw: string): CliEnvelope {
  // The CLI may emit multiple newline-delimited JSON objects (stream-json).
  // We want the last one with type === 'result'.
  const lines = raw.trim().split('\n').filter(Boolean)
  for (let i = lines.length - 1; i >= 0; i--) {
    try {
      const parsed = JSON.parse(lines[i]) as Record<string, unknown>
      if (parsed.type === 'result') {
        return {
          type: String(parsed.type),
          is_error: Boolean(parsed.is_error),
          result: typeof parsed.result === 'string' ? parsed.result : '',
        }
      }
    } catch {
      // skip malformed lines
    }
  }
  throw new LLMGenerationError(
    `Unexpected Claude CLI output format. Raw: ${raw.slice(0, 300)}`
  )
}
