'use client'

import { useState } from 'react'
import ReactMarkdown from 'react-markdown'

interface Props {
  content: string | null
}

export function MarkdownViewer({ content }: Props) {
  const [mode, setMode] = useState<'preview' | 'raw'>('preview')

  if (!content) {
    return (
      <div className="flex items-center justify-center h-full text-slate-400 text-sm">
        No content
      </div>
    )
  }

  return (
    <div className="flex flex-col h-full overflow-hidden">
      {/* Toolbar */}
      <div className="flex-shrink-0 flex items-center gap-1 px-4 py-2 border-b border-slate-200 bg-white">
        <button
          onClick={() => setMode('preview')}
          className={`px-3 py-1 rounded text-xs font-medium transition-colors ${
            mode === 'preview'
              ? 'bg-amber-100 text-amber-700 border border-amber-300'
              : 'text-slate-500 hover:text-slate-700 hover:bg-slate-100'
          }`}
        >
          Preview
        </button>
        <button
          onClick={() => setMode('raw')}
          className={`px-3 py-1 rounded text-xs font-medium transition-colors ${
            mode === 'raw'
              ? 'bg-amber-100 text-amber-700 border border-amber-300'
              : 'text-slate-500 hover:text-slate-700 hover:bg-slate-100'
          }`}
        >
          Raw
        </button>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto">
        {mode === 'preview' ? (
          <div className="px-8 py-6 max-w-3xl mx-auto prose prose-slate prose-sm">
            <ReactMarkdown
              components={{
                h1: ({ children }) => (
                  <h1 className="text-2xl font-bold text-slate-900 mt-0 mb-4 pb-2 border-b border-slate-200">{children}</h1>
                ),
                h2: ({ children }) => (
                  <h2 className="text-lg font-semibold text-slate-800 mt-6 mb-2">{children}</h2>
                ),
                h3: ({ children }) => (
                  <h3 className="text-base font-semibold text-slate-800 mt-4 mb-1">{children}</h3>
                ),
                p: ({ children }) => (
                  <p className="text-sm text-slate-700 leading-relaxed mb-3">{children}</p>
                ),
                ul: ({ children }) => (
                  <ul className="list-disc list-inside text-sm text-slate-700 mb-3 space-y-1 ml-2">{children}</ul>
                ),
                ol: ({ children }) => (
                  <ol className="list-decimal list-inside text-sm text-slate-700 mb-3 space-y-1 ml-2">{children}</ol>
                ),
                li: ({ children }) => <li className="text-sm text-slate-700">{children}</li>,
                code: ({ children, className }) => {
                  const isBlock = !!className
                  return isBlock ? (
                    <code className="block bg-slate-100 rounded-md px-3 py-2 text-xs font-mono text-slate-800 overflow-x-auto">{children}</code>
                  ) : (
                    <code className="bg-slate-100 rounded px-1 py-0.5 text-xs font-mono text-slate-800">{children}</code>
                  )
                },
                pre: ({ children }) => (
                  <pre className="bg-slate-100 rounded-md px-4 py-3 mb-3 overflow-x-auto">{children}</pre>
                ),
                strong: ({ children }) => (
                  <strong className="font-semibold text-slate-900">{children}</strong>
                ),
                blockquote: ({ children }) => (
                  <blockquote className="border-l-4 border-amber-400 pl-4 italic text-slate-600 my-3">{children}</blockquote>
                ),
                hr: () => <hr className="border-slate-200 my-4" />,
                a: ({ href, children }) => (
                  <a href={href} className="text-amber-600 hover:text-amber-800 underline" target="_blank" rel="noreferrer">{children}</a>
                ),
              }}
            >
              {content}
            </ReactMarkdown>
          </div>
        ) : (
          <pre className="px-6 py-4 text-xs font-mono text-slate-700 whitespace-pre-wrap leading-relaxed">
            {content}
          </pre>
        )}
      </div>
    </div>
  )
}
