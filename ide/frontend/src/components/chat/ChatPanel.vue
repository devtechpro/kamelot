<script setup lang="ts">
import { ref, nextTick, watch, computed } from 'vue'
import { marked } from 'marked'
import { useChatStore } from '@/stores/chat'
import { useProjectStore } from '@/stores/project'

const chatStore = useChatStore()
const projectStore = useProjectStore()
const input = ref('')
const messagesEl = ref<HTMLElement | null>(null)
const testResults = ref<Map<string, { loading: boolean; status?: number; body?: string }>>(new Map())

// Configure marked for compact output
marked.setOptions({ breaks: true, gfm: true })

function renderMarkdown(text: string): string {
  if (!text) return ''
  return marked.parse(text) as string
}

function scrollToBottom() {
  nextTick(() => {
    if (messagesEl.value) {
      messagesEl.value.scrollTop = messagesEl.value.scrollHeight
    }
  })
}

watch(() => chatStore.messages.length, scrollToBottom)
watch(() => chatStore.messages.at(-1)?.content, scrollToBottom)

// Show test buttons when project is running and has exposed operations
const testableOps = computed(() => {
  if (!projectStore.currentProject || projectStore.runnerState !== 'RUNNING' || !projectStore.runnerPort) return []
  return projectStore.operations.map(op => ({
    operationId: op.operationId,
    method: op.method.toUpperCase(),
    path: op.path,
  }))
})

// Show test buttons after the last assistant message that had tool use (project was changed)
function shouldShowTestButtons(msgIndex: number): boolean {
  if (chatStore.isStreaming) return false
  const msg = chatStore.messages[msgIndex]
  if (msg.role !== 'assistant') return false
  if (!msg.tools || msg.tools.length === 0) return false
  // Only show on the last assistant message with tools
  for (let i = msgIndex + 1; i < chatStore.messages.length; i++) {
    if (chatStore.messages[i].role === 'assistant' && chatStore.messages[i].tools?.length) return false
  }
  return testableOps.value.length > 0
}

async function testEndpoint(op: { operationId: string; method: string; path: string }) {
  const port = projectStore.runnerPort
  if (!port) return

  const key = op.operationId
  testResults.value.set(key, { loading: true })

  try {
    const res = await fetch('/api/test-endpoint', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ port, method: op.method, path: op.path }),
    })
    const data = await res.json()
    testResults.value.set(key, { loading: false, status: data.status, body: data.body })
  } catch (err: any) {
    testResults.value.set(key, { loading: false, status: 0, body: `Error: ${err.message}` })
  }
  scrollToBottom()
}

async function send() {
  const text = input.value.trim()
  if (!text || chatStore.isStreaming) return
  input.value = ''
  testResults.value.clear()
  await chatStore.sendMessage(text)
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}
</script>

<template>
  <div class="chat-panel">
    <div class="chat-header">
      <span class="chat-title">AI Assistant</span>
      <button class="chat-clear" @click="chatStore.clearMessages(); testResults.clear()" title="Clear chat" :disabled="chatStore.isStreaming">
        Clear
      </button>
    </div>

    <div class="chat-messages" ref="messagesEl">
      <div v-if="chatStore.messages.length === 0" class="chat-empty">
        Ask me to create flows, add steps, configure connectors, or explain your project.
      </div>
      <div
        v-for="(msg, i) in chatStore.messages"
        :key="i"
        :class="['chat-msg', msg.role]"
      >
        <div class="msg-role">{{ msg.role === 'user' ? 'You' : 'Claude' }}</div>
        <div v-if="msg.role === 'user'" class="msg-content">{{ msg.content }}</div>
        <div v-else class="msg-content md-content" v-html="renderMarkdown(msg.content)"></div>
        <span v-if="chatStore.isStreaming && i === chatStore.messages.length - 1 && msg.role === 'assistant'" class="cursor">|</span>
        <div v-if="msg.tools && msg.tools.length > 0" class="msg-tools">
          <div v-for="(tool, j) in msg.tools" :key="j" class="tool-badge">
            Applied: {{ tool.name.replace(/_/g, ' ') }}
          </div>
        </div>

        <!-- Test buttons after assistant messages that changed the project -->
        <div v-if="shouldShowTestButtons(i)" class="test-buttons">
          <div class="test-label">Test endpoints:</div>
          <button
            v-for="op in testableOps"
            :key="op.operationId"
            class="test-btn"
            :disabled="testResults.get(op.operationId)?.loading"
            @click="testEndpoint(op)"
          >
            <span class="test-method">{{ op.method }}</span> {{ op.path }}
          </button>
          <!-- Test results -->
          <div v-for="op in testableOps" :key="'r-' + op.operationId">
            <div v-if="testResults.get(op.operationId)" class="test-result">
              <div v-if="testResults.get(op.operationId)?.loading" class="test-loading">Running...</div>
              <template v-else>
                <div class="test-status" :class="{ ok: (testResults.get(op.operationId)?.status ?? 0) < 400, err: (testResults.get(op.operationId)?.status ?? 0) >= 400 }">
                  {{ op.method }} {{ op.path }} &rarr; {{ testResults.get(op.operationId)?.status }}
                </div>
                <pre class="test-body">{{ testResults.get(op.operationId)?.body }}</pre>
              </template>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="chat-input-area">
      <textarea
        v-model="input"
        @keydown="handleKeydown"
        :disabled="chatStore.isStreaming || !projectStore.currentProject"
        placeholder="Describe what you want to build..."
        rows="2"
      ></textarea>
      <button
        class="send-btn"
        @click="send"
        :disabled="!input.trim() || chatStore.isStreaming || !projectStore.currentProject"
      >
        {{ chatStore.isStreaming ? '...' : 'Send' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.chat-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-primary);
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}

