'use client'

import { useMemo, useState, useEffect, useCallback } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  ReactFlowProvider,
  useReactFlow,
  type Node,
  type Edge,
  type NodeMouseHandler,
  MarkerType,
  Position,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { parseCamelYaml } from '@/lib/camel-parser'
import { serializeCamelYaml } from '@/lib/camel-serializer'
import { findComponentByScheme } from '@/lib/camel-component-registry'
import { NodeDetailDrawer } from '@/components/NodeDetailDrawer'
import type { NodeData, StepKind } from '@/lib/interfaces/INodeData'

interface Props {
  yaml: string | null
  /** When provided, canvas switches to editable mode */
  onYamlChange?: (yaml: string) => void
  /** Called when a node is selected (editable mode only) */
  onNodeSelect?: (node: { id: string; data: NodeData } | null) => void
}

export function RouteCanvas({ yaml, onYamlChange, onNodeSelect }: Props) {
  const isEditable = !!onYamlChange

  if (!yaml && !isEditable) {
    return (
      <div className="flex items-center justify-center w-full h-full text-slate-400 text-sm">
        <div className="flex flex-col items-center gap-3">
          <PipelineIcon />
          <p>Generate a route to see the flow diagram here</p>
        </div>
      </div>
    )
  }

  return (
    <ReactFlowProvider>
      <FlowCanvasInner yaml={yaml ?? ''} onYamlChange={onYamlChange} onNodeSelect={onNodeSelect} />
    </ReactFlowProvider>
  )
}

// ── Inner canvas component (inside ReactFlowProvider so useReactFlow works) ──

interface InnerProps {
  yaml: string  // always a string (empty string for blank canvas)
  onYamlChange?: (yaml: string) => void
  onNodeSelect?: (node: { id: string; data: NodeData } | null) => void
}

