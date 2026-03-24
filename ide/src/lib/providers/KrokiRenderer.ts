import type { IDiagramRenderer } from '../interfaces/IDiagramRenderer'
import { buildKrokiUrl } from '../kroki'

export class DiagramRenderError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message)
    this.name = 'DiagramRenderError'
  }
}

export class KrokiRenderer implements IDiagramRenderer {
  private readonly baseUrl: string

  constructor(baseUrl?: string) {
    this.baseUrl = baseUrl ?? process.env.KROKI_URL ?? 'https://kroki.io'
  }

  async render(source: string): Promise<string> {
    return buildKrokiUrl(source, this.baseUrl)
  }
}
