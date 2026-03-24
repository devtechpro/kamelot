'use client'

import { useState, useEffect, useRef } from 'react'

interface Props {
  diagramUrl: string | null
}

export function SequenceDiagram({ diagramUrl }: Props) {
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState(false)
  const [zoom, setZoom] = useState(1)
  const containerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    setLoaded(false)
    setError(false)
    setZoom(1)
  }, [diagramUrl])

  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    function handleWheel(e: WheelEvent) {
      e.preventDefault()
      setZoom((z) => Math.min(4, Math.max(0.25, z - e.deltaY * 0.001)))
    }
    el.addEventListener('wheel', handleWheel, { passive: false })
    return () => el.removeEventListener('wheel', handleWheel)
  }, [])

  if (!diagramUrl) {
    return (
      <div className="flex items-center justify-center w-full h-full text-slate-400 text-sm">
        Generate a route to see the sequence diagram here
      </div>
    )
  }

  return (
    <div className="relative w-full h-full">
      {/* Zoom controls */}
      <div className="absolute top-3 right-3 z-10 flex items-center gap-1 bg-white/90 border border-slate-200 rounded-lg shadow-sm px-1 py-1">
        <button
          onClick={() => setZoom((z) => Math.min(4, +(z + 0.2).toFixed(1)))}
          aria-label="Zoom in"
          className="w-6 h-6 flex items-center justify-center text-slate-600 hover:text-slate-900 hover:bg-slate-100 rounded text-base font-bold transition-colors"
        >
          +
        </button>
        <button
          onClick={() => setZoom(1)}
          aria-label="Reset zoom"
          className="px-1.5 h-6 flex items-center justify-center text-[10px] font-medium text-slate-500 hover:text-slate-900 hover:bg-slate-100 rounded transition-colors min-w-[32px]"
        >
          {Math.round(zoom * 100)}%
        </button>
        <button
          onClick={() => setZoom((z) => Math.max(0.25, +(z - 0.2).toFixed(1)))}
          aria-label="Zoom out"
          className="w-6 h-6 flex items-center justify-center text-slate-600 hover:text-slate-900 hover:bg-slate-100 rounded text-base font-bold transition-colors"
        >
          −
        </button>
      </div>

      {/* Scrollable diagram area */}
      <div ref={containerRef} className="w-full h-full overflow-auto bg-white p-4">
        <div
          style={{
            transform: `scale(${zoom})`,
            transformOrigin: 'top center',
            display: 'inline-block',
            minWidth: '100%',
          }}
        >
          {!loaded && !error && (
            <div className="flex items-center justify-center py-16">
              <div className="w-8 h-8 border-2 border-amber-400 border-t-transparent rounded-full animate-spin" />
            </div>
          )}
          {error ? (
            <div className="flex items-center justify-center py-16 text-slate-400 text-sm">
              <p>Diagram unavailable — Kroki could not render the diagram</p>
            </div>
          ) : (
            // eslint-disable-next-line @next/next/no-img-element
            <img
              key={diagramUrl}
              src={diagramUrl}
              alt="PlantUML sequence diagram"
              className={`max-w-full h-auto transition-opacity duration-200 ${loaded ? 'opacity-100' : 'opacity-0'}`}
              onLoad={() => setLoaded(true)}
              onError={() => {
                setLoaded(true)
                setError(true)
              }}
            />
          )}
        </div>
      </div>
    </div>
  )
}
