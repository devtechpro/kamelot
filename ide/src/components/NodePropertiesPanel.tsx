'use client'

import { useState, useEffect } from 'react'
import type { NodeData } from '@/lib/interfaces/INodeData'
import { findComponentByScheme, type ComponentParam } from '@/lib/camel-component-registry'

interface Props {
  nodeId: string | null
  nodeData: NodeData | null
  onChange: (nodeId: string, updated: NodeData) => void
  onDelete: (nodeId: string) => void
}

export function NodePropertiesPanel({ nodeId, nodeData, onChange, onDelete }: Props) {
  if (!nodeId || !nodeData) {
    return (
      <div className="flex flex-col h-full bg-white border-l border-slate-200">
        <div className="px-3 py-3 border-b border-slate-200 bg-slate-50">
          <p className="text-xs font-semibold uppercase tracking-wider text-slate-400">Properties</p>
        </div>
        <div className="flex-1 flex items-center justify-center px-4">
          <p className="text-xs text-slate-400 text-center leading-relaxed">
            Select a node on the canvas to edit its properties
          </p>
        </div>
      </div>
    )
  }

  return <NodeEditor nodeId={nodeId} nodeData={nodeData} onChange={onChange} onDelete={onDelete} />
}

// ── Editor (rendered only when a node is selected) ────────────────────────────

interface EditorProps {
  nodeId: string
  nodeData: NodeData
  onChange: (nodeId: string, updated: NodeData) => void
  onDelete: (nodeId: string) => void
}

