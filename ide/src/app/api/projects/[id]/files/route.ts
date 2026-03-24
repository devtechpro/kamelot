import { NextRequest, NextResponse } from 'next/server'
import { FsProjectStore } from '@/lib/providers/FsProjectStore'
import { KrokiRenderer } from '@/lib/providers/KrokiRenderer'
import { ProjectNotFoundError } from '@/lib/interfaces/IProjectStore'
import { generateCamelXml } from '@/lib/camel-parser'

const store = new FsProjectStore()

const KNOWN_FILES = ['route.yaml', 'route.xml', 'sequence.puml', 'application.yaml', 'test-output.txt', 'requirements.md']

export async function GET(
  _request: NextRequest,
  { params }: { params: { id: string } }
): Promise<NextResponse> {
  try {
    const results: { name: string; content: string }[] = []

    for (const filename of KNOWN_FILES) {
      try {
        const content = await store.readFile(params.id, filename)
        results.push({ name: filename, content })
      } catch {
        // file doesn't exist — skip
      }
    }

    // Auto-generate route.xml for existing projects that have route.yaml but no route.xml
    const hasYaml = results.find((f) => f.name === 'route.yaml')
    const hasXml = results.find((f) => f.name === 'route.xml')
    if (hasYaml && !hasXml) {
      const xml = generateCamelXml(hasYaml.content)
      if (xml) {
        await store.saveFile(params.id, 'route.xml', xml)
        results.push({ name: 'route.xml', content: xml })
      }
    }

    // Render Kroki URL for sequence diagram if present
    let diagramUrl: string | null = null
    const seqFile = results.find((f) => f.name === 'sequence.puml')
    if (seqFile) {
      try {
        const renderer = new KrokiRenderer()
        diagramUrl = await renderer.render(seqFile.content)
      } catch {
        // non-fatal
      }
    }

    return NextResponse.json({ files: results, diagramUrl }, { status: 200 })
  } catch (err) {
    if (err instanceof ProjectNotFoundError) {
      return NextResponse.json({ error: err.message }, { status: 404 })
    }
    return NextResponse.json({ error: 'Failed to load project files' }, { status: 500 })
  }
}

export async function PUT(
  request: NextRequest,
  { params }: { params: { id: string } }
): Promise<NextResponse> {
  let body: { filename?: string; content?: string }
  try {
    body = (await request.json()) as { filename?: string; content?: string }
  } catch {
    return NextResponse.json({ error: 'Invalid JSON body' }, { status: 400 })
  }

  if (!body.filename || typeof body.filename !== 'string') {
    return NextResponse.json({ error: 'filename is required' }, { status: 400 })
  }

  if (typeof body.content !== 'string') {
    return NextResponse.json({ error: 'content is required' }, { status: 400 })
  }

  try {
    await store.saveFile(params.id, body.filename, body.content)
    return new NextResponse(null, { status: 200 })
  } catch (err) {
    if (err instanceof ProjectNotFoundError) {
      return NextResponse.json({ error: err.message }, { status: 404 })
    }
    return NextResponse.json({ error: 'Failed to save file' }, { status: 500 })
  }
}

export async function DELETE(
  request: NextRequest,
  { params }: { params: { id: string } }
): Promise<NextResponse> {
  let body: { filename?: string }
  try {
    body = (await request.json()) as { filename?: string }
  } catch {
    return NextResponse.json({ error: 'Invalid JSON body' }, { status: 400 })
  }

  if (!body.filename || typeof body.filename !== 'string') {
    return NextResponse.json({ error: 'filename is required' }, { status: 400 })
  }

  try {
    await store.deleteFile(params.id, body.filename)
    return new NextResponse(null, { status: 204 })
  } catch (err) {
    if (err instanceof ProjectNotFoundError) {
      return NextResponse.json({ error: err.message }, { status: 404 })
    }
    return NextResponse.json({ error: 'Failed to delete file' }, { status: 500 })
  }
}