function FlowCanvasInner({ yaml, onYamlChange, onNodeSelect }: InnerProps) {
  const isEditable = !!onYamlChange
  const { screenToFlowPosition } = useReactFlow()

  // ── Read-only mode: derive nodes/edges directly from YAML ─────────────────
  const { flowData, parseError } = useMemo(() => {
    if (isEditable) return { flowData: null, parseError: null }
    try {
      return { flowData: parseCamelYaml(yaml), parseError: null }
    } catch (err) {
      return { flowData: null, parseError: err instanceof Error ? err.message : 'Failed to parse route' }
    }
  }, [yaml, isEditable])

  // ── Editable mode: controlled nodes/edges state ───────────────────────────
  const [editNodes, setEditNodes] = useState<Node[]>([])
  const [editEdges, setEditEdges] = useState<Edge[]>([])
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [isDragOver, setIsDragOver] = useState(false)
  const [parseEditError, setParseEditError] = useState<string | null>(null)
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; nodeId: string } | null>(null)

  // Re-seed from YAML whenever it changes externally (AI generation, YAML editor, property edits)
  useEffect(() => {
    if (!isEditable) return
    if (!yaml) {
      setEditNodes([])
      setEditEdges([])
      setParseEditError(null)
      return
    }
    try {
      const parsed = parseCamelYaml(yaml)
      setEditNodes(parsed.nodes)
      setEditEdges(parsed.edges)
      setParseEditError(null)
    } catch (err) {
      setParseEditError(err instanceof Error ? err.message : 'Failed to parse route')
    }
  }, [yaml, isEditable])

  // ── Read-only node click → NodeDetailDrawer ───────────────────────────────
  const [readOnlySelectedNode, setReadOnlySelectedNode] = useState<NodeData | null>(null)
  const handleNodeClick: NodeMouseHandler = useCallback((_evt, node) => {
    if (!isEditable) {
      setReadOnlySelectedNode(node.data as unknown as NodeData)
      return
    }
    const nodeId = node.id
    const data = node.data as unknown as NodeData
    setSelectedNodeId(nodeId)
    onNodeSelect?.({ id: nodeId, data })
  }, [isEditable, onNodeSelect])

  const handlePaneClick = useCallback(() => {
    if (!isEditable) return
    setSelectedNodeId(null)
    onNodeSelect?.(null)
    setContextMenu(null)
  }, [isEditable, onNodeSelect])

  // ── Right-click context menu ───────────────────────────────────────────────
  const handleNodeContextMenu = useCallback((evt: React.MouseEvent, node: Node) => {
    if (!isEditable) return
    evt.preventDefault()
    const data = node.data as unknown as NodeData
    if (data.stepKind === 'from') return  // cannot delete the source node
    setContextMenu({ x: evt.clientX, y: evt.clientY, nodeId: node.id })
  }, [isEditable])

  // ── Serialize and emit via onYamlChange ───────────────────────────────────
  const serializeAndEmit = useCallback((nodes: Node[]) => {
    if (!onYamlChange) return
    try {
      const newYaml = serializeCamelYaml(nodes)
      onYamlChange(newYaml)
    } catch (err) {
      console.error('[RouteCanvas] Serialization failed:', err)
    }
  }, [onYamlChange])

  // ── Drag to reorder: sort main nodes by x-position after drag ─────────────
  const handleNodeDragStop = useCallback((_evt: React.MouseEvent, draggedNode: Node) => {
    if (!isEditable) return

    // Merge the dragged node's NEW position (ReactFlow tracks it internally;
    // editNodes still has the old position until we update state)
    const withNewPos = editNodes.map(n =>
      n.id === draggedNode.id ? { ...n, position: draggedNode.position } : n
    )

    const mainNodes = withNewPos.filter(n => !(n.data as unknown as NodeData).isException)
    const exceptionNodes = withNewPos.filter(n => (n.data as unknown as NodeData).isException)

    // Sort main nodes by x position, then reassign stepIndex
    const sorted = [...mainNodes].sort((a, b) => a.position.x - b.position.x)
    const renumbered = sorted.map((n, idx) => ({
      ...n,
      data: { ...(n.data as unknown as NodeData), stepIndex: idx } as unknown as Record<string, unknown>,
    }))

    // Rebuild edges for the renumbered chain
    const newEdges = buildMainChainEdges(renumbered)
    // Keep exception edges (they connect from node-0)
    const excEdges = editEdges.filter(e => e.style?.stroke === '#EF4444')

    const allNodes = [...renumbered, ...exceptionNodes]
    setEditNodes(allNodes)
    setEditEdges([...newEdges, ...excEdges])
    serializeAndEmit(allNodes)
  }, [isEditable, editNodes, editEdges, serializeAndEmit])

  // ── Delete key → remove selected node ────────────────────────────────────
  useEffect(() => {
    if (!isEditable) return
    function handleKeyDown(e: KeyboardEvent) {
      const tag = (e.target as HTMLElement).tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return
      if ((e.key === 'Delete' || e.key === 'Backspace') && selectedNodeId) {
        // Don't delete the from node
        const node = editNodes.find(n => n.id === selectedNodeId)
        if (!node) return
        const data = node.data as unknown as NodeData
        if (data.stepKind === 'from') return

        deleteNode(selectedNodeId)
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [isEditable, selectedNodeId, editNodes])

  const deleteNode = useCallback((nodeId: string) => {
    const remaining = editNodes.filter(n => n.id !== nodeId)

    // Renumber main chain
    const mainNodes = remaining.filter(n => !(n.data as unknown as NodeData).isException)
    const exceptionNodes = remaining.filter(n => (n.data as unknown as NodeData).isException)
    const sorted = [...mainNodes].sort((a, b) =>
      (a.data as unknown as NodeData).stepIndex - (b.data as unknown as NodeData).stepIndex
    )
    const renumbered = sorted.map((n, idx) => ({
      ...n,
      data: { ...(n.data as unknown as NodeData), stepIndex: idx } as unknown as Record<string, unknown>,
    }))

    const newEdges = buildMainChainEdges(renumbered)
    const excEdges = editEdges.filter(e => e.style?.stroke === '#EF4444')

    const allNodes = [...renumbered, ...exceptionNodes]
    setEditNodes(allNodes)
    setEditEdges([...newEdges, ...excEdges])
    setSelectedNodeId(null)
    onNodeSelect?.(null)
    serializeAndEmit(allNodes)
  }, [editNodes, editEdges, onNodeSelect, serializeAndEmit])

  // ── Drop from palette ─────────────────────────────────────────────────────
  const handleDragOver = useCallback((e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault()
    e.dataTransfer.dropEffect = 'copy'
    setIsDragOver(true)
  }, [])

  const handleDragLeave = useCallback(() => {
    setIsDragOver(false)
  }, [])

  const handleDrop = useCallback((e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault()
    setIsDragOver(false)
    if (!isEditable) return

    const scheme = e.dataTransfer.getData('application/camel-component')
    if (!scheme) return

    const def = findComponentByScheme(scheme)
    if (!def) return

    const flowPosition = screenToFlowPosition({ x: e.clientX, y: e.clientY })

    // ── Trigger component: use as (or replace) the from-node ─────────────────
    if (def.stepKind === 'from') {
      const existingFrom = editNodes.find(n => (n.data as unknown as NodeData).stepKind === 'from')
      const fromId = existingFrom?.id ?? `node-from-${Date.now()}`
      const newFrom: Node = {
        id: fromId,
        type: 'default',
        position: { x: 0, y: 0 },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        data: {
          label: def.label,
          params: {},
          uri: def.defaultUri ?? '',
          component: def.scheme,
          stepKind: 'from' as const,
          rawStep: { ...def.defaultRawStep },
          stepIndex: 0,
          routeId: (existingFrom?.data as unknown as NodeData)?.routeId ?? 'route-1',
        } as unknown as Record<string, unknown>,
        style: buildNodeStyle('from'),
      }
      const updatedNodes = existingFrom
        ? editNodes.map(n => n.id === fromId ? newFrom : n)
        : [newFrom]
      const newEdges = buildMainChainEdges(updatedNodes)
      const excEdges = editEdges.filter(e => e.style?.stroke === '#EF4444')
      setEditNodes(updatedNodes)
      setEditEdges([...newEdges, ...excEdges])
      serializeAndEmit(updatedNodes)
      setSelectedNodeId(fromId)
      onNodeSelect?.({ id: fromId, data: newFrom.data as unknown as NodeData })
      return
    }

    // ── Bootstrap an empty canvas: auto-create a timer from-node ──────────────
    if (editNodes.length === 0) {
      const fromNode: Node = {
        id: 'node-from',
        type: 'default',
        position: { x: 0, y: 0 },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        data: {
          label: 'Timer',
          params: { repeatCount: '1' },
          uri: 'timer:trigger',
          component: 'timer',
          stepKind: 'from' as const,
          rawStep: { uri: 'timer:trigger', parameters: { repeatCount: '1' } },
          stepIndex: 0,
          routeId: 'route-1',
        } as unknown as Record<string, unknown>,
        style: buildNodeStyle('from'),
      }
      const newNodeId = `node-drop-${Date.now()}`
      const newNode: Node = {
        id: newNodeId,
        type: 'default',
        position: { x: 220, y: 0 },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        data: {
          label: def.label,
          params: {},
          uri: def.defaultUri ?? '',
          component: def.scheme,
          stepKind: def.stepKind,
          rawStep: { ...def.defaultRawStep },
          stepIndex: 1,
        } as unknown as Record<string, unknown>,
        style: buildNodeStyle(def.stepKind),
      }
      const allNodes = [fromNode, newNode]
      setEditNodes(allNodes)
      setEditEdges(buildMainChainEdges(allNodes))
      serializeAndEmit(allNodes)
      setSelectedNodeId(newNodeId)
      onNodeSelect?.({ id: newNodeId, data: newNode.data as unknown as NodeData })
      return
    }

    // Find insertion index: last main node whose x <= drop x
    const mainNodes = editNodes.filter(n => !(n.data as unknown as NodeData).isException)
    const sorted = [...mainNodes].sort((a, b) =>
      (a.data as unknown as NodeData).stepIndex - (b.data as unknown as NodeData).stepIndex
    )
    let insertAfterIndex = 0
    for (const n of sorted) {
      if (n.position.x <= flowPosition.x) {
        insertAfterIndex = (n.data as unknown as NodeData).stepIndex
      }
    }

    const newStepIndex = insertAfterIndex + 1
    const nodeId = `node-drop-${Date.now()}`
    const nodeStyle = buildNodeStyle(def.stepKind)

    const newNode: Node = {
      id: nodeId,
      type: 'default',
      position: { x: newStepIndex * 220, y: 0 },
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      data: {
        label: def.label,
        params: {},
        uri: def.defaultUri ?? '',
        component: def.scheme,
        stepKind: def.stepKind,
        rawStep: { ...def.defaultRawStep },
        stepIndex: newStepIndex,
      } as unknown as Record<string, unknown>,
      style: nodeStyle,
    }

    // Shift existing nodes after the insertion point
    const exceptionNodes = editNodes.filter(n => (n.data as unknown as NodeData).isException)
    const shiftedMain = sorted.map(n => {
      const d = n.data as unknown as NodeData
      if (d.stepIndex >= newStepIndex) {
        return {
          ...n,
          position: { ...n.position, x: (d.stepIndex + 1) * 220 },
          data: { ...d, stepIndex: d.stepIndex + 1 } as unknown as Record<string, unknown>,
        }
      }
      return n
    })

    const allMain = [...shiftedMain, newNode].sort((a, b) =>
      (a.data as unknown as NodeData).stepIndex - (b.data as unknown as NodeData).stepIndex
    )
    const newEdges = buildMainChainEdges(allMain)
    const excEdges = editEdges.filter(e => e.style?.stroke === '#EF4444')
    const allNodes = [...allMain, ...exceptionNodes]

    setEditNodes(allNodes)
    setEditEdges([...newEdges, ...excEdges])
    serializeAndEmit(allNodes)

    // Select the new node
    setSelectedNodeId(nodeId)
    onNodeSelect?.({ id: nodeId, data: newNode.data as unknown as NodeData })
  }, [isEditable, editNodes, editEdges, screenToFlowPosition, serializeAndEmit, onNodeSelect])

  // ── Render error states ───────────────────────────────────────────────────
  const errorMessage = isEditable ? parseEditError : parseError
  if (errorMessage) {
    return (
      <div className="flex items-center justify-center w-full h-full p-6">
        <div className="rounded-lg border border-orange-200 bg-orange-50 px-4 py-3 text-sm text-orange-700 max-w-md">
          <p className="font-semibold mb-1">Could not visualise route</p>
          <p className="text-xs">{errorMessage}</p>
        </div>
      </div>
    )
  }

  if (!isEditable && !flowData) return null

  const displayNodes = isEditable ? editNodes : (flowData?.nodes ?? [])
  const displayEdges = isEditable ? editEdges : (flowData?.edges ?? [])

  return (
    <div
      className={`w-full h-full relative overflow-hidden animate-fade-in-up transition-colors duration-150 ${
        isDragOver ? 'bg-amber-50/50 ring-2 ring-inset ring-amber-300' : ''
      }`}
      onDrop={isEditable ? handleDrop : undefined}
      onDragOver={isEditable ? handleDragOver : undefined}
      onDragLeave={isEditable ? handleDragLeave : undefined}
    >
      <ReactFlow
        nodes={displayNodes as Node[]}
        edges={displayEdges as Edge[]}
        fitView
        fitViewOptions={{ padding: 0.3 }}
        nodesDraggable={isEditable}
        nodesConnectable={false}
        elementsSelectable={true}
        deleteKeyCode={null}
        panOnDrag
        zoomOnScroll
        onNodeClick={handleNodeClick}
        onPaneClick={handlePaneClick}
        onNodeContextMenu={isEditable ? handleNodeContextMenu : undefined}
        onNodeDragStop={isEditable ? handleNodeDragStop : undefined}
        proOptions={{ hideAttribution: false }}
      >
        <Background color="#E2E8F0" gap={20} />
        <Controls showInteractive={false} />
      </ReactFlow>

      {/* Read-only mode: NodeDetailDrawer overlay */}
      {!isEditable && (
        <NodeDetailDrawer
          node={readOnlySelectedNode}
          onClose={() => setReadOnlySelectedNode(null)}
        />
      )}

      {/* Editable mode: drag-over hint */}
      {isEditable && isDragOver && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <div className="bg-amber-100 border border-amber-300 rounded-lg px-4 py-2 text-amber-700 text-sm font-medium shadow">
            Drop to add component
          </div>
        </div>
      )}

      {/* Editable mode: selection indicator */}
      {isEditable && selectedNodeId && !contextMenu && (
        <div className="absolute bottom-10 left-1/2 -translate-x-1/2 pointer-events-none">
          <div className="bg-slate-800/70 text-white text-xs px-2 py-1 rounded-md">
            Right-click to delete · Drag to reorder
          </div>
        </div>
      )}

      {/* Right-click context menu */}
      {contextMenu && (
        <div
          className="fixed z-50 bg-white border border-slate-200 rounded-lg shadow-xl py-1 min-w-[140px]"
          style={{ left: contextMenu.x, top: contextMenu.y }}
        >
          <button
            onClick={() => {
              deleteNode(contextMenu.nodeId)
              setContextMenu(null)
            }}
            className="w-full px-3 py-2 text-xs text-left text-red-600 hover:bg-red-50 flex items-center gap-2 transition-colors"
          >
            <ContextTrashIcon />
            Delete node
          </button>
        </div>
      )}

      {/* Dismiss context menu on click outside */}
      {contextMenu && (
        <div
          className="fixed inset-0 z-40"
          onClick={() => setContextMenu(null)}
        />
      )}
    </div>
  )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function buildMainChainEdges(mainNodes: Node[]): Edge[] {
  const sorted = [...mainNodes].sort(
    (a, b) => (a.data as unknown as NodeData).stepIndex - (b.data as unknown as NodeData).stepIndex
  )
  const edges: Edge[] = []
  for (let i = 1; i < sorted.length; i++) {
    edges.push({
      id: `edge-${i - 1}-${i}`,
      source: sorted[i - 1].id,
      target: sorted[i].id,
      type: 'default',
      animated: false,
      style: { stroke: '#94A3B8', strokeWidth: 2 },
      markerEnd: { type: MarkerType.ArrowClosed, color: '#94A3B8' },
    })
  }
  return edges
}

function buildNodeStyle(stepKind: StepKind): React.CSSProperties {
  const colour = getColourForStepKind(stepKind)
  return {
    background: colour,
    color: '#fff',
    border: 'none',
    borderRadius: '8px',
    padding: '8px 14px',
    fontSize: '12px',
    fontWeight: 500,
    minWidth: '120px',
    textAlign: 'center',
    boxShadow: '0 2px 8px rgba(0,0,0,0.15)',
  }
}

function getColourForStepKind(stepKind: StepKind): string {
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
    default: return '#3B82F6'
  }
}

function ContextTrashIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 14 14" fill="none">
      <path d="M2 3.5h10M5.5 3.5V2.5a.5.5 0 0 1 .5-.5h2a.5.5 0 0 1 .5.5v1M11 3.5l-.7 7.7a.5.5 0 0 1-.5.3H4.2a.5.5 0 0 1-.5-.3L3 3.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
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
