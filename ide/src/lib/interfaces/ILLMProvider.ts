export interface GenerateResult {
  yaml: string
  plantuml: string
  diagramUrl: string
  applicationYaml: string
  explanation: string
  requirements?: string
}

export interface ClarificationResult {
  type: 'clarification'
  question: string
  suggestions: string[]
  requirements?: string
}

export interface RouteResult extends GenerateResult {
  type: 'result'
}

export interface AnswerResult {
  type: 'answer'
  content: string
}

export type LLMResponse = ClarificationResult | RouteResult | AnswerResult

export interface ILLMProvider {
  generate(
    messages: ConversationMessage[],
    plantUml?: string,
    context?: string
  ): Promise<LLMResponse>
}

export interface ConversationMessage {
  role: 'user' | 'assistant'
  content: string
}
