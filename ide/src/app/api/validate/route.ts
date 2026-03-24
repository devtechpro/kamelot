import { NextRequest, NextResponse } from 'next/server'
import yaml from 'js-yaml'

interface ValidateRequestBody {
  yaml?: string
}

export async function POST(request: NextRequest): Promise<NextResponse> {
  let body: ValidateRequestBody
  try {
    body = (await request.json()) as ValidateRequestBody
  } catch {
    return NextResponse.json({ error: 'Invalid JSON body' }, { status: 400 })
  }

  if (!body.yaml || typeof body.yaml !== 'string') {
    return NextResponse.json({ error: 'yaml field is required' }, { status: 400 })
  }

  try {
    const parsed = yaml.load(body.yaml)

    if (!Array.isArray(parsed) || parsed.length === 0) {
      return NextResponse.json(
        { valid: false, message: 'YAML must contain a top-level route list' },
        { status: 422 }
      )
    }

    const hasRoute = parsed.some(
      (entry) =>
        typeof entry === 'object' &&
        entry !== null &&
        'route' in entry
    )

    if (!hasRoute) {
      return NextResponse.json(
        { valid: false, message: 'YAML must contain at least one route definition' },
        { status: 422 }
      )
    }

    return NextResponse.json({ valid: true }, { status: 200 })
  } catch (err) {
    return NextResponse.json(
      { valid: false, message: 'YAML parse error', details: String(err) },
      { status: 422 }
    )
  }
}
