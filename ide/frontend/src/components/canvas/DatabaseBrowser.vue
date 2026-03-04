<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { useProjectStore } from '@/stores/project'
import type { AdapterConfig, TableInfo, ColumnInfo } from '@/types'

const props = defineProps<{ connector: AdapterConfig }>()
const projectStore = useProjectStore()

const tables = ref<TableInfo[]>([])
const selectedTable = ref<string | null>(null)
const columns = ref<ColumnInfo[]>([])
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(0)
const pageSize = 50

const loading = ref(false)
const loadingRows = ref(false)
const error = ref<string | null>(null)

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

async function loadTables() {
  loading.value = true
  error.value = null
  try {
    const data = await projectStore.fetchTables(props.connector.id)
    if (data.error) {
      error.value = data.error
      tables.value = []
      return
    }
    tables.value = data.tables ?? []
    // Auto-select first table or the configured one
    if (tables.value.length > 0) {
      const configured = props.connector.postgres?.table
      const match = configured ? tables.value.find(t => t.name === configured) : null
      selectTable(match?.name ?? tables.value[0].name)
    } else {
      selectedTable.value = null
    }
  } catch (e: any) {
    error.value = e.message || 'Failed to connect'
    tables.value = []
  } finally {
    loading.value = false
  }
}

async function selectTable(name: string) {
  selectedTable.value = name
  page.value = 0
  loadingRows.value = true
  try {
    const [colData, rowData] = await Promise.all([
      projectStore.fetchColumns(props.connector.id, name),
      projectStore.fetchRows(props.connector.id, name, pageSize, 0),
    ])
    columns.value = colData.columns ?? []
    rows.value = rowData.rows ?? []
    total.value = rowData.total ?? 0
  } catch (e: any) {
    error.value = e.message
  } finally {
    loadingRows.value = false
  }
}

async function changePage(newPage: number) {
  if (!selectedTable.value || newPage < 0 || newPage >= totalPages.value) return
  page.value = newPage
  loadingRows.value = true
  try {
    const data = await projectStore.fetchRows(props.connector.id, selectedTable.value, pageSize, newPage * pageSize)
    rows.value = data.rows ?? []
    total.value = data.total ?? 0
  } finally {
    loadingRows.value = false
  }
}

function refresh() {
  loadTables()
}

function formatCell(value: any): string {
  if (value === null || value === undefined) return 'NULL'
  if (typeof value === 'string' && value.length > 80) return value.substring(0, 80) + '...'
  return String(value)
}

function formatType(type: string): string {
  const map: Record<string, string> = {
    'text': 'text',
    'varchar': 'varchar',
    'int4': 'int',
    'int8': 'bigint',
    'float4': 'float',
    'float8': 'double',
    'bool': 'bool',
    'timestamp': 'timestamp',
    'timestamptz': 'timestamptz',
    'uuid': 'uuid',
    'jsonb': 'jsonb',
    'json': 'json',
  }
  return map[type] ?? type
}

// Load on mount and when connector changes
watch(() => props.connector.id, () => loadTables(), { immediate: true })
</script>

