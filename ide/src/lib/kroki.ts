import zlib from 'zlib'

export class KrokiEncodingError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'KrokiEncodingError'
  }
}

export function encodePlantUmlForKroki(source: string): string {
  const compressed = zlib.deflateSync(Buffer.from(source, 'utf-8'))
  return Buffer.from(compressed)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=/g, '')
}

export function buildKrokiUrl(source: string, baseUrl = 'https://kroki.io'): string {
  const encoded = encodePlantUmlForKroki(source)
  return `${baseUrl}/plantuml/svg/${encoded}`
}
