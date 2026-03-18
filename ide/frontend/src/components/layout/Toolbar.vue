<script setup lang="ts">
import { useProjectStore } from '@/stores/project'
import { useCanvasStore } from '@/stores/canvas'
import { useUiStore } from '@/stores/ui'
import NewProjectDialog from '@/components/tree/NewProjectDialog.vue'
import SpecUploadDialog from '@/components/tree/SpecUploadDialog.vue'
import ConnectorDialog from '@/components/tree/ConnectorDialog.vue'
import { useChatStore } from '@/stores/chat'

const projectStore = useProjectStore()
const canvas = useCanvasStore()
const ui = useUiStore()
const chatStore = useChatStore()
</script>

<template>
  <div class="toolbar">
    <div class="toolbar-left">
      <span class="logo">Studio</span>
      <template v-if="projectStore.currentProject">
        <span class="sep">/</span>
        <span class="project-name">{{ projectStore.currentProject.name }}</span>
        <button class="gear-btn" @click="canvas.setTab('settings')" title="Project settings">&#x2699;</button>
      </template>
    </div>
    <div class="toolbar-center">
      <template v-if="projectStore.currentProject">
        <button
          v-if="projectStore.runnerState !== 'RUNNING' && projectStore.runnerState !== 'STOPPING'"
          class="run-btn"
          :disabled="projectStore.runnerState === 'STARTING'"
          @click="projectStore.runProject()"
        >
          {{ projectStore.runnerState === 'STARTING' ? 'Starting...' : 'Run' }}
        </button>
        <button
          v-if="projectStore.runnerState === 'RUNNING' || projectStore.runnerState === 'STOPPING'"
          class="stop-btn"
          :disabled="projectStore.runnerState === 'STOPPING'"
          @click="projectStore.stopProject()"
        >
          {{ projectStore.runnerState === 'STOPPING' ? 'Stopping...' : 'Stop' }}
        </button>
        <span v-if="projectStore.runnerState === 'RUNNING' && projectStore.runnerPort" class="runner-info">
          :{{ projectStore.runnerPort }}
        </span>
        <span v-if="projectStore.hotReload" class="hot-reload-badge" title="Changes auto-apply while running">
          hot
        </span>
        <span v-if="projectStore.runnerError" class="runner-error" :title="projectStore.runnerError">
          Error
        </span>
      </template>
    </div>
    <div class="toolbar-right">
      <button
        v-if="projectStore.currentProject && chatStore.chatAvailable"
        :class="['chat-btn', { active: ui.chatPanelVisible }]"
        @click="ui.toggleChat()"
        title="Toggle AI Assistant"
      >
        AI Chat
      </button>
      <button @click="ui.showNewProject = true">New project</button>
      <button v-if="projectStore.currentProject" @click="ui.showSpecUpload = true">Add spec</button>
    </div>
  </div>

  <NewProjectDialog v-if="ui.showNewProject" @close="ui.showNewProject = false" />
  <SpecUploadDialog v-if="ui.showSpecUpload" @close="ui.showSpecUpload = false" />
  <ConnectorDialog v-if="ui.showAddConnector" @close="ui.showAddConnector = false" />
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 40px;
  padding: 0 14px;
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.logo {
  font-weight: 700;
  font-size: 13px;
  color: var(--text-primary);
  letter-spacing: -0.02em;
}

.sep {
  color: var(--border);
  font-size: 13px;
}

.project-name {
  color: var(--text-secondary);
  font-size: 12px;
}

.gear-btn {
  background: transparent;
  border: none;
  font-size: 14px;
  color: var(--text-muted);
  cursor: pointer;
  padding: 2px 4px;
  line-height: 1;
}

.gear-btn:hover {
  color: var(--accent);
}

.toolbar-center {
  display: flex;
  align-items: center;
  gap: 6px;
}

.run-btn {
  background: #2d7d46 !important;
  color: #fff !important;
  border-color: #2d7d46 !important;
  font-weight: 600;
}
.run-btn:hover:not(:disabled) {
  background: #257039 !important;
}
.run-btn:disabled {
  opacity: 0.6;
}

.stop-btn {
  background: #c74545 !important;
  color: #fff !important;
  border-color: #c74545 !important;
  font-weight: 600;
}
.stop-btn:hover:not(:disabled) {
  background: #b23d3d !important;
}

.runner-info {
  font-size: 11px;
  color: #2d7d46;
  font-weight: 600;
  font-family: monospace;
}

.hot-reload-badge {
  font-size: 9px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  padding: 1px 5px;
  border-radius: 3px;
  background: rgba(212, 140, 50, 0.12);
  color: #b07d4f;
  cursor: help;
}

.runner-error {
  font-size: 11px;
  color: #c74545;
  cursor: help;
}

.toolbar-right {
  display: flex;
  gap: 4px;
}

.chat-btn {
  background: transparent;
  border: 1px solid var(--border);
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: 4px;
  cursor: pointer;
}
.chat-btn:hover {
  border-color: var(--accent);
  color: var(--accent);
}
.chat-btn.active {
  background: var(--accent);
  color: #fff;
  border-color: var(--accent);
}
</style>
