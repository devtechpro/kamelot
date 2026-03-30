'use client'

import { useState, useMemo } from 'react'
import {
  CAMEL_COMPONENT_REGISTRY,
  COMPONENT_CATEGORIES,
  type ComponentCategory,
} from '@/lib/camel-component-registry'

interface Props {
  onDragStart?: (scheme: string) => void
}

export function ComponentPalette({ onDragStart }: Props) {
  const [search, setSearch] = useState('')
  const [collapsed, setCollapsed] = useState<Set<ComponentCategory>>(new Set())

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!q) return CAMEL_COMPONENT_REGISTRY
    return CAMEL_COMPONENT_REGISTRY.filter(
      (c) =>
        c.label.toLowerCase().includes(q) ||
        c.scheme.toLowerCase().includes(q) ||
        c.description.toLowerCase().includes(q) ||
        c.category.toLowerCase().includes(q)
    )
  }, [search])

  const byCategory = useMemo(() => {
    const map = new Map<ComponentCategory, typeof CAMEL_COMPONENT_REGISTRY>()
    for (const cat of COMPONENT_CATEGORIES) {
      const items = filtered.filter((c) => c.category === cat)
      if (items.length > 0) map.set(cat, items)
    }
    return map
  }, [filtered])

  function toggleCategory(cat: ComponentCategory) {
    setCollapsed((prev) => {
      const next = new Set(prev)
      if (next.has(cat)) next.delete(cat)
      else next.add(cat)
      return next
    })
  }

  function handleDragStart(e: React.DragEvent<HTMLDivElement>, scheme: string) {
    e.dataTransfer.setData('application/camel-component', scheme)
    e.dataTransfer.effectAllowed = 'copy'
    onDragStart?.(scheme)
  }

  return (
    <div className="flex flex-col h-full bg-white border-l border-slate-200 select-none">
      {/* Header */}
      <div className="px-3 py-3 border-b border-slate-200 bg-slate-50">
        <p className="text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
          Components
        </p>
        <input
          type="text"
          placeholder="Search components…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="w-full text-xs px-2 py-1.5 rounded-md border border-slate-200 bg-white text-slate-700 placeholder-slate-400 focus:outline-none focus:ring-1 focus:ring-amber-400 focus:border-amber-400"
        />
      </div>

      {/* Component list */}
      <div className="flex-1 overflow-y-auto">
        {byCategory.size === 0 ? (
          <div className="px-3 py-6 text-center">
            <p className="text-xs text-slate-400">No components match &ldquo;{search}&rdquo;</p>
          </div>
        ) : (
          Array.from(byCategory.entries()).map(([cat, items]) => {
            const isCollapsed = collapsed.has(cat)
            return (
              <div key={cat} className="border-b border-slate-100 last:border-0">
                {/* Category header */}
                <button
                  onClick={() => toggleCategory(cat)}
                  className="w-full flex items-center justify-between px-3 py-2 hover:bg-slate-50 transition-colors"
                >
                  <span className="text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                    {cat}
                  </span>
                  <span className="text-slate-400 text-xs">{isCollapsed ? '▸' : '▾'}</span>
                </button>

                {/* Component chips */}
                {!isCollapsed && (
                  <div className="px-3 pb-2 flex flex-wrap gap-1.5">
                    {items.map((comp) => (
                      <div
                        key={comp.scheme}
                        draggable
                        onDragStart={(e) => handleDragStart(e, comp.scheme)}
                        title={comp.description}
                        className="
                          cursor-grab active:cursor-grabbing
                          px-2 py-1 rounded-md border text-[10px] font-medium
                          bg-slate-50 text-slate-700 border-slate-200
                          hover:bg-amber-50 hover:border-amber-300 hover:text-amber-800
                          transition-colors duration-150 leading-none
                          max-w-full truncate
                        "
                      >
                        {comp.label}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )
          })
        )}
      </div>

      {/* Footer hint */}
      <div className="px-3 py-2 border-t border-slate-100 bg-slate-50">
        <p className="text-[10px] text-slate-400 leading-tight">
          Drag a component onto the flow canvas to add it
        </p>
      </div>
    </div>
  )
}
