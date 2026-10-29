<script setup>
import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, query } from '../api.js'
import AsyncState from '../components/AsyncState.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { useResource } from '../composables/useResource.js'
import { formatDuration, formatTime } from '../format.js'

const route = useRoute()
const router = useRouter()

const list = useResource(() => api('/sessions'))

const detail = useResource(
  async () => {
    const id = encodeURIComponent(route.params.id)
    const [session, toolCalls] = await Promise.all([
      api(`/sessions/${id}`),
      api(`/tool-calls${query({ sessionId: route.params.id, limit: 100 })}`)
    ])
    return { session, toolCalls }
  },
  { immediate: false, isEmpty: () => false }
)

watch(
  () => route.params.id,
  (id) => {
    if (id) detail.reload()
  },
  { immediate: true }
)

function open(sessionId) {
  router.push(`/sessions/${sessionId}`)
}
</script>

<template>
  <h1>Sessions</h1>
  <p class="page-intro">
    Conversations stored in SQLite, most recently active first (latest 100). Read-only.
  </p>
  <AsyncState
    :state="list.state.value"
    :error="list.error.value"
    empty-text="No sessions yet. Chat with an agent or run a scheduled task to create one."
    @retry="list.reload"
  >
    <table>
      <thead>
        <tr>
          <th>Session</th>
          <th>Profile</th>
          <th>Channel</th>
          <th>Messages</th>
          <th>Last user message</th>
          <th>Last active</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="s in list.data.value"
          :key="s.sessionId"
          class="clickable"
          :class="{ selected: s.sessionId === route.params.id }"
          data-test="session-row"
          @click="open(s.sessionId)"
        >
          <td class="mono">{{ s.sessionId }}</td>
          <td>{{ s.profileName }}</td>
          <td>{{ s.channel }}</td>
          <td>{{ s.messageCount }}</td>
          <td class="muted wrap">{{ s.lastUserMessage ?? '—' }}</td>
          <td>{{ formatTime(s.updatedAt) }}</td>
        </tr>
      </tbody>
    </table>
  </AsyncState>

  <section v-if="route.params.id" data-test="session-detail">
    <h2>
      <span class="mono">{{ route.params.id }}</span>
    </h2>
    <AsyncState
      :state="detail.state.value"
      :error="detail.error.value"
      @retry="detail.reload"
    >
      <p class="muted">
        {{ detail.data.value.session.totalMessages }} message(s)
        <template v-if="detail.data.value.session.truncated">
          — showing only the latest {{ detail.data.value.session.messages.length }}
        </template>
      </p>
      <div v-if="!detail.data.value.session.messages.length" class="card muted">
        This session has no messages yet.
      </div>
      <div
        v-for="(m, i) in detail.data.value.session.messages"
        :key="i"
        class="message"
        :class="m.role"
        data-test="message"
      >
        <div class="role">{{ m.role }}</div>
        {{ m.content }}
        <div v-for="call in m.toolCalls" :key="call.id" class="muted mono">
          → {{ call.name }}
        </div>
      </div>

      <h2>Tool calls in this session</h2>
      <div v-if="!detail.data.value.toolCalls.length" class="card muted">
        No tool calls were recorded for this session.
      </div>
      <table v-else data-test="session-tool-calls">
        <thead>
          <tr>
            <th>When</th>
            <th>Tool</th>
            <th>Outcome</th>
            <th>Reason</th>
            <th>Took</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="call in detail.data.value.toolCalls" :key="call.id">
            <td>{{ formatTime(call.occurredAt) }}</td>
            <td class="mono">{{ call.toolName }}</td>
            <td>
              <StatusBadge :tone="call.success ? 'ok' : 'danger'">
                {{ call.success ? 'success' : (call.errorCategory ?? 'failed') }}
              </StatusBadge>
            </td>
            <td class="muted">{{ call.errorMessage ?? '' }}</td>
            <td>{{ formatDuration(call.durationMs) }}</td>
          </tr>
        </tbody>
      </table>
    </AsyncState>
  </section>
</template>