.chat-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-primary);
}

.chat-clear {
  font-size: 10px;
  color: var(--text-muted);
  background: transparent;
  border: 1px solid var(--border);
  border-radius: 3px;
  padding: 2px 6px;
  cursor: pointer;
}
.chat-clear:hover:not(:disabled) {
  color: var(--text-secondary);
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 8px 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.chat-empty {
  color: var(--text-muted);
  font-size: 11px;
  text-align: center;
  padding: 24px 8px;
  line-height: 1.5;
}

.chat-msg {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.msg-role {
  font-size: 10px;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.chat-msg.user .msg-content {
  color: var(--text-primary);
  font-size: 12px;
  line-height: 1.5;
}

.chat-msg.assistant .msg-content {
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

/* Markdown rendered content */
.md-content :deep(p) {
  margin: 0 0 8px 0;
}
.md-content :deep(p:last-child) {
  margin-bottom: 0;
}
.md-content :deep(code) {
  background: var(--bg-secondary);
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 11px;
  font-family: monospace;
}
.md-content :deep(pre) {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 8px;
  overflow-x: auto;
  margin: 6px 0;
}
.md-content :deep(pre code) {
  background: none;
  padding: 0;
  font-size: 11px;
}
.md-content :deep(ul), .md-content :deep(ol) {
  margin: 4px 0;
  padding-left: 18px;
}
.md-content :deep(li) {
  margin: 2px 0;
}
.md-content :deep(strong) {
  color: var(--text-primary);
}
.md-content :deep(h1), .md-content :deep(h2), .md-content :deep(h3) {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 8px 0 4px 0;
}

.cursor {
  animation: blink 1s step-end infinite;
  color: var(--accent);
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

.msg-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}

.tool-badge {
  font-size: 10px;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 3px;
  background: rgba(45, 125, 70, 0.12);
  color: #2d7d46;
}

/* Test endpoint buttons */
.test-buttons {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.test-label {
  font-size: 10px;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.test-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  font-size: 11px;
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 4px;
  cursor: pointer;
  color: var(--text-secondary);
  text-align: left;
  width: fit-content;
}
.test-btn:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}
.test-btn:disabled {
  opacity: 0.5;
}

.test-method {
  font-weight: 700;
  font-size: 10px;
  color: #2d7d46;
  font-family: monospace;
}

.test-result {
  margin-top: 4px;
}

.test-loading {
  font-size: 10px;
  color: var(--text-muted);
  font-style: italic;
}

.test-status {
  font-size: 10px;
  font-weight: 600;
  font-family: monospace;
}
.test-status.ok {
  color: #2d7d46;
}
.test-status.err {
  color: #c74545;
}

.test-body {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 6px 8px;
  font-size: 10px;
  font-family: monospace;
  overflow-x: auto;
  margin: 4px 0 0 0;
  max-height: 150px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text-secondary);
}

.chat-input-area {
  padding: 8px 12px;
  border-top: 1px solid var(--border);
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.chat-input-area textarea {
  flex: 1;
  resize: none;
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 6px 8px;
  font-size: 12px;
  font-family: inherit;
  background: var(--bg-secondary);
  color: var(--text-primary);
  outline: none;
}
.chat-input-area textarea:focus {
  border-color: var(--accent);
}

.send-btn {
  align-self: flex-end;
  padding: 6px 12px;
  font-size: 11px;
  font-weight: 600;
  background: var(--accent);
  color: #fff;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}
.send-btn:disabled {
  opacity: 0.5;
  cursor: default;
}
.send-btn:hover:not(:disabled) {
  filter: brightness(0.9);
}
</style>
