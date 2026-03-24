import { NextResponse } from 'next/server'
import { warmUpJBang } from '@/lib/jbang'

export async function GET(): Promise<NextResponse> {
  warmUpJBang()
  return NextResponse.json({ ok: true }, { status: 200 })
}