function NodeEditor({ nodeId, nodeData, onChange, onDelete }: EditorProps) {
  const def = findComponentByScheme(nodeData.component)

  // Local draft to avoid calling onChange on every keystroke in a debounced pattern
  // Using controlled state for immediate sync (each field fires onChange on blur)
  const [uriDraft, setUriDraft] = useState(nodeData.uri)
  const [paramsDraft, setParamsDraft] = useState<Record<string, string>>({ ...nodeData.params })
  const [rawExtra, setRawExtra] = useState<[string, string][]>([])

  // Sync draft when external nodeData changes (different node selected or AI update)
  useEffect(() => {
    setUriDraft(nodeData.uri)
    setParamsDraft({ ...nodeData.params })
    // Build extra raw key/value pairs not covered by the registry
    if (def) {
      const knownKeys = new Set(def.params.map(p => p.name))
      const extra = Object.entries(nodeData.params).filter(([k]) => !knownKeys.has(k))
      setRawExtra(extra)
    } else {
      setRawExtra(Object.entries(nodeData.params))
    }
  }, [nodeId, nodeData, def])

  const isFromNode = nodeData.stepKind === 'from'
  const hasUri = ['from', 'to', 'toD'].includes(nodeData.stepKind)

  function emitChange(uriOverride?: string, paramsOverride?: Record<string, string>) {
    const newUri = uriOverride ?? uriDraft
    const newParams = paramsOverride ?? paramsDraft

    // Rebuild rawStep from uri and params
    const newRawStep: Record<string, unknown> =
      hasUri
        ? { uri: newUri, ...(Object.keys(newParams).length > 0 ? { parameters: newParams } : {}) }
        : buildRawStepFromParams(nodeData, newParams)

    const updated: NodeData = {
      ...nodeData,
      uri: newUri,
      params: newParams,
      rawStep: newRawStep,
    }
    onChange(nodeId, updated)
  }

  function handleUriBlur() {
    emitChange(uriDraft, paramsDraft)
  }

  function handleParamChange(paramName: string, value: string) {
    const updated = { ...paramsDraft, [paramName]: value }
    setParamsDraft(updated)
    return updated
  }

  function handleParamBlur(paramName: string) {
    emitChange(uriDraft, paramsDraft)
  }

  function handleExtraKeyChange(idx: number, key: string) {
    const updated = [...rawExtra]
    const oldKey = updated[idx][0]
    updated[idx] = [key, updated[idx][1]]
    setRawExtra(updated)
    // Rename key in paramsDraft
    const newParams = { ...paramsDraft }
    if (oldKey && oldKey in newParams) delete newParams[oldKey]
    if (key) newParams[key] = updated[idx][1]
    setParamsDraft(newParams)
  }

  function handleExtraValueChange(idx: number, value: string) {
    const updated = [...rawExtra]
    updated[idx] = [updated[idx][0], value]
    setRawExtra(updated)
    const newParams = { ...paramsDraft }
    updated.forEach(([k, v]) => { if (k) newParams[k] = v })
    setParamsDraft(newParams)
  }

  function handleExtraBlur() {
    const newParams = { ...paramsDraft }
    rawExtra.forEach(([k, v]) => { if (k) newParams[k] = v })
    setParamsDraft(newParams)
    emitChange(uriDraft, newParams)
  }

  function handleAddExtraParam() {
    setRawExtra(prev => [...prev, ['', '']])
  }

  const stepKindColour = getStepKindColour(nodeData.stepKind)

  return (
    <div className="flex flex-col h-full bg-white border-l border-slate-200">
      {/* Header */}
      <div className="px-3 py-3 border-b border-slate-200 bg-slate-50 flex items-start justify-between">
        <div className="min-w-0 flex-1">
          <p className="text-xs font-semibold uppercase tracking-wider text-slate-400 mb-1">
            Properties
          </p>
          <div className="flex items-center gap-1.5 flex-wrap">
            <span className="text-sm font-semibold text-slate-800 truncate">{nodeData.label}</span>
            <span
              className="text-[9px] font-semibold uppercase tracking-wider px-1.5 py-0.5 rounded-full text-white"
              style={{ background: stepKindColour }}
            >
              {nodeData.stepKind}
            </span>
          </div>
        </div>
        <button
          onClick={() => onDelete(nodeId)}
          disabled={isFromNode}
          title={isFromNode ? 'Cannot delete the source node' : 'Delete this node'}
          className="ml-2 mt-0.5 w-7 h-7 rounded-md flex items-center justify-center text-slate-400 hover:text-red-500 hover:bg-red-50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors flex-shrink-0"
        >
          <TrashIcon />
        </button>
      </div>

      {/* Scrollable form body */}
      <div className="flex-1 overflow-y-auto">
        {/* URI field (for endpoint nodes) */}
        {hasUri && (
          <FieldSection title="URI">
            <input
              type="text"
              value={uriDraft}
              onChange={e => setUriDraft(e.target.value)}
              onBlur={handleUriBlur}
              className="w-full text-xs font-mono px-2 py-1.5 rounded-md border border-slate-200 bg-white text-slate-700 focus:outline-none focus:ring-1 focus:ring-amber-400 focus:border-amber-400"
              placeholder="component:endpoint"
            />
          </FieldSection>
        )}

        {/* Registry-defined parameters */}
        {def && def.params.length > 0 && (
          <FieldSection title="Parameters">
            {def.params.map(param => (
              <ParamField
                key={param.name}
                param={param}
                value={paramsDraft[param.name] ?? param.defaultValue ?? ''}
                onChange={v => handleParamChange(param.name, v)}
                onBlur={() => handleParamBlur(param.name)}
              />
            ))}
          </FieldSection>
        )}

        {/* Generic key/value editor for unknown components or extra params */}
        <FieldSection
          title={def ? 'Additional Parameters' : 'Parameters'}
          action={
            <button
              onClick={handleAddExtraParam}
              className="text-[10px] text-amber-600 hover:text-amber-800 font-medium"
            >
              + Add
            </button>
          }
        >
          {rawExtra.map(([k, v], idx) => (
            <div key={idx} className="flex gap-1 mb-1">
              <input
                type="text"
                value={k}
                onChange={e => handleExtraKeyChange(idx, e.target.value)}
                onBlur={handleExtraBlur}
                placeholder="key"
                className="w-2/5 text-xs font-mono px-1.5 py-1 rounded border border-slate-200 bg-white text-slate-600 focus:outline-none focus:ring-1 focus:ring-amber-400"
              />
              <input
                type="text"
                value={v}
                onChange={e => handleExtraValueChange(idx, e.target.value)}
                onBlur={handleExtraBlur}
                placeholder="value"
                className="flex-1 text-xs font-mono px-1.5 py-1 rounded border border-slate-200 bg-white text-slate-700 focus:outline-none focus:ring-1 focus:ring-amber-400"
              />
            </div>
          ))}
          {rawExtra.length === 0 && (
            <p className="text-xs text-slate-400 italic">No additional parameters</p>
          )}
        </FieldSection>

        {/* Step kind info */}
        <div className="px-3 py-3 border-t border-slate-100">
          <p className="text-[10px] text-slate-400">
            Component: <span className="font-mono text-slate-500">{nodeData.component || '—'}</span>
          </p>
        </div>
      </div>
    </div>
  )
}

// ── Sub-components ────────────────────────────────────────────────────────────

