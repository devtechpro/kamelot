<script setup lang="ts">
import { useCanvasStore } from '@/stores/canvas'
import { useProjectStore } from '@/stores/project'
import type { StepConfig } from '@/types'

const canvas = useCanvasStore()
const projectStore = useProjectStore()

const stepTypes = [
  {
    type: 'process' as const,
    label: 'Process',
    desc: 'Transform or enrich the request body',
    color: 'var(--step-process)',
    bg: 'var(--step-process-bg)',
    create: (): StepConfig => ({ type: 'process', name: '', expression: 'body' }),
  },
  {
    type: 'call' as const,
    label: 'Call',
    desc: 'Call an external adapter (HTTP, DB)',
    color: 'var(--step-call)',
    bg: 'var(--step-call-bg)',
    create: (): StepConfig => ({ type: 'call', adapterName: '', method: 'GET', path: '/' }),
  },
  {
    type: 'map' as const,
    label: 'Map',
    desc: 'Transform body fields for the next step',
    color: 'var(--step-map)',
    bg: 'var(--step-map-bg)',
    create: (): StepConfig => ({ type: 'map', fields: [] }),
  },
  {
    type: 'respond' as const,
    label: 'Respond',
    desc: 'Build a response with mapped fields',
    color: 'var(--step-respond)',
    bg: 'var(--step-respond-bg)',
    create: (): StepConfig => ({ type: 'respond', fields: [] as { key: string; value: string; mode: 'to' | 'set' }[] }),
  },
  {
    type: 'log' as const,
    label: 'Log',
    desc: 'Log a message for debugging',
    color: 'var(--step-log)',
    bg: 'var(--step-log-bg)',
    create: (): StepConfig => ({ type: 'log', message: '', level: 'INFO' }),
  },
]

function pick(def: typeof stepTypes[number]) {
  if (!canvas.addingStep) return
  const { operationId, index } = canvas.addingStep
  projectStore.addStep(operationId, def.create(), index)
  // Select the newly added step for editing
  canvas.selectStep(operationId, index)
}
</script>

<template>
  <div class="step-picker">
    <h4 class="picker-title">Add step</h4>
    <p class="picker-hint">Choose a step type to add to the flow.</p>

    <div class="picker-grid">
      <button
        v-for="st in stepTypes"
        :key="st.type"
        class="picker-card"
        :style="{ '--card-color': st.color, '--card-bg': st.bg }"
        @click="pick(st)"
      >
        <div class="card-indicator"></div>
        <div class="card-content">
          <span class="card-label">{{ st.label }}</span>
          <span class="card-desc">{{ st.desc }}</span>
        </div>
      </button>
    </div>
  </div>
</template>

<style scoped>
.step-picker {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.picker-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.picker-hint {
  font-size: 11px;
  color: var(--text-muted);
}

.picker-grid {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.picker-card {
  display: flex;
  align-items: stretch;
  text-align: left;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--card-bg);
  transition: border-color 0.12s, background 0.12s;
  overflow: hidden;
}

.picker-card:hover {
  border-color: var(--card-color);
  background: var(--card-bg);
}

.card-indicator {
  width: 3px;
  flex-shrink: 0;
  background: var(--card-color);
}

.card-content {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
}

.card-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-primary);
}

.card-desc {
  font-size: 11px;
  color: var(--text-muted);
  line-height: 1.3;
}
</style>
