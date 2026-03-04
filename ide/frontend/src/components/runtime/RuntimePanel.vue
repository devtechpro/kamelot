<script setup lang="ts">
import { ref, watch, computed, onUnmounted } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useCanvasStore } from '@/stores/canvas'
import MappingPanel from '@/components/mapping/MappingPanel.vue'

const projectStore = useProjectStore()
const canvas = useCanvasStore()

const activeTab = ref<'console' | 'mapping'>('console')

const logs = ref<{ time: string; level: string; message: string }[]>([
  { time: new Date().toLocaleTimeString(), level: 'INFO', message: 'Studio ready' },
])

// Cursor for log polling
let logCursor = 0
let pollTimer: ReturnType<typeof setInterval> | null = null

// Auto-switch to mapping tab when a map step is selected
const isMapStepSelected = computed(() => {
  const sel = canvas.selection
  if (!sel || sel.type !== 'step') return false
  const flow = projectStore.getFlow(sel.operationId)
  if (!flow) return false
  const step = flow.steps[sel.stepIndex]
  return step?.type === 'map'
})

watch(isMapStepSelected, (isMap) => {
  if (isMap) activeTab.value = 'mapping'
})

// Poll for server logs when integration is running
async function pollLogs() {
  try {
    const { entries, cursor } = await projectStore.fetchLogs(logCursor)
    if (entries.length > 0) {
      logCursor = cursor
      for (const entry of entries) {
        logs.value.push({
          time: new Date(entry.time).toLocaleTimeString(),
          level: entry.level,
          message: entry.message,
        })
      }
    }
  } catch {
    // ignore polling errors
  }
}

watch(() => projectStore.runnerState, (state, prev) => {
  if (state === 'STARTING') addLog('INFO', 'Starting integration...')
  if (state === 'RUNNING' && prev !== 'RUNNING') {
    addLog('INFO', `Integration running on port ${projectStore.runnerPort}`)
    // Start polling for server logs
    logCursor = 0
    if (!pollTimer) {
      pollLogs()
      pollTimer = setInterval(pollLogs, 1500)
    }
  }
  if (state === 'STOPPED' && prev === 'STOPPING') {
    addLog('INFO', 'Integration stopped')
    stopPolling()
  }
  if (state === 'FAILED') {
    addLog('ERROR', `Failed: ${projectStore.runnerError || 'unknown error'}`)
    stopPolling()
  }
})

// Also start polling if already running on mount
if (projectStore.runnerState === 'RUNNING' && !pollTimer) {
  pollTimer = setInterval(pollLogs, 1500)
  pollLogs()
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

onUnmounted(() => {
  stopPolling()
})

function addLog(level: string, message: string) {
  logs.value.push({ time: new Date().toLocaleTimeString(), level, message })
}

function clearLogs() {
  logs.value = []
  logCursor = 0
}
</script>

<template>
  <div class="runtime-panel">
    <div class="panel-header">
      <div class="tab-bar">
        <button
          class="tab-btn"
          :class="{ active: activeTab === 'console' }"
          @click="activeTab = 'console'"
        >Console</button>
        <button
          class="tab-btn"
          :class="{ active: activeTab === 'mapping' }"
          @click="activeTab = 'mapping'"
        >Mapping</button>
      </div>
      <div class="header-right">
        <span v-if="projectStore.runnerState === 'RUNNING'" class="status-badge running">Running</span>
        <span v-else-if="projectStore.runnerState === 'STARTING'" class="status-badge starting">Starting</span>
        <button v-if="activeTab === 'console'" @click="clearLogs">Clear</button>
      </div>
    </div>

    <!-- Console tab -->
    <div v-if="activeTab === 'console'" class="log-area" ref="logArea">
      <div v-if="!logs.length" class="empty-logs">No output</div>
      <div v-for="(log, i) in logs" :key="i" class="log-line">
        <span class="log-time">{{ log.time }}</span>
        <span class="log-level" :class="log.level.toLowerCase()">{{ log.level }}</span>
        <span class="log-message">{{ log.message }}</span>
      </div>
    </div>

    <!-- Mapping tab -->
    <MappingPanel v-if="activeTab === 'mapping'" />
  </div>
</template>

<style scoped>
.runtime-panel {
  height: 100%;
  background: var(--bg-secondary);
  display: flex;
  flex-direction: column;
}

.tab-bar {
  display: flex;
  gap: 0;
}

.tab-btn {
  padding: 4px 12px;
  font-size: 11px;
  font-weight: 500;
  color: var(--text-muted);
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  transition: color 0.1s, border-color 0.1s;
}

.tab-btn:hover {
  color: var(--text-secondary);
}

.tab-btn.active {
  color: var(--text-primary);
  border-bottom-color: var(--accent);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-badge {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 3px;
}
.status-badge.running {
  background: rgba(45, 125, 70, 0.12);
  color: #2d7d46;
}
.status-badge.starting {
  background: rgba(176, 125, 79, 0.12);
  color: var(--accent);
}

.log-area {
  flex: 1;
  overflow-y: auto;
  padding: 6px 12px;
  font-family: var(--font-mono);
  font-size: 11px;
}

.empty-logs {
  color: var(--text-muted);
}

.log-line {
  display: flex;
  gap: 10px;
  padding: 1px 0;
  white-space: nowrap;
}

.log-time {
  color: var(--text-muted);
  min-width: 64px;
}

.log-level {
  min-width: 38px;
  font-weight: 600;
  font-size: 10px;
}

.log-level.info { color: var(--blue); }
.log-level.warn { color: var(--yellow); }
.log-level.error { color: var(--red); }
.log-level.debug { color: var(--text-muted); }

.log-message {
  color: var(--text-secondary);
}
</style>
