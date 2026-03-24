import { NextRequest, NextResponse } from 'next/server'
import { FsProjectStore } from '@/lib/providers/FsProjectStore'

const store = new FsProjectStore()

export async function GET(): Promise<NextResponse> {
  try {
    const projects = await store.list()
    return NextResponse.json(projects, { status: 200 })
  } catch {
    return NextResponse.json({ error: 'Failed to list projects' }, { status: 500 })
  }
}

export async function POST(request: NextRequest): Promise<NextResponse> {
  let body: { name?: string; folder?: string }
  try {
    body = (await request.json()) as { name?: string; folder?: string }
  } catch {
    return NextResponse.json({ error: 'Invalid JSON body' }, { status: 400 })
  }

  if (!body.name || typeof body.name !== 'string' || body.name.trim().length === 0) {
    return NextResponse.json({ error: 'name is required' }, { status: 400 })
  }

  try {
    const project = await store.create(body.name.trim(), body.folder ?? '')
    return NextResponse.json(project, { status: 201 })
  } catch {
    return NextResponse.json({ error: 'Failed to create project' }, { status: 500 })
  }
}
