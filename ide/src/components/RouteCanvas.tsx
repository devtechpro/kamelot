'use client'

import { useMemo, useState } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  ReactFlowProvider,
  type Node,
  type Edge,
  type NodeMouseHandler,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { parseCamelYaml } from '@/lib/camel-parser'
import { NodeDetailDrawer } from '@/components/NodeDetailDrawer'
import type { NodeData } from '@/components/NodeDetailDrawer'

interface Props {
  yaml: string | null
}

export function RouteCanvas({ yaml }: Props) {
  if (!yaml) {
    return (
      <div className="flex items-center justify-center w-full h-full text-slate-400 text-sm">
        <div className="flex flex-col items-center gap-3">
          <PipelineIcon />
          <p>Generate a route to see the flow diagram here</p>
        </div>
      </div>
    )
  }

  return <FlowCanvas yaml={yaml} />
}

function FlowCanvas({ yaml }: { yaml: string }) {
  const [selectedNode, setSelectedNode] = useState<NodeData | null>(null)

  const { flowData, parseError } = useMemo(() => {
    try {
      return { flowData: parseCamelYaml(yaml), parseError: null }
    } catch (err) {
      return {
        flowData: null,
        parseError: err instanceof Error ? err.message : 'Failed to parse route',
      }
    }
  }, [yaml])

  if (parseError) {
    return (
      <div className="flex items-center justify-center w-full h-full p-6">
        <div className="rounded-lg border border-orange-200 bg-orange-50 px-4 py-3 text-sm text-orange-700 max-w-md">
          <p className="font-semibold mb-1">Could not visualise route</p>
          <p className="text-xs">{parseError}</p>
        </div>
      </div>
    )
  }

  if (!flowData) return null

  const handleNodeClick: NodeMouseHandler = (_evt, node) => {
    setSelectedNode(node.data as unknown as NodeData)
  }

  return (
    <ReactFlowProvider>
      <div className="w-full h-full relative overflow-hidden animate-fade-in-up">
        <ReactFlow
          nodes={flowData.nodes as Node[]}
          edges={flowData.edges as Edge[]}
          fitView
          fitViewOptions={{ padding: 0.3 }}
          nodesDraggable={false}
          nodesConnectable={false}
          elementsSelectable={true}
          panOnDrag
          zoomOnScroll
          onNodeClick={handleNodeClick}
          proOptions={{ hideAttribution: false }}
        >
          <Background color="#E2E8F0" gap={20} />
          <Controls showInteractive={false} />
        </ReactFlow>
        <NodeDetailDrawer node={selectedNode} onClose={() => setSelectedNode(null)} />
      </div>
    </ReactFlowProvider>
  )
}

function PipelineIcon() {
  return (
    <svg width="48" height="24" viewBox="0 0 48 24" fill="none" className="opacity-25">
      <rect x="0" y="6" width="12" height="12" rx="3" stroke="#64748B" strokeWidth="1.5" />
      <rect x="18" y="6" width="12" height="12" rx="3" stroke="#64748B" strokeWidth="1.5" />
      <rect x="36" y="6" width="12" height="12" rx="3" stroke="#64748B" strokeWidth="1.5" />
      <path d="M12 12h6M30 12h6" stroke="#64748B" strokeWidth="1.5" strokeLinecap="round" />
      <path
        d="M34 10l2 2-2 2"
        stroke="#64748B"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
