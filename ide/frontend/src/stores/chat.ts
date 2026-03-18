import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useProjectStore } from '@/stores/project'

export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  tools?: { name: string; result: string }[]
}

export const useChatStore = defineStore('chat', () => {
  const messages = ref<ChatMessage[]>([])
  const isStreaming = ref(false)
  const chatVisible = ref(false)
  const chatAvailable = ref(false)

  async function checkAvailability() {
    try {
      const res = await fetch('/api/chat/status')
      const data = await res.json()
      chatAvailable.value = data.available
    } catch {
      chatAvailable.value = false
    }
  }

  function toggleChat() {
    chatVisible.value = !chatVisible.value
  }

  async function sendMessage(text: string) {
    const projectStore = useProjectStore()
    if (!projectStore.currentProject || isStreaming.value) return

    // Add user message
    messages.value.push({ role: 'user', content: text })

    // Prepare assistant message placeholder
    const assistantMsg: ChatMessage = { role: 'assistant', content: '', tools: [] }
    messages.value.push(assistantMsg)
    isStreaming.value = true

    try {
      // Build conversation history (only text content for API)
      const history = messages.value.slice(0, -1).map(m => ({
        role: m.role,
        content: m.content,
      }))

      const res = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          projectId: projectStore.currentProject.id,
          messages: history,
        }),
      })

      if (!res.ok) {
        const err = await res.json()
        assistantMsg.content = `Error: ${err.error || 'Failed to connect'}`
        return
      }

      const reader = res.body?.getReader()
      if (!reader) return

      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('event: ')) {
            const eventType = line.slice(7)
            // Next line should be data:
            continue
          }
          if (line.startsWith('data: ')) {
            const rawData = line.slice(6)
            try {
              const data = JSON.parse(rawData)
              // Determine event type from the data shape
              if ('text' in data) {
                assistantMsg.content += data.text
              } else if ('name' in data && 'result' in data) {
                assistantMsg.tools!.push({ name: data.name, result: data.result })
              } else if ('projectChanged' in data) {
                // Reload project to reflect tool mutations
                await projectStore.loadProject(projectStore.currentProject!.id)
              }
            } catch {
              // Skip malformed data
            }
          }
        }
      }
    } catch (err: any) {
      assistantMsg.content += `\n\nConnection error: ${err.message}`
    } finally {
      isStreaming.value = false
    }
  }

  function clearMessages() {
    messages.value = []
  }

  return {
    messages,
    isStreaming,
    chatVisible,
    chatAvailable,
    checkAvailability,
    toggleChat,
    sendMessage,
    clearMessages,
  }
})
