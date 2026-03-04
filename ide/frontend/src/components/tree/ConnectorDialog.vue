<script setup lang="ts">
import { ref, computed } from 'vue'
import { useProjectStore } from '@/stores/project'

function genId() {
  return Math.random().toString(36).substring(2, 10)
}

const emit = defineEmits<{ close: [] }>()
const projectStore = useProjectStore()

const name = ref('')
const type = ref<'http' | 'postgres'>('http')
const specId = ref('')
const error = ref('')

const specs = computed(() => projectStore.currentProject?.specs ?? [])

function create() {
  if (!name.value.trim()) {
    error.value = 'Name is required'
    return
  }
  if (projectStore.currentProject?.adapters.find(a => a.name === name.value.trim())) {
    error.value = 'A connector with this name already exists'
    return
  }

  projectStore.addConnector({
    id: genId(),
    name: name.value.trim(),
    type: type.value,
    specId: type.value === 'http' && specId.value ? specId.value : undefined,
    baseUrl: type.value === 'http' ? '' : undefined,
    postgres: type.value === 'postgres' ? { url: '', username: '', password: '', table: '' } : undefined,
  })
  emit('close')
}
</script>

<template>
  <div class="overlay" @click.self="emit('close')">
    <div class="dialog">
      <div class="dialog-header">Add Connector</div>
      <div class="dialog-body">
        <div class="field">
          <label>Name</label>
          <input v-model="name" placeholder="e.g. payments-api" @keyup.enter="create" />
        </div>
        <div class="field">
          <label>Type</label>
          <select v-model="type">
            <option value="http">HTTP API</option>
            <option value="postgres">PostgreSQL</option>
          </select>
        </div>
        <div class="field" v-if="type === 'http'">
          <label>Spec (optional)</label>
          <select v-model="specId">
            <option value="">None</option>
            <option v-for="s in specs" :key="s.id" :value="s.id">
              {{ s.parsed.title || s.filename }}
            </option>
          </select>
          <span class="hint">Bind to an API spec from your library</span>
        </div>
        <div v-if="error" class="error">{{ error }}</div>
      </div>
      <div class="dialog-footer">
        <button @click="emit('close')">Cancel</button>
        <button class="primary" @click="create">Create</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.dialog {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  width: 400px;
  max-width: 90vw;
}

.dialog-header {
  padding: 12px 16px;
  font-weight: 500;
  font-size: 13px;
  border-bottom: 1px solid var(--border-subtle);
}

.dialog-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.field {
  display: flex;
  flex-direction: column;
}

.hint {
  font-size: 10px;
  color: var(--text-muted);
  margin-top: 3px;
}

.error {
  color: var(--red);
  font-size: 12px;
  padding: 6px 8px;
  background: rgba(194, 122, 122, 0.08);
  border-radius: var(--radius-sm);
}

.dialog-footer {
  padding: 10px 16px;
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  border-top: 1px solid var(--border-subtle);
}
</style>
