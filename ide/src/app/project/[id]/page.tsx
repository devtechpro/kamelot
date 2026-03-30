'use client'

import { useState, useRef, useEffect } from 'react'
import { useParams, useRouter } from 'next/navigation'
import dynamic from 'next/dynamic'
import { Panel, PanelGroup, PanelResizeHandle } from 'react-resizable-panels'
import type { ImperativePanelHandle } from 'react-resizable-panels'
import { FileTree } from '@/components/FileTree'
import type { GeneratedFile } from '@/components/FileTree'
import type { ConversationMessage, LLMResponse } from '@/lib/interfaces/ILLMProvider'
import type { ExecutionResult } from '@/lib/interfaces/IRouteExecutor'
import type { ClarificationState } from '@/components/Terminal'
import type { StreamLine } from '@/components/TestPanel'
import type { NodeData } from '@/lib/interfaces/INodeData'
import { parseCamelYaml } from '@/lib/camel-parser'
import { serializeCamelYaml } from '@/lib/camel-serializer'

const Terminal = dynamic(
  () => import('@/components/Terminal').then((m) => m.Terminal),
  { ssr: false, loading: () => <PanelSkeleton dark /> }
)

const YamlEditor = dynamic(
  () => import('@/components/YamlEditor').then((m) => m.YamlEditor),
  { ssr: false, loading: () => <PanelSkeleton /> }
)

const RouteCanvas = dynamic(
  () => import('@/components/RouteCanvas').then((m) => m.RouteCanvas),
  { ssr: false, loading: () => <PanelSkeleton /> }
)

const SequenceDiagram = dynamic(
  () => import('@/components/SequenceDiagram').then((m) => m.SequenceDiagram),
  { ssr: false, loading: () => <PanelSkeleton /> }
)

const PropertiesEditor = dynamic(
  () => import('@/components/PropertiesEditor').then((m) => m.PropertiesEditor),
  { ssr: false, loading: () => <PanelSkeleton /> }
)

const TestPanel = dynamic(
  () => import('@/components/TestPanel').then((m) => m.TestPanel),
  { ssr: false, loading: () => <PanelSkeleton dark /> }
)

const ComponentPalette = dynamic(
  () => import('@/components/ComponentPalette').then((m) => m.ComponentPalette),
  { ssr: false, loading: () => <PanelSkeleton /> }
)

const NodePropertiesPanel = dynamic(
  () => import('@/components/NodePropertiesPanel').then((m) => m.NodePropertiesPanel),
  { ssr: false, loading: () => <PanelSkeleton /> }
)

const MarkdownViewer = dynamic(
  () => import('@/components/MarkdownViewer').then((m) => m.MarkdownViewer),
  { ssr: false, loading: () => <PanelSkeleton /> }
)

interface OpenTab {
  id: string
  name: string
  type: 'flow' | 'yaml' | 'sequence' | 'properties' | 'markdown'
}

type BottomTab = 'terminal' | 'testoutput'

