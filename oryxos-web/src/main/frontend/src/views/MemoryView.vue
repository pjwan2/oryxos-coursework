<script setup>
import { api } from '../api.js'
import AsyncState from '../components/AsyncState.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { useResource } from '../composables/useResource.js'

const {
  state,
  data: memory,
  error,
  reload
} = useResource(() => api('/memory'), {
  // "Empty" means both lists are empty; the backend name is shown whenever there is data.
  isEmpty: (m) => m.core.length === 0 && m.archival.length === 0
})
</script>

<template>
  <h1>Memory</h1>
  <p class="page-intro">
    Long-term memory the agents saved. Read-only. CORE entries are always in the prompt; ARCHIVAL
    entries are searched and truncated.
  </p>
  <AsyncState
    :state="state"
    :error="error"
    empty-text="No memories saved yet. An agent with the save_memory tool can add some."
    @retry="reload"
  >
    <p>
      Backend <StatusBadge tone="ok">{{ memory.backend }}</StatusBadge>
    </p>
    <h2>Core ({{ memory.core.length }})</h2>
    <div v-if="!memory.core.length" class="card muted">No core entries.</div>
    <div v-for="(entry, i) in memory.core" :key="'c' + i" class="message" data-test="core-entry">
      <div class="role">{{ entry.savedOn ?? 'undated' }}</div>
      {{ entry.content }}
    </div>
    <h2>Archival (latest {{ memory.archival.length }}, up to {{ memory.archivalLimit }})</h2>
    <div v-if="!memory.archival.length" class="card muted">No archival entries.</div>
    <div
      v-for="(entry, i) in memory.archival"
      :key="'a' + i"
      class="message"
      data-test="archival-entry"
    >
      <div class="role">{{ entry.savedOn ?? 'undated' }}</div>
      {{ entry.content }}
    </div>
  </AsyncState>
</template>
