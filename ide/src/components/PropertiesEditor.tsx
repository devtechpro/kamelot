'use client'

import { useState } from 'react'
import dynamic from 'next/dynamic'
import yaml from 'js-yaml'

const MonacoEditor = dynamic(
  () => import('@monaco-editor/react').then((m) => m.default),
  {
    ssr: false,
    loading: () => <div className="w-full h-full bg-slate-900 animate-pulse rounded-lg" />,
  }
)

interface Props {
  value: string
  onChange: (value: string) => void
  onSave?: () => void
}

type EditMode = 'yaml' | 'form'

function keyToLabel(key: string): string {
  return key
    .split('.')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ')
}

function isPasswordField(key: string): boolean {
  return /key|secret|token|password/i.test(key)
}

function parseFormValues(yamlText: string): Record<string, string> | null {
  try {
    const parsed = yaml.load(yamlText)
    if (typeof parsed !== 'object' || parsed === null) return null
    const record = parsed as Record<string, unknown>
    if (Object.values(record).some((v) => typeof v !== 'string')) return null
    return record as Record<string, string>
  } catch {
    return null
  }
}

export function PropertiesEditor({ value, onChange, onSave }: Props) {
  const [mode, setMode] = useState<EditMode>('yaml')
  const [savedIndicator, setSavedIndicator] = useState(false)

  const formValues = parseFormValues(value)
  const canShowForm = formValues !== null

  function triggerSave() {
    if (!onSave) return
    onSave()
    setSavedIndicator(true)
    setTimeout(() => setSavedIndicator(false), 2000)
  }

  function handleFormChange(key: string, newVal: string) {
    if (!formValues) return
    const updated = { ...formValues, [key]: newVal }
    try {
      const dumped = yaml.dump(updated, { lineWidth: -1 }).trimEnd()
      onChange(dumped)
    } catch {
      // fallback: ignore
    }
  }

  const effectiveMode: EditMode = mode === 'form' && !canShowForm ? 'yaml' : mode

  return (
    <div className="flex flex-col w-full h-full">
      <div className="flex items-center gap-2 px-4 py-2 bg-slate-800 border-b border-slate-700">
        <svg width="14" height="14" viewBox="0 0 14 14" fill="none" className="flex-shrink-0">
          <rect x="1" y="1" width="12" height="12" rx="2" stroke="#10B981" strokeWidth="1.2" />
          <path
            d="M4 5h2M8 5h2M4 7.5h2M8 7.5h2M4 10h2M8 10h2"
            stroke="#10B981"
            strokeWidth="1"
            strokeLinecap="round"
          />
        </svg>
        <span className="text-xs font-medium text-slate-300">application.yaml</span>
        <span className="ml-auto text-[11px] text-emerald-400 font-medium">
          Fill in your credentials before running the route
        </span>

        {/* YAML/Form toggle */}
        <div className="flex rounded-md overflow-hidden border border-slate-600 ml-3">
          <button
            onClick={() => setMode('yaml')}
            className={`px-2.5 py-1 text-[11px] font-medium transition-colors ${
              effectiveMode === 'yaml'
                ? 'bg-amber-500 text-[#0F1729]'
                : 'bg-slate-700 text-slate-300 hover:bg-slate-600'
            }`}
          >
            YAML
          </button>
          <button
            onClick={() => setMode('form')}
            disabled={!canShowForm}
            title={!canShowForm ? 'Cannot parse as form — use YAML mode' : undefined}
            className={`px-2.5 py-1 text-[11px] font-medium transition-colors disabled:opacity-40 disabled:cursor-not-allowed ${
              effectiveMode === 'form'
                ? 'bg-amber-500 text-[#0F1729]'
                : 'bg-slate-700 text-slate-300 hover:bg-slate-600'
            }`}
          >
            Form
          </button>
        </div>

        {/* Save button */}
        {onSave && (
          <button
            onClick={triggerSave}
            aria-label="Save application.yaml"
            className={`ml-2 px-2.5 py-1 text-[11px] font-medium rounded transition-colors ${
              savedIndicator
                ? 'bg-emerald-600 text-white'
                : 'bg-slate-600 hover:bg-slate-500 text-slate-200'
            }`}
          >
            {savedIndicator ? 'Saved ✓' : 'Save'}
          </button>
        )}
      </div>

      <div className="flex-1 overflow-hidden">
        {effectiveMode === 'yaml' && (
          <MonacoEditor
            height="100%"
            language="yaml"
            theme="vs-dark"
            value={value}
            onChange={(v) => onChange(v ?? '')}
            options={{
              minimap: { enabled: false },
              fontSize: 13,
              lineNumbers: 'off',
              scrollBeyondLastLine: false,
              wordWrap: 'on',
              fontFamily: 'var(--font-geist-mono), ui-monospace, monospace',
              renderLineHighlight: 'line',
              overviewRulerLanes: 0,
              folding: false,
              lineDecorationsWidth: 0,
              lineNumbersMinChars: 0,
            }}
          />
        )}

        {effectiveMode === 'form' && formValues && (
          <div className="overflow-y-auto h-full bg-slate-900 p-4">
            <div className="space-y-3 max-w-lg">
              {Object.entries(formValues).map(([key, val]) => (
                <div key={key}>
                  <label className="block text-xs font-medium text-slate-400 mb-1">
                    {keyToLabel(key)}
                  </label>
                  <input
                    type={isPasswordField(key) ? 'password' : 'text'}
                    value={val}
                    onChange={(e) => handleFormChange(key, e.target.value)}
                    placeholder={`Enter ${keyToLabel(key).toLowerCase()}`}
                    className="w-full rounded-md bg-slate-800 border border-slate-600 text-slate-200 text-sm px-3 py-1.5 font-mono focus:outline-none focus:ring-1 focus:ring-amber-500 focus:border-amber-500 placeholder-slate-600"
                  />
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
