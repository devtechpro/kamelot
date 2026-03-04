<script setup lang="ts">
import { ref } from 'vue'
import { useProjectStore } from '@/stores/project'

const emit = defineEmits<{ close: [] }>()
const projectStore = useProjectStore()

const name = ref('')
const description = ref('')

async function create() {
  if (!name.value.trim()) return
  await projectStore.createProject(name.value.trim(), description.value.trim() || undefined)
  emit('close')
}
</script>

<template>
  <div class="overlay" @click.self="emit('close')">
    <div class="dialog">
      <div class="dialog-header">New project</div>
      <div class="dialog-body">
        <div class="field">
          <label>Name</label>
          <input
            v-model="name"
            placeholder="my-integration"
            autofocus
            @keyup.enter="create"
          />
        </div>
        <div class="field">
          <label>Description</label>
          <input v-model="description" placeholder="Optional" />
        </div>
      </div>
      <div class="dialog-footer">
        <button @click="emit('close')">Cancel</button>
        <button class="primary" @click="create" :disabled="!name.length">Create</button>
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
  width: 380px;
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

.dialog-footer {
  padding: 10px 16px;
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  border-top: 1px solid var(--border-subtle);
}
</style>
