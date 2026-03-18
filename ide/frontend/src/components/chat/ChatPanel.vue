<script setup lang="ts">
import { ref, nextTick, watch } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useProjectStore } from '@/stores/project'

const chatStore = useChatStore()
const projectStore = useProjectStore()
const input = ref('')
const messagesEl = ref<HTMLElement | null>(null)

function scrollToBottom() {
  nextTick(() => {
    if (messagesEl.value) {
      messagesEl.value.scrollTop = messagesEl.value.scrollHeight
    }
  })
}

watch(() => chatStore.messages.length, scrollToBottom)
watch(() => chatStore.messages.at(-1)?.content, scrollToBottom)

async function send() {
  const text = input.value.trim()
  if (!text || chatStore.isStreaming) return
  input.value = ''
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
      <button class="chat-clear" @click="chatStore.clearMessages()" title="Clear chat" :disabled="chatStore.isStreaming">
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
        <div class="msg-content">{{ msg.content }}<span v-if="chatStore.isStreaming && i === chatStore.messages.length - 1 && msg.role === 'assistant'" class="cursor">|</span></div>
        <div v-if="msg.tools && msg.tools.length > 0" class="msg-tools">
          <div v-for="(tool, j) in msg.tools" :key="j" class="tool-badge">
            Applied: {{ tool.name.replace(/_/g, ' ') }}
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
  white-space: pre-wrap;
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
