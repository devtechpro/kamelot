'use client'

export interface NodeData {
  label: string
  params: Record<string, string>
  uri: string
  component: string
}

interface Props {
  node: NodeData | null
  onClose: () => void
}

export function NodeDetailDrawer({ node, onClose }: Props) {
  const paramEntries = node ? Object.entries(node.params) : []

  return (
    <>
      {node && (
        <div
          className="absolute inset-0 z-10"
          onClick={onClose}
          aria-label="Close node details"
        />
      )}
      <div
        className={`absolute right-0 top-0 bottom-0 w-72 bg-white shadow-xl border-l border-slate-200 z-20 transition-transform duration-250 flex flex-col ${
          node ? 'translate-x-0' : 'translate-x-full'
        }`}
      >
        {node && (
          <>
            <div className="flex items-center justify-between px-4 py-3 border-b border-slate-200 bg-slate-50">
              <div>
                <p className="text-xs font-semibold uppercase tracking-wider text-slate-400">
                  Component
                </p>
                <p className="text-sm font-semibold text-slate-800 mt-0.5">{node.component}</p>
              </div>
              <button
                onClick={onClose}
                aria-label="Close drawer"
                className="w-7 h-7 rounded-md hover:bg-slate-200 text-slate-400 hover:text-slate-600 flex items-center justify-center transition-colors text-lg leading-none"
              >
                ×
              </button>
            </div>

            <div className="px-4 py-3 border-b border-slate-100">
              <p className="text-xs font-medium text-slate-400 mb-1">URI</p>
              <p className="text-xs font-mono text-slate-700 break-all bg-slate-50 rounded px-2 py-1.5">
                {node.uri || '—'}
              </p>
            </div>

            <div className="flex-1 overflow-y-auto px-4 py-3">
              <p className="text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
                Parameters
              </p>
              {paramEntries.length === 0 ? (
                <p className="text-xs text-slate-400 italic">No parameters</p>
              ) : (
                <table className="w-full text-xs">
                  <tbody>
                    {paramEntries.map(([key, value]) => (
                      <tr key={key} className="border-b border-slate-100 last:border-0">
                        <td className="py-1.5 pr-2 font-medium text-slate-600 align-top w-1/2 break-all">
                          {key}
                        </td>
                        <td className="py-1.5 font-mono text-slate-700 break-all">
                          {String(value)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </>
        )}
      </div>
    </>
  )
}
