<script setup lang="ts">
import { useProjectStore } from '@/stores/project'
import type { FlowConfig, OperationDef } from '@/types'

const props = defineProps<{
  flow: FlowConfig
  operation?: OperationDef | null
}>()

const projectStore = useProjectStore()

function updateStatusCode(value: string) {
  const code = parseInt(value)
  if (!isNaN(code) && code >= 100 && code <= 599) {
    projectStore.updateFlowStatus(props.flow.operationId, code)
  }
}
</script>

<template>
  <div class="flow-props">
    <h4 class="section-title">Flow: {{ flow.operationId }}</h4>

    <div v-if="operation" class="info-grid">
      <div class="info-row">
        <label>Method</label>
        <span class="value mono">{{ operation.method }}</span>
      </div>
      <div class="info-row">
        <label>Path</label>
        <span class="value mono">{{ operation.path }}</span>
      </div>
      <div v-if="operation.requestSchema" class="info-row">
        <label>Request</label>
        <span class="value">{{ operation.requestSchema }}</span>
      </div>
      <div v-if="operation.responseSchema" class="info-row">
        <label>Response</label>
        <span class="value">{{ operation.responseSchema }}</span>
      </div>
      <div v-if="operation.parameters.length" class="info-row">
        <label>Params</label>
        <span class="value">
          {{ operation.parameters.map(p => p.name).join(', ') }}
        </span>
      </div>
    </div>

    <div class="field">
      <label>Status Code</label>
      <input
        type="number"
        :value="flow.statusCode || 200"
        @change="(e: Event) => updateStatusCode((e.target as HTMLInputElement).value)"
        min="100"
        max="599"
      />
    </div>

    <div class="step-summary">
      <label>Steps ({{ flow.steps.length }})</label>
      <div v-if="!flow.steps.length" class="hint">
        Click the + button in the lane to add steps.
      </div>
      <div v-for="(step, i) in flow.steps" :key="i" class="step-item">
        <span class="step-num">{{ i + 1 }}</span>
        <span class="step-type">{{ step.type }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.flow-props {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border-subtle);
}

.info-grid {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.info-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.info-row label {
  min-width: 70px;
  font-size: 11px;
  margin-bottom: 0;
}

.value {
  font-size: 12px;
  color: var(--text-primary);
}

.mono {
  font-family: var(--font-mono);
}

.field {
  display: flex;
  flex-direction: column;
}

.field input {
  width: 100px;
}

.step-summary {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.step-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  font-size: 12px;
  background: var(--bg-surface);
  border-radius: var(--radius-sm);
}

.step-num {
  font-size: 10px;
  font-weight: 700;
  color: var(--text-muted);
  min-width: 16px;
}

.step-type {
  text-transform: capitalize;
}

.hint {
  font-size: 11px;
  color: var(--text-muted);
  font-style: italic;
}
</style>
