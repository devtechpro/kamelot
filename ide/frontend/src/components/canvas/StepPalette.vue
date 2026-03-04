<script setup lang="ts">
import { ref } from 'vue'
import type { StepConfig } from '@/types'

const props = defineProps<{ index: number }>()
const emit = defineEmits<{ add: [step: StepConfig] }>()

const showMenu = ref(false)

const stepTypes = [
  { type: 'process' as const, label: 'Process', color: 'var(--step-process)', create: (): StepConfig => ({ type: 'process', name: '', expression: 'body' }) },
  { type: 'call' as const, label: 'Call', color: 'var(--step-call)', create: (): StepConfig => ({ type: 'call', adapterName: '', method: 'GET', path: '/' }) },
  { type: 'log' as const, label: 'Log', color: 'var(--step-log)', create: (): StepConfig => ({ type: 'log', message: '', level: 'INFO' }) },
  { type: 'map' as const, label: 'Map', color: 'var(--step-map)', create: (): StepConfig => ({ type: 'map', fields: [] }) },
  { type: 'respond' as const, label: 'Respond', color: 'var(--step-respond)', create: (): StepConfig => ({ type: 'respond', fields: [{ key: '', value: '', mode: 'set' }] }) },
]

function addStep(def: typeof stepTypes[number]) {
  emit('add', def.create())
  showMenu.value = false
}
</script>

<template>
  <div class="step-palette">
    <button class="add-btn" @click.stop="showMenu = !showMenu" title="Add step">+</button>
    <div v-if="showMenu" class="palette-menu" @click.stop>
      <div
        v-for="st in stepTypes"
        :key="st.type"
        class="palette-item"
        @click="addStep(st)"
      >
        <span class="dot" :style="{ background: st.color }"></span>
        {{ st.label }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.step-palette {
  position: relative;
  flex-shrink: 0;
  margin-left: 4px;
}

.add-btn {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  font-weight: 300;
  color: var(--text-muted);
  border: 1px dashed var(--border);
  padding: 0;
  transition: all 0.1s;
}

.add-btn:hover {
  color: var(--accent);
  border-color: var(--accent);
}

.palette-menu {
  position: absolute;
  top: 100%;
  left: 50%;
  transform: translateX(-50%);
  margin-top: 6px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 4px;
  z-index: 50;
  min-width: 120px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
}

.palette-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  cursor: pointer;
  border-radius: var(--radius-sm);
  font-size: 12px;
  color: var(--text-secondary);
}

.palette-item:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}
</style>
