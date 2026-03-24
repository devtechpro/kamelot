import { NextRequest, NextResponse } from 'next/server'
import { FsProjectStore } from '@/lib/providers/FsProjectStore'
import { ProjectNotFoundError } from '@/lib/interfaces/IProjectStore'

const store = new FsProjectStore()

export async function GET(
  _request: NextRequest,
  { params }: { params: { id: string } }
): Promise<NextResponse> {
  try {
    const project = await store.get(params.id)
    return NextResponse.json(project, { status: 200 })
  } catch (err) {
    if (err instanceof ProjectNotFoundError) {
      return NextResponse.json({ error: err.message }, { status: 404 })
    }
    return NextResponse.json({ error: 'Failed to get project' }, { status: 500 })
  }
}

export async function DELETE(
  _request: NextRequest,
  { params }: { params: { id: string } }
): Promise<NextResponse> {
  try {
    await store.delete(params.id)
    return new NextResponse(null, { status: 204 })
  } catch (err) {
    if (err instanceof ProjectNotFoundError) {
      return NextResponse.json({ error: err.message }, { status: 404 })
    }
    return NextResponse.json({ error: 'Failed to delete project' }, { status: 500 })
  }
}