interface FieldSectionProps {
  title: string
  children: React.ReactNode
  action?: React.ReactNode
}
function FieldSection({ title, children, action }: FieldSectionProps) {
  return (
    <div className="px-3 py-3 border-b border-slate-100">
      <div className="flex items-center justify-between mb-2">
        <p className="text-[10px] font-semibold uppercase tracking-wider text-slate-400">{title}</p>
        {action}
      </div>
      {children}
    </div>
  )
}

interface ParamFieldProps {
  param: ComponentParam
  value: string
  onChange: (v: string) => void
  onBlur: () => void
}
function ParamField({ param, value, onChange, onBlur }: ParamFieldProps) {
  return (
    <div className="mb-2 last:mb-0">
      <label className="block text-[10px] font-medium text-slate-500 mb-0.5">
        {param.label}
        {param.required && <span className="text-red-400 ml-0.5">*</span>}
      </label>
      {param.type === 'boolean' ? (
        <select
          value={value}
          onChange={e => onChange(e.target.value)}
          onBlur={onBlur}
          className="w-full text-xs px-2 py-1.5 rounded-md border border-slate-200 bg-white text-slate-700 focus:outline-none focus:ring-1 focus:ring-amber-400"
        >
          <option value="true">true</option>
          <option value="false">false</option>
        </select>
      ) : param.type === 'expression' ? (
        <textarea
          value={value}
          onChange={e => onChange(e.target.value)}
          onBlur={onBlur}
          rows={2}
          className="w-full text-xs font-mono px-2 py-1.5 rounded-md border border-slate-200 bg-white text-slate-700 resize-y focus:outline-none focus:ring-1 focus:ring-amber-400"
          placeholder={param.defaultValue}
        />
      ) : (
        <input
          type={param.type === 'password' ? 'password' : param.type === 'integer' ? 'number' : 'text'}
          value={value}
          onChange={e => onChange(e.target.value)}
          onBlur={onBlur}
          className="w-full text-xs font-mono px-2 py-1.5 rounded-md border border-slate-200 bg-white text-slate-700 focus:outline-none focus:ring-1 focus:ring-amber-400"
          placeholder={param.defaultValue}
        />
      )}
      {param.description && (
        <p className="text-[9px] text-slate-400 mt-0.5 leading-tight">{param.description}</p>
      )}
    </div>
  )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function buildRawStepFromParams(nodeData: NodeData, params: Record<string, string>): Record<string, unknown> {
  // For non-URI steps, rebuild rawStep based on stepKind conventions
  const { stepKind } = nodeData
  switch (stepKind) {
    case 'log':
      return { ...nodeData.rawStep, ...params }
    case 'marshal':
    case 'unmarshal': {
      const format = params.format ?? 'json'
      return { [format]: {} }
    }
    case 'transform':
    case 'setBody': {
      const exprType = params.expressionType ?? 'simple'
      const exprValue = params.expression ?? params.simple ?? params.constant ?? params.jq ?? ''
      return { [exprType]: exprValue }
    }
    case 'setHeader': {
      const exprType = params.expressionType ?? 'constant'
      const exprValue = params.expression ?? params.constant ?? params.simple ?? ''
      return { name: params.name ?? '', [exprType]: exprValue }
    }
    case 'removeHeaders':
      return { pattern: params.pattern ?? '*' }
    case 'filter': {
      const exprType = params.expressionType ?? 'simple'
      const exprValue = params.expression ?? params.simple ?? ''
      return { [exprType]: exprValue, ...(Array.isArray(nodeData.rawStep.steps) ? { steps: nodeData.rawStep.steps } : {}) }
    }
    default:
      return { ...nodeData.rawStep, ...params }
  }
}

function getStepKindColour(stepKind: string): string {
  switch (stepKind) {
    case 'from': return '#22C55E'
    case 'to':
    case 'toD': return '#3B82F6'
    case 'log': return '#64748B'
    case 'marshal':
    case 'unmarshal':
    case 'transform':
    case 'setBody':
    case 'setHeader':
    case 'removeHeaders': return '#8B5CF6'
    case 'filter':
    case 'choice':
    case 'doTry': return '#F59E0B'
    case 'onException':
    case 'doCatch': return '#EF4444'
    default: return '#94A3B8'
  }
}

function TrashIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
      <path d="M2 3.5h10M5.5 3.5V2.5a.5.5 0 0 1 .5-.5h2a.5.5 0 0 1 .5.5v1M11 3.5l-.7 7.7a.5.5 0 0 1-.5.3H4.2a.5.5 0 0 1-.5-.3L3 3.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}
