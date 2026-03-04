<script setup lang="ts">
import { computed } from 'vue'
import { useCanvasStore } from '@/stores/canvas'
import type { StepConfig } from '@/types'
import FlowNode from './FlowNode.vue'

const props = defineProps<{
  step: StepConfig
  index: number
  operationId: string
}>()

const canvas = useCanvasStore()

const isSelected = computed(() =>
  canvas.selection?.type === 'step' &&
  canvas.selection.operationId === props.operationId &&
  canvas.selection.stepIndex === props.index
)

const stepColor = computed(() => `var(--step-${props.step.type})`)
const stepBg = computed(() => `var(--step-${props.step.type}-bg)`)

const stepLabel = computed(() => {
  switch (props.step.type) {
    case 'process': return props.step.name || 'transform'
    case 'call': return props.step.adapterName ? `${props.step.method} ${props.step.adapterName}` : `${props.step.method} ${props.step.path}`
    case 'log': return props.step.message || 'log message'
    case 'respond': {
      const filled = props.step.fields.filter(f => f.key)
      return filled.length ? filled.map(f => `${f.key}`).join(', ') : 'response'
    }
    case 'map': {
      const filled = props.step.fields.filter(f => f.key)
      return filled.length ? `map ${filled.length} fields` : 'mapping'
    }
  }
})

function onClick(e: MouseEvent) {
  e.stopPropagation()
  canvas.selectStep(props.operationId, props.index)
}
</script>

<template>
  <FlowNode
    class="step-block"
    :class="{ selected: isSelected }"
    :style="{ borderLeftColor: stepColor, borderLeftWidth: '3px', background: stepBg }"
    @click="onClick"
  >
    <div class="step-type" :style="{ color: stepColor }">{{ step.type }}</div>
    <div class="step-label">{{ stepLabel }}</div>
  </FlowNode>
</template>

<style scoped>
.step-block {
  transition: border-color 0.1s, box-shadow 0.1s;
}

.step-block:hover {
  border-color: v-bind(stepColor);
}

.step-block.selected {
  border-color: v-bind(stepColor);
  box-shadow: 0 0 0 1px v-bind(stepColor);
}

.step-type {
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.step-label {
  font-size: 11px;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
