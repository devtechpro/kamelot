<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useCanvasStore } from '@/stores/canvas'
import FlowLane from './FlowLane.vue'
import SpecViewer from './SpecViewer.vue'
import DatabaseBrowser from './DatabaseBrowser.vue'
import { extractAdapterBlock } from '@/utils/dsl-extract'

const projectStore = useProjectStore()
const canvas = useCanvasStore()

const activeSpec = computed(() => {
  const sel = canvas.selection
  if (sel?.type !== 'spec') return null
  return projectStore.currentProject?.specs.find(s => s.id === sel.specId) ?? null
})

const activeConnector = computed(() => {
  const sel = canvas.selection
  if (sel?.type !== 'connector') return null
  return projectStore.currentProject?.adapters.find(a => a.id === sel.adapterId) ?? null
})

const connectorDsl = ref('')

watch(activeConnector, async (connector) => {
  if (!connector) { connectorDsl.value = ''; return }
  const fullDsl = await projectStore.fetchDsl()
  connectorDsl.value = extractAdapterBlock(fullDsl, connector.name) ?? ''
}, { immediate: true })

const activeFlow = computed(() => {
  if (!canvas.selection && !canvas.addingStep) return null
  const sel = canvas.selection
  const opId = (sel && (sel.type === 'flow' || sel.type === 'step') ? sel.operationId : null) || canvas.addingStep?.operationId
  return opId ? projectStore.currentProject?.flows.find(f => f.operationId === opId) ?? null : null
})

const activeOperation = computed(() => {
  if (!activeFlow.value) return undefined
  return projectStore.operations.find(o => o.operationId === activeFlow.value!.operationId)
})
</script>

<template>
  <div class="flow-canvas">
    <div v-if="!projectStore.currentProject" class="empty-state">
      <p>Open a project to get started.</p>
    </div>

    <div v-else-if="activeSpec" class="canvas-content">
      <SpecViewer :spec="activeSpec" />
    </div>

    <div v-else-if="activeConnector && activeConnector.type === 'postgres'" class="canvas-fill">
      <DatabaseBrowser :connector="activeConnector" />
    </div>

    <div v-else-if="activeConnector" class="canvas-content">
      <div class="connector-canvas">
        <div class="connector-header">
          <span class="connector-type-badge" :class="activeConnector.type">{{ activeConnector.type }}</span>
          <h3 class="connector-title">{{ activeConnector.name }}</h3>
        </div>
        <pre class="connector-code"><code>{{ connectorDsl }}</code></pre>
      </div>
    </div>

    <div v-else-if="!activeFlow" class="empty-state">
      <p>Select a flow, spec, or connector from the tree.</p>
    </div>

    <div v-else class="canvas-content">
      <FlowLane
        :flow="activeFlow"
        :operation="activeOperation"
      />
    </div>
  </div>
</template>

<style scoped>
.flow-canvas {
  height: 100%;
  overflow: auto;
  padding: 16px;
}

.canvas-fill {
  height: 100%;
  margin: -16px;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-muted);
  font-size: 13px;
}

.canvas-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.connector-canvas {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 600px;
}

.connector-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.connector-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.connector-type-badge {
  font-size: 9px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  padding: 2px 8px;
  border-radius: 3px;
  flex-shrink: 0;
}

.connector-type-badge.http {
  color: var(--method-get);
  background: rgba(92, 138, 74, 0.1);
}

.connector-type-badge.postgres {
  color: var(--method-post);
  background: rgba(74, 122, 156, 0.1);
}

.connector-type-badge.in-memory {
  color: var(--text-muted);
  background: rgba(128, 128, 128, 0.1);
}

.connector-code {
  margin: 0;
  padding: 16px 20px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius);
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.7;
  tab-size: 4;
  color: var(--text-primary);
  overflow-x: auto;
}

.connector-code code {
  white-space: pre;
}
</style>
