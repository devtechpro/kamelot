import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useCanvasStore } from '@/stores/canvas'
import type { Project, FlowConfig, StepConfig, OperationDef, SpecFile, AdapterConfig } from '@/types'

const API = '/api/projects'

export const useProjectStore = defineStore('project', () => {
  const projects = ref<Project[]>([])
  const currentProject = ref<Project | null>(null)
  const loading = ref(false)
  const dsl = ref('')
  const runnerState = ref<string>('STOPPED')
  const runnerPort = ref<number | null>(null)
  const runnerError = ref<string | null>(null)
  const hotReload = ref(false)

  function autoSelectFirst() {
    const canvas = useCanvasStore()
    // Only auto-select flows from exposed specs
    const exposedOpIds = new Set(exposedSpecs.value.flatMap(s => s.parsed.operations.map(o => o.operationId)))
    const firstExposedFlow = currentProject.value?.flows.find(f => exposedOpIds.has(f.operationId))
    if (firstExposedFlow) {
      canvas.selectFlow(firstExposedFlow.operationId)
    } else {
      canvas.clearSelection()
    }
  }

  const exposedSpecs = computed<SpecFile[]>(() => {
    if (!currentProject.value) return []
    const ids = currentProject.value.expose?.specIds ?? []
    return currentProject.value.specs.filter(s => ids.includes(s.id))
  })

  const operations = computed<OperationDef[]>(() => {
    return exposedSpecs.value.flatMap(s => s.parsed.operations)
  })

  async function fetchProjects() {
    loading.value = true
    try {
      const res = await fetch(API)
      projects.value = await res.json()
    } finally {
      loading.value = false
    }
  }

  async function createProject(name: string, description?: string) {
    const res = await fetch(API, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, description }),
    })
    const project = await res.json()
    projects.value.unshift(project)
    currentProject.value = project
    return project
  }

  async function loadProject(id: string) {
    loading.value = true
    try {
      const res = await fetch(`${API}/${id}`)
      currentProject.value = await res.json()
      autoSelectFirst()
      await fetchRunnerStatus()
    } finally {
      loading.value = false
    }
  }

  function closeProject() {
    currentProject.value = null
    runnerState.value = 'STOPPED'
    runnerPort.value = null
    runnerError.value = null
    hotReload.value = false
  }

  async function saveProject() {
    if (!currentProject.value) return
    await fetch(`${API}/${currentProject.value.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(currentProject.value),
    })
  }

  async function uploadSpec(filename: string, content: string) {
    if (!currentProject.value) return
    const res = await fetch(`${API}/${currentProject.value.id}/specs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ filename, content }),
    })
    if (!res.ok) {
      const err = await res.json()
      throw new Error(err.error)
    }
    // Reload project to get the new spec in the library
    const reload = await fetch(`${API}/${currentProject.value.id}`)
    currentProject.value = await reload.json()
  }

  async function toggleExposeSpec(specId: string) {
    if (!currentProject.value) return
    const raw = currentProject.value.expose as any
    // Handle legacy specId and missing specIds
    const expose = currentProject.value.expose
      ? { ...currentProject.value.expose, specIds: currentProject.value.expose.specIds ?? (raw?.specId ? [raw.specId] : []) }
      : { specIds: [] as string[], port: 8080, host: '0.0.0.0' }
    const isExposed = expose.specIds.includes(specId)

    if (isExposed) {
      // Unexpose: remove specId and remove its flows
      expose.specIds = expose.specIds.filter(id => id !== specId)
      const spec = currentProject.value.specs.find(s => s.id === specId)
      if (spec) {
        const remainingExposedOps = new Set(
          currentProject.value.specs
            .filter(s => expose.specIds.includes(s.id))
            .flatMap(s => s.parsed.operations.map(o => o.operationId))
        )
        currentProject.value.flows = currentProject.value.flows.filter(
          f => remainingExposedOps.has(f.operationId) || !spec.parsed.operations.some(o => o.operationId === f.operationId)
        )
      }
    } else {
      // Expose: add specId and create flow stubs
      expose.specIds = [...expose.specIds, specId]
      const spec = currentProject.value.specs.find(s => s.id === specId)
      if (spec) {
        for (const op of spec.parsed.operations) {
          if (!currentProject.value.flows.find(f => f.operationId === op.operationId)) {
            currentProject.value.flows.push({ operationId: op.operationId, steps: [] })
          }
        }
      }
    }

    currentProject.value.expose = expose
    await saveProject()

    // Auto-select first flow if we just exposed
    if (!isExposed && currentProject.value.flows.length) {
      const canvas = useCanvasStore()
      canvas.selectFlow(currentProject.value.flows[0].operationId)
    }
  }

  function addConnector(connector: AdapterConfig) {
    if (!currentProject.value) return
    currentProject.value.adapters.push(connector)
    saveProject()
    const canvas = useCanvasStore()
    canvas.selectConnector(connector.id)
  }

  function updateConnector(id: string, updates: Partial<AdapterConfig>) {
    if (!currentProject.value) return
    const idx = currentProject.value.adapters.findIndex(a => a.id === id)
    if (idx === -1) return
    currentProject.value.adapters[idx] = { ...currentProject.value.adapters[idx], ...updates }
    saveProject()
  }

  function removeConnector(id: string) {
    if (!currentProject.value) return
    currentProject.value.adapters = currentProject.value.adapters.filter(a => a.id !== id)
    saveProject()
    const canvas = useCanvasStore()
    if (canvas.selection?.type === 'connector' && canvas.selection.adapterId === id) {
      canvas.clearSelection()
    }
  }

  async function testConnection(connector: AdapterConfig): Promise<{ ok: boolean; server?: string; error?: string }> {
    if (!currentProject.value) return { ok: false, error: 'No project' }
    const res = await fetch(`${API}/${currentProject.value.id}/connectors/test`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ type: connector.type, postgres: connector.postgres }),
    })
    return res.json()
  }

  async function fetchDsl() {
    if (!currentProject.value) return ''
    const res = await fetch(`${API}/${currentProject.value.id}/dsl`)
    const data = await res.json()
    dsl.value = data.dsl
    return data.dsl
  }

  function getFlow(operationId: string): FlowConfig | undefined {
    return currentProject.value?.flows.find(f => f.operationId === operationId)
  }

  function addStep(operationId: string, step: StepConfig, index?: number) {
    const flow = getFlow(operationId)
    if (!flow) return
    if (index !== undefined) {
      flow.steps.splice(index, 0, step)
    } else {
      flow.steps.push(step)
    }
    saveProject()
  }

  function updateStep(operationId: string, stepIndex: number, step: StepConfig) {
    const flow = getFlow(operationId)
    if (!flow) return
    flow.steps[stepIndex] = step
    saveProject()
  }

  function removeStep(operationId: string, stepIndex: number) {
    const flow = getFlow(operationId)
    if (!flow) return
    flow.steps.splice(stepIndex, 1)
    saveProject()
  }

  function updateFlowStatus(operationId: string, statusCode: number) {
    const flow = getFlow(operationId)
    if (!flow) return
    flow.statusCode = statusCode
    saveProject()
  }

  async function runProject() {
    if (!currentProject.value) return
    runnerState.value = 'STARTING'
    runnerError.value = null
    try {
      const res = await fetch(`${API}/${currentProject.value.id}/run`, { method: 'POST' })
      const data = await res.json()
      runnerState.value = data.state || 'FAILED'
      runnerPort.value = data.port || null
      runnerError.value = data.error || null
      hotReload.value = data.hotReload || false
    } catch (e: any) {
      runnerState.value = 'FAILED'
      runnerError.value = e.message
    }
  }

  async function stopProject() {
    if (!currentProject.value) return
    runnerState.value = 'STOPPING'
    try {
      const res = await fetch(`${API}/${currentProject.value.id}/stop`, { method: 'POST' })
      const data = await res.json()
      runnerState.value = data.state || 'STOPPED'
      runnerPort.value = null
      runnerError.value = null
      hotReload.value = false
    } catch (e: any) {
      runnerError.value = e.message
    }
  }

  async function fetchRunnerStatus() {
    if (!currentProject.value) return
    try {
      const res = await fetch(`${API}/${currentProject.value.id}/status`)
      const data = await res.json()
      runnerState.value = data.state || 'STOPPED'
      runnerPort.value = data.port || null
      runnerError.value = data.error || null
      hotReload.value = data.hotReload || false
    } catch {
      // ignore
    }
  }

  async function fetchLogs(since = 0): Promise<{ entries: { id: number; time: string; level: string; message: string }[]; cursor: number }> {
    if (!currentProject.value) return { entries: [], cursor: 0 }
    const res = await fetch(`${API}/${currentProject.value.id}/logs?since=${since}`)
    return res.json()
  }

  async function fetchTables(adapterId: string) {
    if (!currentProject.value) return { tables: [] }
    const res = await fetch(`${API}/${currentProject.value.id}/connectors/${adapterId}/tables`)
    return res.json()
  }

  async function fetchColumns(adapterId: string, table: string) {
    if (!currentProject.value) return { columns: [] }
    const res = await fetch(`${API}/${currentProject.value.id}/connectors/${adapterId}/tables/${encodeURIComponent(table)}/columns`)
    return res.json()
  }

  async function fetchRows(adapterId: string, table: string, limit = 50, offset = 0) {
    if (!currentProject.value) return { rows: [], total: 0, limit, offset }
    const res = await fetch(`${API}/${currentProject.value.id}/connectors/${adapterId}/tables/${encodeURIComponent(table)}/rows?limit=${limit}&offset=${offset}`)
    return res.json()
  }

  async function deleteProject(id: string) {
    await fetch(`${API}/${id}`, { method: 'DELETE' })
    projects.value = projects.value.filter(p => p.id !== id)
    if (currentProject.value?.id === id) {
      currentProject.value = null
    }
  }

  return {
    projects,
    currentProject,
    loading,
    dsl,
    operations,
    exposedSpecs,
    runnerState,
    runnerPort,
    runnerError,
    hotReload,
    fetchProjects,
    createProject,
    loadProject,
    closeProject,
    saveProject,
    uploadSpec,
    toggleExposeSpec,
    addConnector,
    updateConnector,
    removeConnector,
    testConnection,
    fetchDsl,
    fetchLogs,
    fetchTables,
    fetchColumns,
    fetchRows,
    getFlow,
    addStep,
    updateStep,
    removeStep,
    updateFlowStatus,
    runProject,
    stopProject,
    fetchRunnerStatus,
    deleteProject,
  }
})
