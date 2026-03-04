<script setup lang="ts">
import { computed, ref } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useCanvasStore } from '@/stores/canvas'
import { detectLeftSchema, detectRightSchema } from '@/utils/schema-detect'
import type { RespondField, FieldDef } from '@/types'

const projectStore = useProjectStore()
const canvas = useCanvasStore()

const selectedSource = ref<string | null>(null)

const stepContext = computed(() => {
  const sel = canvas.selection
  if (!sel || sel.type !== 'step') return null
  const flow = projectStore.getFlow(sel.operationId)
  if (!flow) return null
  const step = flow.steps[sel.stepIndex]
  if (!step || step.type !== 'map') return null
  const operation = projectStore.operations.find(o => o.operationId === sel.operationId)
  return { flow, step, stepIndex: sel.stepIndex, operationId: sel.operationId, operation }
})

const leftSchema = computed(() => {
  if (!stepContext.value || !projectStore.currentProject) return null
  return detectLeftSchema(
    stepContext.value.flow,
    stepContext.value.stepIndex,
    stepContext.value.operation,
    projectStore.currentProject.adapters,
    projectStore.currentProject.specs,
  )
})

const rightSchema = computed(() => {
  if (!stepContext.value || !projectStore.currentProject) return null
  return detectRightSchema(
    stepContext.value.flow,
    stepContext.value.stepIndex,
    stepContext.value.operation,
    projectStore.currentProject.adapters,
    projectStore.currentProject.specs,
  )
})

const mappedFields = computed<RespondField[]>(() => {
  return stepContext.value?.step.fields ?? []
})

// Index mappings by target key for quick lookup
const mappingsByTarget = computed(() => {
  const map = new Map<string, RespondField>()
  for (const f of mappedFields.value) {
    map.set(f.key, f)
  }
  return map
})

// Index mappings by source value (for "to" mode) to highlight connected source fields
const mappedSources = computed(() => {
  const set = new Set<string>()
  for (const f of mappedFields.value) {
    if (f.mode === 'to') set.add(f.value)
  }
  return set
})

function onSourceClick(fieldName: string) {
  selectedSource.value = selectedSource.value === fieldName ? null : fieldName
}

function onTargetClick(fieldName: string) {
  if (!stepContext.value) return

  if (selectedSource.value) {
    // Create a "to" mapping
    const fields = [...mappedFields.value.filter(f => f.key !== fieldName)]
    fields.push({ key: fieldName, value: selectedSource.value, mode: 'to' })
    updateFields(fields)
    selectedSource.value = null
  }
}

function addSetField(fieldName: string) {
  if (!stepContext.value) return
  const fields = [...mappedFields.value.filter(f => f.key !== fieldName)]
  fields.push({ key: fieldName, value: '', mode: 'set' })
  updateFields(fields)
}

function removeMapping(key: string) {
  if (!stepContext.value) return
  updateFields(mappedFields.value.filter(f => f.key !== key))
}

function updateSetValue(key: string, value: string) {
  if (!stepContext.value) return
  const fields = mappedFields.value.map(f => f.key === key ? { ...f, value } : f)
  updateFields(fields)
}

function autoMap() {
  if (!stepContext.value || !leftSchema.value?.schema || !rightSchema.value?.schema) return
  const leftFields = new Set(leftSchema.value.schema.fields.map(f => f.name))
  const existing = new Set(mappedFields.value.map(f => f.key))
  const newFields: RespondField[] = [...mappedFields.value]
  for (const target of rightSchema.value.schema.fields) {
    if (!existing.has(target.name) && leftFields.has(target.name)) {
      newFields.push({ key: target.name, value: target.name, mode: 'to' })
    }
  }
  updateFields(newFields)
}

function updateFields(fields: RespondField[]) {
  if (!stepContext.value) return
  projectStore.updateStep(
    stepContext.value.operationId,
    stepContext.value.stepIndex,
    { type: 'map', fields },
  )
}

function fieldType(field: FieldDef): string {
  return field.format || field.type
}
</script>

