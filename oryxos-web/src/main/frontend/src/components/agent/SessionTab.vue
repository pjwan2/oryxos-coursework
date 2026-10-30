<script setup>
import { ref } from 'vue'
import { api } from '../../api.js'
import AsyncState from '../AsyncState.vue'
import { useResource } from '../../composables/useResource.js'

const props = defineProps({ agent: { type: Object, required: true } })

const base = () => `/agents/${encodeURIComponent(props.agent.name)}/session`
const session = useResource(() => api(base()), { isEmpty: (s) => s.messages.length === 0 })

const message = ref('')
const sending = ref(false)
const sendError = ref(null)

async function send() {
  sending.value = true
  sendError.value = null
  try {
    await api(`${base()}/messages`, { method: 'POST', body: { message: message.value } })
    message.value = ''
  } catch (failure) {
    sendError.value = failure.message
  } finally {
    sending.value = false
    await session.reload()
  }
}
</script>

<template>
  <p class="muted">
    The agent's fixed console conversation (<span class="mono">admin:console:{{ agent.name }}</span
    >). It runs the same agent as the API, the CLI and the scheduler.
  </p>
  <AsyncState
    :state="session.state.value"
    :error="session.error.value"
    empty-text="No messages yet. Say something below."
    @retry="session.reload"
  >
    <div
      v-for="(m, i) in session.data.value.messages"
      :key="i"
      :class="['message', m.role]"
      data-test="message"
    >
      <div class="role">{{ m.role }}</div>
      <template v-if="m.toolCalls && m.toolCalls.length">
        <span v-for="c in m.toolCalls" :key="c.id" class="chip">calls {{ c.name }}</span>
      </template>
      <template v-if="m.toolReplies && m.toolReplies.length">
        <div v-for="r in m.toolReplies" :key="r.id" class="mono">{{ r.name }}: {{ r.responseData }}</div>
      </template>
      {{ m.content }}
    </div>
  </AsyncState>
  <form class="row" style="margin-top: 12px" data-test="session-form" @submit.prevent="send">
    <input
      v-model="message"
      placeholder="Message the agent"
      aria-label="Message"
      style="flex: 1; min-width: 220px"
      data-test="message-input"
    />
    <button class="primary" :disabled="sending || !message.trim()" data-test="send">
      {{ sending ? 'Waiting for the agent…' : 'Send' }}
    </button>
  </form>
  <div v-if="sendError" class="notice danger" role="alert" style="margin-top: 8px" data-test="send-error">
    {{ sendError }}
  </div>
</template>

<style scoped>
input {
  font: inherit;
  color: var(--text);
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 5px 10px;
}
</style>
