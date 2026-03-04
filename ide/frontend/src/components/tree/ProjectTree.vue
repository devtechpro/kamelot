<script setup lang="ts">
import { ref } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useUiStore } from '@/stores/ui'
import { useCanvasStore } from '@/stores/canvas'

const projectStore = useProjectStore()
const ui = useUiStore()
const canvas = useCanvasStore()

const expandedSpecs = ref<Set<string>>(new Set())

function methodColor(method: string): string {
  const colors: Record<string, string> = {
    GET: 'var(--method-get)',
    POST: 'var(--method-post)',
    PUT: 'var(--method-put)',
    PATCH: 'var(--method-patch)',
    DELETE: 'var(--method-delete)',
  }
  return colors[method] || 'var(--text-secondary)'
}

function isExposed(specId: string): boolean {
  return projectStore.currentProject?.expose?.specIds?.includes(specId) ?? false
}

function toggleSpecExpanded(specId: string) {
  if (expandedSpecs.value.has(specId)) {
    expandedSpecs.value.delete(specId)
  } else {
    expandedSpecs.value.add(specId)
  }
}

function flowsForSpec(specId: string) {
  const spec = projectStore.currentProject?.specs.find(s => s.id === specId)
  if (!spec) return []
  const opIds = new Set(spec.parsed.operations.map(o => o.operationId))
  return projectStore.currentProject?.flows.filter(f => opIds.has(f.operationId)) ?? []
}

function operationForFlow(operationId: string) {
  return projectStore.operations.find(o => o.operationId === operationId)
}

async function handleToggleExpose(specId: string) {
  const wasExposed = isExposed(specId)
  await projectStore.toggleExposeSpec(specId)
  if (!wasExposed) {
    expandedSpecs.value.add(specId)
  }
}
</script>

<template>
  <div class="tree-panel">
    <div class="panel-header">
      <span>Explorer</span>
    </div>
    <div class="panel-body">
      <!-- No project state -->
      <div v-if="!projectStore.currentProject && !projectStore.projects.length" class="empty">
        No projects yet
      </div>

      <!-- Project list -->
      <div v-if="!projectStore.currentProject && projectStore.projects.length">
        <div
          v-for="p in projectStore.projects"
          :key="p.id"
          class="tree-item"
          @click="projectStore.loadProject(p.id)"
        >
          {{ p.name }}
        </div>
      </div>

      <!-- Current project tree -->
      <div v-if="projectStore.currentProject" class="tree">
        <div class="tree-item back" @click="projectStore.closeProject()">
          &larr; Projects
        </div>

        <!-- API section (exposed specs with flows) -->
        <div class="tree-section" v-if="projectStore.exposedSpecs.length">
          <div class="tree-item section-header">
            API
            <span class="port-badge" v-if="projectStore.runnerState === 'RUNNING' && projectStore.runnerPort">
              :{{ projectStore.runnerPort }}
            </span>
          </div>
          <div v-for="spec in projectStore.exposedSpecs" :key="spec.id">
            <div
              class="tree-item indent spec-group"
              @click="toggleSpecExpanded(spec.id)"
            >
              <span class="expand-icon">{{ expandedSpecs.has(spec.id) ? '▾' : '▸' }}</span>
              {{ spec.parsed.title || spec.filename }}
            </div>
            <template v-if="expandedSpecs.has(spec.id)">
              <div
                v-for="flow in flowsForSpec(spec.id)"
                :key="flow.operationId"
                class="tree-item indent-deep"
                :class="{ selected: canvas.selection?.type === 'flow' && canvas.selection.operationId === flow.operationId }"
                @click="canvas.selectFlow(flow.operationId)"
              >
                <span class="method-badge" :style="{ color: methodColor(operationForFlow(flow.operationId)?.method || 'GET') }">
                  {{ operationForFlow(flow.operationId)?.method || '?' }}
                </span>
                <span class="flow-path">{{ operationForFlow(flow.operationId)?.path || flow.operationId }}</span>
                <span class="step-count" v-if="flow.steps.length">({{ flow.steps.length }})</span>
              </div>
            </template>
          </div>
        </div>

        <!-- CONNECTORS section -->
        <div class="tree-section">
          <div class="tree-item section-header">
            CONNECTORS
            <button class="inline-btn" @click="ui.showAddConnector = true">+</button>
          </div>
          <div
            v-for="adapter in projectStore.currentProject.adapters"
            :key="adapter.id"
            class="tree-item indent connector-item"
            :class="{ selected: canvas.selection?.type === 'connector' && canvas.selection.adapterId === adapter.id }"
            @click="canvas.selectConnector(adapter.id)"
          >
            <span class="connector-type" :class="adapter.type">{{ adapter.type }}</span>
            {{ adapter.name }}
          </div>
          <div v-if="!projectStore.currentProject.adapters.length" class="tree-item indent hint">
            No connectors
          </div>
        </div>

        <!-- SPECS section (library) -->
        <div class="tree-section">
          <div class="tree-item section-header">
            SPECS
            <button class="inline-btn" @click="ui.showSpecUpload = true">+</button>
          </div>
          <div
            v-for="spec in projectStore.currentProject.specs"
            :key="spec.id"
            class="tree-item indent spec-item"
            :class="{ selected: canvas.selection?.type === 'spec' && canvas.selection.specId === spec.id }"
            @click="canvas.selectSpec(spec.id)"
          >
            <span class="spec-name">{{ spec.parsed.title || spec.filename }}</span>
            <button
              class="expose-btn"
              :class="{ active: isExposed(spec.id) }"
              :title="isExposed(spec.id) ? 'Unexpose from API' : 'Expose as API'"
              @click.stop="handleToggleExpose(spec.id)"
            >
              {{ isExposed(spec.id) ? 'API' : 'expose' }}
            </button>
          </div>
          <div v-if="!projectStore.currentProject.specs.length" class="tree-item indent hint">
            No specs
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tree-panel {
  height: 100%;
  background: var(--bg-secondary);
  display: flex;
  flex-direction: column;
}

