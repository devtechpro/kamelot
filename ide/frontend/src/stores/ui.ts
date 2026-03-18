import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useUiStore = defineStore('ui', () => {
  const leftPanelVisible = ref(true)
  const rightPanelVisible = ref(true)
  const bottomPanelVisible = ref(true)
  const showSpecUpload = ref(false)
  const showNewProject = ref(false)
  const showAddConnector = ref(false)
  const chatPanelVisible = ref(false)

  function toggleLeft() { leftPanelVisible.value = !leftPanelVisible.value }
  function toggleRight() { rightPanelVisible.value = !rightPanelVisible.value }
  function toggleBottom() { bottomPanelVisible.value = !bottomPanelVisible.value }
  function toggleChat() { chatPanelVisible.value = !chatPanelVisible.value }

  return {
    leftPanelVisible,
    rightPanelVisible,
    bottomPanelVisible,
    chatPanelVisible,
    showSpecUpload,
    showNewProject,
    showAddConnector,
    toggleLeft,
    toggleRight,
    toggleBottom,
    toggleChat,
  }
})
