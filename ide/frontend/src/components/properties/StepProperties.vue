<script setup lang="ts">
import { reactive, watch } from 'vue'
import { useProjectStore } from '@/stores/project'
import type { StepConfig } from '@/types'

const props = defineProps<{
  step: StepConfig
  stepIndex: number
  operationId: string
}>()

const projectStore = useProjectStore()

// Local editable copy
const local = reactive<any>({ ...props.step })

watch(() => props.step, (newStep) => {
  Object.assign(local, newStep)
}, { deep: true })

function save() {
  projectStore.updateStep(props.operationId, props.stepIndex, { ...local } as StepConfig)
}

function remove() {
  projectStore.removeStep(props.operationId, props.stepIndex)
}

function addField(mode: 'to' | 'set' = 'set') {
  if (local.type === 'respond' || local.type === 'map') {
    local.fields.push({ key: '', value: '', mode })
    save()
  }
}

function removeField(index: number) {
  if (local.type === 'respond' || local.type === 'map') {
    local.fields.splice(index, 1)
    save()
  }
}
</script>

<template>
  <div class="step-props">
    <div class="step-props-header">
      <h4 class="section-title">{{ local.type }} step</h4>
      <button class="danger-btn" @click="remove" title="Remove step">&times;</button>
    </div>

    <!-- Process -->
    <template v-if="local.type === 'process'">
      <div class="field">
        <label>Name</label>
        <input v-model="local.name" placeholder="e.g. enrich" @change="save" />
      </div>
      <div class="field">
        <label>Expression</label>
        <textarea
          v-model="local.expression"
          rows="5"
          placeholder="body + mapOf(&quot;key&quot; to &quot;value&quot;)"
          @change="save"
        ></textarea>
        <span class="hint">Kotlin expression. Use `body` for input map.</span>
      </div>
    </template>

    <!-- Call -->
    <template v-else-if="local.type === 'call'">
      <div class="field">
        <label>Connector</label>
        <select v-model="local.adapterName" @change="save">
          <option value="" disabled>Select a connector</option>
          <option v-for="a in projectStore.currentProject?.adapters ?? []" :key="a.id" :value="a.name">
            {{ a.name }} ({{ a.type }})
          </option>
        </select>
        <span class="hint" v-if="!projectStore.currentProject?.adapters?.length">
          Add a connector first via the tree panel.
        </span>
      </div>
      <div class="field">
        <label>Method</label>
        <select v-model="local.method" @change="save">
          <option>GET</option>
          <option>POST</option>
          <option>PUT</option>
          <option>PATCH</option>
          <option>DELETE</option>
        </select>
      </div>
      <div class="field">
        <label>Path</label>
        <input v-model="local.path" placeholder="/resource/{id}" @change="save" />
      </div>
    </template>

    <!-- Log -->
    <template v-else-if="local.type === 'log'">
      <div class="field">
        <label>Message</label>
        <input v-model="local.message" placeholder="Log message" @change="save" />
      </div>
      <div class="field">
        <label>Level</label>
        <select v-model="local.level" @change="save">
          <option>DEBUG</option>
          <option>INFO</option>
          <option>WARN</option>
          <option>ERROR</option>
        </select>
      </div>
    </template>

    <!-- Respond -->
    <template v-else-if="local.type === 'respond'">
      <div class="field">
        <label>Response Fields</label>
        <div class="field-list">
          <div v-for="(f, i) in local.fields" :key="i" class="field-row">
            <input
              v-model="f.key"
              :placeholder="f.mode === 'to' ? 'response field' : 'response field'"
              class="field-key"
              @change="save"
            />
            <button
              class="mode-toggle"
              :class="f.mode"
              @click="f.mode = f.mode === 'to' ? 'set' : 'to'; save()"
              :title="f.mode === 'to' ? 'to: maps source field → response' : 'set: assigns value'"
            >{{ f.mode }}</button>
            <input
              v-model="f.value"
              :placeholder="f.mode === 'to' ? 'source field' : 'value or expression'"
              class="field-value"
              @change="save"
            />
            <button class="remove-field" @click="removeField(i)">&times;</button>
          </div>
        </div>
        <div class="add-field-actions">
          <button class="add-field-btn" @click="addField('to')">+ to field</button>
          <button class="add-field-btn" @click="addField('set')">+ set field</button>
        </div>
        <span class="hint">
          <strong>to</strong> = map source field to response.
          <strong>set</strong> = assign literal, expression, or function: now(), uuid()
        </span>
      </div>
    </template>

    <!-- Map -->
    <template v-else-if="local.type === 'map'">
      <div class="field">
        <label>Field Mappings</label>
        <div class="field-list">
          <div v-for="(f, i) in local.fields" :key="i" class="field-row">
            <input
              v-model="f.key"
              placeholder="target field"
              class="field-key"
              @change="save"
            />
            <button
              class="mode-toggle"
              :class="f.mode"
              @click="f.mode = f.mode === 'to' ? 'set' : 'to'; save()"
              :title="f.mode === 'to' ? 'to: maps source field' : 'set: assigns value'"
            >{{ f.mode }}</button>
            <input
              v-model="f.value"
              :placeholder="f.mode === 'to' ? 'source field' : 'value or expression'"
              class="field-value"
              @change="save"
            />
            <button class="remove-field" @click="removeField(i)">&times;</button>
          </div>
        </div>
        <div class="add-field-actions">
          <button class="add-field-btn" @click="addField('to')">+ to field</button>
          <button class="add-field-btn" @click="addField('set')">+ set field</button>
        </div>
        <span class="hint">
          <strong>to</strong> = pass source field through.
          <strong>set</strong> = assign literal, expression, or function: now(), uuid()
        </span>
      </div>
    </template>
  </div>
</template>

<style scoped>
.step-props {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.step-props-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  text-transform: capitalize;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.hint {
  font-size: 10px;
  color: var(--text-muted);
  margin-top: 2px;
}

.danger-btn {
  background: transparent;
  color: var(--red);
  font-size: 18px;
  padding: 2px 6px;
  line-height: 1;
}

.danger-btn:hover {
  background: rgba(176, 80, 80, 0.1);
}

/* Respond field editor */
.field-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-row {
  display: flex;
  gap: 4px;
  align-items: center;
}

.field-key {
  width: 35% !important;
  font-family: var(--font-mono);
  font-size: 12px;
}

.field-value {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12px;
}

.remove-field {
  padding: 2px 6px;
  font-size: 14px;
  color: var(--text-muted);
  background: transparent;
  flex-shrink: 0;
}

.remove-field:hover {
  color: var(--red);
}

.add-field-actions {
  display: flex;
  gap: 12px;
}

.add-field-btn {
  font-size: 11px;
  color: var(--accent);
  background: transparent;
  text-align: left;
  padding: 4px 0;
}

.add-field-btn:hover {
  background: transparent;
  text-decoration: underline;
}

.mode-toggle {
  font-size: 10px;
  font-weight: 600;
  font-family: var(--font-mono);
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  flex-shrink: 0;
  min-width: 28px;
  text-align: center;
  cursor: pointer;
  line-height: 1.4;
}

.mode-toggle.to {
  background: rgba(130, 170, 255, 0.12);
  color: var(--blue, #82aaff);
  border: 1px solid rgba(130, 170, 255, 0.25);
}

.mode-toggle.set {
  background: rgba(199, 146, 234, 0.12);
  color: var(--purple, #c792ea);
  border: 1px solid rgba(199, 146, 234, 0.25);
}
</style>