<template>
  <div class="mapping-panel">
    <div v-if="!stepContext" class="empty-state">
      Select a map step to see field mappings.
    </div>

    <div v-else-if="!leftSchema?.schema && !rightSchema?.schema" class="empty-state">
      No schemas detected. Add fields manually in the properties panel.
    </div>

    <div v-else class="mapping-content">
      <div class="mapping-header">
        <div class="schema-label left">{{ leftSchema?.label || 'Source' }}</div>
        <div class="mapping-actions">
          <button class="auto-map-btn" @click="autoMap" title="Map fields with matching names">Auto-map</button>
        </div>
        <div class="schema-label right">{{ rightSchema?.label || 'Target' }}</div>
      </div>

      <div class="mapping-columns">
        <!-- Left column: source fields -->
        <div class="column source-column">
          <div
            v-for="field in (leftSchema?.schema?.fields ?? [])"
            :key="field.name"
            class="schema-field"
            :class="{
              selected: selectedSource === field.name,
              mapped: mappedSources.has(field.name),
            }"
            @click="onSourceClick(field.name)"
          >
            <span class="field-name">{{ field.name }}</span>
            <span class="field-type">{{ fieldType(field) }}</span>
          </div>
          <div v-if="!leftSchema?.schema?.fields?.length" class="no-fields">No fields</div>
        </div>

        <!-- Center: connection indicators -->
        <div class="connections">
          <div
            v-for="f in mappedFields"
            :key="f.key"
            class="connection-line"
            :class="f.mode"
          >
            <template v-if="f.mode === 'to'">
              <span class="conn-source">{{ f.value }}</span>
              <span class="conn-arrow">&rarr;</span>
              <span class="conn-target">{{ f.key }}</span>
            </template>
            <template v-else>
              <span class="conn-expr">{{ f.value || '?' }}</span>
              <span class="conn-arrow">&rarr;</span>
              <span class="conn-target">{{ f.key }}</span>
            </template>
            <button class="conn-remove" @click="removeMapping(f.key)" title="Remove mapping">&times;</button>
          </div>
          <div v-if="!mappedFields.length" class="no-mappings">Click a source field, then a target field to map.</div>
        </div>

        <!-- Right column: target fields -->
        <div class="column target-column">
          <div
            v-for="field in (rightSchema?.schema?.fields ?? [])"
            :key="field.name"
            class="schema-field"
            :class="{
              mapped: mappingsByTarget.has(field.name),
              clickable: !!selectedSource,
            }"
            @click="onTargetClick(field.name)"
          >
            <span class="field-name">{{ field.name }}</span>
            <span class="field-type">{{ fieldType(field) }}</span>
            <button
              v-if="!mappingsByTarget.has(field.name) && !selectedSource"
              class="set-btn"
              @click.stop="addSetField(field.name)"
              title="Set a value"
            >set</button>
            <input
              v-if="mappingsByTarget.has(field.name) && mappingsByTarget.get(field.name)?.mode === 'set'"
              class="set-input"
              :value="mappingsByTarget.get(field.name)?.value"
              placeholder="value"
              @change="updateSetValue(field.name, ($event.target as HTMLInputElement).value)"
              @click.stop
            />
          </div>
          <div v-if="!rightSchema?.schema?.fields?.length" class="no-fields">No fields</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mapping-panel {
  height: 100%;
  overflow: auto;
  padding: 8px 12px;
  font-size: 12px;
}

.empty-state {
  color: var(--text-muted);
  padding: 16px 0;
  font-size: 11px;
}

.mapping-content {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.mapping-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.schema-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-secondary);
  flex: 1;
}

.schema-label.right {
  text-align: right;
}

.mapping-actions {
  flex-shrink: 0;
}

.auto-map-btn {
  font-size: 10px;
  padding: 2px 8px;
  color: var(--accent);
  background: rgba(176, 125, 79, 0.08);
  border: 1px solid rgba(176, 125, 79, 0.2);
  border-radius: 3px;
}

.auto-map-btn:hover {
  background: rgba(176, 125, 79, 0.15);
}

.mapping-columns {
  display: flex;
  gap: 8px;
  min-height: 0;
}

.column {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.connections {
  flex: 1.5;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.schema-field {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 8px;
  border-radius: 3px;
  cursor: pointer;
  transition: background 0.1s;
  border: 1px solid transparent;
}

.schema-field:hover {
  background: var(--bg-hover);
}

.schema-field.selected {
  background: rgba(176, 125, 79, 0.12);
  border-color: var(--step-map);
}

.schema-field.mapped {
  background: rgba(92, 138, 74, 0.08);
  border-color: rgba(92, 138, 74, 0.2);
}

.schema-field.clickable {
  cursor: crosshair;
}

.schema-field.clickable:hover {
  background: rgba(176, 125, 79, 0.15);
  border-color: var(--step-map);
}

.field-name {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.field-type {
  font-size: 9px;
  color: var(--text-muted);
  flex-shrink: 0;
}

.no-fields {
  color: var(--text-muted);
  font-size: 10px;
  padding: 4px 8px;
}

/* Connection indicators */
.connection-line {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 6px;
  font-family: var(--font-mono);
  font-size: 10px;
  border-radius: 3px;
  background: var(--bg-hover);
}

.connection-line.to {
  color: var(--blue, #82aaff);
}

.connection-line.set {
  color: var(--purple, #c792ea);
}

.conn-source, .conn-target {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conn-arrow {
  color: var(--text-muted);
  flex-shrink: 0;
}

.conn-expr {
  color: var(--purple, #c792ea);
  font-style: italic;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conn-remove {
  margin-left: auto;
  padding: 0 3px;
  font-size: 12px;
  color: var(--text-muted);
  background: transparent;
  flex-shrink: 0;
  opacity: 0;
}

.connection-line:hover .conn-remove {
  opacity: 1;
}

.conn-remove:hover {
  color: var(--red);
}

.no-mappings {
  color: var(--text-muted);
  font-size: 10px;
  padding: 4px 6px;
  text-align: center;
}

.set-btn {
  font-size: 9px;
  padding: 0 4px;
  color: var(--purple, #c792ea);
  background: transparent;
  margin-left: auto;
  flex-shrink: 0;
  opacity: 0;
}

.schema-field:hover .set-btn {
  opacity: 1;
}

.set-input {
  width: 70px;
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 10px;
  padding: 1px 4px;
  border-radius: 2px;
  flex-shrink: 0;
}
</style>