.empty {
  padding: 16px 12px;
  color: var(--text-muted);
  font-size: 12px;
}

.tree-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  cursor: pointer;
  font-size: 12px;
  user-select: none;
  color: var(--text-secondary);
}

.tree-item:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.tree-item.selected {
  background: var(--bg-active);
  color: var(--text-primary);
}

.tree-item.indent {
  padding-left: 24px;
}

.tree-item.indent-deep {
  padding-left: 40px;
}

.tree-item.back {
  padding: 6px 12px;
  color: var(--text-muted);
  font-size: 11px;
  border-bottom: 1px solid var(--border-subtle);
}

.section-header {
  font-size: 10px;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-top: 8px;
  cursor: default;
}

.section-header:hover {
  background: transparent;
  color: var(--text-muted);
}

.spec-item {
  display: flex;
  align-items: center;
}

.spec-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.expose-btn {
  margin-left: auto;
  padding: 1px 6px;
  font-size: 9px;
  font-weight: 500;
  color: var(--text-muted);
  background: transparent;
  border: 1px solid var(--border-subtle);
  border-radius: 3px;
  cursor: pointer;
  line-height: 1.4;
}

.expose-btn.active {
  color: var(--method-post);
  border-color: var(--method-post);
}

.expose-btn:hover {
  color: var(--text-primary);
  border-color: var(--text-primary);
}

.spec-group {
  font-weight: 500;
  color: var(--text-secondary);
}

.expand-icon {
  font-size: 9px;
  width: 10px;
  color: var(--text-muted);
}

.flow-path {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.method-badge {
  font-size: 9px;
  font-weight: 600;
  font-family: var(--font-mono);
  min-width: 28px;
}

.step-count {
  margin-left: auto;
  font-size: 10px;
  color: var(--text-muted);
}

.port-badge {
  margin-left: auto;
  font-size: 10px;
  font-family: var(--font-mono);
  color: var(--method-get);
}

.connector-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.connector-type {
  font-size: 8px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  padding: 1px 4px;
  border-radius: 2px;
  flex-shrink: 0;
}

.connector-type.http {
  color: var(--method-get);
  background: rgba(97, 175, 239, 0.1);
}

.connector-type.postgres {
  color: var(--method-post);
  background: rgba(152, 195, 121, 0.1);
}

.connector-type.in-memory {
  color: var(--text-muted);
  background: rgba(128, 128, 128, 0.1);
}

.inline-btn {
  margin-left: auto;
  padding: 0 5px;
  font-size: 13px;
  color: var(--text-muted);
  line-height: 1;
}

.hint {
  color: var(--text-muted);
  font-size: 11px;
  font-style: italic;
  cursor: default;
}

.hint:hover {
  background: transparent;
  color: var(--text-muted);
}
</style>
