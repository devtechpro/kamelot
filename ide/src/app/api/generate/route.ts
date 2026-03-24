import { NextRequest, NextResponse } from 'next/server'
import { createLLMProvider } from '@/lib/llm'
import { KrokiRenderer } from '@/lib/providers/KrokiRenderer'
import { LLMGenerationError } from '@/lib/providers/AnthropicProvider'
import { generateApplicationYaml, fixCamelYamlFormat, generateCamelXml } from '@/lib/camel-parser'
import { FsProjectStore } from '@/lib/providers/FsProjectStore'
import type { ConversationMessage } from '@/lib/interfaces/ILLMProvider'

interface GenerateRequestBody {
  messages?: ConversationMessage[]
  plantUml?: string
  projectId?: string
  currentYaml?: string
}

export async function POST(request: NextRequest): Promise<NextResponse> {
  let body: GenerateRequestBody
  try {
    body = (await request.json()) as GenerateRequestBody
  } catch {
    return NextResponse.json({ error: 'Invalid JSON body' }, { status: 400 })
  }

  const { messages, plantUml, projectId, currentYaml } = body

  if ((!messages || messages.length === 0) && !plantUml) {
    return NextResponse.json(
      { error: 'Either messages or plantUml must be provided' },
      { status: 400 }
    )
  }

  if (messages && !Array.isArray(messages)) {
    return NextResponse.json({ error: 'messages must be an array' }, { status: 400 })
  }

  try {
    const provider = await createLLMProvider()
    const systemContext = currentYaml
      ? `\n\n## Current Route In Session\n\nThe following route is currently open in the user's editor. Use it when they ask questions about it:\n\`\`\`yaml\n${currentYaml}\n\`\`\``
      : undefined
    const response = await provider.generate(messages ?? [], plantUml, systemContext)

    if (response.type === 'clarification') {
      // Save requirements.md if the LLM included it
      if (projectId && response.requirements) {
        const store = new FsProjectStore()
        void store.saveFile(projectId, 'requirements.md', response.requirements).catch(() => {})
      }
      return NextResponse.json(response, { status: 200 })
    }

    if (response.type === 'answer') {
      return NextResponse.json(response, { status: 200 })
    }

    const fixedYaml = fixCamelYamlFormat(response.yaml)
    const renderer = new KrokiRenderer()
    const diagramUrl = await renderer.render(response.plantuml)
    const applicationYaml = generateApplicationYaml(fixedYaml)
    const routeXml = generateCamelXml(fixedYaml)

    // Fire-and-forget project file save
    if (projectId) {
      const store = new FsProjectStore()
      void store.saveFile(projectId, 'route.yaml', fixedYaml).catch(() => {})
      void store.saveFile(projectId, 'sequence.puml', response.plantuml).catch(() => {})
      if (applicationYaml) {
        void store.saveFile(projectId, 'application.yaml', applicationYaml).catch(() => {})
      }
      if (routeXml) {
        void store.saveFile(projectId, 'route.xml', routeXml).catch(() => {})
      }
      if (response.requirements) {
        void store.saveFile(projectId, 'requirements.md', response.requirements).catch(() => {})
      }
    }

    return NextResponse.json(
      {
        type: 'result',
        yaml: fixedYaml,
        plantuml: response.plantuml,
        diagramUrl,
        applicationYaml,
        routeXml,
        explanation: response.explanation,
      },
      { status: 200 }
    )
  } catch (err) {
    if (err instanceof LLMGenerationError) {
      return NextResponse.json({ error: err.message }, { status: 422 })
    }
    if (err instanceof Error && (
      err.message.includes('ANTHROPIC_API_KEY') ||
      err.message.includes('No LLM provider available')
    )) {
      return NextResponse.json({ error: err.message }, { status: 500 })
    }
    return NextResponse.json({ error: 'Internal server error' }, { status: 500 })
  }
}
