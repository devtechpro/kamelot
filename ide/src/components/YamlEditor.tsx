'use client'

import dynamic from 'next/dynamic'

const MonacoEditor = dynamic(
  () => import('@monaco-editor/react').then((m) => m.default),
  {
    ssr: false,
    loading: () => (
      <div className="w-full h-full bg-slate-900 animate-pulse rounded-lg" />
    ),
  }
)

interface Props {
  value: string | null
  language?: string
  onChange?: (value: string) => void
}

export function YamlEditor({ value, language = 'yaml', onChange }: Props) {
  if (!value && !onChange) {
    return (
      <div className="flex items-center justify-center w-full h-full text-slate-400 text-sm bg-slate-900 rounded-lg">
        Generate a route to see the {language.toUpperCase()} here
      </div>
    )
  }

  return (
    <div className="w-full h-full animate-fade-in-up">
      <MonacoEditor
        height="100%"
        language={language}
        theme="vs-dark"
        value={value ?? ''}
        onChange={onChange ? (v) => onChange(v ?? '') : undefined}
        options={{
          readOnly: !onChange,
          minimap: { enabled: false },
          fontSize: 13,
          lineNumbers: 'on',
          scrollBeyondLastLine: false,
          wordWrap: 'on',
          fontFamily: 'var(--font-geist-mono), ui-monospace, monospace',
          renderLineHighlight: onChange ? 'line' : 'none',
          overviewRulerLanes: 0,
        }}
      />
    </div>
  )
}
