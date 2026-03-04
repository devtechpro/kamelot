<script setup lang="ts">
import { reactive, watch } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useCanvasStore } from '@/stores/canvas'

const projectStore = useProjectStore()

const local = reactive({
  name: '',
  description: '',
  version: 1,
  port: 8080,
  host: '0.0.0.0',
})

// Sync from store
watch(
  () => projectStore.currentProject,
  (p) => {
    if (!p) return
    local.name = p.name
    local.description = p.description ?? ''
    local.version = p.version
    local.port = p.expose?.port ?? 8080
    local.host = p.expose?.host ?? '0.0.0.0'
  },
  { immediate: true, deep: true },
)

function saveProject() {
  if (!projectStore.currentProject) return
  projectStore.currentProject.name = local.name
  projectStore.currentProject.description = local.description || undefined
  projectStore.currentProject.version = local.version
  projectStore.saveProject()
}

function saveExpose() {
  if (!projectStore.currentProject) return
  if (!projectStore.currentProject.expose) {
    projectStore.currentProject.expose = {
      specIds: [],
      port: local.port,
      host: local.host,
    }
  } else {
    projectStore.currentProject.expose.port = local.port
    projectStore.currentProject.expose.host = local.host
  }
  projectStore.saveProject()
}

async function confirmDelete() {
  if (!projectStore.currentProject) return
  const name = projectStore.currentProject.name
  if (!confirm(`Delete project "${name}"? This cannot be undone.`)) return
  const id = projectStore.currentProject.id
  await projectStore.deleteProject(id)
  const canvas = useCanvasStore()
  canvas.setTab('canvas')
}
</script>

<template>
  <div class="settings" v-if="projectStore.currentProject">
    <div class="settings-inner">
      <!-- Server section -->
      <div class="settings-section">
        <div class="section-label">Server</div>
        <p class="section-hint" v-if="!projectStore.exposedSpecs.length">
          Expose a spec to enable the server settings.
        </p>
        <template v-else>
          <div class="field">
            <label>Port</label>
            <input
              type="number"
              v-model.number="local.port"
              min="1"
              max="65535"
              @change="saveExpose"
            />
            <span class="hint">The port the integration listens on when running.</span>
          </div>
          <div class="field">
            <label>Host</label>
            <input
              v-model="local.host"
              placeholder="0.0.0.0"
              @change="saveExpose"
            />
            <span class="hint">Bind address. Use 0.0.0.0 for all interfaces or 127.0.0.1 for local only.</span>
          </div>
        </template>
      </div>

      <!-- Project section -->
      <div class="settings-section">
        <div class="section-label">Project</div>
        <div class="field">
          <label>Name</label>
          <input v-model="local.name" @change="saveProject" />
        </div>
        <div class="field">
          <label>Description</label>
          <textarea
            v-model="local.description"
            rows="3"
            placeholder="What this integration does..."
            @change="saveProject"
          ></textarea>
        </div>
        <div class="field">
          <label>Version</label>
          <input
            type="number"
            v-model.number="local.version"
            min="1"
            @change="saveProject"
          />
        </div>
      </div>

      <!-- Info section -->
      <div class="settings-section">
        <div class="section-label">Info</div>
        <div class="info-row">
          <span class="info-label">Project ID</span>
          <code class="info-value">{{ projectStore.currentProject.id }}</code>
        </div>
        <div class="info-row">
          <span class="info-label">Specs</span>
          <span class="info-value">{{ projectStore.currentProject.specs.length }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">Flows</span>
          <span class="info-value">{{ projectStore.currentProject.flows.length }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">Connectors</span>
          <span class="info-value">{{ projectStore.currentProject.adapters.length }}</span>
        </div>
        <div class="info-row" v-if="projectStore.currentProject.createdAt">
          <span class="info-label">Created</span>
          <span class="info-value">{{ new Date(projectStore.currentProject.createdAt).toLocaleDateString() }}</span>
        </div>
      </div>

      <!-- Danger zone -->
      <div class="settings-section danger-section">
        <div class="section-label danger">Danger Zone</div>
        <button class="danger-btn" @click="confirmDelete">Delete project</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings {
  height: 100%;
  overflow-y: auto;
  padding: 24px;
}

.settings-inner {
  max-width: 480px;
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.settings-section {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.section-label {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--text-muted);
  padding-bottom: 4px;
  border-bottom: 1px solid var(--border-subtle);
}

.section-label.danger {
  color: var(--red);
  border-bottom-color: rgba(176, 80, 80, 0.2);
}

.section-hint {
  font-size: 12px;
  color: var(--text-muted);
  font-style: italic;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field label {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-secondary);
}

.field input,
.field textarea,
.field select {
  font-size: 13px;
  padding: 6px 10px;
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  font-family: var(--font-sans);
}

.field input:focus,
.field textarea:focus,
.field select:focus {
  outline: none;
  border-color: var(--accent);
}

.field input[type="number"] {
  max-width: 120px;
  font-family: var(--font-mono);
}

.field textarea {
  resize: vertical;
  min-height: 60px;
}

.hint {
  font-size: 10px;
  color: var(--text-muted);
}

.info-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0;
  font-size: 12px;
}

.info-label {
  color: var(--text-muted);
}

.info-value {
  color: var(--text-secondary);
  font-family: var(--font-mono);
  font-size: 11px;
}

.danger-section {
  padding-top: 8px;
}

.danger-btn {
  width: fit-content;
  padding: 6px 14px;
  font-size: 12px;
  font-weight: 500;
  color: var(--red);
  background: transparent;
  border: 1px solid rgba(176, 80, 80, 0.3);
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.danger-btn:hover {
  background: rgba(176, 80, 80, 0.06);
  border-color: var(--red);
}
</style>
