import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Selection } from '@/types'

export const useCanvasStore = defineStore('canvas', () => {
  const selection = ref<Selection>(null)
  const activeTab = ref<'canvas' | 'source' | 'settings'>('canvas')
  const addingStep = ref<{ operationId: string; index: number } | null>(null)

  const rightPanelOpen = computed(() => !!selection.value || !!addingStep.value)

  function selectFlow(operationId: string) {
    addingStep.value = null
    selection.value = { type: 'flow', operationId }
  }

  function selectStep(operationId: string, stepIndex: number) {
    addingStep.value = null
    selection.value = { type: 'step', operationId, stepIndex }
  }

  function selectConnector(adapterId: string) {
    addingStep.value = null
    selection.value = { type: 'connector', adapterId }
  }

  function selectSpec(specId: string) {
    addingStep.value = null
    selection.value = { type: 'spec', specId }
  }

  function startAddStep(operationId: string, index: number) {
    selection.value = null
    addingStep.value = { operationId, index }
  }

  function clearSelection() {
    selection.value = null
    addingStep.value = null
  }

  function setTab(tab: 'canvas' | 'source' | 'settings') {
    activeTab.value = tab
  }

  return { selection, activeTab, addingStep, rightPanelOpen, selectFlow, selectStep, selectConnector, selectSpec, startAddStep, clearSelection, setTab }
})
