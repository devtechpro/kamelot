<script setup lang="ts">
import { reactive, ref, watch, computed } from 'vue'
import { useProjectStore } from '@/stores/project'
import type { AdapterConfig } from '@/types'

const props = defineProps<{ connector: AdapterConfig }>()
const projectStore = useProjectStore()

const testing = ref(false)
const testResult = ref<{ ok: boolean; server?: string; error?: string } | null>(null)

const local = reactive<any>({ ...props.connector })

watch(() => props.connector, (c) => {
  Object.assign(local, { ...c })
  testResult.value = null
}, { deep: true })

const specs = computed(() => projectStore.currentProject?.specs ?? [])
const boundSpec = computed(() => specs.value.find(s => s.id === local.specId))

function ensurePostgres() {
  if (!local.postgres) local.postgres = { url: '', username: '', password: '', table: '' }
  return local.postgres
}

function savePg(field: string, value: string) {
  const pg = ensurePostgres()
  pg[field] = value
  save()
}

function save() {
  testResult.value = null
  projectStore.updateConnector(props.connector.id, { ...local })
}

async function testConn() {
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await projectStore.testConnection({ ...local })
  } catch (e: any) {
    testResult.value = { ok: false, error: e.message }
  } finally {
    testing.value = false
  }
}

function remove() {
  projectStore.removeConnector(props.connector.id)
}
</script>

<template>
  <div class="connector-props">
    <div class="props-header">
      <h4 class="section-title">{{ local.name }}</h4>
      <button class="danger-btn" @click="remove" title="Remove connector">&times;</button>
    </div>

    <div class="type-badge" :class="local.type">{{ local.type }}</div>

    <div class="field">
      <label>Name</label>
      <input v-model="local.name" @change="save" />
    </div>

    <div class="field" v-if="local.type === 'http'">
      <label>Spec</label>
      <select v-model="local.specId" @change="save">
        <option :value="undefined">None</option>
        <option v-for="s in specs" :key="s.id" :value="s.id">
          {{ s.parsed.title || s.filename }}
        </option>
      </select>
      <span class="hint" v-if="boundSpec">
        {{ boundSpec.parsed.operations.length }} operations,
        {{ boundSpec.parsed.schemas.length }} schemas
      </span>
    </div>

    <!-- HTTP config -->
    <template v-if="local.type === 'http'">
      <div class="config-section">Connection</div>
      <div class="field">
        <label>Base URL</label>
        <input v-model="local.baseUrl" placeholder="https://api.example.com" @change="save" />
      </div>
    </template>

    <!-- Postgres config -->
    <template v-if="local.type === 'postgres'">
      <div class="config-section">Connection</div>
      <div class="field">
        <label>URL</label>
        <input
          :value="local.postgres?.url"
          placeholder="jdbc:postgresql://localhost:5432/mydb"
          @change="(e: Event) => savePg('url', (e.target as HTMLInputElement).value)"
        />
      </div>
      <div class="field">
        <label>Username</label>
        <input
          :value="local.postgres?.username"
          placeholder="postgres"
          @change="(e: Event) => savePg('username', (e.target as HTMLInputElement).value)"
        />
      </div>
      <div class="field">
        <label>Password</label>
        <input
          type="password"
          :value="local.postgres?.password || ''"
          placeholder="password"
          @change="(e: Event) => savePg('password', (e.target as HTMLInputElement).value)"
        />
      </div>
      <div class="field">
        <label>Table</label>
        <input
          :value="local.postgres?.table"
          placeholder="contacts"
          @change="(e: Event) => savePg('table', (e.target as HTMLInputElement).value)"
        />
      </div>
      <div class="field">
        <label>Schema</label>
        <input
          :value="local.postgres?.schema || ''"
          placeholder="public"
          @change="(e: Event) => savePg('schema', (e.target as HTMLInputElement).value)"
        />
        <span class="hint">Optional. Defaults to public.</span>
      </div>

      <button
        class="test-btn"
        :disabled="testing || !local.postgres?.url"
        @click="testConn"
      >
        {{ testing ? 'Testing...' : 'Test Connection' }}
      </button>
      <div v-if="testResult" class="test-result" :class="{ ok: testResult.ok, fail: !testResult.ok }">
        <template v-if="testResult.ok">
          Connected — {{ testResult.server }}
        </template>
        <template v-else>
          {{ testResult.error }}
        </template>
      </div>
    </template>
  </div>
</template>

<style scoped>
.connector-props {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.props-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.type-badge {
  display: inline-block;
  padding: 2px 8px;
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  border-radius: 3px;
  width: fit-content;
}

.type-badge.http {
  color: var(--method-get);
  background: rgba(97, 175, 239, 0.1);
}

.type-badge.postgres {
  color: var(--method-post);
  background: rgba(152, 195, 121, 0.1);
}

.config-section {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding-top: 4px;
  border-top: 1px solid var(--border-subtle);
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

.test-btn {
  padding: 6px 12px;
  font-size: 11px;
  font-weight: 600;
  color: var(--text-primary);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  width: fit-content;
}

.test-btn:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}

.test-btn:disabled {
  opacity: 0.5;
  cursor: default;
}

.test-result {
  font-size: 11px;
  padding: 6px 10px;
  border-radius: var(--radius-sm);
  line-height: 1.4;
}

.test-result.ok {
  color: #2d7d46;
  background: rgba(45, 125, 70, 0.08);
}

.test-result.fail {
  color: var(--red);
  background: rgba(194, 122, 122, 0.08);
}
</style>
