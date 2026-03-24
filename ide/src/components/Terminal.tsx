'use client'

import { useState, useRef, useEffect } from 'react'
import type { ConversationMessage } from '@/lib/interfaces/ILLMProvider'

export interface ClarificationState {
  question: string
  suggestions: string[]
}

interface Props {
  conversation: ConversationMessage[]
  prompt: string
  onPromptChange: (v: string) => void
  onGenerate: () => void
  onClearTerminal: () => void
  isGenerating: boolean
  error: string | null
  clarification: ClarificationState | null
  onClarificationAnswer: (answer: string) => void
  plantUmlFileName: string | null
  onPlantUmlUpload: (content: string, name: string) => void
  onPlantUmlClear: () => void
}

type ViewMode = 'simple' | 'pro'

export function Terminal({
  conversation,
  prompt,
  onPromptChange,
  onGenerate,
  onClearTerminal,
  isGenerating,
  error,
  clarification,
  onClarificationAnswer,
  plantUmlFileName,
  onPlantUmlUpload,
  onPlantUmlClear,
}: Props) {
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [viewMode, setViewMode] = useState<ViewMode>('simple')

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [conversation, clarification, isGenerating])

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (
      e.key === 'Enter' &&
      !isGenerating &&
      (prompt.trim().length > 0 || !!plantUmlFileName)
    ) {
      onGenerate()
    }
  }

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = (event) => {
      const content = event.target?.result
      if (typeof content === 'string') {
        onPlantUmlUpload(content, file.name)
      }
    }
    reader.readAsText(file)
    e.target.value = ''
  }

  return (
    <div className="flex flex-col h-full bg-[#0F1729]">
      {/* View mode toggle */}
      <div className="flex-shrink-0 flex items-center justify-end px-3 py-1.5 border-b border-white/5">
        <div className="flex rounded overflow-hidden border border-slate-700">
          <button
            onClick={() => setViewMode('simple')}
            aria-label="Simple view"
            className={`px-2.5 py-0.5 text-[10px] font-medium transition-colors ${
              viewMode === 'simple'
                ? 'bg-amber-500 text-[#0F1729]'
                : 'bg-transparent text-slate-500 hover:text-slate-300'
            }`}
          >
            Simple
          </button>
          <button
            onClick={() => setViewMode('pro')}
            aria-label="Pro view"
            className={`px-2.5 py-0.5 text-[10px] font-medium transition-colors ${
              viewMode === 'pro'
                ? 'bg-amber-500 text-[#0F1729]'
                : 'bg-transparent text-slate-500 hover:text-slate-300'
            }`}
          >
            Pro
          </button>
        </div>
      </div>

      {/* Message list */}
      <div className="flex-1 overflow-y-auto px-4 py-3 space-y-3">
        {conversation.length === 0 && !clarification && !isGenerating && !error && (
          <p className="text-slate-600 text-xs text-center py-6">
            Describe your integration to get started
          </p>
        )}

        {viewMode === 'simple' ? (
          <>
            {conversation.map((msg, i) => (
              <div key={i} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                {msg.role === 'assistant' && (
                  <div className="max-w-[80%]">
                    <p className="text-amber-400 text-[10px] font-medium mb-1 ml-1">Kamelot Author</p>
                    <div className="rounded-xl px-3 py-2 bg-[#1a2540] text-slate-200 text-sm leading-relaxed">
                      {msg.content}
                    </div>
                  </div>
                )}
                {msg.role === 'user' && (
                  <div className="max-w-[80%]">
                    <div className="rounded-xl px-3 py-2 bg-amber-500 text-[#0F1729] text-sm font-medium">
                      {msg.content}
                    </div>
                  </div>
                )}
              </div>
            ))}

            {/* Clarification suggestions — question is already in conversation */}
            {clarification && !isGenerating && clarification.suggestions.length > 0 && (
              <div className="flex flex-wrap gap-1.5 ml-1">
                {clarification.suggestions.map((s) => (
                  <button
                    key={s}
                    onClick={() => onClarificationAnswer(s)}
                    className="rounded-full border border-amber-500/50 bg-[#1a2540] hover:bg-amber-500/20 text-amber-400 text-xs px-3 py-1 transition-colors"
                  >
                    {s}
                  </button>
                ))}
              </div>
            )}

            {/* Thinking indicator */}
            {isGenerating && (
              <div className="flex justify-start">
                <div className="max-w-[80%]">
                  <p className="text-amber-400 text-[10px] font-medium mb-1 ml-1">Kamelot Author</p>
                  <div className="rounded-xl px-3 py-2 bg-[#1a2540]">
                    <div className="flex gap-1 items-center h-4">
                      {[0, 1, 2].map((i) => (
                        <span
                          key={i}
                          className="w-1.5 h-1.5 rounded-full bg-amber-400 animate-bounce"
                          style={{ animationDelay: `${i * 150}ms` }}
                        />
                      ))}
                    </div>
                  </div>
                </div>
              </div>
            )}
          </>
        ) : (
          /* Pro view — plain monospace text */
          <div className="font-mono text-xs space-y-2">
            {conversation.map((msg, i) =>
              msg.role === 'user' ? (
                <div key={i} className="text-amber-300">
                  {'> '}
                  {msg.content}
                </div>
              ) : (
                <div key={i} className="text-slate-300 leading-relaxed">
                  <span className="text-amber-500 font-semibold">Kamelot: </span>
                  {msg.content}
                </div>
              )
            )}

            {/* Clarification suggestions in pro mode — question is already in conversation */}
            {clarification && !isGenerating && clarification.suggestions.length > 0 && (
              <div className="flex flex-wrap gap-1.5 mt-1">
                {clarification.suggestions.map((s) => (
                  <button
                    key={s}
                    onClick={() => onClarificationAnswer(s)}
                    className="rounded-full border border-amber-500/50 bg-[#1a2540] hover:bg-amber-500/20 text-amber-400 text-xs px-3 py-1 transition-colors"
                  >
                    {s}
                  </button>
                ))}
              </div>
            )}

            {isGenerating && (
              <div className="text-slate-500">
                <span className="text-amber-500 font-semibold">Kamelot: </span>
                <span className="animate-pulse">…</span>
              </div>
            )}
          </div>
        )}

        {/* Error (both modes) */}
        {error && (
          <div className="flex justify-start">
            <div className="rounded-xl px-3 py-2 bg-red-900/30 border border-red-700/30 text-red-300 text-xs max-w-[80%] leading-relaxed">
              {error}
            </div>
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      {/* Input row */}
      <div className="flex-shrink-0 bg-[#0a1020] border-t border-white/10 px-3 py-2.5 flex items-center gap-2">
        <span className="text-amber-500 font-mono text-sm font-bold flex-shrink-0">›</span>

        {plantUmlFileName ? (
          <span className="flex items-center gap-1 rounded-full bg-amber-500/20 text-amber-400 text-xs px-2.5 py-1 flex-1">
            <svg width="10" height="10" viewBox="0 0 12 12" fill="none">
              <path
                d="M2 2h5l3 3v5H2V2z"
                stroke="currentColor"
                strokeWidth="1.2"
                strokeLinejoin="round"
              />
            </svg>
            <span className="truncate">{plantUmlFileName}</span>
            <button
              onClick={onPlantUmlClear}
              aria-label="Remove uploaded file"
              className="ml-1 hover:text-amber-300 flex-shrink-0"
            >
              ×
            </button>
          </span>
        ) : (
          <input
            type="text"
            value={prompt}
            onChange={(e) => onPromptChange(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={isGenerating}
            placeholder="Describe your integration…"
            className="flex-1 bg-transparent text-slate-200 text-sm font-mono placeholder-slate-600 focus:outline-none disabled:opacity-50 min-w-0"
          />
        )}

        {/* PlantUML upload */}
        <button
          onClick={() => fileInputRef.current?.click()}
          aria-label="Upload PlantUML file"
          className="text-slate-500 hover:text-slate-300 p-1 transition-colors flex-shrink-0"
        >
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
            <path
              d="M7 1.5v7M4.5 5.5L7 2.5l2.5 3M2 11h10"
              stroke="currentColor"
              strokeWidth="1.4"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </button>

        {/* Clear terminal */}
        <button
          onClick={onClearTerminal}
          aria-label="Clear terminal"
          className="text-slate-500 hover:text-slate-300 p-1 transition-colors flex-shrink-0"
        >
          <svg width="13" height="13" viewBox="0 0 13 13" fill="none">
            <path
              d="M1.5 1.5l10 10M11.5 1.5l-10 10"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeLinecap="round"
            />
          </svg>
        </button>

        <input
          ref={fileInputRef}
          type="file"
          accept=".puml,.txt"
          onChange={handleFileChange}
          className="hidden"
          aria-label="Upload PlantUML file"
        />
      </div>
    </div>
  )
}
