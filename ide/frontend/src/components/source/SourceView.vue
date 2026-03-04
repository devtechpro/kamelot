<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useCanvasStore } from '@/stores/canvas'
import { extractFlowBlock, extractAdapterBlock } from '@/utils/dsl-extract'

const projectStore = useProjectStore()
const canvas = useCanvasStore()
const code = ref('')
const loading = ref(false)

const selectedSpec = computed(() => {
  const sel = canvas.selection
  if (sel?.type !== 'spec') return null
  return projectStore.currentProject?.specs.find(s => s.id === sel.specId) ?? null
})

const selectedFlow = computed(() => {
  const sel = canvas.selection
  if (!sel || (sel.type !== 'flow' && sel.type !== 'step')) return null
  return projectStore.currentProject?.flows.find(f => f.operationId === sel.operationId) ?? null
})

const selectedOperation = computed(() => {
  if (!selectedFlow.value) return null
  return projectStore.operations.find(o => o.operationId === selectedFlow.value!.operationId) ?? null
})

const selectedConnector = computed(() => {
  const sel = canvas.selection
  if (sel?.type !== 'connector') return null
  return projectStore.currentProject?.adapters.find(a => a.id === sel.adapterId) ?? null
})

const isSnippet = computed(() => !!selectedSpec.value || !!selectedFlow.value || !!selectedConnector.value)

const breadcrumbs = computed(() => {
  const projectName = (projectStore.currentProject?.name || 'untitled') + '.kt'
  if (selectedSpec.value) {
    return [{ label: selectedSpec.value.filename }]
  }
  if (selectedFlow.value && selectedOperation.value) {
    return [
      { label: projectName, action: 'full' },
      { label: `${selectedOperation.value.method} ${selectedOperation.value.path}` },
    ]
  }
  if (selectedFlow.value) {
    return [
      { label: projectName, action: 'full' },
      { label: selectedFlow.value.operationId },
    ]
  }
  if (selectedConnector.value) {
    return [
      { label: projectName, action: 'full' },
      { label: `${selectedConnector.value.name} (${selectedConnector.value.type})` },
    ]
  }
  return [{ label: projectName }]
})

const sourceBadge = computed(() => {
  if (selectedSpec.value) return 'openapi'
  if (isSnippet.value) return 'snippet'
  return 'generated'
})

function showFullDsl() {
  canvas.clearSelection()
}

async function loadSource() {
  if (selectedSpec.value) {
    code.value = selectedSpec.value.content
    return
  }

  // Everything else needs the full DSL from BFF (single source of truth)
  loading.value = true
  try {
    const fullDsl = await projectStore.fetchDsl()
    if (selectedFlow.value) {
      code.value = extractFlowBlock(fullDsl, selectedFlow.value.operationId) ?? fullDsl
    } else if (selectedConnector.value) {
      code.value = extractAdapterBlock(fullDsl, selectedConnector.value.name) ?? fullDsl
    } else {
      code.value = fullDsl
    }
  } catch {
    code.value = '// Error generating DSL'
  } finally {
    loading.value = false
  }
}

watch(() => canvas.activeTab, (tab) => {
  if (tab === 'source') loadSource()
})

watch(() => canvas.selection, () => {
  if (canvas.activeTab === 'source') loadSource()
})

watch(() => projectStore.currentProject?.updatedAt, () => {
  if (canvas.activeTab === 'source') loadSource()
})

onMounted(() => {
  if (canvas.activeTab === 'source') loadSource()
})
</script>

<template>
  <div class="source-view">
    <div class="source-toolbar">
      <div class="breadcrumb">
        <template v-for="(crumb, i) in breadcrumbs" :key="i">
          <span v-if="i > 0" class="sep">&rsaquo;</span>
          <span
            class="crumb"
            :class="{ clickable: !!crumb.action, active: i === breadcrumbs.length - 1 }"
            @click="crumb.action === 'full' ? showFullDsl() : undefined"
          >{{ crumb.label }}</span>
        </template>
      </div>
      <span class="badge">{{ sourceBadge }}</span>
    </div>
    <pre class="code-editor"><code>{{ code }}</code></pre>
  </div>
</template>

<style scoped>
.source-view {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.source-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 12px;
  border-bottom: 1px solid var(--border-subtle);
  flex-shrink: 0;
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 4px;
  font-family: var(--font-mono);
  font-size: 11px;
  min-width: 0;
  overflow: hidden;
}

.crumb {
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.crumb.active {
  color: var(--text-secondary);
}

.crumb.clickable {
  cursor: pointer;
}

.crumb.clickable:hover {
  color: var(--accent);
  text-decoration: underline;
}

.sep {
  color: var(--text-muted);
  flex-shrink: 0;
}

.badge {
  font-size: 9px;
  padding: 1px 5px;
  border-radius: 3px;
  background: var(--bg-hover);
  color: var(--text-muted);
  flex-shrink: 0;
  margin-left: auto;
}

.code-editor {
  flex: 1;
  margin: 0;
  padding: 12px 16px;
  overflow: auto;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.6;
  tab-size: 4;
  background: var(--bg-primary);
  color: var(--text-primary);
}

.code-editor code {
  white-space: pre;
}
</style>
