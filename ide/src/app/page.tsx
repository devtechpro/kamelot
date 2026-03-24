'use client'

import { useState, useEffect } from 'react'
import { useRouter } from 'next/navigation'
import type { Project } from '@/lib/interfaces/IProjectStore'

export default function Page() {
  const router = useRouter()
  const [projects, setProjects] = useState<Project[]>([])
  const [loading, setLoading] = useState(true)
  const [showModal, setShowModal] = useState(false)
  const [newName, setNewName] = useState('')
  const [newFolder, setNewFolder] = useState('')
  const [creating, setCreating] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)

  useEffect(() => {
    void fetchProjects()
  }, [])

  async function fetchProjects() {
    setLoading(true)
    try {
      const res = await fetch('/api/projects')
      if (res.ok) {
        const data = (await res.json()) as Project[]
        setProjects(data)
      }
    } catch {
      // silently fail — empty state shown
    } finally {
      setLoading(false)
    }
  }

  async function handleCreate() {
    if (!newName.trim()) return
    setCreating(true)
    setCreateError(null)

    try {
      const res = await fetch('/api/projects', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: newName.trim(), folder: newFolder.trim() }),
      })

      if (res.status === 201) {
        const data = (await res.json()) as Project
        router.push(`/project/${data.id}`)
      } else {
        const err = (await res.json()) as { error?: string }
        setCreateError(err.error ?? 'Failed to create project')
      }
    } catch {
      setCreateError('Network error — please try again')
    } finally {
      setCreating(false)
    }
  }

  function formatDate(iso: string): string {
    try {
      return new Date(iso).toLocaleDateString(undefined, {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
      })
    } catch {
      return iso
    }
  }

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      {/* Header */}
      <header className="bg-[#0F1729] px-6 py-3 flex items-center gap-3 shadow-lg">
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 rounded-md bg-amber-500 flex items-center justify-center">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
              <path
                d="M2 4h12M2 8h8M2 12h10"
                stroke="#0F1729"
                strokeWidth="2"
                strokeLinecap="round"
              />
            </svg>
          </div>
          <span className="text-white font-semibold text-lg tracking-tight">Kamelot</span>
          <span className="text-amber-400 font-semibold text-lg tracking-tight">Author</span>
        </div>
        <div className="ml-4 h-5 w-px bg-white/20" />
        <span className="text-slate-400 text-sm">AI coded integrations powered by Devtech.pro</span>

        <div className="ml-auto">
          <button
            onClick={() => setShowModal(true)}
            className="rounded-lg bg-amber-500 hover:bg-amber-400 text-[#0F1729] font-semibold text-sm py-2 px-4 transition-colors"
          >
            New Project
          </button>
        </div>
      </header>

      {/* Main */}
      <main className="flex-1 px-8 py-8 max-w-6xl mx-auto w-full">
        <div className="mb-6">
          <h1 className="text-2xl font-bold text-slate-800">Projects</h1>
          <p className="text-sm text-slate-500 mt-1">
            Your integration projects — click to open the IDE
          </p>
        </div>

        {loading && (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            {[1, 2, 3].map((i) => (
              <div key={i} className="h-28 rounded-xl bg-slate-200 animate-pulse" />
            ))}
          </div>
        )}

        {!loading && projects.length === 0 && (
          <div className="flex flex-col items-center justify-center py-24 gap-4">
            <svg width="56" height="56" viewBox="0 0 56 56" fill="none" className="opacity-20">
              <path
                d="M10 8h22l14 14v26H10V8z"
                stroke="#64748B"
                strokeWidth="2"
                strokeLinejoin="round"
              />
              <path d="M32 8v14h14" stroke="#64748B" strokeWidth="2" strokeLinejoin="round" />
              <path
                d="M20 28h16M20 35h10"
                stroke="#64748B"
                strokeWidth="2"
                strokeLinecap="round"
              />
            </svg>
            <p className="text-slate-500 font-medium">No projects yet.</p>
            <p className="text-slate-400 text-sm">Create your first integration.</p>
            <button
              onClick={() => setShowModal(true)}
              className="mt-2 rounded-xl bg-amber-500 hover:bg-amber-400 text-[#0F1729] font-semibold text-base py-3 px-8 transition-colors"
            >
              New Project
            </button>
          </div>
        )}

        {!loading && projects.length > 0 && (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            {projects.map((project) => (
              <button
                key={project.id}
                onClick={() => router.push(`/project/${project.id}`)}
                className="text-left rounded-xl bg-white border border-slate-200 hover:border-amber-300 hover:shadow-md p-5 transition-all group"
              >
                <div className="flex items-start gap-3">
                  <div className="w-9 h-9 rounded-lg bg-amber-100 flex items-center justify-center flex-shrink-0 group-hover:bg-amber-200 transition-colors">
                    <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
                      <path
                        d="M3 3h7l5 5v7H3V3z"
                        stroke="#F59E0B"
                        strokeWidth="1.5"
                        strokeLinejoin="round"
                      />
                      <path d="M10 3v5h5" stroke="#F59E0B" strokeWidth="1.5" strokeLinejoin="round" />
                    </svg>
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-semibold text-slate-800 truncate">{project.name}</p>
                    <p
                      className="text-xs text-slate-400 mt-0.5 truncate"
                      title={project.folder}
                    >
                      {project.folder}
                    </p>
                    <p className="text-xs text-slate-400 mt-1">{formatDate(project.createdAt)}</p>
                  </div>
                </div>
              </button>
            ))}
          </div>
        )}
      </main>

      {/* New Project Modal */}
      {showModal && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white rounded-2xl shadow-2xl w-[480px] p-6">
            <div className="flex items-center justify-between mb-5">
              <h2 className="text-lg font-bold text-slate-800">New Project</h2>
              <button
                onClick={() => {
                  setShowModal(false)
                  setNewName('')
                  setNewFolder('')
                  setCreateError(null)
                }}
                aria-label="Close"
                className="w-8 h-8 rounded-lg hover:bg-slate-100 text-slate-500 hover:text-slate-700 flex items-center justify-center text-xl transition-colors"
              >
                ×
              </button>
            </div>

            <div className="space-y-4">
              <div>
                <label className="block text-xs font-semibold uppercase tracking-wider text-slate-500 mb-1.5">
                  Project Name *
                </label>
                <input
                  type="text"
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  placeholder="e.g. Dubai Flight Arrivals"
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && newName.trim()) void handleCreate()
                  }}
                  className="w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-amber-400 focus:border-transparent"
                  autoFocus
                />
              </div>

              <div>
                <label className="block text-xs font-semibold uppercase tracking-wider text-slate-500 mb-1.5">
                  Folder Path{' '}
                  <span className="normal-case font-normal text-slate-400">
                    (optional — defaults to ~/.kamelot/projects/{'{id}'})
                  </span>
                </label>
                <input
                  type="text"
                  value={newFolder}
                  onChange={(e) => setNewFolder(e.target.value)}
                  placeholder="Leave blank to use default"
                  className="w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-amber-400 focus:border-transparent font-mono"
                />
              </div>

              {createError && (
                <p className="text-xs text-red-600 bg-red-50 rounded-md px-3 py-2 border border-red-200">
                  {createError}
                </p>
              )}
            </div>

            <div className="flex gap-3 mt-6">
              <button
                onClick={() => void handleCreate()}
                disabled={!newName.trim() || creating}
                className="flex-1 rounded-lg bg-amber-500 hover:bg-amber-400 text-[#0F1729] font-semibold text-sm py-2.5 px-4 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              >
                {creating ? 'Creating…' : 'Create Project'}
              </button>
              <button
                onClick={() => {
                  setShowModal(false)
                  setNewName('')
                  setNewFolder('')
                  setCreateError(null)
                }}
                className="rounded-lg border border-slate-200 hover:bg-slate-50 text-slate-600 font-medium text-sm py-2 px-4 transition-colors"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
