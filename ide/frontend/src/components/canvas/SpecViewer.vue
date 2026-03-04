<script setup lang="ts">
import { computed, ref } from 'vue'
import type { SpecFile, SchemaDef } from '@/types'

const props = defineProps<{ spec: SpecFile }>()

const expandedSchemas = ref<Set<string>>(new Set())

// Auto-expand schemas that are referenced by operations
const referencedSchemas = computed(() => {
  const refs = new Set<string>()
  for (const op of props.spec.parsed.operations) {
    if (op.requestSchema) refs.add(op.requestSchema)
    if (op.responseSchema) refs.add(op.responseSchema)
  }
  return refs
})

function toggleSchema(name: string) {
  if (expandedSchemas.value.has(name)) {
    expandedSchemas.value.delete(name)
  } else {
    expandedSchemas.value.add(name)
  }
}

function isExpanded(name: string) {
  return expandedSchemas.value.has(name) || referencedSchemas.value.has(name)
}

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

function methodBg(method: string): string {
  const bg: Record<string, string> = {
    GET: 'rgba(92, 138, 74, 0.08)',
    POST: 'rgba(74, 122, 156, 0.08)',
    PUT: 'rgba(176, 125, 79, 0.08)',
    PATCH: 'rgba(156, 138, 58, 0.08)',
    DELETE: 'rgba(176, 80, 80, 0.08)',
  }
  return bg[method] || 'rgba(128, 128, 128, 0.05)'
}

function getSchema(name: string): SchemaDef | undefined {
  return props.spec.parsed.schemas.find(s => s.name === name)
}

function isRequired(schema: SchemaDef, fieldName: string): boolean {
  return schema.required?.includes(fieldName) ?? false
}

function scrollToSchema(name: string) {
  expandedSchemas.value.add(name)
  const el = document.getElementById(`schema-${name}`)
  el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}
</script>

