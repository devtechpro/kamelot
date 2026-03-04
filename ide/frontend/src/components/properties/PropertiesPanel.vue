<script setup lang="ts">
import { computed } from 'vue'
import { useCanvasStore } from '@/stores/canvas'
import { useProjectStore } from '@/stores/project'
import FlowProperties from './FlowProperties.vue'
import StepProperties from './StepProperties.vue'
import StepPicker from './StepPicker.vue'
import ConnectorProperties from './ConnectorProperties.vue'

const canvas = useCanvasStore()
const projectStore = useProjectStore()

const selectedFlow = computed(() => {
  const sel = canvas.selection
  if (!sel || sel.type === 'connector' || sel.type === 'spec') return null
  return projectStore.getFlow(sel.operationId)
})

const selectedStep = computed(() => {
  if (canvas.selection?.type !== 'step') return null
  const flow = projectStore.getFlow(canvas.selection.operationId)
  return flow?.steps[canvas.selection.stepIndex] ?? null
})

const selectedOperation = computed(() => {
  const sel = canvas.selection
  if (!sel || sel.type === 'connector' || sel.type === 'spec') return null
  return projectStore.operations.find(o => o.operationId === sel.operationId)
})

const selectedConnector = computed(() => {
  const sel = canvas.selection
  if (sel?.type !== 'connector') return null
  return projectStore.currentProject?.adapters.find(a => a.id === sel.adapterId) ?? null
})

const panelTitle = computed(() => {
  if (canvas.addingStep) return 'Add Step'
  if (canvas.selection?.type === 'connector') return 'Connector'
  if (canvas.selection?.type === 'spec') return 'Spec'
  if (canvas.selection?.type === 'step') return 'Step'
  if (canvas.selection?.type === 'flow') return 'Flow'
  return 'Properties'
})

const hasContent = computed(() => canvas.addingStep || canvas.selection)
</script>

<template>
  <div class="properties-panel">
    <div class="panel-header">
      <span>{{ panelTitle }}</span>
      <button v-if="hasContent" class="close-btn" @click="canvas.clearSelection()">&times;</button>
    </div>
    <div class="panel-body">
      <!-- Step picker mode -->
      <template v-if="canvas.addingStep">
        <StepPicker />
      </template>

      <!-- Connector properties -->
      <template v-else-if="canvas.selection?.type === 'connector' && selectedConnector">
        <ConnectorProperties :connector="selectedConnector" />
      </template>

      <!-- Step properties -->
      <template v-else-if="canvas.selection?.type === 'step' && selectedStep && selectedFlow">
        <StepProperties
          :step="selectedStep"
          :stepIndex="canvas.selection.stepIndex"
          :operationId="canvas.selection.operationId"
        />
      </template>

      <!-- Flow properties -->
      <template v-else-if="canvas.selection?.type === 'flow' && selectedFlow">
        <FlowProperties
          :flow="selectedFlow"
          :operation="selectedOperation"
        />
      </template>

      <!-- Empty state -->
      <template v-else>
        <div class="empty-hint">Select a flow, step, or connector to view its properties.</div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.properties-panel {
  height: 100%;
  background: var(--bg-secondary);
  display: flex;
  flex-direction: column;
}

.close-btn {
  padding: 2px 6px;
  font-size: 16px;
  line-height: 1;
  background: transparent;
}

.empty-hint {
  color: var(--text-muted);
  font-size: 12px;
  padding: 8px 0;
}
</style>
