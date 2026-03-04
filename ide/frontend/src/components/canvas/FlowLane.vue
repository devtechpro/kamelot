<script setup lang="ts">
import { computed, ref } from 'vue'
import { useCanvasStore } from '@/stores/canvas'
import type { FlowConfig, OperationDef } from '@/types'
import FlowNode from './FlowNode.vue'
import StepBlock from './StepBlock.vue'

const props = defineProps<{
  flow: FlowConfig
  operation?: OperationDef
}>()

const canvas = useCanvasStore()
const hoveredGap = ref<number | null>(null)

const isFlowSelected = computed(() =>
  canvas.selection?.type === 'flow' && canvas.selection.operationId === props.flow.operationId
)

const addingAtIndex = computed(() => {
  if (canvas.addingStep?.operationId === props.flow.operationId) {
    return canvas.addingStep.index
  }
  return null
})

const methodColor = computed(() => {
  const colors: Record<string, string> = {
    GET: 'var(--method-get)',
    POST: 'var(--method-post)',
    PUT: 'var(--method-put)',
    PATCH: 'var(--method-patch)',
    DELETE: 'var(--method-delete)',
  }
  return colors[props.operation?.method || 'GET'] || 'var(--text-secondary)'
})

function onLaneClick() {
  canvas.selectFlow(props.flow.operationId)
}

function onAddStep(index: number) {
  canvas.startAddStep(props.flow.operationId, index)
}

function cancelAdd() {
  const opId = canvas.addingStep?.operationId
  canvas.addingStep = null
  if (opId) canvas.selectFlow(opId)
}
</script>

<template>
  <div
    class="flow-lane"
    :class="{ selected: isFlowSelected }"
    @click.self="onLaneClick"
  >
    <div class="lane-header" @click="onLaneClick">
      <span class="method-badge" :style="{ color: methodColor, borderColor: methodColor }">
        {{ operation?.method || '?' }}
      </span>
      <span class="operation-id">{{ flow.operationId }}</span>
      <span class="path">{{ operation?.path || '' }}</span>
      <span v-if="flow.statusCode && flow.statusCode !== 200" class="status-badge">
        {{ flow.statusCode }}
      </span>
    </div>

    <div class="flow-area" @click.self="onLaneClick">
      <div class="steps-row">
        <!-- Trigger -->
        <FlowNode
          tall
          class="trigger"
          :style="{ borderColor: methodColor, background: 'var(--bg-secondary)' }"
          @click.stop="onLaneClick"
        >
          <span class="trigger-method" :style="{ color: methodColor }">{{ operation?.method || 'API' }}</span>
          <span class="trigger-path">{{ operation?.path || '' }}</span>
        </FlowNode>

        <!-- Arrow from trigger -->
        <div
          class="arrow-gap"
          @mouseenter="hoveredGap = 0"
          @mouseleave="hoveredGap = null"
        >
          <button
            v-if="hoveredGap === 0 && addingAtIndex === null"
            class="inline-add"
            @click.stop="onAddStep(0)"
          ><svg width="10" height="10" viewBox="0 0 10 10"><line x1="5" y1="1" x2="5" y2="9" stroke="currentColor" stroke-width="1.5"/><line x1="1" y1="5" x2="9" y2="5" stroke="currentColor" stroke-width="1.5"/></svg></button>
        </div>

        <!-- Placeholder at index 0 -->
        <template v-if="addingAtIndex === 0">
          <FlowNode dashed class="placeholder">
            <button class="placeholder-close" @click.stop="cancelAdd">
              <svg width="10" height="10" viewBox="0 0 10 10"><line x1="2" y1="2" x2="8" y2="8" stroke="currentColor" stroke-width="1.5"/><line x1="8" y1="2" x2="2" y2="8" stroke="currentColor" stroke-width="1.5"/></svg>
            </button>
          </FlowNode>
          <div class="arrow-gap"></div>
        </template>

        <!-- Steps with arrows between each -->
        <template v-for="(step, i) in flow.steps" :key="i">
          <StepBlock :step="step" :index="i" :operationId="flow.operationId" />

          <div
            class="arrow-gap"
            @mouseenter="hoveredGap = i + 1"
            @mouseleave="hoveredGap = null"
          >
            <button
              v-if="hoveredGap === i + 1 && addingAtIndex === null"
              class="inline-add"
              @click.stop="onAddStep(i + 1)"
            ><svg width="10" height="10" viewBox="0 0 10 10"><line x1="5" y1="1" x2="5" y2="9" stroke="currentColor" stroke-width="1.5"/><line x1="1" y1="5" x2="9" y2="5" stroke="currentColor" stroke-width="1.5"/></svg></button>
          </div>

          <!-- Placeholder at index i+1 -->
          <template v-if="addingAtIndex === i + 1">
            <FlowNode dashed class="placeholder">
              <button class="placeholder-close" @click.stop="cancelAdd">
                <svg width="10" height="10" viewBox="0 0 10 10"><line x1="2" y1="2" x2="8" y2="8" stroke="currentColor" stroke-width="1.5"/><line x1="8" y1="2" x2="2" y2="8" stroke="currentColor" stroke-width="1.5"/></svg>
              </button>
            </FlowNode>
            <div class="arrow-gap"></div>
          </template>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.flow-lane {
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  transition: border-color 0.12s, box-shadow 0.12s;
}

