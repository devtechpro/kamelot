<script setup lang="ts">
import { ref } from 'vue'
import { useProjectStore } from '@/stores/project'

const emit = defineEmits<{ close: [] }>()
const projectStore = useProjectStore()

const filename = ref('')
const content = ref('')
const error = ref('')
const loading = ref(false)

function onFileSelect(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  filename.value = file.name
  const reader = new FileReader()
  reader.onload = () => {
    content.value = reader.result as string
  }
  reader.readAsText(file)
}

async function upload() {
  if (!content.value.trim()) {
    error.value = 'Provide an OpenAPI spec'
    return
  }
  if (!filename.value.trim()) {
    filename.value = 'api-spec.yaml'
  }
  loading.value = true
  error.value = ''
  try {
    await projectStore.uploadSpec(filename.value, content.value)
    emit('close')
  } catch (err: any) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="overlay" @click.self="emit('close')">
    <div class="dialog">
      <div class="dialog-header">Add OpenAPI spec</div>
      <div class="dialog-body">
        <div class="field">
          <label>File</label>
          <input type="file" accept=".yaml,.yml,.json" @change="onFileSelect" />
        </div>
        <div class="divider">or paste below</div>
        <div class="field">
          <label>Filename</label>
          <input v-model="filename" placeholder="api-spec.yaml" />
        </div>
        <div class="field">
          <label>Content</label>
          <textarea
            v-model="content"
            rows="10"
            placeholder="openapi: 3.0.3&#10;info:&#10;  title: My API&#10;  version: '1.0'&#10;paths: ..."
          ></textarea>
        </div>
        <div v-if="error" class="error">{{ error }}</div>
      </div>
      <div class="dialog-footer">
        <button @click="emit('close')">Cancel</button>
        <button class="primary" @click="upload" :disabled="loading">
          {{ loading ? 'Parsing...' : 'Add' }}
        </button>
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
  width: 480px;
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

.divider {
  text-align: center;
  font-size: 11px;
  color: var(--text-muted);
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