<template>
  <div class="db-browser">
    <!-- Header -->
    <div class="db-header">
      <div class="db-title">
        <span class="db-icon">PG</span>
        <span class="db-name">{{ connector.name }}</span>
        <span class="db-url">{{ connector.postgres?.url?.replace(/^jdbc:postgresql:\/\//, '') }}</span>
      </div>
      <button class="refresh-btn" @click="refresh" :disabled="loading" title="Refresh">
        <span :class="{ spinning: loading }">&#x21bb;</span>
      </button>
    </div>

    <!-- Error state -->
    <div v-if="error" class="db-error">
      <strong>Connection error</strong>
      <span>{{ error }}</span>
    </div>

    <!-- Loading state -->
    <div v-else-if="loading && !tables.length" class="db-loading">
      Connecting...
    </div>

    <!-- Browser -->
    <div v-else class="db-body">
      <!-- Table sidebar -->
      <div class="table-sidebar">
        <div class="sidebar-header">Tables</div>
        <div v-if="!tables.length" class="sidebar-empty">No tables found</div>
        <div
          v-for="t in tables"
          :key="t.name"
          class="table-item"
          :class="{ active: selectedTable === t.name }"
          @click="selectTable(t.name)"
        >
          <span class="table-name">{{ t.name }}</span>
          <span class="table-count">{{ t.rowCount }}</span>
        </div>
      </div>

      <!-- Data grid -->
      <div class="data-panel">
        <template v-if="selectedTable">
          <!-- Table header -->
          <div class="data-header">
            <span class="data-table-name">{{ selectedTable }}</span>
            <span class="data-meta">{{ total }} row{{ total !== 1 ? 's' : '' }}</span>
          </div>

          <!-- Loading rows -->
          <div v-if="loadingRows" class="data-loading">Loading...</div>

          <!-- Data table -->
          <div v-else class="data-grid-wrapper">
            <table class="data-grid" v-if="columns.length">
              <thead>
                <tr>
                  <th v-for="col in columns" :key="col.name">
                    <span class="col-name">{{ col.name }}</span>
                    <span class="col-type">{{ formatType(col.type) }}</span>
                    <span v-if="col.isPrimaryKey" class="col-pk" title="Primary key">PK</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!rows.length">
                  <td :colspan="columns.length" class="empty-cell">No data</td>
                </tr>
                <tr v-for="(row, i) in rows" :key="i">
                  <td v-for="col in columns" :key="col.name" :class="{ 'null-cell': row[col.name] === null }">
                    {{ formatCell(row[col.name]) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- Pagination -->
          <div v-if="totalPages > 1" class="pagination">
            <button @click="changePage(page - 1)" :disabled="page === 0">&lsaquo; Prev</button>
            <span class="page-info">Page {{ page + 1 }} of {{ totalPages }}</span>
            <button @click="changePage(page + 1)" :disabled="page >= totalPages - 1">Next &rsaquo;</button>
          </div>
        </template>
        <div v-else class="data-empty">Select a table to browse its data.</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.db-browser {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 0;
}

.db-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border-subtle);
  flex-shrink: 0;
}

.db-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.db-icon {
  font-size: 10px;
  font-weight: 700;
  color: #336791;
  background: rgba(51, 103, 145, 0.1);
  padding: 2px 6px;
  border-radius: 3px;
  letter-spacing: 0.5px;
}

.db-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.db-url {
  font-size: 11px;
  color: var(--text-muted);
  font-family: var(--font-mono);
}

.refresh-btn {
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 4px 8px;
  font-size: 16px;
  cursor: pointer;
  color: var(--text-secondary);
  line-height: 1;
}

.refresh-btn:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}

.spinning {
  display: inline-block;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.db-error {
  margin: 16px;
  padding: 12px 16px;
  background: rgba(176, 80, 80, 0.06);
  border: 1px solid rgba(176, 80, 80, 0.2);
  border-radius: var(--radius);
  color: var(--red);
  font-size: 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.db-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 120px;
  color: var(--text-muted);
  font-size: 12px;
}

.db-body {
  display: flex;
  flex: 1;
  min-height: 0;
}

/* Table sidebar */
.table-sidebar {
  width: 180px;
  min-width: 140px;
  border-right: 1px solid var(--border-subtle);
  overflow-y: auto;
  flex-shrink: 0;
}

.sidebar-header {
  padding: 8px 12px;
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--text-muted);
  border-bottom: 1px solid var(--border-subtle);
}

.sidebar-empty {
  padding: 12px;
  color: var(--text-muted);
  font-size: 11px;
}

.table-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px;
  cursor: pointer;
  font-size: 12px;
  color: var(--text-secondary);
  border-left: 2px solid transparent;
}

.table-item:hover {
  background: var(--bg-hover);
}

.table-item.active {
  background: var(--accent-subtle);
  border-left-color: var(--accent);
  color: var(--text-primary);
  font-weight: 500;
}

.table-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-count {
  font-size: 10px;
  color: var(--text-muted);
  background: var(--bg-surface);
  padding: 1px 5px;
  border-radius: 8px;
  flex-shrink: 0;
}

/* Data panel */
.data-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
}

.data-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--border-subtle);
  flex-shrink: 0;
}

.data-table-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.data-meta {
  font-size: 11px;
  color: var(--text-muted);
}

.data-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 80px;
  color: var(--text-muted);
  font-size: 12px;
}

.data-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-muted);
  font-size: 12px;
}

.data-grid-wrapper {
  flex: 1;
  overflow: auto;
}

.data-grid {
  width: 100%;
  border-collapse: collapse;
  font-size: 11px;
  font-family: var(--font-mono);
}

.data-grid thead {
  position: sticky;
  top: 0;
  z-index: 1;
}

.data-grid th {
  text-align: left;
  padding: 6px 12px;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
  font-weight: 500;
  color: var(--text-secondary);
}

.col-name {
  color: var(--text-primary);
  font-weight: 600;
}

.col-type {
  margin-left: 6px;
  font-size: 10px;
  color: var(--text-muted);
  font-weight: 400;
}

.col-pk {
  margin-left: 4px;
  font-size: 9px;
  font-weight: 700;
  color: var(--accent);
  background: rgba(176, 125, 79, 0.1);
  padding: 0 3px;
  border-radius: 2px;
}

.data-grid td {
  padding: 5px 12px;
  border-bottom: 1px solid var(--border-subtle);
  white-space: nowrap;
  color: var(--text-secondary);
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.data-grid tbody tr:hover {
  background: var(--bg-hover);
}

.null-cell {
  color: var(--text-muted) !important;
  font-style: italic;
}

.empty-cell {
  text-align: center;
  color: var(--text-muted);
  font-style: italic;
  padding: 20px 12px !important;
}

/* Pagination */
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 8px 14px;
  border-top: 1px solid var(--border-subtle);
  flex-shrink: 0;
}

.pagination button {
  font-size: 11px;
  padding: 3px 10px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  color: var(--text-secondary);
}

.pagination button:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}

.pagination button:disabled {
  opacity: 0.4;
  cursor: default;
}

.page-info {
  font-size: 11px;
  color: var(--text-muted);
}
</style>
