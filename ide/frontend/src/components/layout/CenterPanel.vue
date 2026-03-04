<script setup lang="ts">
import { useCanvasStore } from '@/stores/canvas'
import { useProjectStore } from '@/stores/project'
import FlowCanvas from '@/components/canvas/FlowCanvas.vue'
import SourceView from '@/components/source/SourceView.vue'
import ProjectSettings from '@/components/settings/ProjectSettings.vue'

const canvas = useCanvasStore()
const projectStore = useProjectStore()

function switchToSource() {
  if (!projectStore.currentProject) return
  canvas.setTab('source')
}
</script>

<template>
  <div class="center-panel">
    <div class="tabs">
      <button
        class="tab"
        :class="{ active: canvas.activeTab === 'canvas' }"
        @click="canvas.setTab('canvas')"
      >
        Canvas
      </button>
      <button
        class="tab"
        :class="{ active: canvas.activeTab === 'source', disabled: !projectStore.currentProject }"
        @click="switchToSource"
      >
        Source
      </button>
      <button
        class="tab"
        :class="{ active: canvas.activeTab === 'settings', disabled: !projectStore.currentProject }"
        @click="projectStore.currentProject && canvas.setTab('settings')"
      >
        Settings
      </button>
    </div>
    <div class="tab-content">
      <FlowCanvas v-if="canvas.activeTab === 'canvas'" />
      <ProjectSettings v-else-if="canvas.activeTab === 'settings' && projectStore.currentProject" />
      <SourceView v-else-if="canvas.activeTab === 'source' && projectStore.currentProject" />
    </div>
  </div>
</template>

<style scoped>
.center-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-primary);
}

.tabs {
  display: flex;
  gap: 0;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--bg-secondary);
  flex-shrink: 0;
}

.tab {
  padding: 7px 16px;
  font-size: 12px;
  font-weight: 400;
  border-radius: 0;
  background: transparent;
  color: var(--text-muted);
  border-bottom: 2px solid transparent;
}

.tab:hover:not(.disabled) {
  color: var(--text-secondary);
  background: transparent;
}

.tab.active {
  color: var(--text-primary);
  border-bottom-color: var(--accent);
  background: transparent;
}

.tab.disabled {
  opacity: 0.3;
  cursor: default;
}

.tab-content {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
</style>