export default function ProjectPage() {
  const params = useParams()
  const router = useRouter()
  const projectId = typeof params.id === 'string' ? params.id : ''

  const leftPanelRef = useRef<ImperativePanelHandle>(null)
  const bottomPanelRef = useRef<ImperativePanelHandle>(null)
  const [leftPanelOpen, setLeftPanelOpen] = useState(true)
  const [isBottomExpanded, setIsBottomExpanded] = useState(false)
  const [bottomTab, setBottomTab] = useState<BottomTab>('terminal')
  const [openTabs, setOpenTabs] = useState<OpenTab[]>([])
  const [activeTabId, setActiveTabId] = useState<string | null>(null)

  // Terminal / generation state
  const [prompt, setPrompt] = useState('')
  const [plantUmlFile, setPlantUmlFile] = useState<string | null>(null)
  const [plantUmlFileName, setPlantUmlFileName] = useState<string | null>(null)
  const [conversation, setConversation] = useState<ConversationMessage[]>([])
  const [clarification, setClarification] = useState<ClarificationState | null>(null)
  const [isGenerating, setIsGenerating] = useState(false)
  const [generateError, setGenerateError] = useState<string | null>(null)

  // Generated content
  const [generatedYaml, setGeneratedYaml] = useState<string | null>(null)
  const [generatedPlantuml, setGeneratedPlantuml] = useState<string | null>(null)
  const [diagramUrl, setDiagramUrl] = useState<string | null>(null)
  const [applicationYaml, setApplicationYaml] = useState<string>('')
const [generatedFiles, setGeneratedFiles] = useState<GeneratedFile[]>([])

  // Manual canvas editing
  const [selectedCanvasNode, setSelectedCanvasNode] = useState<{ id: string; data: NodeData } | null>(null)

  // Save indicator
  const [savedIndicator, setSavedIndicator] = useState(false)

  // Execution state
  const [executionResult, setExecutionResult] = useState<ExecutionResult | null>(null)
  const [isExecuting, setIsExecuting] = useState(false)
  const [isLive, setIsLive] = useState(false)
  const [executeError, setExecuteError] = useState<string | null>(null)
  const [jbangMissing, setJbangMissing] = useState(false)
  const [streamLines, setStreamLines] = useState<StreamLine[]>([])

  // Clear canvas node selection when switching away from the Flow tab
  useEffect(() => {
    const tab = openTabs.find((t) => t.id === activeTabId)
    if (tab?.type !== 'flow') {
      setSelectedCanvasNode(null)
    }
  }, [activeTabId, openTabs])

  // Keep a ref to applicationYaml for the save handler to always use latest value
  const applicationYamlRef = useRef(applicationYaml)
  useEffect(() => {
    applicationYamlRef.current = applicationYaml
  }, [applicationYaml])

  // Page-level CMD+S — save all files regardless of active tab
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.metaKey && e.key === 's') {
        e.preventDefault()
        void handleSaveAll()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, generatedYaml, generatedPlantuml])

  // Load saved project files on mount + fire warmup
  useEffect(() => {
    if (!projectId) return
    void loadProjectFiles()
    // Fire-and-forget JBang pre-warm
    void fetch('/api/execute/warmup')
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId])

  async function loadProjectFiles(): Promise<void> {
    try {
      const res = await fetch(`/api/projects/${projectId}/files`)
      if (!res.ok) return
      const data = (await res.json()) as {
        files: { name: string; content: string }[]
        diagramUrl: string | null
      }

      const routeYaml = data.files.find((f) => f.name === 'route.yaml')
      const seqPuml = data.files.find((f) => f.name === 'sequence.puml')
      const appYaml = data.files.find((f) => f.name === 'application.yaml')
      const testOut = data.files.find((f) => f.name === 'test-output.txt')
      const reqMd = data.files.find((f) => f.name === 'requirements.md')

      if (!routeYaml && !seqPuml && !appYaml) return // nothing saved yet

      const files: GeneratedFile[] = [
        { name: 'route.flow', type: 'flow', content: '', tabTarget: 'flow' },
      ]

      if (routeYaml) {
        setGeneratedYaml(routeYaml.content)
        files.push({ name: 'route.yaml', type: 'yaml', content: routeYaml.content, tabTarget: 'yaml' })
      }
      if (seqPuml) {
        setGeneratedPlantuml(seqPuml.content)
        if (data.diagramUrl) setDiagramUrl(data.diagramUrl)
        files.push({ name: 'sequence.puml', type: 'plantuml', content: seqPuml.content, tabTarget: 'sequence' })
      }
      if (appYaml) {
        setApplicationYaml(appYaml.content)
        applicationYamlRef.current = appYaml.content
        files.push({
          name: 'application.yaml',
          type: 'properties',
          content: appYaml.content,
          editable: true,
          tabTarget: 'properties',
        })
      }
      if (testOut) {
        files.push({ name: 'test-output.txt', type: 'text', content: testOut.content })
      }
      if (reqMd) {
        files.push({ name: 'requirements.md', type: 'text', content: reqMd.content, tabTarget: 'markdown' })
      }

      setGeneratedFiles(files)
      setOpenTabs([{ id: 'route.flow', name: 'route.flow', type: 'flow' }])
      setActiveTabId('route.flow')
    } catch {
      // silently fail — start fresh
    }
  }

  async function callGenerate(messages: ConversationMessage[]): Promise<void> {
    setIsGenerating(true)
    setGenerateError(null)
    setClarification(null)

    try {
      const res = await fetch('/api/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(
          plantUmlFile
            ? { plantUml: plantUmlFile, projectId }
            : { messages, projectId, currentYaml: generatedYaml ?? undefined }
        ),
      })

      const data = (await res.json()) as LLMResponse & {
        error?: string
        applicationYaml?: string
explanation?: string
        content?: string
        requirements?: string
      }

      if (!res.ok) {
        setGenerateError((data as { error?: string }).error ?? 'Generation failed')
        return
      }

      if (data.type === 'clarification') {
        // Persist the question in conversation history so it survives regardless
        // of whether the user clicks a suggestion or types their own reply.
        setConversation((prev) => [
          ...prev,
          { role: 'assistant', content: data.question },
        ])
        setClarification({ question: data.question, suggestions: data.suggestions })
        if (data.requirements) {
          setGeneratedFiles((prev) => {
            const existing = prev.find((f) => f.name === 'requirements.md')
            if (existing) {
              return prev.map((f) => f.name === 'requirements.md' ? { ...f, content: data.requirements! } : f)
            }
            return [...prev, { name: 'requirements.md', type: 'text' as const, content: data.requirements!, tabTarget: 'markdown' as const }]
          })
        }
        return
      }

      if (data.type === 'answer') {
        setConversation((prev) => [
          ...prev,
          { role: 'assistant', content: data.content! },
        ])
        return
      }

      if (data.type === 'result') {
        // Push explanation as assistant message
        if (data.explanation) {
          setConversation((prev) => [
            ...prev,
            { role: 'assistant', content: data.explanation! },
          ])
        }

        setGeneratedYaml(data.yaml)
        setGeneratedPlantuml(data.plantuml)
        setDiagramUrl(data.diagramUrl)

        const appYaml = data.applicationYaml ?? ''
        setApplicationYaml(appYaml)
        applicationYamlRef.current = appYaml

        const files: GeneratedFile[] = [
          { name: 'route.flow', type: 'flow', content: '', tabTarget: 'flow' },
          { name: 'route.yaml', type: 'yaml', content: data.yaml, tabTarget: 'yaml' },
          {
            name: 'sequence.puml',
            type: 'plantuml',
            content: data.plantuml,
            tabTarget: 'sequence',
          },
          ...(appYaml
            ? [
                {
                  name: 'application.yaml',
                  type: 'properties' as const,
                  content: appYaml,
                  editable: true,
                  tabTarget: 'properties' as const,
                },
              ]
            : []),
        ]
        setGeneratedFiles(files)

        if (data.requirements) {
          setGeneratedFiles((prev) => {
            const withoutReq = prev.filter((f) => f.name !== 'requirements.md')
            return [...withoutReq, { name: 'requirements.md', type: 'text' as const, content: data.requirements!, tabTarget: 'markdown' as const }]
          })
        }

        // Auto-open / activate flow tab
        setOpenTabs((prev) => {
          if (prev.find((t) => t.id === 'route.flow')) return prev
          return [...prev, { id: 'route.flow', name: 'route.flow', type: 'flow' }]
        })
        setActiveTabId('route.flow')
      }
    } catch {
      setGenerateError('Network error — please check your connection and try again')
    } finally {
      setIsGenerating(false)
    }
  }

  // ── Canvas editing handlers ────────────────────────────────────────────────

  function handleCanvasYamlChange(newYaml: string) {
    setGeneratedYaml(newYaml)
    setGeneratedFiles((prev) =>
      prev.map((f) => (f.name === 'route.yaml' ? { ...f, content: newYaml } : f))
    )
  }

  function handleNodeSelect(node: { id: string; data: NodeData } | null) {
    setSelectedCanvasNode(node)
  }

  function handleNodeDataChange(nodeId: string, updatedData: NodeData) {
    setSelectedCanvasNode({ id: nodeId, data: updatedData })
    if (!generatedYaml) return
    try {
      const { nodes } = parseCamelYaml(generatedYaml)
      const updatedNodes = nodes.map((n) =>
        n.id === nodeId
          ? { ...n, data: updatedData as unknown as Record<string, unknown> }
          : n
      )
      const newYaml = serializeCamelYaml(updatedNodes)
      handleCanvasYamlChange(newYaml)
    } catch {
      // Ignore serialization errors — node data remains in the panel
    }
  }

  function handleNodeDelete(nodeId: string) {
    if (!generatedYaml) return
    try {
      const { nodes } = parseCamelYaml(generatedYaml)
      const remaining = nodes.filter((n) => n.id !== nodeId)
      const mainNodes = remaining.filter(
        (n) => !(n.data as unknown as NodeData).isException
      )
      const sorted = [...mainNodes].sort(
        (a, b) =>
          (a.data as unknown as NodeData).stepIndex -
          (b.data as unknown as NodeData).stepIndex
      )
      const renumbered = sorted.map((n, idx) => ({
        ...n,
        data: { ...(n.data as unknown as NodeData), stepIndex: idx } as unknown as Record<string, unknown>,
      }))
      const exceptionNodes = remaining.filter(
        (n) => (n.data as unknown as NodeData).isException
      )
      const newYaml = serializeCamelYaml([...renumbered, ...exceptionNodes])
      handleCanvasYamlChange(newYaml)
      setSelectedCanvasNode(null)
    } catch {
      // Ignore errors
    }
  }

  function handleGenerate() {
    const userMessage: ConversationMessage = { role: 'user', content: prompt }
    const updated = [...conversation, userMessage]
    setConversation(updated)
    setPrompt('')
    void callGenerate(updated)
  }

  function handleClarificationAnswer(answer: string) {
    // The assistant question is already in conversation (added when clarification arrived).
    // Only add the user's answer.
    const userAnswer: ConversationMessage = { role: 'user', content: answer }
    const updated = [...conversation, userAnswer]
    setConversation(updated)
    void callGenerate(updated)
  }

  function handleClearTerminal() {
    setPrompt('')
    setPlantUmlFile(null)
    setPlantUmlFileName(null)
    setConversation([])
    setClarification(null)
    setGenerateError(null)
  }

  function handlePropertiesChange(value: string) {
    setApplicationYaml(value)
    setGeneratedFiles((prev) =>
      prev.map((f) => (f.name === 'application.yaml' ? { ...f, content: value } : f))
    )
  }

  async function handleSaveAll(): Promise<void> {
    if (!projectId) return
    const saves: Promise<unknown>[] = [
      fetch(`/api/projects/${projectId}/files`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ filename: 'application.yaml', content: applicationYamlRef.current }),
      }),
    ]
    if (generatedYaml) {
      saves.push(
        fetch(`/api/projects/${projectId}/files`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ filename: 'route.yaml', content: generatedYaml }),
        })
      )
    }
    if (generatedPlantuml) {
      saves.push(
        fetch(`/api/projects/${projectId}/files`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ filename: 'sequence.puml', content: generatedPlantuml }),
        })
      )
    }
    await Promise.all(saves).catch(() => {})
    setSavedIndicator(true)
    setTimeout(() => setSavedIndicator(false), 2000)
  }

  async function handleExecute(): Promise<void> {
    if (!generatedYaml) return
    setIsExecuting(true)
    setIsLive(false)
    setExecuteError(null)
    setJbangMissing(false)
    setExecutionResult(null)
    setStreamLines([])

    try {
      const res = await fetch('/api/execute', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ yaml: generatedYaml, applicationYaml, projectId }),
      })

      if (res.status === 503) {
        setJbangMissing(true)
        return
      }

      if (!res.ok || !res.body) {
        setExecuteError('Execution failed')
        return
      }

      // Read SSE stream
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        // SSE events are separated by double newlines
        const parts = buffer.split('\n\n')
        buffer = parts.pop() ?? ''

        for (const part of parts) {
          if (!part.startsWith('data: ')) continue
          try {
            const event = JSON.parse(part.slice(6)) as {
              type: 'stdout' | 'stderr' | 'live' | 'done'
              line?: string
              result?: ExecutionResult
            }
            if (event.type === 'stdout' || event.type === 'stderr') {
              setStreamLines((prev) => [...prev, { type: event.type as 'stdout' | 'stderr', line: event.line ?? '' }])
            } else if (event.type === 'live') {
              setIsLive(true)
            } else if (event.type === 'done' && event.result) {
              setExecutionResult(event.result)
              if (event.result.stdout || event.result.stderr) {
                const outputFile: GeneratedFile = {
                  name: 'test-output.txt',
                  type: 'text',
                  content:
                    event.result.stdout +
                    (event.result.stderr ? `\n\nSTDERR:\n${event.result.stderr}` : ''),
                }
                setGeneratedFiles((prev) => [
                  ...prev.filter((f) => f.name !== 'test-output.txt'),
                  outputFile,
                ])
              }
            }
          } catch {
            // malformed event — skip
          }
        }
      }
    } catch {
      setExecuteError('Network error during execution')
    } finally {
      setIsExecuting(false)
      setIsLive(false)
    }
  }

  async function handleStop(): Promise<void> {
    await fetch('/api/execute', { method: 'DELETE' })
    // State resets automatically when the SSE stream closes (done event)
  }

  function handleExecuteAndSwitch() {
    void handleExecute()
    setBottomTab('testoutput')
  }

  function handleFileClick(file: GeneratedFile) {
    if (!file.tabTarget) return
    const tabType = file.tabTarget as 'flow' | 'yaml' | 'sequence' | 'properties' | 'markdown'
    const tabId = file.name
    setOpenTabs((prev) => {
      if (prev.find((t) => t.id === tabId)) return prev
      return [...prev, { id: tabId, name: file.name, type: tabType }]
    })
    setActiveTabId(tabId)
  }

  function closeTab(tabId: string) {
    const idx = openTabs.findIndex((t) => t.id === tabId)
    const next = openTabs.filter((t) => t.id !== tabId)
    if (activeTabId === tabId) {
      const newActive = next[idx - 1]?.id ?? next[idx]?.id ?? null
      setActiveTabId(newActive)
    }
    setOpenTabs(next)
  }

  function handleDeleteFile(file: GeneratedFile) {
    setGeneratedFiles((prev) => prev.filter((f) => f.name !== file.name))
    closeTab(file.name)
  }

  const activeTab = openTabs.find((t) => t.id === activeTabId) ?? null
  const canRun = !!generatedYaml && !isExecuting
  const isFlowEditing = activeTab?.type === 'flow'

  return (
    <div className="h-screen flex flex-col overflow-hidden bg-slate-50">
      {/* Header */}
      <header className="flex-shrink-0 bg-[#0F1729] px-6 py-3 flex items-center gap-3 shadow-lg z-10">
        <div
          className="flex items-center gap-2 cursor-pointer"
          onClick={() => router.push('/')}
          aria-label="Back to projects"
        >
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
        <div className="flex-1" />
        {savedIndicator && (
          <span className="text-emerald-400 text-xs font-medium px-2 py-1 bg-emerald-900/30 rounded transition-opacity">
            Saved ✓
          </span>
        )}
      </header>

      {/* Layout */}
      <div className="flex-1 overflow-hidden">
        <PanelGroup direction="vertical" id="outer" style={{ height: '100%' }}>
          <Panel defaultSize={75} minSize={50}>
            <PanelGroup direction="horizontal" id="inner" style={{ height: '100%' }}>
              {/* Left panel — file tree */}
              <Panel
                ref={leftPanelRef}
                defaultSize={18}
                minSize={0}
                maxSize={30}
                collapsible
                onCollapse={() => setLeftPanelOpen(false)}
                onExpand={() => setLeftPanelOpen(true)}
                style={{ overflow: 'hidden' }}
              >
                <aside className="w-full h-full border-r border-slate-200 bg-white overflow-y-auto">
                  <FileTree
                    files={generatedFiles}
                    activeFile={null}
                    onFileClick={handleFileClick}
                    onDeleteFile={handleDeleteFile}
                  />
                </aside>
              </Panel>

              {/* Resize handle with collapse toggle */}
              <PanelResizeHandle
                style={{
                  width: '6px',
                  background: '#E2E8F0',
                  cursor: 'col-resize',
                  position: 'relative',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <button
                  onClick={() => {
                    if (leftPanelOpen) {
                      leftPanelRef.current?.collapse()
                    } else {
                      leftPanelRef.current?.expand()
                    }
                  }}
                  aria-label={leftPanelOpen ? 'Collapse file tree' : 'Expand file tree'}
                  style={{
                    position: 'absolute',
                    zIndex: 10,
                    width: '16px',
                    height: '32px',
                    background: '#F1F5F9',
                    border: '1px solid #CBD5E1',
                    borderRadius: '4px',
                    color: '#64748B',
                    fontSize: '12px',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    cursor: 'pointer',
                  }}
                  onMouseEnter={(e) => {
                    ;(e.currentTarget as HTMLButtonElement).style.background = '#FCD34D'
                  }}
                  onMouseLeave={(e) => {
                    ;(e.currentTarget as HTMLButtonElement).style.background = '#F1F5F9'
                  }}
                >
                  {leftPanelOpen ? '‹' : '›'}
                </button>
              </PanelResizeHandle>

              {/* Right panel — dynamic tabs + editing sidebar */}
              <Panel defaultSize={82} style={{ overflow: 'hidden' }}>
                <div className="flex h-full overflow-hidden">
                  {/* Main tab area */}
                  <main className="flex flex-col flex-1 overflow-hidden min-w-0">
                    {/* Tab strip */}
                    <div className="flex-shrink-0 border-b border-slate-200 bg-white px-4 flex items-end gap-1 min-h-[38px]">
                      {openTabs.map((tab) => (
                        <div
                          key={tab.id}
                          className={`flex items-center gap-1.5 px-3 py-2 text-xs font-medium border-b-2 -mb-px cursor-pointer transition-colors select-none ${
                            activeTabId === tab.id
                              ? 'border-amber-500 text-amber-600'
                              : 'border-transparent text-slate-500 hover:text-slate-700'
                          }`}
                          onClick={() => setActiveTabId(tab.id)}
                        >
                          <span>{tab.name}</span>
                          <button
                            onClick={(e) => {
                              e.stopPropagation()
                              closeTab(tab.id)
                            }}
                            aria-label={`Close ${tab.name}`}
                            className="w-3.5 h-3.5 rounded-sm hover:bg-slate-200 text-slate-400 hover:text-slate-600 flex items-center justify-center text-[11px] leading-none transition-colors"
                          >
                            ×
                          </button>
                        </div>
                      ))}
                      {openTabs.length === 0 && (
                        <span className="text-xs text-slate-400 py-2 px-1">
                          Click a file in the panel to open it
                        </span>
                      )}
                    </div>

                    {/* Tab content */}
                    <div className="flex-1 overflow-hidden relative" style={{ minHeight: 0 }}>
                      {activeTab?.type === 'flow' && (
                        <div className="absolute inset-0">
                          <RouteCanvas
                            yaml={generatedYaml}
                            onYamlChange={handleCanvasYamlChange}
                            onNodeSelect={handleNodeSelect}
                          />
                        </div>
                      )}
                      {activeTab?.type === 'yaml' && (
                        <div className="absolute inset-0">
                          <YamlEditor
                            value={generatedYaml}
                            onChange={(v) => {
                              setGeneratedYaml(v)
                              setGeneratedFiles((prev) =>
                                prev.map((f) => (f.name === 'route.yaml' ? { ...f, content: v } : f))
                              )
                            }}
                          />
                        </div>
                      )}
{activeTab?.type === 'sequence' && (
                        <div className="absolute inset-0">
                          <SequenceDiagram diagramUrl={diagramUrl} />
                        </div>
                      )}
                      {activeTab?.type === 'properties' && (
                        <div className="absolute inset-0">
                          <PropertiesEditor
                            value={applicationYaml}
                            onChange={handlePropertiesChange}
                            onSave={() => void handleSaveAll()}
                          />
                        </div>
                      )}
                      {activeTab?.type === 'markdown' && (
                        <div className="absolute inset-0">
                          <MarkdownViewer
                            content={generatedFiles.find((f) => f.name === activeTab.id)?.content ?? null}
                          />
                        </div>
                      )}
                      {!activeTab && (
                        <div className="flex items-center justify-center h-full text-slate-400 text-sm">
                          <div className="text-center">
                            <p>Click a file in the panel to open it</p>
                          </div>
                        </div>
                      )}
                    </div>
                  </main>

                  {/* Right sidebar: always visible palette / properties when node selected */}
                  <div className="flex-shrink-0 w-56 border-l border-slate-200 overflow-hidden">
                    {isFlowEditing && selectedCanvasNode ? (
                      <NodePropertiesPanel
                        nodeId={selectedCanvasNode.id}
                        nodeData={selectedCanvasNode.data}
                        onChange={handleNodeDataChange}
                        onDelete={handleNodeDelete}
                      />
                    ) : (
                      <ComponentPalette />
                    )}
                  </div>
                </div>
              </Panel>
            </PanelGroup>
          </Panel>

          {/* Horizontal resize handle */}
          <PanelResizeHandle
            style={{
              height: '5px',
              background: '#E2E8F0',
              cursor: 'row-resize',
            }}
          />

          {/* Bottom panel */}
          <Panel ref={bottomPanelRef} defaultSize={25} minSize={15} style={{ overflow: 'hidden' }}>
            <div className="flex flex-col h-full bg-white">
              {/* Bottom panel header */}
              <div className="flex-shrink-0 flex items-center gap-1.5 px-4 py-1.5 border-b border-slate-100 bg-white">
                <button
                  onClick={() => setBottomTab('terminal')}
                  className={`text-xs font-medium px-3 py-1.5 rounded transition-colors ${
                    bottomTab === 'terminal'
                      ? 'bg-amber-100 text-amber-700'
                      : 'text-slate-500 hover:text-slate-700'
                  }`}
                >
                  Terminal
                </button>

                {/* Run / Stop button */}
                {isExecuting ? (
                  <button
                    onClick={() => void handleStop()}
                    aria-label="Stop the running route"
                    className="flex items-center gap-1.5 rounded bg-red-500 hover:bg-red-400 text-white text-xs font-semibold px-3 py-1.5 transition-colors"
                  >
                    <svg width="8" height="8" viewBox="0 0 8 8" fill="currentColor">
                      <rect width="8" height="8" rx="1" />
                    </svg>
                    Stop
                  </button>
                ) : (
                  <button
                    onClick={handleExecuteAndSwitch}
                    disabled={!canRun}
                    aria-label="Run the route via JBang"
                    className="flex items-center gap-1.5 rounded bg-green-500 hover:bg-green-400 text-white text-xs font-semibold px-3 py-1.5 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    <svg width="8" height="10" viewBox="0 0 8 10" fill="currentColor">
                      <path d="M0 0l8 5-8 5V0z" />
                    </svg>
                    Run
                  </button>
                )}

                <button
                  onClick={() => setBottomTab('testoutput')}
                  className={`text-xs font-medium px-3 py-1.5 rounded transition-colors ${
                    bottomTab === 'testoutput'
                      ? 'bg-amber-100 text-amber-700'
                      : 'text-slate-500 hover:text-slate-700'
                  }`}
                >
                  Test Output
                </button>

                <div className="flex-1" />

                {/* Expand/collapse terminal */}
                <button
                  onClick={() => {
                    if (isBottomExpanded) {
                      bottomPanelRef.current?.resize(25)
                      setIsBottomExpanded(false)
                    } else {
                      bottomPanelRef.current?.resize(65)
                      setIsBottomExpanded(true)
                    }
                  }}
                  aria-label={isBottomExpanded ? 'Collapse terminal' : 'Expand terminal'}
                  className="text-slate-400 hover:text-slate-600 p-1 rounded transition-colors"
                  title={isBottomExpanded ? 'Collapse terminal' : 'Expand terminal'}
                >
                  {isBottomExpanded ? (
                    <svg width="13" height="13" viewBox="0 0 13 13" fill="none">
                      <path d="M2 9l4.5-4.5L11 9" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
                    </svg>
                  ) : (
                    <svg width="13" height="13" viewBox="0 0 13 13" fill="none">
                      <path d="M2 4l4.5 4.5L11 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
                    </svg>
                  )}
                </button>

                {/* Clear terminal */}
                <button
                  onClick={handleClearTerminal}
                  aria-label="Clear terminal"
                  className="text-slate-400 hover:text-slate-600 p-1 rounded transition-colors"
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
              </div>

              {/* Bottom panel content */}
              <div className="flex-1 overflow-hidden">
                {bottomTab === 'terminal' && (
                  <Terminal
                    conversation={conversation}
                    prompt={prompt}
                    onPromptChange={setPrompt}
                    onGenerate={handleGenerate}
                    onClearTerminal={handleClearTerminal}
                    isGenerating={isGenerating}
                    error={generateError}
                    clarification={clarification}
                    onClarificationAnswer={handleClarificationAnswer}
                    plantUmlFileName={plantUmlFileName}
                    onPlantUmlUpload={(content, name) => {
                      setPlantUmlFile(content)
                      setPlantUmlFileName(name)
                    }}
                    onPlantUmlClear={() => {
                      setPlantUmlFile(null)
                      setPlantUmlFileName(null)
                    }}
                  />
                )}
                {bottomTab === 'testoutput' && (
                  <TestPanel
                    isExecuting={isExecuting}
                    isLive={isLive}
                    result={executionResult}
                    error={executeError}
                    jbangMissing={jbangMissing}
                    streamLines={streamLines}
                  />
                )}
              </div>
            </div>
          </Panel>
        </PanelGroup>
      </div>
    </div>
  )
}

function PanelSkeleton({ dark }: { dark?: boolean }) {
  return (
    <div
      className={`w-full h-full animate-pulse ${dark ? 'bg-[#0F1729]' : 'bg-slate-50'}`}
    />
  )
}