.flow-lane:hover {
  border-color: var(--text-muted);
}

.flow-lane.selected {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent);
}

.lane-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--border-subtle);
  cursor: pointer;
  user-select: none;
}

.method-badge {
  font-family: var(--font-mono);
  font-weight: 700;
  font-size: 10px;
  border: 1px solid;
  border-radius: 3px;
  padding: 1px 5px;
}

.operation-id {
  font-weight: 500;
  font-size: 12px;
  color: var(--text-primary);
}

.path {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-muted);
}

.status-badge {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--yellow);
}

.flow-area {
  padding: 20px 16px;
}

.steps-row {
  display: flex;
  align-items: flex-start;
}

/* ── Trigger variant ── */
.trigger {
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
}

.trigger-method {
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.trigger-path {
  font-size: 11px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── Arrow ── */
.arrow-gap {
  position: relative;
  flex: 0 0 55px;
  height: 48px;
}

.arrow-gap::before {
  content: '';
  position: absolute;
  top: 50%;
  left: 0;
  right: 8px;
  height: 1.5px;
  background: var(--border);
  transform: translateY(-50%);
}

.arrow-gap::after {
  content: '';
  position: absolute;
  top: 50%;
  right: 0;
  transform: translateY(-50%);
  border-top: 5px solid transparent;
  border-bottom: 5px solid transparent;
  border-left: 8px solid var(--border);
}

/* ── Hover add button ── */
.inline-add {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: 1.5px solid var(--border);
  background: var(--bg-primary);
  color: var(--border);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 3;
  padding: 0;
  box-shadow: 0 1px 4px rgba(0,0,0,0.08);
  animation: fadeIn 0.1s ease-out;
}

.inline-add:hover {
  border-color: var(--text-muted);
  color: var(--text-muted);
  transform: translate(-50%, -50%) scale(1.15);
}

/* ── Placeholder ── */
.placeholder {
  animation: placeholderIn 0.15s ease-out;
}

.placeholder-close {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 1.5px solid var(--border);
  background: var(--bg-primary);
  color: var(--border);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  padding: 0;
}

.placeholder-close:hover {
  border-color: var(--red);
  color: var(--red);
}

@keyframes placeholderIn {
  from { opacity: 0; transform: scaleX(0); }
  to { opacity: 1; transform: scaleX(1); }
}

@keyframes fadeIn {
  from { opacity: 0; transform: translate(-50%, -50%) scale(0.8); }
  to { opacity: 1; transform: translate(-50%, -50%) scale(1); }
}
</style>
