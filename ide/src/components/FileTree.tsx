'use client'

import { useState, useEffect } from 'react'

export type FileType = 'yaml' | 'plantuml' | 'properties' | 'text' | 'flow'

export interface GeneratedFile {
  name: string
  type: FileType
  content: string
  editable?: boolean
  tabTarget?: 'yaml' | 'sequence' | 'properties' | 'flow' | 'markdown'
}

interface ContextMenuState {
  x: number
  y: number
  file: GeneratedFile
}

interface Props {
  files: GeneratedFile[]
  activeFile: string | null
  onFileClick: (file: GeneratedFile) => void
  onDeleteFile?: (file: GeneratedFile) => void
}

const fileIcons: Record<FileType, React.ReactNode> = {
  flow: (
    <svg width="14" height="14" viewBox="0 0 14 14" fill="none" className="flex-shrink-0">
      <rect x="1" y="5" width="4" height="4" rx="1" stroke="#F59E0B" strokeWidth="1.2" />
      <rect x="9" y="5" width="4" height="4" rx="1" stroke="#F59E0B" strokeWidth="1.2" />
      <path d="M5 7h4" stroke="#F59E0B" strokeWidth="1" strokeLinecap="round" />
    </svg>
  ),
  yaml: (
    <svg width="14" height="14" viewBox="0 0 14 14" fill="none" className="flex-shrink-0">
      <rect x="1" y="1" width="12" height="12" rx="2" stroke="#F59E0B" strokeWidth="1.2" />
      <path d="M4 5h6M4 7h4M4 9h5" stroke="#F59E0B" strokeWidth="1" strokeLinecap="round" />
    </svg>
  ),
  plantuml: (
    <svg width="14" height="14" viewBox="0 0 14 14" fill="none" className="flex-shrink-0">
      <rect x="1" y="1" width="12" height="12" rx="2" stroke="#6366F1" strokeWidth="1.2" />
      <path d="M4 4h2v2H4zM8 4h2v2H8zM6 8h2v2H6z" fill="#6366F1" opacity="0.7" />
      <path d="M5 6l2 2 2-2" stroke="#6366F1" strokeWidth="1" strokeLinecap="round" />
    </svg>
  ),
  properties: (
    <svg width="14" height="14" viewBox="0 0 14 14" fill="none" className="flex-shrink-0">
      <rect x="1" y="1" width="12" height="12" rx="2" stroke="#10B981" strokeWidth="1.2" />
      <path
        d="M4 5h2M8 5h2M4 7.5h2M8 7.5h2M4 10h2M8 10h2"
        stroke="#10B981"
        strokeWidth="1"
        strokeLinecap="round"
      />
    </svg>
  ),
  text: (
    <svg width="14" height="14" viewBox="0 0 14 14" fill="none" className="flex-shrink-0">
      <rect x="1" y="1" width="12" height="12" rx="2" stroke="#64748B" strokeWidth="1.2" />
      <path d="M4 5h6M4 7h6M4 9h4" stroke="#64748B" strokeWidth="1" strokeLinecap="round" />
    </svg>
  ),
}

const fileLabels: Record<FileType, string> = {
  flow: 'Flow Diagram',
  yaml: 'Camel Route',
  plantuml: 'Sequence Diagram',
  properties: 'Configuration',
  text: 'Output',
}

export function FileTree({ files, activeFile, onFileClick, onDeleteFile }: Props) {
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null)

  useEffect(() => {
    if (!contextMenu) return
    function handleClick() {
      setContextMenu(null)
    }
    document.addEventListener('click', handleClick)
    return () => document.removeEventListener('click', handleClick)
  }, [contextMenu])

  if (files.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center h-full gap-3 px-4 text-center">
        <svg width="36" height="36" viewBox="0 0 36 36" fill="none" className="opacity-20">
          <path
            d="M6 4h14l10 10v18H6V4z"
            stroke="#64748B"
            strokeWidth="1.5"
            strokeLinejoin="round"
          />
          <path d="M20 4v10h10" stroke="#64748B" strokeWidth="1.5" strokeLinejoin="round" />
        </svg>
        <p className="text-xs text-slate-400 leading-relaxed">Generated files will appear here</p>
      </div>
    )
  }

  return (
    <div className="flex flex-col p-2 gap-0.5">
      <p className="text-xs font-semibold uppercase tracking-wider text-slate-400 px-2 py-1.5 mb-1">
        Generated Files
      </p>
      {files.map((file) => (
        <button
          key={file.name}
          onClick={() => onFileClick(file)}
          onContextMenu={(e) => {
            e.preventDefault()
            if (onDeleteFile) {
              setContextMenu({ x: e.clientX, y: e.clientY, file })
            }
          }}
          aria-label={`Open ${file.name}`}
          className={`w-full flex items-center gap-2.5 rounded-md px-2.5 py-2 text-left transition-colors ${
            activeFile === file.name
              ? 'bg-amber-50 text-amber-700'
              : 'text-slate-600 hover:bg-slate-100'
          }`}
        >
          {fileIcons[file.type]}
          <span className="flex-1 min-w-0">
            <span className="block text-xs font-medium truncate">{file.name}</span>
            <span className="block text-[11px] text-slate-400">
              {fileLabels[file.type]}
              {file.editable && (
                <span className="ml-1.5 text-emerald-500 font-medium">editable</span>
              )}
            </span>
          </span>
        </button>
      ))}

      {/* Context menu */}
      {contextMenu && (
        <div
          className="fixed z-50 bg-white border border-slate-200 rounded-lg shadow-lg py-1 min-w-[140px]"
          style={{ top: contextMenu.y, left: contextMenu.x }}
        >
          <button
            onClick={() => {
              onDeleteFile?.(contextMenu.file)
              setContextMenu(null)
            }}
            className="w-full text-left px-3 py-1.5 text-xs text-red-600 hover:bg-red-50 transition-colors"
          >
            Delete file
          </button>
        </div>
      )}
    </div>
  )
}