<template>
  <div class="spec-viewer">
    <!-- Header -->
    <div class="spec-header">
      <h2 class="spec-title">{{ spec.parsed.title }}</h2>
      <span class="spec-file">{{ spec.filename }}</span>
    </div>

    <!-- Operations -->
    <div class="section" v-if="spec.parsed.operations.length">
      <div class="section-label">
        <span class="section-icon">&#9656;</span>
        Paths
        <span class="count">{{ spec.parsed.operations.length }}</span>
      </div>

      <div
        v-for="op in spec.parsed.operations"
        :key="op.operationId"
        class="op-card"
        :style="{ borderLeftColor: methodColor(op.method) }"
      >
        <div class="op-header">
          <span class="method-pill" :style="{ color: methodColor(op.method), background: methodBg(op.method) }">
            {{ op.method }}
          </span>
          <span class="op-path">{{ op.path }}</span>
          <span class="op-id">{{ op.operationId }}</span>
        </div>

        <div class="op-summary" v-if="op.summary">{{ op.summary }}</div>

        <!-- Parameters -->
        <div class="op-detail" v-if="op.parameters.length">
          <span class="detail-label">params</span>
          <span v-for="param in op.parameters" :key="param.name" class="param-chip" :class="{ required: param.required }">
            {{ param.name }}<span class="param-in">{{ param.in }}</span>
          </span>
        </div>

        <!-- Request / Response schemas -->
        <div class="op-schemas">
          <div class="schema-ref" v-if="op.requestSchema" @click="scrollToSchema(op.requestSchema)">
            <span class="arrow-label">&#8594; request</span>
            <span class="schema-link">{{ op.requestSchema }}</span>
          </div>
          <div class="schema-ref" v-if="op.responseSchema" @click="scrollToSchema(op.responseSchema)">
            <span class="arrow-label">&#8592; response</span>
            <span class="schema-link">{{ op.responseSchema }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Schemas -->
    <div class="section" v-if="spec.parsed.schemas.length">
      <div class="section-label">
        <span class="section-icon">&#9656;</span>
        Schemas
        <span class="count">{{ spec.parsed.schemas.length }}</span>
      </div>

      <div
        v-for="schema in spec.parsed.schemas"
        :key="schema.name"
        :id="`schema-${schema.name}`"
        class="schema-card"
        :class="{ referenced: referencedSchemas.has(schema.name) }"
      >
        <div class="schema-header" @click="toggleSchema(schema.name)">
          <span class="expand-icon">{{ isExpanded(schema.name) ? '&#9662;' : '&#9656;' }}</span>
          <span class="schema-name">{{ schema.name }}</span>
          <span class="field-count">{{ schema.fields.length }} fields</span>
        </div>

        <div class="schema-fields" v-if="isExpanded(schema.name)">
          <div
            v-for="field in schema.fields"
            :key="field.name"
            class="field-row"
            :class="{ required: isRequired(schema, field.name) }"
          >
            <span class="field-name">{{ field.name }}</span>
            <span class="field-type">{{ field.type }}<template v-if="field.format">.{{ field.format }}</template></span>
            <span class="field-req" v-if="isRequired(schema, field.name)">required</span>
            <span class="field-desc" v-if="field.description">{{ field.description }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.spec-viewer {
  display: flex;
  flex-direction: column;
  gap: 24px;
  max-width: 720px;
  padding: 8px 0;
}

.spec-header {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.spec-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
  letter-spacing: -0.3px;
}

.spec-file {
  font-size: 11px;
  font-family: var(--font-mono);
  color: var(--text-muted);
}

/* Sections */
.section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.section-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10px;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.section-icon {
  font-size: 8px;
}

.count {
  font-weight: 400;
  color: var(--text-muted);
  font-size: 10px;
}

/* Operation cards */
.op-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 14px;
  background: var(--bg-secondary);
  border-radius: var(--radius);
  border-left: 3px solid var(--border);
}

.op-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.method-pill {
  font-size: 10px;
  font-weight: 700;
  font-family: var(--font-mono);
  padding: 2px 6px;
  border-radius: 3px;
  letter-spacing: 0.3px;
  flex-shrink: 0;
}

.op-path {
  font-size: 13px;
  font-family: var(--font-mono);
  font-weight: 500;
  color: var(--text-primary);
}

.op-id {
  margin-left: auto;
  font-size: 10px;
  font-family: var(--font-mono);
  color: var(--text-muted);
  flex-shrink: 0;
}

.op-summary {
  font-size: 11px;
  color: var(--text-secondary);
  line-height: 1.4;
}

.op-detail {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

.detail-label {
  font-size: 9px;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.3px;
  margin-right: 2px;
}

.param-chip {
  font-size: 10px;
  font-family: var(--font-mono);
  padding: 1px 6px;
  background: var(--bg-surface);
  border-radius: 3px;
  color: var(--text-secondary);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.param-chip.required {
  color: var(--text-primary);
  font-weight: 500;
}

.param-in {
  font-size: 8px;
  color: var(--text-muted);
  font-weight: 400;
}

.op-schemas {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.schema-ref {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}

.schema-ref:hover .schema-link {
  color: var(--accent);
  text-decoration: underline;
}

.arrow-label {
  font-size: 9px;
  color: var(--text-muted);
}

.schema-link {
  font-size: 11px;
  font-family: var(--font-mono);
  color: var(--accent);
  font-weight: 500;
}

/* Schema cards */
.schema-card {
  background: var(--bg-secondary);
  border-radius: var(--radius);
  border: 1px solid var(--border-subtle);
  overflow: hidden;
}

.schema-card.referenced {
  border-color: var(--border);
}

.schema-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  cursor: pointer;
  user-select: none;
}

.schema-header:hover {
  background: var(--bg-hover);
}

.expand-icon {
  font-size: 9px;
  color: var(--text-muted);
  width: 10px;
}

.schema-name {
  font-size: 13px;
  font-family: var(--font-mono);
  font-weight: 600;
  color: var(--text-primary);
}

.field-count {
  margin-left: auto;
  font-size: 10px;
  color: var(--text-muted);
}

.schema-fields {
  border-top: 1px solid var(--border-subtle);
}

.field-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 5px 12px 5px 28px;
  font-size: 12px;
}

.field-row:not(:last-child) {
  border-bottom: 1px solid var(--border-subtle);
}

.field-row.required .field-name {
  font-weight: 600;
}

.field-name {
  font-family: var(--font-mono);
  color: var(--text-primary);
  min-width: 100px;
  flex-shrink: 0;
}

.field-type {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
  flex-shrink: 0;
}

.field-req {
  font-size: 9px;
  color: var(--red);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  flex-shrink: 0;
}

.field-desc {
  font-size: 11px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
